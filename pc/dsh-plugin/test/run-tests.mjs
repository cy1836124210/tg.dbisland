/**
 * dsh-islandbridge 验证脚本 —— 真跑，不是纸面测试。
 *
 * 做法（与任务要求一致）：
 *   1. 造假 ctx：`webServer.register` 收集路由，`effect` 收集清理函数，
 *      `logger` 收集日志。加载真实插件 `../index.js`。
 *   2. 用 node:http 起**真**的服务，请求分派逻辑照抄本机
 *      @deepseek-ai/dsh-host-webserver/lib/index.js 的 match()/handle() 语义
 *      （exact 表命中优先，然后 prefix 最长前缀胜出，未命中 404）。
 *   3. 发**真**请求：401 / 200 / SSE 收到 POST 进去的帧 / 心跳 / 限流 /
 *      超大帧 / 卸载后端口关闭 且 SSE 连接被关。
 *
 * 运行： node pc/dsh-plugin/test/run-tests.mjs
 */

import { createServer } from 'node:http'
import net from 'node:net'
import { strict as assert } from 'node:assert'
import process from 'node:process'

import {
  apply,
  Config,
  inject,
  name,
  normalizeConfig,
  readToken,
  tokensMatch,
  validateFrame,
  PROTO,
  VERSION,
} from '../index.js'

/* ------------------------------------------------------------------ *
 * 迷你测试框架
 * ------------------------------------------------------------------ */
let passed = 0
let failed = 0
const failures = []

async function test (label, fn) {
  try {
    await fn()
    passed++
    console.log(`  PASS  ${label}`)
  } catch (err) {
    failed++
    failures.push({ label, err })
    console.log(`  FAIL  ${label}`)
    console.log(`        ${err && err.message ? err.message.split('\n').join('\n        ') : err}`)
  }
}

function section (title) {
  console.log(`\n=== ${title} ===`)
}

/* ------------------------------------------------------------------ *
 * 假 ctx + 真 http 服务
 * ------------------------------------------------------------------ */
function makeFakeCtx (host = '127.0.0.1') {
  const exact = new Map()
  const prefixes = new Map()
  const effects = []
  const logs = { info: [], warn: [], error: [] }

  const push = (level) => (msg) => { logs[level].push(String(msg)) }

  const webServer = {
    host,
    port: undefined,
    register (route) {
      const table = route.kind === 'exact' ? exact : prefixes
      if (table.has(route.path)) throw new Error(`webserver: duplicate ${route.kind} route "${route.path}"`)
      table.set(route.path, route)
      return () => { table.delete(route.path) }
    },
    // 照抄本机 dsh-host-webserver 的 match()：exact 优先，prefix 最长前缀胜出
    match (pathname) {
      const e = exact.get(pathname)
      if (e !== undefined) return e
      let best
      for (const [prefix, route] of prefixes) {
        if (pathname !== prefix && !pathname.startsWith(`${prefix}/`)) continue
        if (best === undefined || prefix.length > best.path.length) best = { route, path: prefix }
      }
      return best?.route
    },
  }

  const ctx = {
    webServer,
    effect (fn, label) {
      const dispose = fn()
      effects.push({ label, dispose })
      return dispose
    },
    logger: { info: push('info'), warn: push('warn'), error: push('error') },
  }

  return { ctx, webServer, effects, logs, routeCount: () => exact.size + prefixes.size }
}

/** 起真服务；请求分派照抄 dsh-host-webserver 的 handle()。 */
async function boot (config, host = '127.0.0.1') {
  const fake = makeFakeCtx(host)
  apply(fake.ctx, config)

  const server = createServer((req, res) => {
    const run = async () => {
      const pathname = new URL(req.url ?? '/', 'http://x').pathname
      const route = fake.webServer.match(pathname)
      if (route !== undefined) {
        await route.handler(req, res)
        return
      }
      res.writeHead(404)
      res.end()
    }
    run().catch((err) => {
      fake.logs.warn.push(`handler threw: ${err.message}`)
      if (res.headersSent) { res.destroy(); return }
      res.writeHead(400)
      res.end()
    })
  })

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, host, () => {
      server.off('error', reject)
      resolve()
    })
  })
  fake.webServer.port = server.address().port

  return {
    ...fake,
    server,
    base: `http://${host}:${fake.webServer.port}`,
    async unload () {
      // 我们的 effect 清理（插件的责任）
      for (const { dispose } of [...fake.effects].reverse()) {
        if (typeof dispose === 'function') await dispose()
      }
      // 服务关闭（真实 DSH 里由 WebServer 自己的 ctx.effect 负责：
      // close() + closeAllConnections()，见 dsh-host-webserver/lib/index.js）
      await new Promise((resolve) => {
        server.close(() => resolve())
        server.closeAllConnections?.()
      })
    },
  }
}

/* ------------------------------------------------------------------ *
 * SSE 读取器
 * ------------------------------------------------------------------ */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 单条后台读取循环把整条流累积到 buf；waitFor 只轮询 buf。
 * （第一版用 Promise.race(read, timeout) 会丢掉被放弃的那次 read 已取走的数据，
 *   was a real bug in the harness, not in the plugin — see docs/DSH_PLUGIN.md。）
 */
function sseReader (res) {
  const reader = res.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  let closed = false
  let readError = null

  const pump = (async () => {
    try {
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buf += dec.decode(value, { stream: true })
      }
    } catch (err) {
      readError = err
    } finally {
      closed = true
    }
  })()

  return {
    get text () { return buf },
    get closed () { return closed },
    get readError () { return readError },
    async waitFor (pred, timeoutMs = 6000) {
      const deadline = Date.now() + timeoutMs
      for (;;) {
        if (pred(buf)) return buf
        if (Date.now() >= deadline) break
        await sleep(20)
      }
      throw new Error(`SSE timeout after ${timeoutMs}ms; received so far:\n${JSON.stringify(buf)}`)
    },
    async waitClosed (timeoutMs = 6000) {
      const deadline = Date.now() + timeoutMs
      while (!closed && Date.now() < deadline) await sleep(20)
      if (!closed) throw new Error(`SSE stream did not close; received:\n${JSON.stringify(buf)}`)
      await pump
      return true
    },
    async cancel () { try { await reader.cancel() } catch { /* already closed */ } },
  }
}

const TOKEN = 'test-token-3f9a1c'

/* ------------------------------------------------------------------ *
 * 结论采集
 * ------------------------------------------------------------------ */
const findings = {}

async function main () {
  console.log(`dsh-islandbridge ${VERSION} — 插件名为 ${JSON.stringify(name)}，inject=${JSON.stringify(inject)}`)
  console.log(`Config 类型: ${Config && typeof Config === 'object' ? (Config.constructor?.name ?? 'plain object') : typeof Config}`)

  /* ---------------------------------------------------------------- */
  section('A. 鉴权（所有端点）')

  const a = await boot({ token: TOKEN, heartbeatSeconds: 1, maxFrameBytes: 2048 })
  findings.mountedRoutes = a.routeCount()
  findings.mountLogs = [...a.logs.info]

  await test('无令牌 GET /islandbridge/health -> 401', async () => {
    const r = await fetch(`${a.base}/islandbridge/health`)
    assert.equal(r.status, 401, `expected 401, got ${r.status}`)
    const j = await r.json()
    assert.equal(j.ok, false)
    assert.equal(r.headers.get('www-authenticate'), 'Bearer')
  })

  await test('错误令牌 -> 401', async () => {
    const r = await fetch(`${a.base}/islandbridge/health`, { headers: { authorization: 'Bearer wrong-token' } })
    assert.equal(r.status, 401)
  })

  await test('长度相同但内容不同的令牌 -> 401（定长比较路径）', async () => {
    const r = await fetch(`${a.base}/islandbridge/health`, { headers: { authorization: `Bearer ${'x'.repeat(TOKEN.length)}` } })
    assert.equal(r.status, 401)
  })

  await test('Authorization: Bearer <token> -> 200', async () => {
    const r = await fetch(`${a.base}/islandbridge/health`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(r.status, 200, `expected 200, got ${r.status}`)
    const j = await r.json()
    assert.equal(j.ok, true)
    assert.equal(j.proto, PROTO)
    assert.equal(j.clients, 0)
    findings.health = j
  })

  await test('?token=<token> -> 200', async () => {
    const r = await fetch(`${a.base}/islandbridge/health?token=${encodeURIComponent(TOKEN)}`)
    assert.equal(r.status, 200)
    assert.equal((await r.json()).ok, true)
  })

  await test('无令牌 POST /islandbridge/event -> 401（POST 也要令牌）', async () => {
    const r = await fetch(`${a.base}/islandbridge/event`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ t: 'ping' }),
    })
    assert.equal(r.status, 401, `expected 401, got ${r.status}`)
  })

  await test('无令牌 GET /islandbridge/（监控页）-> 401', async () => {
    const r = await fetch(`${a.base}/islandbridge/`)
    assert.equal(r.status, 401)
  })

  await test('prefix 路由没有吞掉别的路径：GET /nope -> 404', async () => {
    const r = await fetch(`${a.base}/nope`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(r.status, 404)
  })

  /* ---------------------------------------------------------------- */
  section('B. SSE 订阅 + POST 帧 fan-out')

  const sse = await fetch(`${a.base}/islandbridge/events?token=${encodeURIComponent(TOKEN)}`, {
    headers: { accept: 'text/event-stream' },
  })
  const reader = sseReader(sse)

  await test('GET /islandbridge/events -> 200 且 content-type 以 text/event-stream 开头（gzip 中间件据此跳过压缩）', () => {
    assert.equal(sse.status, 200)
    const ct = sse.headers.get('content-type')
    findings.sseContentType = ct
    assert.ok(ct.startsWith('text/event-stream'), `content-type was ${ct}`)
  })

  await test('连接立即收到 ": hello" 注释帧', async () => {
    await reader.waitFor((t) => t.includes(': hello'))
  })

  await test('GET /islandbridge/health 报告 clients=1', async () => {
    const r = await fetch(`${a.base}/islandbridge/health`, { headers: { authorization: `Bearer ${TOKEN}` } })
    const j = await r.json()
    assert.equal(j.clients, 1, `clients was ${j.clients}`)
  })

  const frame = { t: 'chat.delta', mid: 'm-42', kind: 'text', text: '你好，星河岛' }

  await test('POST /islandbridge/event 合法帧 -> 200 且 delivered=1', async () => {
    const r = await fetch(`${a.base}/islandbridge/event`, {
      method: 'POST',
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
      body: JSON.stringify(frame),
    })
    assert.equal(r.status, 200, `expected 200, got ${r.status}`)
    const j = await r.json()
    assert.equal(j.ok, true)
    assert.equal(j.t, 'chat.delta')
    assert.equal(j.delivered, 1, `delivered was ${j.delivered}`)
    findings.postResult = j
  })

  await test('SSE 订阅者收到 POST 进去的那一帧（data: {json}）', async () => {
    const buf = await reader.waitFor((t) => t.includes('data: ') && t.includes('chat.delta'))
    const line = buf.split('\n').find((l) => l.startsWith('data: '))
    assert.ok(line, `no data: line in ${JSON.stringify(buf)}`)
    const parsed = JSON.parse(line.slice('data: '.length))
    assert.deepEqual(parsed, frame, 'round-tripped frame differed')
    findings.receivedFrame = parsed
  })

  await test('心跳 ": heartbeat" 按 heartbeatSeconds=1 到达', async () => {
    await reader.waitFor((t) => t.includes(': heartbeat'), 3500)
  })

  /* ---------------------------------------------------------------- */
  section('C. 输入校验')

  const bad = async (body, headers = {}) => {
    const r = await fetch(`${a.base}/islandbridge/event`, {
      method: 'POST',
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json', ...headers },
      body,
    })
    return { status: r.status, json: await r.json().catch(() => null) }
  }

  await test('非法 JSON -> 400', async () => {
    const r = await bad('{not json')
    assert.equal(r.status, 400, `got ${r.status}`)
    assert.equal(r.json.error, 'invalid JSON')
  })

  await test('JSON 数组 -> 400', async () => {
    const r = await bad('[1,2,3]')
    assert.equal(r.status, 400)
    assert.match(r.json.error, /must be a JSON object/)
  })

  await test('缺 t 字段 -> 400', async () => {
    const r = await bad(JSON.stringify({ mid: 'x' }))
    assert.equal(r.status, 400)
    assert.match(r.json.error, /frame\.t/)
  })

  await test('t 不是协议形状（大写/空格）-> 400', async () => {
    assert.equal((await bad(JSON.stringify({ t: 'Chat Delta' }))).status, 400)
    assert.equal((await bad(JSON.stringify({ t: '' }))).status, 400)
  })

  await test('超过 maxFrameBytes(2048) -> 413', async () => {
    const r = await bad(JSON.stringify({ t: 'chat.delta', text: 'x'.repeat(4000) }))
    assert.equal(r.status, 413, `got ${r.status}`)
    assert.match(r.json.error, /maxFrameBytes/)
  })

  await test('超限帧被记录（logger.warn）', () => {
    assert.ok(a.logs.warn.some((m) => m.includes('maxFrameBytes')), `warn log missing; got ${JSON.stringify(a.logs.warn)}`)
  })

  await test('GET 到 POST 端点 -> 405；POST 到 GET 端点 -> 405', async () => {
    const g = await fetch(`${a.base}/islandbridge/event`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(g.status, 405)
    const p = await fetch(`${a.base}/islandbridge/events`, { method: 'POST', headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(p.status, 405)
  })

  await test('未知子路径 -> 404', async () => {
    const r = await fetch(`${a.base}/islandbridge/nope`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(r.status, 404)
  })

  /* ---------------------------------------------------------------- */
  section('D. 卸载：端口关闭 + SSE 连接被关 + 路由移除')

  await test('unload() 之前端口是活的', async () => {
    const r = await fetch(`${a.base}/islandbridge/health?token=${TOKEN}`)
    assert.equal(r.status, 200)
  })

  await a.unload()

  await test('unload() 后端口关闭（全新 socket 连接 ECONNREFUSED）', async () => {
    // 用全新裸 socket，绕开 fetch/undici 的连接池（池里的旧连接会报 ECONNRESET，
    // 那是池的假象，不能证明端口状态）。
    let code
    try {
      const s = net.connect(new URL(a.base).port, '127.0.0.1')
      await new Promise((resolve, reject) => { s.once('connect', resolve); s.once('error', reject) })
      s.destroy()
    } catch (err) { code = err.code }
    findings.afterUnloadError = code ?? 'connected'
    assert.equal(code, 'ECONNREFUSED', `expected ECONNREFUSED on a fresh socket, got ${code}`)
  })

  await test('unload() 后已建立的 SSE 连接被服务端关闭', async () => {
    await reader.waitClosed(4000)
  })

  await test('unload() 移除了注册的路由并打印了卸载日志', () => {
    assert.equal(a.routeCount(), 0, `routes still registered: ${a.routeCount()}`)
    assert.ok(a.logs.info.some((m) => m.includes('unmounted')), `no unmount log; got ${JSON.stringify(a.logs.info)}`)
    assert.ok(a.logs.info.some((m) => m.includes('SSE client(s) closed')))
  })

  /* ---------------------------------------------------------------- */
  section('E. 速率上限')

  const e = await boot({ token: TOKEN, maxFramesPerSecond: 1, maxFrameBurst: 1 })
  await test('burst=1 时第二帧立刻被限流 -> 429 + Retry-After', async () => {
    const post = () => fetch(`${e.base}/islandbridge/event`, {
      method: 'POST',
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
      body: JSON.stringify({ t: 'ping' }),
    })
    const first = await post()
    assert.equal(first.status, 200, `first got ${first.status}`)
    const second = await post()
    assert.equal(second.status, 429, `second got ${second.status}`)
    assert.equal(second.headers.get('retry-after'), '1')
    findings.rateLimit = { first: first.status, second: second.status }
  })
  await test('限流被记录到日志', () => {
    assert.ok(e.logs.warn.some((m) => m.includes('rate limit')), JSON.stringify(e.logs.warn))
  })
  await e.unload()

  /* ---------------------------------------------------------------- */
  section('F. 监听地址守卫（本插件不能改绑定，只能默认拒绝）')

  await test('webServer.host = 0.0.0.0 且未开 allowNonLoopback -> 拒绝挂载（0 条路由）+ error 日志', async () => {
    const f = await boot({ token: TOKEN }, '0.0.0.0')
    assert.equal(f.routeCount(), 0, `mounted ${f.routeCount()} routes despite non-loopback host`)
    assert.ok(f.logs.error.some((m) => m.includes('not a loopback address')), JSON.stringify(f.logs.error))
    await f.unload()
  })

  await test('显式 allowNonLoopback: true -> 挂载并以 warn 说明与 GUI 同接口', async () => {
    const f = await boot({ token: TOKEN, allowNonLoopback: true }, '0.0.0.0')
    assert.ok(f.routeCount() > 0, 'expected the route to be registered')
    assert.ok(f.logs.warn.some((m) => m.includes('DSH Web GUI share this interface')), JSON.stringify(f.logs.warn))
    await f.unload()
  })

  await test('127.0.0.1 与 ::1 / localhost 都算回环', async () => {
    for (const h of ['127.0.0.1', 'localhost', '::1']) {
      const f = await boot({ token: TOKEN }, h)
      assert.ok(f.routeCount() > 0, `${h} was wrongly refused`)
      await f.unload()
    }
  })

  /* ---------------------------------------------------------------- */
  section('G. 令牌生成：未配置时随机生成并打印一次（而不是放行）')

  await test('未给 token -> 生成随机令牌、日志里只出现一次、且无令牌请求仍然 401', async () => {
    const f = await boot({ heartbeatSeconds: 3600 })
    const tokenLines = f.logs.warn.filter((m) => m.trim().startsWith('token: '))
    assert.equal(tokenLines.length, 1, `expected exactly 1 token log, got ${tokenLines.length}`)
    const generated = tokenLines[0].trim().slice('token: '.length)
    assert.ok(generated.length >= 32, `generated token too short: ${generated.length}`)
    findings.generatedTokenLength = generated.length

    const noTok = await fetch(`${f.base}/islandbridge/health`)
    assert.equal(noTok.status, 401, 'must still 401 without the generated token')
    const withTok = await fetch(`${f.base}/islandbridge/health?token=${encodeURIComponent(generated)}`)
    assert.equal(withTok.status, 200, 'generated token must actually work')
    await f.unload()
  })

  /* ---------------------------------------------------------------- */
  section('H. 纯函数单测')

  await test('tokensMatch 定长/空值语义', () => {
    assert.equal(tokensMatch('abc', 'abc'), true)
    assert.equal(tokensMatch('abc', 'abd'), false)
    assert.equal(tokensMatch('abc', 'ab'), false)
    assert.equal(tokensMatch('', ''), false, 'empty expected token must never match')
    assert.equal(tokensMatch('abc', undefined), false)
  })

  await test('readToken 只认 Bearer 与 ?token=', () => {
    const u = (q) => new URL(`http://x/?${q}`)
    assert.equal(readToken({ authorization: 'Bearer tok' }, u('')), 'tok')
    assert.equal(readToken({ authorization: 'bearer tok' }, u('')), 'tok')
    assert.equal(readToken({}, u('token=tok')), 'tok')
    assert.equal(readToken({ authorization: 'Basic tok' }, u('')), null)
    assert.equal(readToken({}, u('')), null)
  })

  await test('validateFrame 边界', () => {
    assert.equal(validateFrame({ t: 'ping' }).ok, true)
    assert.equal(validateFrame({ t: 'send.result', ok: false }).ok, true)
    assert.equal(validateFrame({ t: 'plan.progress' }).ok, true)
    assert.equal(validateFrame(null).ok, false)
    assert.equal(validateFrame([]).ok, false)
    assert.equal(validateFrame({ t: 5 }).ok, false)
    assert.equal(validateFrame({ t: 'chat.delta' }, ['ping']).ok, false)
  })

  await test('normalizeConfig 钳制并报告问题', () => {
    const { config, problems } = normalizeConfig({ maxFrameBytes: 1, heartbeatSeconds: -3, basePath: 'islandbridge', allowedTypes: ['ok.type', 42] })
    assert.equal(config.maxFrameBytes, 64, 'clamped to min 64')
    assert.equal(config.heartbeatSeconds, 1, 'clamped to min 1')
    assert.equal(config.basePath, '/islandbridge', 'leading slash added')
    assert.deepEqual(config.allowedTypes, ['ok.type'])
    assert.ok(problems.length >= 2, `expected problems, got ${JSON.stringify(problems)}`)
    findings.normalizeProblems = problems
  })

  await test('normalizeConfig 拒绝 basePath 为 "/"（会吞掉 SPA fallback）', () => {
    const { config, problems } = normalizeConfig({ basePath: '/' })
    assert.equal(config.basePath, '/islandbridge')
    assert.ok(problems.some((p) => p.includes('shadow the SPA fallback')))
  })

  await test('allowNonLoopback 只认字面 true', () => {
    assert.equal(normalizeConfig({ allowNonLoopback: true }).config.allowNonLoopback, true)
    assert.equal(normalizeConfig({ allowNonLoopback: 'yes' }).config.allowNonLoopback, false)
    assert.equal(normalizeConfig({}).config.allowNonLoopback, false)
  })

  /* ---------------------------------------------------------------- */
  console.log(`\n${'='.repeat(72)}`)
  console.log(`结果: ${passed} passed, ${failed} failed`)
  if (failed > 0) {
    console.log('\n失败明细:')
    for (const f of failures) console.log(`  - ${f.label}\n    ${f.err?.stack ?? f.err}`)
  }
  console.log('\n--- 采集到的证据 ---')
  console.log(JSON.stringify(findings, null, 2))

  process.exit(failed === 0 ? 0 : 1)
}

main().catch((err) => {
  console.error('harness crashed:', err)
  process.exit(2)
})
