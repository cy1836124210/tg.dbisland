/**
 * real-loader-test.mjs — 用**真的** Cordis 与**真的** WebServer 类跑一遍本插件。
 *
 * `run-tests.mjs` 用的是照抄语义的假 ctx；`schema-test.mjs` 只取出真 schemastery。
 * 本脚本补上二者都覆盖不到的三件事：
 *   1. 插件被当作**真包**按裸名解析（`import * as p from 'dsh-islandbridge'`，
 *      即装进 profile 后的布局），由真 `Context` apply；
 *   2. 路由注册在 `@deepseek-ai/dsh-host-webserver` 的**真 `WebServer` 实例**上
 *      （真 node:http、真 prefix 匹配、真 404 语义）；
 *   3. 卸载走真生命周期：`fiber.dispose()`（**是异步的，必须 await**）→
 *      `ctx.effect` 处置器跑完 → 路由从 webServer 的 prefix 表里消失（404）。
 *
 * 依赖：从本机 DSH 的 `app.asar` 里抽出约 1.5 MB 的真依赖闭包（cordis +
 * dsh-host-webserver + 其真依赖）。缺失时本脚本会自己抽一次；本机没装 DSH
 * （找不到 app.asar）则 SKIP 并 exit 0 —— 绝不假装通过。
 *
 * 运行（在 pc/dsh-plugin 下）：
 *   node test/real-loader-test.mjs
 * 覆盖 asar / 解包目录：
 *   node test/real-loader-test.mjs "D:/app/ai/DSH/resources/app.asar"
 *   $env:IB_REAL_ROOT="D:/tmp/ib-real"
 */
import { existsSync, mkdirSync, copyFileSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { tmpdir } from 'node:os'
import process from 'node:process'

/* ------------------------------------------------------------------ *
 * 会被写进 ROOT/harness.mjs 的脚本（同一文件，避免两份代码漂移）。
 * ------------------------------------------------------------------ */
const HARNESS_SOURCE = String.raw`
import { Context } from '@deepseek-ai/cordis'
import webServerPlugin from '@deepseek-ai/dsh-host-webserver'
import * as islandbridge from 'dsh-islandbridge'
import net from 'node:net'

const TOKEN = 'real-loader-token-0123456789abcdef'
const results = []
const ok = (name, cond, extra = '') => {
  results.push([name, !!cond])
  console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  ' + extra : ''))
}

// ---- 1. 包形态（真包、真解析）----
ok('按裸包名解析到 dsh-islandbridge', typeof islandbridge.apply === 'function')
ok('导出 name === "islandbridge"', islandbridge.name === 'islandbridge', String(islandbridge.name))
ok('导出 apply 函数', typeof islandbridge.apply === 'function')
ok('inject 含 webServer', Array.isArray(islandbridge.inject) && islandbridge.inject.includes('webServer'), JSON.stringify(islandbridge.inject))
ok('真 schemastery 分支生效（SCHEMA_SOURCE）', islandbridge.SCHEMA_SOURCE === '@deepseek-ai/schemastery', String(islandbridge.SCHEMA_SOURCE))
ok('Config 满足 Standard Schema 接口', !!islandbridge.Config && typeof islandbridge.Config['~standard'] === 'object')

// ---- 2. 真 WebServer 服务 ----
const ctx = new Context()
ctx.plugin(webServerPlugin, { host: '127.0.0.1', port: 0 })
for (let i = 0; i < 40 && !ctx.webServer; i++) await new Promise((r) => setTimeout(r, 25))
ok('真 WebServer 服务起来了', !!ctx.webServer, 'port=' + (ctx.webServer && ctx.webServer.port))
const port = ctx.webServer.port
const base = 'http://127.0.0.1:' + port + '/islandbridge'
const before = ctx.webServer.prefixes.size

// ---- 3. apply + 鉴权（走真路由匹配）----
const fiber = ctx.plugin(islandbridge, { token: TOKEN, heartbeatSeconds: 1 })
for (let i = 0; i < 40 && ctx.webServer.prefixes.size === before; i++) await new Promise((r) => setTimeout(r, 25))
ok('apply 后真 webServer 上多了一条 prefix 路由', ctx.webServer.prefixes.size === before + 1, [...ctx.webServer.prefixes.keys()].join(','))

let r = await fetch(base + '/health')
ok('无令牌 /health -> 401', r.status === 401, 'status=' + r.status)
r = await fetch(base + '/health', { headers: { authorization: 'Bearer wrong-token' } })
ok('错令牌 -> 401', r.status === 401, 'status=' + r.status)
r = await fetch(base + '/health', { headers: { authorization: 'Bearer ' + TOKEN } })
const health = await r.json()
ok('Bearer 正确 -> 200 + ok:true', r.status === 200 && health.ok === true, JSON.stringify(health))
r = await fetch(base + '/health?token=' + encodeURIComponent(TOKEN))
ok('?token= 也放行（EventSource 用）', r.status === 200, 'status=' + r.status)
r = await fetch('http://127.0.0.1:' + port + '/definitely-not-ours')
ok('未注册路径 -> 404（没抢 fallback 席位）', r.status === 404, 'status=' + r.status)

// ---- 4. 真 SSE：裸 socket 订阅 + POST 推帧 ----
const sock = net.connect(port, '127.0.0.1')
let raw = ''
sock.setEncoding('utf8')
sock.on('data', (d) => { raw += d })
await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej) })
sock.write('GET /islandbridge/events?token=' + encodeURIComponent(TOKEN) + ' HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n')
for (let i = 0; i < 40 && !raw.includes('HTTP/1.1 200'); i++) await new Promise((r2) => setTimeout(r2, 25))
ok('SSE 握手 200', raw.includes('HTTP/1.1 200'))
ok('SSE 响应头是 text/event-stream', /content-type:\s*text\/event-stream/i.test(raw))

const frame = { t: 'chat.delta', text: '中文原样往返 · ok' }
const post = await fetch(base + '/event', {
  method: 'POST',
  headers: { authorization: 'Bearer ' + TOKEN, 'content-type': 'application/json' },
  body: JSON.stringify(frame),
})
const pj = await post.json()
ok('POST /event -> 200 {ok:true}', post.status === 200 && pj.ok === true, JSON.stringify(pj))
for (let i = 0; i < 40 && !raw.includes('data: '); i++) await new Promise((r2) => setTimeout(r2, 25))
ok('订阅者 socket 上出现 data: 帧', raw.includes('data: '))
ok('帧格式为 data: {json}\\n\\n', /data: \{.*\}\n\n/.test(raw))
ok('中文原样往返', raw.includes('中文原样往返 · ok'))
sock.destroy()

// ---- 5. 卸载：真生命周期 ----
const ret = fiber.dispose()
ok('fiber.dispose() 返回 Promise（必须 await）', ret && typeof ret.then === 'function')
await ret
await new Promise((r2) => setTimeout(r2, 200))
ok('卸载后路由从真 webServer 的 prefix 表移除', ctx.webServer.prefixes.size === before, 'size=' + ctx.webServer.prefixes.size)
r = await fetch(base + '/health', { headers: { authorization: 'Bearer ' + TOKEN } })
ok('卸载后同一路径 -> 404', r.status === 404, 'status=' + r.status)

const passed = results.filter((x) => x[1]).length
const failed = results.length - passed
console.log('\n结果: ' + passed + ' passed, ' + failed + ' failed')
process.exit(failed === 0 ? 0 : 1)
`

/* ------------------------------------------------------------------ *
 * 驱动：准备真依赖闭包 → 把插件按真包布局放好 → 在 ROOT 里跑 harness
 * ------------------------------------------------------------------ */
const here = dirname(fileURLToPath(import.meta.url))
const pkgDir = resolve(here, '..')

const ASAR = process.argv[2] ?? 'D:/app/ai/DSH/resources/app.asar'
const ROOT = process.env.IB_REAL_ROOT ?? join(tmpdir(), 'islandbridge-real')

if (!existsSync(ASAR)) {
  console.log(`SKIP: 找不到本机 DSH 的 app.asar（${ASAR}）—— 本机没装 DSH 时属正常`)
  process.exit(0)
}

const nodeModules = join(ROOT, 'node_modules')
if (!existsSync(join(nodeModules, '@deepseek-ai', 'cordis', 'lib', 'index.js'))) {
  console.log(`extracting real closure from ${ASAR} -> ${nodeModules}`)
  const r = spawnSync(process.execPath, [join(here, 'extract-real-closure.mjs'), ASAR, nodeModules], { stdio: 'inherit' })
  if (r.status !== 0) {
    console.error('FAIL: 无法抽出真依赖闭包')
    process.exit(1)
  }
}

// 以真包的布局把插件放进 node_modules（与装进 profile 后一致）
const installed = join(nodeModules, 'dsh-islandbridge')
mkdirSync(installed, { recursive: true })
for (const f of ['index.js', 'package.json', 'cordis.patch.yml']) {
  copyFileSync(join(pkgDir, f), join(installed, f))
}

// 断言写在 harness 里；它必须落在 ROOT 才能按裸包名解析依赖。
const harness = join(ROOT, 'harness.mjs')
writeFileSync(harness, HARNESS_SOURCE, 'utf8')

console.log(`running harness in ${ROOT}`)
const res = spawnSync(process.execPath, [harness], { stdio: 'inherit', cwd: ROOT })
process.exit(res.status ?? 1)
