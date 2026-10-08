/**
 * dsh-islandbridge Config schema 验证 —— 用**本机 DSH 安装包里真实的**
 * @deepseek-ai/schemastery（asar 内提取，版本以安装包为准），
 * 证明插件导出的 Config 在真校验器下是对的，且真 schema 的输出能直接喂给 apply()。
 *
 * 为什么要这么绕：插件的 import 是有回退的（找不到 schemastery 就退化成普通对象），
 * 直接跑 `node index.js` 走的是回退分支。要验证「真 schema 分支」，
 * 必须让模块真的能 resolve 到该包 —— 于是把它从 app.asar 里解到临时目录。
 *
 * 运行： node pc/dsh-plugin/test/schema-test.mjs
 */

import { existsSync, mkdirSync, writeFileSync, copyFileSync, readFileSync, rmSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { tmpdir } from 'node:os'
import process from 'node:process'
import { strict as assert } from 'node:assert'

import { openAsar, dumpAsarTree } from './asar-read.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const pluginDir = join(here, '..')

function findAsar () {
  const candidates = [
    process.env.DSH_ASAR,
    'D:\\app\\ai\\DSH\\resources\\app.asar',
    join(process.env.LOCALAPPDATA ?? '', 'Programs', 'DeepSeek Harness', 'resources', 'app.asar'),
    '/Applications/DeepSeek Harness.app/Contents/Resources/app.asar',
  ].filter(Boolean)
  return candidates.find((p) => existsSync(p))
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
  console.log('dsh-islandbridge Config schema 验证（用本机 DSH 安装包里的真 schemastery）\n')

  const asarPath = findAsar()
  if (!asarPath) {
    console.log('SKIP: 找不到本机 DSH 的 app.asar —— 真 schema 分支未验证。')
    console.log('      可用 DSH_ASAR=<path> 指定。')
    process.exit(3)
  }
  console.log(`asar: ${asarPath}`)

  const tmp = join(tmpdir(), `dsh-islandbridge-schema-${process.pid}`)
  rmSync(tmp, { recursive: true, force: true })
  mkdirSync(join(tmp, 'node_modules'), { recursive: true })

  const asar = openAsar(asarPath)
  let dumped = 0
  try {
    // 只需要 schemastery 的运行时依赖链：它自己 + cosmokit + @standard-schema/spec
    dumped += dumpAsarTree(asar, /^(?:dsh\/)?node_modules\/@deepseek-ai\/schemastery\//, join(tmp, 'node_modules'))
    dumped += dumpAsarTree(asar, /^(?:dsh\/)?node_modules\/@deepseek-ai\/cosmokit\//, join(tmp, 'node_modules'))
    dumped += dumpAsarTree(asar, /^(?:dsh\/)?node_modules\/@standard-schema\/spec\//, join(tmp, 'node_modules'))

    const version = JSON.parse(asar.text('dsh/node_modules/@deepseek-ai/schemastery/package.json')).version
    console.log(`提取 ${dumped} 个文件到 ${tmp}`)
    console.log(`@deepseek-ai/schemastery@${version}\n`)

    copyFileSync(join(pluginDir, 'index.js'), join(tmp, 'index.js'))
    copyFileSync(join(pluginDir, 'package.json'), join(tmp, 'package.json'))
  } finally {
    asar.close()
  }

  // 探针：在临时目录里 import 插件（此时 schemastery 可解析），把结果写成 JSON。
  // 用 stdio:'inherit' + 结果文件，避免依赖子进程管道捕获。
  writeFileSync(join(tmp, 'probe.mjs'), `
import { writeFileSync } from 'node:fs'

const out = { ok: false }
try {
  const mod = await import('./index.js')
  out.schemaSource = mod.SCHEMA_SOURCE
  out.configType = typeof mod.Config
  out.configCtor = mod.Config?.constructor?.name
  out.configIsFunction = typeof mod.Config === 'function'
  out.configString = String(mod.Config).slice(0, 300)

  // 只给 token，其余应被默认值填满
  out.resolved = mod.Config({ token: 'abc' })
  out.resolvedKeys = Object.keys(out.resolved)

  // 真校验器是否拒绝类型错误（记录，不猜）
  try { mod.Config({ token: 123 }); out.tokenNumberThrew = false } catch (e) { out.tokenNumberThrew = true; out.tokenNumberError = String(e.message).slice(0, 200) }
  try { mod.Config({ heartbeatSeconds: 'nope' }); out.badNumberThrew = false } catch (e) { out.badNumberThrew = true; out.badNumberError = String(e.message).slice(0, 200) }
  try { mod.Config({ allowedTypes: 'not-an-array' }); out.badArrayThrew = false } catch (e) { out.badArrayThrew = true; out.badArrayError = String(e.message).slice(0, 200) }

  // 真 schema 的输出直接喂给 apply()，必须能挂载
  const routes = []
  let effectRan = false
  const effects = []
  const ctx = {
    webServer: {
      host: '127.0.0.1',
      port: 12345,
      register (r) { routes.push(r); return () => { const i = routes.indexOf(r); if (i >= 0) routes.splice(i, 1) } },
    },
    effect (fn) { effectRan = true; const d = fn(); effects.push(d); return d },
    logger: { info () {}, warn () {}, error () {} },
  }
  // 真 schemastery 会把配置解析成带默认值的对象
  mod.apply(ctx, mod.Config({ token: 'from-real-schema' }))
  out.applyRegistered = routes.length
  out.applyRouteKind = routes[0]?.kind
  out.applyRoutePath = routes[0]?.path
  out.effectRan = effectRan
  // 卸载 disposer 可用
  let disposed = false
  for (const d of effects) { if (typeof d === 'function') { await d(); disposed = true } }
  out.disposerRan = disposed
  out.routesAfterDispose = routes.length

  out.ok = true
} catch (e) {
  out.error = String(e.stack ?? e)
}
writeFileSync('./result.json', JSON.stringify(out, null, 2))
`)

  console.log('--- probe 输出（真 schemastery 分支）---')
  const run = spawnSync(process.execPath, ['probe.mjs'], { cwd: tmp, stdio: 'inherit' })
  console.log('--- probe 结束 ---\n')

  const resultPath = join(tmp, 'result.json')
  let r
  try {
    r = JSON.parse(readFileSync(resultPath, 'utf8'))
  } catch {
    console.log(`FAIL: probe 没有产出 result.json（spawn status=${run.status}）`)
    process.exit(1)
  }

  await test('插件在真 schemastery 下能加载（probe ok）', () => {
    assert.equal(r.ok, true, r.error ?? 'probe reported ok=false')
  })

  await test("SCHEMA_SOURCE === '@deepseek-ai/schemastery'（走的是真 schema 分支，不是回退）", () => {
    assert.equal(r.schemaSource, '@deepseek-ai/schemastery')
  })

  await test('Config 是真 Schema（function，可调用校验）', () => {
    assert.equal(r.configType, 'function', `typeof Config === ${r.configType}`)
    assert.equal(r.configIsFunction, true)
  })

  await test('Config({token}) 补齐全部默认值且值正确', () => {
    const v = r.resolved
    assert.equal(v.token, 'abc')
    assert.equal(v.basePath, '/islandbridge')
    assert.equal(v.maxFrameBytes, 65536)
    assert.equal(v.maxFramesPerSecond, 60)
    assert.equal(v.maxFrameBurst, 120)
    assert.equal(v.heartbeatSeconds, 15)
    assert.equal(v.maxClients, 32)
    assert.equal(v.maxClientBufferBytes, 1048576)
    assert.deepEqual(v.allowedTypes, [])
    assert.equal(v.allowNonLoopback, false)
    assert.equal(v.allowCors, false)
  })

  await test('真 schema 的解析结果能直接喂给 apply()，注册出 prefix 路由', () => {
    assert.equal(r.applyRegistered, 1, `registered ${r.applyRegistered}`)
    assert.equal(r.applyRouteKind, 'prefix')
    assert.equal(r.applyRoutePath, '/islandbridge')
    assert.equal(r.effectRan, true)
  })

  await test('真 schema 下 apply() 的 disposer 能卸载并摘掉路由', () => {
    assert.equal(r.disposerRan, true)
    assert.equal(r.routesAfterDispose, 0)
  })

  console.log(`\n真校验器对非法输入的行为（记录，不作断言）:`)
  console.log(`  token: 123           -> ${r.tokenNumberThrew ? `抛出: ${r.tokenNumberError}` : '未抛出（被强制转换）'}`)
  console.log(`  heartbeatSeconds:'x' -> ${r.badNumberThrew ? `抛出: ${r.badNumberError}` : '未抛出（被强制转换）'}`)
  console.log(`  allowedTypes: 'x'    -> ${r.badArrayThrew ? `抛出: ${r.badArrayError}` : '未抛出（被强制转换）'}`)
  console.log(`  Config.toString() 片段: ${JSON.stringify(r.configString)}`)

  // 无论真 schema 是否对类型错误抛异常，apply() 里的 normalizeConfig 都是最终防线，
  // 所以下面这条必须成立：非法类型经过 normalizeConfig 会被钳回安全值。
  await test('最终防线：apply() 自己的 normalizeConfig 兜住真 schema 放过的非法值', async () => {
    const { normalizeConfig } = await import('../index.js')
    const { config } = normalizeConfig({ maxFrameBytes: 1, heartbeatSeconds: -5, allowNonLoopback: 'yes' })
    assert.equal(config.maxFrameBytes, 64)
    assert.equal(config.heartbeatSeconds, 1)
    assert.equal(config.allowNonLoopback, false)
  })

  rmSync(tmp, { recursive: true, force: true })

  console.log(`\n结果: ${passed} passed, ${failed} failed`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((err) => { console.error('crashed:', err); process.exit(2) })
