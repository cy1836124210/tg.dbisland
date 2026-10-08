/**
 * dsh-islandbridge 线级(wire-level)验证：不用 fetch，直接裸 TCP socket，
 * 断言 SSE 真的把 POST 进去的帧按 `data: {json}\n\n` 写到了连接上，
 * 含多字节中文原样往返。
 *
 * 这一层是为了排除「测试框架自己缓冲/丢 chunk」造成的假阴性：
 * 它只看 socket 上的原始字节。
 *
 * 运行： node pc/dsh-plugin/test/sse-wire-test.mjs
 */

import { createServer } from 'node:http'
import net from 'node:net'
import process from 'node:process'
import { strict as assert } from 'node:assert'

import { apply } from '../index.js'

const TOKEN = 'wire-token-7b21'

function makeFakeCtx (host = '127.0.0.1') {
  const exact = new Map()
  const prefixes = new Map()
  const effects = []
  const logs = { info: [], warn: [], error: [] }
  const push = (l) => (m) => logs[l].push(String(m))
  const webServer = {
    host,
    port: undefined,
    register (route) {
      const t = route.kind === 'exact' ? exact : prefixes
      if (t.has(route.path)) throw new Error('duplicate route')
      t.set(route.path, route)
      return () => t.delete(route.path)
    },
    match (p) {
      const e = exact.get(p)
      if (e !== undefined) return e
      let best
      for (const [prefix, route] of prefixes) {
        if (p !== prefix && !p.startsWith(`${prefix}/`)) continue
        if (best === undefined || prefix.length > best.path.length) best = { route, path: prefix }
      }
      return best?.route
    },
  }
  return { ctx: { webServer, effect: (fn) => { effects.push(fn()) }, logger: { info: push('info'), warn: push('warn'), error: push('error') } }, webServer, effects, logs }
}

let passed = 0
let failed = 0

async function test (label, fn) {
  try { await fn(); passed++; console.log(`  PASS  ${label}`) } catch (err) {
    failed++
    console.log(`  FAIL  ${label}`)
    console.log(`        ${String(err.message).split('\n').join('\n        ')}`)
  }
}

async function main () {
  console.log('dsh-islandbridge 线级验证（裸 socket，无 fetch）\n')

  const fake = makeFakeCtx()
  apply(fake.ctx, { token: TOKEN, heartbeatSeconds: 1 })

  const server = createServer((req, res) => {
    const p = new URL(req.url ?? '/', 'http://x').pathname
    const route = fake.webServer.match(p)
    if (route !== undefined) {
      route.handler(req, res).catch((err) => { fake.logs.warn.push(`handler threw: ${err.message}`); if (!res.headersSent) { res.writeHead(400); res.end() } })
      return
    }
    res.writeHead(404)
    res.end()
  })
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  const port = server.address().port

  /** 单条 TCP 连接上收到的全部原始字节。 */
  const openSocket = async () => {
    const chunks = []
    const sock = net.connect(port, '127.0.0.1')
    sock.on('data', (b) => chunks.push(b))
    await new Promise((r) => sock.once('connect', r))
    return { sock, bytes: () => Buffer.concat(chunks).toString('utf8'), close: () => sock.destroy() }
  }

  const sseSock = await openSocket()
  sseSock.sock.write(`GET /islandbridge/events?token=${TOKEN} HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n`)
  await new Promise((r) => setTimeout(r, 300))

  await test('SSE 响应头是 200 + text/event-stream + chunked', () => {
    const head = sseSock.bytes()
    assert.match(head, /^HTTP\/1\.1 200 OK\r\n/)
    assert.match(head, /content-type: text\/event-stream; charset=utf-8/i)
    assert.match(head, /Transfer-Encoding: chunked/i)
  })

  await test('chunked 帧里带着 ": hello" 开场注释', () => {
    assert.match(sseSock.bytes(), /data|: hello/)
    assert.ok(sseSock.bytes().includes(': hello'), 'no ": hello" on the wire')
  })

  // 一条含多字节中文的真实事件帧
  const frame = { t: 'chat.delta', mid: 'm-42', kind: 'text', text: '你好，星河岛' }
  const body = JSON.stringify(frame)

  const postSock = await openSocket()
  postSock.sock.write(
    `POST /islandbridge/event?token=${TOKEN} HTTP/1.1\r\nHost: 127.0.0.1\r\n` +
    `Content-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`,
  )
  await new Promise((r) => setTimeout(r, 400))

  await test('POST /event 返回 200 {ok:true,delivered:1}', () => {
    const text = postSock.bytes()
    assert.match(text, /^HTTP\/1\.1 200 OK\r\n/)
    const payload = JSON.parse(text.slice(text.indexOf('\r\n\r\n') + 4))
    assert.equal(payload.ok, true)
    assert.equal(payload.delivered, 1)
    assert.equal(payload.clients, 1)
  })

  await test('SSE socket 上出现 `data: {json}\\n\\n`，且中文原样往返', () => {
    const wire = sseSock.bytes()
    assert.ok(wire.includes('data: '), `no data: frame on the wire; got ${JSON.stringify(wire)}`)
    const line = wire.split('\n').find((l) => l.startsWith('data: '))
    const parsed = JSON.parse(line.slice('data: '.length))
    assert.deepEqual(parsed, frame, 'frame did not round-trip byte-exactly')
    assert.ok(wire.includes('你好，星河岛'), 'multibyte text was mangled on the wire')
  })

  await test('心跳 ": heartbeat" 出现在同一连接上', async () => {
    await new Promise((r) => setTimeout(r, 1200))
    assert.ok(sseSock.bytes().includes(': heartbeat'), `no heartbeat; got ${JSON.stringify(sseSock.bytes())}`)
  })

  await test('chunked 长度前缀正确（帧首 chunk 的十六进制长度 == 该 chunk 字节数）', () => {
    const raw = sseSock.bytes()
    const at = raw.indexOf('data: ')
    assert.ok(at > 0, 'no data frame')
    // 往前找最近的 chunk 长度行：...\r\n<hex>\r\n
    const before = raw.slice(0, at)
    const m = /(?:^|\r\n)([0-9a-fA-F]+)\r\n$/.exec(before)
    assert.ok(m, `could not find chunk size before data frame in ${JSON.stringify(before.slice(-40))}`)
    const declared = parseInt(m[1], 16)
    const chunkStart = at
    const chunkEnd = raw.indexOf('\r\n', chunkStart)
    const actual = Buffer.byteLength(raw.slice(chunkStart, chunkEnd), 'utf8')
    assert.equal(declared, actual, `chunk size ${declared} != actual ${actual} bytes`)
  })

  await test('卸载后 socket 被服务端关闭，端口不再接受连接', async () => {
    for (const d of fake.effects) await d()
    await new Promise((r) => { server.close(() => r()); server.closeAllConnections?.() })
    await new Promise((r) => setTimeout(r, 200))
    let refused = false
    try {
      const s = net.connect(port, '127.0.0.1')
      await new Promise((res, rej) => { s.once('connect', res); s.once('error', rej) })
      s.destroy()
    } catch (err) { refused = err.code === 'ECONNREFUSED' }
    assert.ok(refused, 'port still accepting connections after unload')
  })

  sseSock.close()
  postSock.close()

  console.log(`\n结果: ${passed} passed, ${failed} failed`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((err) => { console.error('crashed:', err); process.exit(2) })
