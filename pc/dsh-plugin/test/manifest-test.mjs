/**
 * dsh-islandbridge 打包/清单验证 —— 证明本目录是一个 DSH 认得的「组合包」(bundle)，
 * 且 cordis.patch.yml 是合法 YAML 且形状正确。
 *
 * 这里模拟本机 DSH (0.2.0-rc.1) 判定「是不是组合包」的那一条：
 *   @deepseek-ai/dsh-plugin-manager/lib/index.js:228
 *     return manifest.dsh?.bundle?.patch === undefined ? undefined : manifest;
 * 并把它指向的 patch 文件真的解析一遍。
 *
 * YAML 用从本机安装包 asar 里取出的真 js-yaml 解析，不手写半个解析器。
 *
 * 注意：**不会**执行 `dsh plugin add`（那会改用户正在用的 profile），
 * 所以「能被 dsh plugin add 真正装上」仍是未验证项 —— 见 docs/DSH_PLUGIN.md。
 *
 * 运行： node pc/dsh-plugin/test/manifest-test.mjs
 */

import { existsSync, readFileSync, mkdirSync, rmSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { tmpdir } from 'node:os'
import process from 'node:process'
import { strict as assert } from 'node:assert'

import { openAsar, dumpAsarTree } from './asar-read.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const pluginDir = resolve(here, '..')

let passed = 0
let failed = 0
async function test (label, fn) {
  try { await fn(); passed++; console.log(`  PASS  ${label}`) } catch (err) {
    failed++
    console.log(`  FAIL  ${label}`)
    console.log(`        ${String(err.message).split('\n').join('\n        ')}`)
  }
}

function findAsar () {
  return [
    process.env.DSH_ASAR,
    'D:\\app\\ai\\DSH\\resources\\app.asar',
  ].filter(Boolean).find((p) => existsSync(p))
}

async function loadYaml () {
  const asarPath = findAsar()
  if (!asarPath) return null
  const tmp = join(tmpdir(), `dsh-islandbridge-yaml-${process.pid}`)
  rmSync(tmp, { recursive: true, force: true })
  mkdirSync(join(tmp, 'node_modules'), { recursive: true })
  const asar = openAsar(asarPath)
  try {
    dumpAsarTree(asar, /^(?:dsh\/)?node_modules\/js-yaml\//, join(tmp, 'node_modules'))
    dumpAsarTree(asar, /^(?:dsh\/)?node_modules\/argparse\//, join(tmp, 'node_modules'))
  } finally {
    asar.close()
  }
  const mod = await import(`file:///${join(tmp, 'node_modules', 'js-yaml', 'index.js').replaceAll('\\', '/')}`)
  return { yaml: mod.default ?? mod, tmp }
}

async function main () {
  console.log('dsh-islandbridge 打包/清单验证\n')

  const pkgPath = join(pluginDir, 'package.json')
  const patchPath = join(pluginDir, 'cordis.patch.yml')
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'))

  const y = await loadYaml()
  if (!y) {
    console.log('SKIP: 找不到 app.asar，无法取真 js-yaml —— patch 未按真解析器验证。')
  } else {
    console.log(`YAML 解析器: 本机安装包里的 js-yaml（提取到 ${y.tmp}）\n`)
  }

  /* ---------------- package.json ---------------- */
  console.log('package.json:')

  await test('type: module', () => assert.equal(pkg.type, 'module'))
  await test('main 指向存在的文件', () => {
    assert.equal(pkg.main, 'index.js')
    assert.ok(existsSync(join(pluginDir, pkg.main)))
  })
  await test('exports 含 "."、"./package.json"、"./cordis.patch.yml"', () => {
    assert.ok(pkg.exports['.'], 'missing "."')
    assert.equal(pkg.exports['./package.json'], './package.json')
    assert.ok(pkg.exports['./cordis.patch.yml'], 'missing "./cordis.patch.yml"')
  })
  await test('exports["."] 解析到的文件真实存在', () => {
    const target = typeof pkg.exports['.'] === 'string' ? pkg.exports['.'] : pkg.exports['.'].default
    assert.ok(existsSync(join(pluginDir, target)), `${target} does not exist`)
  })
  await test('files 列出了发布所需的每一项', () => {
    for (const f of ['index.js', 'cordis.patch.yml']) assert.ok(pkg.files.includes(f), `files missing ${f}`)
  })
  await test('dsh.bundle.patch 存在且指向存在的文件（DSH 判定组合包的那一条）', () => {
    const p = pkg.dsh?.bundle?.patch
    assert.ok(p, 'dsh.bundle.patch is missing — DSH would classify this as not-bundle')
    assert.ok(existsSync(resolve(pluginDir, p)), `${p} does not exist`)
  })
  await test('没有 dsh.client（本插件只需要 host 半）', () => {
    assert.equal(pkg.dsh.client, undefined, 'dsh.client must be absent')
  })
  await test('peerDependencies 里有 @deepseek-ai/cordis', () => {
    assert.ok(pkg.peerDependencies?.['@deepseek-ai/cordis'], 'missing @deepseek-ai/cordis peer')
  })
  await test('peer 里没有任何 @deepseek-ai/dsh* —— 否则会触发版本兼容闸门', () => {
    // @deepseek-ai/dsh-app-boot/lib/index.js:294 只把 "@deepseek-ai/dsh" 和
    // "@deepseek-ai/dsh-*" 的 peer 拿去和运行时版本比对。
    const offenders = Object.keys(pkg.peerDependencies ?? {}).filter((n) => n === '@deepseek-ai/dsh' || n.startsWith('@deepseek-ai/dsh-'))
    assert.deepEqual(offenders, [], `these peers would be gated against 0.2.0-rc.1: ${offenders.join(', ')}`)
  })
  await test('@deepseek-ai/schemastery 若是 peer 必须 optional（避免 autoInstallPeers 去装它）', () => {
    if (pkg.peerDependencies?.['@deepseek-ai/schemastery']) {
      assert.equal(pkg.peerDependenciesMeta?.['@deepseek-ai/schemastery']?.optional, true)
    }
  })

  /* ---------------- cordis.patch.yml ---------------- */
  console.log('\ncordis.patch.yml:')

  await test('文件存在且非空', () => {
    assert.ok(existsSync(patchPath))
    assert.ok(readFileSync(patchPath, 'utf8').trim().length > 0)
  })

  if (y) {
    const doc = y.yaml.load(readFileSync(patchPath, 'utf8'))

    await test('真 js-yaml 能解析，且顶层是序列（loader 要求）', () => {
      assert.ok(Array.isArray(doc), `top level was ${typeof doc}`)
      assert.ok(doc.length >= 1)
    })

    await test('含 - insert: 且 insert 是序列', () => {
      const withInsert = doc.filter((e) => e && e.insert !== undefined)
      assert.equal(withInsert.length, 1, `expected exactly one insert entry, got ${withInsert.length}`)
      assert.ok(Array.isArray(withInsert[0].insert))
    })

    await test('insert 条目形状为 { id, name }，name 与 package.json 的 name 一致', () => {
      const row = doc.find((e) => e?.insert)?.insert[0]
      assert.equal(row.id, 'islandbridge')
      assert.equal(row.name, pkg.name, 'patch name must equal the package name so the loader can resolve it')
      assert.equal(row.name, 'dsh-islandbridge')
    })

    await test('insert 条目没有多余的未知字段（保持最小面）', () => {
      const row = doc.find((e) => e?.insert)?.insert[0]
      const extra = Object.keys(row).filter((k) => !['id', 'name'].includes(k))
      assert.deepEqual(extra, [], `unexpected keys: ${extra.join(', ')}`)
    })

    rmSync(y.tmp, { recursive: true, force: true })
  } else {
    console.log('  (跳过 YAML 断言：没有真解析器)')
  }

  /* ---------------- 模块导出契约 ---------------- */
  console.log('\n模块导出契约:')

  const mod = await import('../index.js')

  await test("导出 name（字符串）且等于 patch 里的插件名", () => {
    assert.equal(typeof mod.name, 'string')
    assert.equal(mod.name, 'islandbridge')
  })
  await test("inject === ['webServer']（本机 webserver 的服务名就是 webServer）", () => {
    assert.deepEqual(mod.inject, ['webServer'])
  })
  await test('导出 Config', () => assert.ok(mod.Config !== undefined))
  await test('导出 apply(ctx, config) 函数', () => {
    assert.equal(typeof mod.apply, 'function')
    assert.ok(mod.apply.length >= 1, `apply.length === ${mod.apply.length}`)
  })
  await test('导出 SCHEMA_SOURCE 便于观察走的是哪条 schema 分支', () => {
    assert.ok(['@deepseek-ai/schemastery', 'schemastery', 'fallback(plain object)'].includes(mod.SCHEMA_SOURCE))
    console.log(`        （本次为 ${mod.SCHEMA_SOURCE}；真 schema 分支由 schema-test.mjs 覆盖）`)
  })

  console.log(`\n结果: ${passed} passed, ${failed} failed`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((err) => { console.error('crashed:', err); process.exit(2) })
