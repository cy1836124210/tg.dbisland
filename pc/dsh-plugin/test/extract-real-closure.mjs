/**
 * Extract a minimal, real dependency closure from the DSH app.asar so the
 * islandbridge plugin can be applied against the *real* Cordis Context and the
 * *real* `@deepseek-ai/dsh-host-webserver` WebServer class — not a fake ctx.
 *
 * Layout: everything lands under `outDir` in a normal node_modules shape
 * (nested `node_modules` are preserved verbatim), so plain `import` works.
 *
 * usage: node extract-real-closure.mjs <app.asar> <outDir>
 */
import { openAsar } from './asar-read.mjs'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

const asarPath = process.argv[2]
const outDir = process.argv[3]
if (!asarPath || !outDir) {
  console.error('usage: extract-real-closure.mjs <app.asar> <outDir>')
  process.exit(2)
}
const asar = openAsar(asarPath)

// name -> the asar path of its package.json (top level or any nested install)
const pkgIndex = new Map()
for (const e of asar.entries) {
  const m = /(?:^|\/)node_modules\/((?:@[^/]+\/)?[^/]+)\/package\.json$/.exec(e.path)
  if (!m) continue
  if (!pkgIndex.has(m[1])) pkgIndex.set(m[1], [])
  pkgIndex.get(m[1]).push(e.path)
}

const ROOTS = ['@deepseek-ai/cordis', '@deepseek-ai/dsh-host-webserver']
const seen = new Set()
const queue = [...ROOTS]
const wanted = []
const missing = []

while (queue.length) {
  const name = queue.shift()
  if (seen.has(name)) continue
  seen.add(name)
  const candidates = pkgIndex.get(name)
  if (!candidates || candidates.length === 0) {
    missing.push(name)
    continue
  }
  wanted.push(name)
  for (const p of candidates) {
    let j
    try { j = JSON.parse(asar.text(p)) } catch { continue }
    const deps = { ...(j.dependencies || {}), ...(j.peerDependencies || {}) }
    for (const d of Object.keys(deps)) if (!seen.has(d)) queue.push(d)
  }
}

console.log(`closure (${wanted.length}):`)
for (const w of wanted) console.log('  ' + w)
if (missing.length) console.log(`not present in this asar (skipped): ${missing.join(', ')}`)

// every entry that lives under any instance of any wanted package
const names = wanted.map((w) => w.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')).join('|')
const re = new RegExp(`^dsh/(?:.*/)?node_modules/(${names})(?:/|$)`)
let n = 0
let bytes = 0
for (const e of asar.entries) {
  if (!re.test(e.path)) continue
  const rel = e.path.replace(/^dsh\/node_modules\//, '')
  const dest = `${outDir}/${rel}`
  mkdirSync(dirname(dest), { recursive: true })
  if (e.size) writeFileSync(dest, asar.read(e.path))
  n++
  bytes += e.size
}
console.log(`dumped ${n} files (${bytes} bytes) to ${outDir}`)
asar.close()

writeFileSync(`${outDir}/../closure.json`, JSON.stringify({ roots: ROOTS, closure: wanted, missing }, null, 2))
