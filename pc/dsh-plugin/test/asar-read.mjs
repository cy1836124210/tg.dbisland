/**
 * 极简 Electron asar 读取器（无依赖）。用于从本机 DSH 安装包里取出
 * 真实的 @deepseek-ai/schemastery，证明插件的 Config 在**真**校验器下成立。
 *
 * asar 布局（Electron，双 pickle）：
 *   [0..4)  u32 = 4             外层 pickle 载荷长度
 *   [4..8)  u32 = headerSize    header pickle blob 的字节数
 *   [8..)   header blob：
 *             [+0] u32 = 内层载荷长度
 *             [+4] u32 = JSON 长度
 *             [+8] JSON，后接 4 字节对齐填充
 *   dataStart = 8 + headerSize
 *
 * CLI：
 *   node asar-read.mjs list  <asar> <pathRegex>
 *   node asar-read.mjs cat   <asar> <memberPath>
 *   node asar-read.mjs dump  <asar> <pathRegex> <outDir>      # 去掉 dsh/node_modules/ 前缀
 *   node asar-read.mjs grep  <asar> <pathRegex> <contentRegex> [maxPerFile] [ctxBytes]
 */

import { readFileSync, openSync, readSync, closeSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import process from 'node:process'

export function openAsar (asarPath) {
  const fd = openSync(asarPath, 'r')
  const sizeBuf = Buffer.alloc(8)
  readSync(fd, sizeBuf, 0, 8, 0)
  const headerSize = sizeBuf.readUInt32LE(4)

  const probe = Buffer.alloc(Math.min(256, headerSize))
  readSync(fd, probe, 0, probe.length, 8)
  const jsonStartInProbe = probe.indexOf('{"files":')
  if (jsonStartInProbe < 4) throw new Error('asar: could not locate the header JSON')
  const jsonLen = probe.readUInt32LE(jsonStartInProbe - 4)
  const jsonBuf = Buffer.alloc(jsonLen)
  readSync(fd, jsonBuf, 0, jsonLen, 8 + jsonStartInProbe)
  const header = JSON.parse(jsonBuf.toString('utf8'))
  const dataStart = 8 + headerSize

  const entries = []
  ;(function walk (node, prefix) {
    for (const [name, entry] of Object.entries(node.files ?? {})) {
      const p = prefix ? `${prefix}/${name}` : name
      if (entry.files) walk(entry, p)
      else entries.push({ path: p, size: Number(entry.size), offset: Number(entry.offset), unpacked: !!entry.unpacked })
    }
  })(header, '')

  const read = (memberPath) => {
    const e = entries.find((x) => x.path === memberPath)
    if (!e) throw new Error(`asar: member not found: ${memberPath}`)
    const buf = Buffer.alloc(e.size)
    if (e.size) readSync(fd, buf, 0, e.size, dataStart + e.offset)
    return buf
  }

  return {
    entries,
    read,
    text: (p) => read(p).toString('utf8'),
    list: (re) => entries.filter((e) => re.test(e.path)),
    close: () => closeSync(fd),
  }
}

/** 把匹配的成员写进 outDir，剥掉 `dsh/node_modules/` 或 `node_modules/` 前缀。 */
export function dumpAsarTree (asar, re, outDir, stripPrefix = /^(?:dsh\/)?node_modules\//) {
  let n = 0
  for (const e of asar.entries) {
    if (!re.test(e.path)) continue
    const rel = e.path.replace(stripPrefix, '')
    const dest = `${outDir}/${rel}`
    mkdirSync(dirname(dest), { recursive: true })
    writeFileSync(dest, asar.read(e.path))
    n++
  }
  return n
}

/* --------------------------- CLI --------------------------- */
const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replaceAll('\\', '/').split('/').pop())
if (isMain) {
  const [, , cmd, asarPath, arg, arg2, arg3] = process.argv
  const asar = openAsar(asarPath)
  try {
    if (cmd === 'list') {
      for (const e of asar.list(new RegExp(arg))) console.log(`${e.size}\t${e.path}`)
    } else if (cmd === 'cat') {
      process.stdout.write(asar.text(arg))
    } else if (cmd === 'dump') {
      console.log(`dumped ${dumpAsarTree(asar, new RegExp(arg), arg2)} files to ${arg2}`)
    } else if (cmd === 'grep') {
      const pathRe = new RegExp(arg)
      const contentRe = new RegExp(arg2)
      const maxPerFile = Number(process.argv[6] ?? 8)
      for (const e of asar.list(pathRe)) {
        if (e.size === 0 || e.size > 4_000_000) continue
        const lines = asar.text(e.path).split('\n')
        let shown = 0
        for (let i = 0; i < lines.length && shown < maxPerFile; i++) {
          if (!contentRe.test(lines[i])) continue
          shown++
          console.log(`${e.path}:${i + 1}: ${lines[i].slice(0, Number(process.argv[7] ?? 200))}`)
        }
      }
    } else {
      console.error('usage: asar-read.mjs <list|cat|dump|grep> <asar> <arg> [arg2]')
      process.exitCode = 2
    }
  } finally {
    asar.close()
  }
}
