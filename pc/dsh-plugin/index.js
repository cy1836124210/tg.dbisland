/**
 * dsh-islandbridge — 豆包(Doubao) → 星河岛(Island) 桥接的 **DSH host 半**。
 *
 * 形态：DSH「组合包」(bundle)。本文件是一个普通 Cordis ESM 插件模块，
 * 具名导出 `name` / `inject` / `Config` / `apply`；同目录的
 * `cordis.patch.yml` 用 `- insert:` 把它挂进 profile 的插件树，
 * `package.json` 的 `dsh.bundle.patch` 指向那个 patch 文件。
 * 本包**只有 host 半**，没有 `dsh.client`（不需要浏览器界面）。
 *
 * 产出的 HTTP 面（与 pc/protocol.md 的 SSE 协议同构）：
 *
 *   GET  <base>/events   text/event-stream，帧格式 `data: {json}\n\n`，
 *                        心跳 `: heartbeat\n\n`；`t` 字段即 pc/protocol.md 的事件类型
 *   POST <base>/event    把一条事件帧 fan-out 给所有 SSE 订阅者（手机/脚本用）
 *   GET  <base>/health   {"ok":true,"proto":"islandbridge-sse/1","clients":N,...}
 *   GET  <base>/         极简人工监控页（token 走 ?token=）
 *
 * 安全基线：
 *   - 所有请求必须带 `Authorization: Bearer <token>` 或 `?token=<token>`，否则 401。
 *     没配 token 时用 node:crypto 生成随机 token 并在日志里打印一次（不是留空放行）。
 *   - 本插件**不能**决定监听地址：端口/host 属于 @deepseek-ai/dsh-host-webserver
 *     的配置。因此这里做的是「检测 + 默认拒绝」：若 webServer.host 不是回环地址，
 *     默认拒绝挂载路由（除非显式 allowNonLoopback: true）。
 *   - 单帧大小上限、JSON 与 `t` 字段校验、注入速率上限。
 *
 * 已验证项与未验证项见仓库 docs/DSH_PLUGIN.md。
 */

import { randomBytes, timingSafeEqual } from 'node:crypto'

/** Cordis 插件名（与 cordis.patch.yml 的 insert 条目对应）。 */
export const name = 'islandbridge'

/** 依赖 webServer 服务：DSH 会等它就绪后再调用 apply。 */
export const inject = ['webServer']

export const VERSION = '0.1.0'

/** pc/protocol.md 里的协议标识。 */
export const PROTO = 'islandbridge-sse/1'

const DEFAULT_BASE_PATH = '/islandbridge'

/** 事件类型白名单形状：`chat.delta` / `plan.end` / `send.result` / `ping` … */
const TYPE_RE = /^[a-z][a-z0-9]*(?:\.[a-z0-9]+)*$/

const DEFAULTS = Object.freeze({
  token: '',
  basePath: DEFAULT_BASE_PATH,
  maxFrameBytes: 64 * 1024,
  maxFramesPerSecond: 60,
  maxFrameBurst: 120,
  heartbeatSeconds: 15,
  maxClients: 32,
  maxClientBufferBytes: 1024 * 1024,
  allowedTypes: [],
  allowNonLoopback: false,
  allowCors: false,
})

/* ------------------------------------------------------------------ *
 * Schemastery：优先真 schema，取不到就退化为普通对象 + 自行校验
 * ------------------------------------------------------------------ *
 * 本机 DSH 0.2.0-rc.1 自带 @deepseek-ai/schemastery@3.18.4
 * (D:\app\ai\DSH\resources\app.asar\dsh\node_modules\@deepseek-ai\schemastery)。
 * 但插件被 `dsh plugin add` 装进 profile 时，profile 的
 * pnpm-workspace.yaml 是 `autoInstallPeers: false` + `nodeLinker: hoisted`，
 * 该包不一定落在插件的解析路径上，所以 import 必须可选。
 * 两条路径的**运行时校验完全一致**：apply() 里始终再走一遍 normalizeConfig()。
 */
let z = null
let schemaSource = 'fallback(plain object)'
try {
  const mod = await import('@deepseek-ai/schemastery')
  z = mod.default ?? mod
  schemaSource = '@deepseek-ai/schemastery'
} catch {
  try {
    const mod = await import('schemastery')
    z = mod.default ?? mod
    schemaSource = 'schemastery'
  } catch {
    z = null
  }
}

/** 实际生效的 schema 来源，便于日志/测试观察：'@deepseek-ai/schemastery' | 'schemastery' | 'fallback(plain object)'。 */
export const SCHEMA_SOURCE = schemaSource

/** 用真 schema 时声明的形状；字段顺序 = 日志顺序。 */
const SCHEMA_SHAPE = {
  /** 共享令牌。留空 = 启动时随机生成并打印一次。 */
  token: z?.string().default(''),
  /** 所有路由的前缀。 */
  basePath: z?.string().default(DEFAULT_BASE_PATH),
  /** POST /event 单帧字节上限。 */
  maxFrameBytes: z?.natural().default(DEFAULTS.maxFrameBytes),
  /** 每秒允许注入的帧数（令牌桶）。 */
  maxFramesPerSecond: z?.number().min(1).default(DEFAULTS.maxFramesPerSecond),
  /** 令牌桶容量（突发额度）。 */
  maxFrameBurst: z?.natural().default(DEFAULTS.maxFrameBurst),
  /** SSE 心跳间隔（秒）。 */
  heartbeatSeconds: z?.natural().default(DEFAULTS.heartbeatSeconds),
  /** 并发 SSE 订阅者上限。 */
  maxClients: z?.natural().default(DEFAULTS.maxClients),
  /** 单个订阅者的 socket 缓冲上限，超过就丢帧而不拖垮全局。 */
  maxClientBufferBytes: z?.natural().default(DEFAULTS.maxClientBufferBytes),
  /** 非空时只放行这些 `t` 值。 */
  allowedTypes: z?.array(z.string()).default([]),
  /** 允许在非回环 host 上挂载（默认拒绝，见文件头安全说明）。 */
  allowNonLoopback: z?.boolean().default(false),
  /** 是否发 Access-Control-Allow-Origin: *（默认关）。 */
  allowCors: z?.boolean().default(false),
}

/**
 * Cordis 读取的配置 schema。真 schemastery 可用时是 validating schema；
 * 否则是「默认值普通对象」，此时校验完全由 {@link normalizeConfig} 承担。
 */
export const Config = z ? z.object(SCHEMA_SHAPE) : { ...DEFAULTS, $schemaSource: schemaSource }

/* ------------------------------------------------------------------ *
 * 纯函数工具（可单测）
 * ------------------------------------------------------------------ */

function isLoopbackHost (host) {
  if (typeof host !== 'string') return false
  const h = host.trim().toLowerCase()
  return h === '127.0.0.1' || h === 'localhost' || h === '::1' || h === '[::1]' || h.startsWith('127.')
}

/** 定长比较，避免令牌比较的时序侧信道。 */
export function tokensMatch (expected, presented) {
  if (typeof expected !== 'string' || typeof presented !== 'string') return false
  if (expected.length === 0) return false
  const a = Buffer.from(expected, 'utf8')
  const b = Buffer.from(presented, 'utf8')
  if (a.length !== b.length) return false
  return timingSafeEqual(a, b)
}

/** `Authorization: Bearer x` 或 `?token=x`；其他一律 null。 */
export function readToken (headers, url) {
  const auth = headers?.authorization
  if (typeof auth === 'string' && /^Bearer[ \t]+/i.test(auth)) return auth.replace(/^Bearer[ \t]+/i, '').trim()
  const q = url?.searchParams?.get?.('token')
  return typeof q === 'string' ? q : null
}

function clampInt (value, { min, max, fallback, label, problems }) {
  const n = typeof value === 'number' ? value : Number(value)
  if (value === undefined || value === null || value === '' || !Number.isFinite(n)) {
    if (value !== undefined && value !== null && value !== '') problems.push(`${label}: not a number (${JSON.stringify(value)}), using ${fallback}`)
    return fallback
  }
  const i = Math.trunc(n)
  if (i < min || i > max) {
    problems.push(`${label}: ${i} out of range [${min}, ${max}], clamped`)
    return Math.min(max, Math.max(min, i))
  }
  return i
}

function normalizeBasePath (raw, problems) {
  if (typeof raw !== 'string' || raw.trim() === '') {
    if (raw !== undefined && raw !== '') problems.push(`basePath: ${JSON.stringify(raw)} is not a non-empty string, using ${DEFAULT_BASE_PATH}`)
    return DEFAULT_BASE_PATH
  }
  let p = raw.trim()
  if (!p.startsWith('/')) p = `/${p}`
  p = p.replace(/\/+$/, '')
  if (p === '') {
    problems.push(`basePath: "/" would claim every path and shadow the SPA fallback, using ${DEFAULT_BASE_PATH}`)
    return DEFAULT_BASE_PATH
  }
  return p
}

/**
 * 无 schema 时的等价校验：永远在 apply() 里跑一遍，所以
 * 「真 schema」与「退化普通对象」两条路径的运行时行为一致。
 * @returns {{config: object, problems: string[]}}
 */
export function normalizeConfig (raw) {
  const problems = []
  if (raw !== undefined && raw !== null && (typeof raw !== 'object' || Array.isArray(raw))) {
    problems.push(`config: expected an object, got ${Array.isArray(raw) ? 'array' : typeof raw}; using defaults`)
  }
  const src = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}

  const cfg = {
    token: typeof src.token === 'string' ? src.token.trim() : '',
    basePath: normalizeBasePath(src.basePath, problems),
    maxFrameBytes: clampInt(src.maxFrameBytes, { min: 64, max: 8 * 1024 * 1024, fallback: DEFAULTS.maxFrameBytes, label: 'maxFrameBytes', problems }),
    maxFramesPerSecond: clampInt(src.maxFramesPerSecond, { min: 1, max: 10000, fallback: DEFAULTS.maxFramesPerSecond, label: 'maxFramesPerSecond', problems }),
    maxFrameBurst: clampInt(src.maxFrameBurst, { min: 1, max: 100000, fallback: DEFAULTS.maxFrameBurst, label: 'maxFrameBurst', problems }),
    heartbeatSeconds: clampInt(src.heartbeatSeconds, { min: 1, max: 3600, fallback: DEFAULTS.heartbeatSeconds, label: 'heartbeatSeconds', problems }),
    maxClients: clampInt(src.maxClients, { min: 1, max: 4096, fallback: DEFAULTS.maxClients, label: 'maxClients', problems }),
    maxClientBufferBytes: clampInt(src.maxClientBufferBytes, { min: 1024, max: 1024 * 1024 * 1024, fallback: DEFAULTS.maxClientBufferBytes, label: 'maxClientBufferBytes', problems }),
    allowedTypes: [],
    allowNonLoopback: src.allowNonLoopback === true,
    allowCors: src.allowCors === true,
  }

  if (Array.isArray(src.allowedTypes)) {
    for (const t of src.allowedTypes) {
      if (typeof t === 'string' && TYPE_RE.test(t)) cfg.allowedTypes.push(t)
      else problems.push(`allowedTypes: ignored invalid entry ${JSON.stringify(t)}`)
    }
  } else if (src.allowedTypes !== undefined) {
    problems.push('allowedTypes: expected an array of strings; allowing all types')
  }

  if (src.token !== undefined && typeof src.token !== 'string') problems.push('token: expected a string; treating as unset')
  return { config: cfg, problems }
}

/** 帧校验：必须是对象、`t` 是协议形状的字符串、且在可选白名单内。 */
export function validateFrame (value, allowedTypes = []) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'frame must be a JSON object' }
  }
  if (typeof value.t !== 'string' || !TYPE_RE.test(value.t)) {
    return { ok: false, error: 'frame.t must be a dotted lowercase type string, e.g. "chat.delta"' }
  }
  if (allowedTypes.length > 0 && !allowedTypes.includes(value.t)) {
    return { ok: false, error: `frame.t ${JSON.stringify(value.t)} is not in allowedTypes` }
  }
  return { ok: true }
}

/** 有上限的 body 读取；超限抛 status=413。 */
export function readBody (req, limit) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers?.['content-length'])
    if (Number.isFinite(declared) && declared > limit) {
      req.resume?.()
      const err = new Error(`frame exceeds maxFrameBytes (content-length ${declared} > ${limit})`)
      err.status = 413
      reject(err)
      return
    }
    let size = 0
    let over = false
    const chunks = []
    req.on('data', (buf) => {
      size += buf.length
      if (size > limit) {
        over = true
        chunks.length = 0
        return
      }
      chunks.push(buf)
    })
    req.on('end', () => {
      if (over) {
        const err = new Error(`frame exceeds maxFrameBytes (> ${limit})`)
        err.status = 413
        reject(err)
        return
      }
      resolve(Buffer.concat(chunks))
    })
    req.on('error', reject)
  })
}

/** 令牌桶（注入速率上限）。 */
export function createBucket (ratePerSecond, burst) {
  let tokens = burst
  let last = Date.now()
  return function take () {
    const now = Date.now()
    tokens = Math.min(burst, tokens + ((now - last) / 1000) * ratePerSecond)
    last = now
    if (tokens < 1) return false
    tokens -= 1
    return true
  }
}

/* ------------------------------------------------------------------ *
 * Cordis 插件入口
 * ------------------------------------------------------------------ */

/**
 * @param {object} ctx Cordis 上下文（携带 webServer 服务与 effect/logger）。
 * @param {object} config 原始配置（已由 Config 校验，这里再兜一层）。
 */
export function apply (ctx, config) {
  const { config: cfg, problems } = normalizeConfig(config)
  const log = makeLogger(ctx)

  for (const p of problems) log.warn(`config: ${p}`)

  if (cfg.token === '') {
    cfg.token = randomBytes(32).toString('base64url')
    log.warn('no token configured — generated an ephemeral one for this session; clients must use it:')
    log.warn(`  token: ${cfg.token}`)
    log.warn('set dsh.bundles config for id "islandbridge" to pin it across restarts.')
  }

  /** SSE 订阅者。 */
  const clients = new Set()
  const take = createBucket(cfg.maxFramesPerSecond, cfg.maxFrameBurst)
  let dropped = 0
  let injected = 0
  const startedAt = Date.now()

  // ---- 监听地址检查：本插件无法改绑定，只能默认拒绝非回环暴露 -------------
  const host = ctx.webServer?.host
  if (!isLoopbackHost(host) && !cfg.allowNonLoopback) {
    log.error(`refusing to mount: webServer.host is ${JSON.stringify(host)}, not a loopback address.`)
    log.error('this plugin cannot change the bind address — it is @deepseek-ai/dsh-host-webserver config.')
    log.error('on this host the bridge would expose the whole DSH Web GUI on the same interface.')
    log.error(`set config allowNonLoopback: true for id "islandbridge" to override deliberately.`)
    return
  }
  if (!isLoopbackHost(host)) {
    log.warn(`webServer.host is ${JSON.stringify(host)} — the bridge AND the DSH Web GUI share this interface.`)
  }

  // ---- 响应助手 ---------------------------------------------------------
  const corsHeaders = cfg.allowCors ? { 'access-control-allow-origin': '*' } : {}

  function sendJson (res, status, payload, extraHeaders = {}) {
    const body = Buffer.from(JSON.stringify(payload), 'utf8')
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': String(body.length),
      'cache-control': 'no-store',
      ...corsHeaders,
      ...extraHeaders,
    })
    res.end(body)
  }

  // ---- 各端点 -----------------------------------------------------------

  function handleEvents (req, res) {
    if (req.method !== 'GET') {
      sendJson(res, 405, { ok: false, error: 'use GET for /events' }, { allow: 'GET' })
      return
    }
    if (clients.size >= cfg.maxClients) {
      sendJson(res, 503, { ok: false, error: `too many SSE clients (max ${cfg.maxClients})` })
      return
    }

    // content-type 以 text/event-stream 开头 → webserver 的 gzip 中间件会跳过压缩
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
      ...corsHeaders,
    })
    res.write(': hello\n\n')

    const client = { res, since: Date.now(), droppedLocal: 0 }
    clients.add(client)
    const drop = () => { clients.delete(client) }
    res.on('close', drop)
    res.on('error', drop)
    log.info(`sse client connected (${clients.size} total)`)
  }

  /** fan-out 一条已校验的帧。 */
  function broadcast (frame) {
    const line = `data: ${JSON.stringify(frame)}\n\n`
    let delivered = 0
    for (const client of clients) {
      if (client.res.writableLength > cfg.maxClientBufferBytes) {
        client.droppedLocal++
        dropped++
        continue
      }
      try {
        client.res.write(line)
        delivered++
      } catch {
        clients.delete(client)
      }
    }
    return delivered
  }

  async function handlePost (req, res) {
    if (req.method !== 'POST') {
      sendJson(res, 405, { ok: false, error: 'use POST for /event' }, { allow: 'POST' })
      return
    }
    if (!take()) {
      dropped++
      log.warn('injection rate limit exceeded — rejecting frame')
      sendJson(res, 429, { ok: false, error: 'rate limit exceeded' }, { 'retry-after': '1' })
      return
    }

    let raw
    try {
      raw = await readBody(req, cfg.maxFrameBytes)
    } catch (err) {
      const status = err.status ?? 400
      log.warn(`rejected frame: ${err.message}`)
      sendJson(res, status, { ok: false, error: err.message })
      return
    }

    let frame
    try {
      frame = JSON.parse(raw.toString('utf8') || 'null')
    } catch (err) {
      log.warn(`rejected frame: invalid JSON (${err.message})`)
      sendJson(res, 400, { ok: false, error: 'invalid JSON' })
      return
    }

    const verdict = validateFrame(frame, cfg.allowedTypes)
    if (!verdict.ok) {
      log.warn(`rejected frame: ${verdict.error}`)
      sendJson(res, 400, { ok: false, error: verdict.error })
      return
    }

    const delivered = broadcast(frame)
    injected++
    sendJson(res, 200, { ok: true, t: frame.t, clients: clients.size, delivered })
  }

  function handleHealth (req, res) {
    if (req.method !== 'GET') {
      sendJson(res, 405, { ok: false, error: 'use GET for /health' }, { allow: 'GET' })
      return
    }
    sendJson(res, 200, {
      ok: true,
      proto: PROTO,
      clients: clients.size,
      version: VERSION,
      host,
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
      injected,
      dropped,
    })
  }

  function handleMonitor (req, res) {
    if (req.method !== 'GET') {
      sendJson(res, 405, { ok: false, error: 'use GET for the monitor page' }, { allow: 'GET' })
      return
    }
    const body = Buffer.from(renderMonitor(cfg.basePath), 'utf8')
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      'content-length': String(body.length),
      'cache-control': 'no-store',
    })
    res.end(body)
  }

  // ---- 单一 prefix 路由，内部按 method + 子路径分派 ----------------------
  async function handler (req, res) {
    let url
    try {
      url = new URL(req.url ?? '/', 'http://localhost')
    } catch {
      sendJson(res, 400, { ok: false, error: 'bad request url' })
      return
    }

    // 鉴权强制：所有请求（含 /health）都要令牌。
    const presented = readToken(req.headers, url)
    if (!tokensMatch(cfg.token, presented)) {
      log.warn(`401 for ${req.method} ${url.pathname} (missing or bad token)`)
      sendJson(res, 401, { ok: false, error: 'unauthorized: send Authorization: Bearer <token> or ?token=<token>' }, { 'www-authenticate': 'Bearer' })
      return
    }

    // prefix 路由命中后 pathname 可能是 basePath、basePath+"/"、basePath+"/x"
    let sub = url.pathname.slice(cfg.basePath.length)
    if (sub === '/') sub = ''

    switch (sub) {
      case '/events': return handleEvents(req, res)
      case '/event': return handlePost(req, res)
      case '/health': return handleHealth(req, res)
      case '': return handleMonitor(req, res)
      default:
        sendJson(res, 404, { ok: false, error: `unknown endpoint ${JSON.stringify(url.pathname)}` })
    }
  }

  // ---- 生命周期：注册路由 + 心跳定时器，卸载时全关 ------------------------
  ctx.effect(() => {
    const disposeRoute = ctx.webServer.register({ kind: 'prefix', path: cfg.basePath, handler })

    const timer = setInterval(() => {
      for (const client of clients) {
        try {
          client.res.write(': heartbeat\n\n')
        } catch {
          clients.delete(client)
        }
      }
    }, cfg.heartbeatSeconds * 1000)
    timer.unref?.()

    log.info(`mounted on ${ctx.webServer.host}:${ctx.webServer.port ?? '?'}${cfg.basePath}`)
    log.info(`  GET  ${cfg.basePath}/events   (SSE)`)
    log.info(`  POST ${cfg.basePath}/event`)
    log.info(`  GET  ${cfg.basePath}/health`)
    log.info(`  GET  ${cfg.basePath}/`)

    return () => {
      clearInterval(timer)
      disposeRoute()
      const closing = [...clients]
      clients.clear()
      for (const client of closing) {
        try { client.res.end() } catch { /* already gone */ }
      }
      log.info(`unmounted: route removed, heartbeat stopped, ${closing.length} SSE client(s) closed`)
    }
  }, 'islandbridge: routes + sse clients')
}

/* ------------------------------------------------------------------ *
 * 日志 / 监控页
 * ------------------------------------------------------------------ */

function makeLogger (ctx) {
  const logger = ctx?.logger
  const wrap = (level) => (msg) => {
    if (logger && typeof logger[level] === 'function') {
      try { logger[level](msg); return } catch { /* fall through */ }
    }
    const sink = level === 'error' || level === 'warn' ? console.error : console.log
    sink(`[islandbridge] ${msg}`)
  }
  return { info: wrap('info'), warn: wrap('warn'), error: wrap('error') }
}

function renderMonitor (basePath) {
  const bp = JSON.stringify(basePath)
  return `<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>IslandBridge (DSH 插件)</title><style>
body{background:#14161a;color:#e8eaf0;font:13px/1.6 Consolas,monospace;margin:0;padding:10px}
h1{font:15px sans-serif;color:#9aa0ab;margin:0 0 8px} h1 b{color:#4d7cfe}
#st{color:#3fce8a}
.ev{border-left:2px solid #2c3038;padding:2px 8px;margin:3px 0;white-space:pre-wrap;word-break:break-all}
.chat{border-color:#4d7cfe}.plan{border-color:#3fce8a}.sys{border-color:#9aa0ab;color:#9aa0ab}
</style></head><body>
<h1>IslandBridge — <b id="st">连接中…</b> <span id="nc"></span></h1>
<div id="f"></div><script>
const BP=${bp}, tok=new URLSearchParams(location.search).get('token')||'';
const f=document.getElementById('f'), st=document.getElementById('st'), nc=document.getElementById('nc');
const es=new EventSource(BP+'/events?token='+encodeURIComponent(tok));
es.onopen=()=>{st.textContent='已连接';st.style.color='#3fce8a'};
es.onerror=()=>{st.textContent='断开重连中…';st.style.color='#e06c75'};
es.onmessage=e=>{const d=document.createElement('div');
 try{const o=JSON.parse(e.data);d.className='ev '+(o.t||'').split('.')[0]}catch(_){d.className='ev'}
 d.textContent=new Date().toLocaleTimeString()+' '+e.data;
 f.appendChild(d);window.scrollTo(0,1e9);while(f.children.length>500)f.removeChild(f.firstChild)};
setInterval(()=>fetch(BP+'/health?token='+encodeURIComponent(tok))
 .then(r=>r.json()).then(h=>{nc.textContent='订阅客户端:'+h.clients}).catch(()=>{}),5000);
</script></body></html>`
}
