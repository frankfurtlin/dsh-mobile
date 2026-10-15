#!/usr/bin/env node
/**
 * CSS-module anchor drift check for mobile UI plugins.
 *
 * Mobile UI plugins for the DSH web UI style the shell by targeting CSS-module
 * class names such as `pI_x6G_frame`. Those hashes are pinned to the frontend
 * build: when DSH is upgraded, the class names change and a plugin can silently
 * stop matching anything. This script extracts every such token from an
 * installed plugin and reports how many still appear in the DSH frontend
 * bundles, so a broken upgrade is caught immediately instead of on the phone.
 *
 * Usage:
 *   node tools/check-anchors.mjs                          # every plugin in the web profile
 *   node tools/check-anchors.mjs --plugin dsh-mobile-xc   # just one
 *   node tools/check-anchors.mjs --min 0.8 --json out.json
 *
 * Exit code is non-zero when any plugin scores below --min (default 0.7).
 */

import { readFileSync, readdirSync, statSync, existsSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, basename, resolve } from 'node:path'

// `dsh` is a global install, so its package sits next to the running node binary
// (`<prefix>/bin/node` -> `<prefix>/lib/node_modules`). DSH_INSTALL overrides.
const DSH_INSTALL = process.env.DSH_INSTALL
  ?? join(dirname(process.execPath), '..', 'lib/node_modules/@deepseek-ai/dsh')
const DEFAULT_PLUGIN_ROOT = process.env.DSH_WEB_PROFILE_MODULES
  ?? join(homedir(), '.dsh/profiles/web/node_modules')

/**
 * Parse `--flag value` / `--flag=value` / booleans.
 *
 * @param {string[]} argv raw arguments
 * @returns {Record<string, string|boolean>} parsed flags
 */
function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (!arg.startsWith('--')) continue
    const eq = arg.indexOf('=')
    if (eq > 0) {
      out[arg.slice(2, eq)] = arg.slice(eq + 1)
    } else if (argv[i + 1] && !argv[i + 1].startsWith('--')) {
      out[arg.slice(2)] = argv[i + 1]
      i += 1
    } else {
      out[arg.slice(2)] = true
    }
  }
  return out
}

const flags = parseArgs(process.argv.slice(2))
if (flags.help) {
  console.log(readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(2, 17).join('\n'))
  process.exit(0)
}

const min = Number(flags.min ?? 0.7)
const pluginRoot = String(flags['plugin-root'] ?? DEFAULT_PLUGIN_ROOT)
const corpusRoot = String(flags.corpus ?? join(DSH_INSTALL, 'node_modules/@deepseek-ai'))
const jsonOut = flags.json ? String(flags.json) : null

/**
 * Walk a directory, returning files whose name matches the filter.
 *
 * @param {string} dir root directory
 * @param {(name: string) => boolean} keep file filter
 * @param {number} [maxDepth] depth guard
 * @returns {string[]} absolute file paths
 */
function walk(dir, keep, maxDepth = 4) {
  const found = []
  const stack = [[dir, 0]]
  while (stack.length > 0) {
    const [current, depth] = stack.pop()
    let entries
    try {
      entries = readdirSync(current, { withFileTypes: true })
    } catch {
      continue
    }
    for (const entry of entries) {
      const full = join(current, entry.name)
      if (entry.isDirectory()) {
        if (depth < maxDepth) stack.push([full, depth + 1])
      } else if (entry.isFile() && keep(entry.name)) {
        found.push(full)
      }
    }
  }
  return found
}

/** CSS-module style token: 5-7 mixed-case characters, underscore, a camel/kebab name. */
const ANCHOR_RE = /\b([A-Za-z0-9]{5,7})_([A-Za-z][A-Za-z0-9]{2,})\b/g

/**
 * True for a CSS-module hash prefix: 5-7 chars with both cases present
 * (`pI_x6G`, `nArs4W`), which rules out SCREAMING_CASE constants such as
 * `CLUSTER_SEL` or `MOBILE_CSS` that the same regex shape would otherwise catch.
 *
 * @param {string} prefix hash candidate
 * @returns {boolean} whether it looks like a build hash
 */
function looksLikeHash(prefix) {
  return /[a-z]/.test(prefix) && /[A-Z]/.test(prefix)
}

/**
 * True for the class-name half: camelCase or kebab-case, never ALL_CAPS.
 *
 * @param {string} name class-name candidate
 * @returns {boolean} whether it looks like a module class name
 */
function looksLikeClassName(name) {
  return /[a-z]/.test(name) && !/^[A-Z0-9_]+$/.test(name)
}
const CORPUS_FILTER = (name) => name.endsWith('.js') || name.endsWith('.css')

console.log(`corpus:  ${corpusRoot}`)
const corpusFiles = existsSync(corpusRoot) ? walk(corpusRoot, CORPUS_FILTER, 3) : []
if (corpusFiles.length === 0) {
  console.error(`no frontend bundles found under ${corpusRoot} - is DSH installed there?`)
  process.exit(2)
}
let corpus = ''
for (const file of corpusFiles) {
  try {
    if (statSync(file).size > 16 * 1024 * 1024) continue
    corpus += readFileSync(file, 'utf8')
  } catch {
    // Unreadable bundle: skip it; a missing anchor will show up as drift.
  }
}
console.log(`         ${corpusFiles.length} bundles, ${(corpus.length / 1024 / 1024).toFixed(1)} MiB scanned`)

/**
 * Pick the plugin packages to inspect.
 *
 * @returns {string[]} plugin package directories
 */
function pluginDirs() {
  if (flags.plugin) {
    const explicit = String(flags.plugin)
    const candidates = [resolve(explicit), join(pluginRoot, explicit)]
    const hit = candidates.find((path) => existsSync(join(path, 'package.json')))
    if (!hit) throw new Error(`plugin not found: ${explicit} (tried ${candidates.join(', ')})`)
    return [hit]
  }
  if (!existsSync(pluginRoot)) throw new Error(`plugin root missing: ${pluginRoot}`)
  const dirs = []
  for (const entry of readdirSync(pluginRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const full = join(pluginRoot, entry.name)
    if (entry.name.startsWith('@')) {
      // Scoped packages live one level deeper: node_modules/@scope/name.
      for (const scoped of readdirSync(full, { withFileTypes: true })) {
        if (scoped.isDirectory()) dirs.push(join(full, scoped.name))
      }
    } else {
      dirs.push(full)
    }
  }
  return dirs.filter((dir) => {
    const pkg = join(dir, 'package.json')
    if (!existsSync(pkg)) return false
    try {
      const meta = JSON.parse(readFileSync(pkg, 'utf8'))
      const name = String(meta.name ?? '')
      // Mobile UI plugins only: the installed profile also holds unrelated runtime deps.
      return /mobile/i.test(name) && !name.startsWith('@deepseek-ai/')
    } catch {
      return false
    }
  })
}

const report = []
let worst = 1
for (const dir of pluginDirs()) {
  const files = walk(dir, (name) => /\.(js|css|mjs|cjs)$/.test(name), 3)
  const tokens = new Set()
  for (const file of files) {
    let text
    try {
      text = readFileSync(file, 'utf8')
    } catch {
      continue
    }
    for (const match of text.matchAll(ANCHOR_RE)) {
      const [token, prefix, name] = match
      if (!looksLikeHash(prefix) || !looksLikeClassName(name)) continue
      tokens.add(token)
    }
  }
  const pluginName = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8')).name ?? basename(dir)
  const all = [...tokens].sort()
  // A plugin with no CSS-module hashes at all is not broken: it means it anchors
  // on semantic class-name fragments / DOM structure instead, which does not
  // drift with the frontend build (ADR-0001's hash-free strategy). This static
  // scan cannot judge those, so report them as n/a rather than a failure; such
  // plugins carry their own runtime anchor audit.
  if (all.length === 0) {
    report.push({ plugin: pluginName, dir, anchors: 0, hits: 0, missing: [], hitRate: null, status: 'hash-free' })
    console.log(`n/a  ${pluginName}: no build-hash anchors — hash-free strategy, static scan does not apply`)
    continue
  }
  const missing = all.filter((token) => !corpus.includes(token))
  const hits = all.length - missing.length
  const rate = hits / all.length
  worst = Math.min(worst, rate)
  const entry = {
    plugin: pluginName,
    dir,
    anchors: all.length,
    hits,
    missing,
    hitRate: Number(rate.toFixed(3)),
    status: rate >= min ? 'ok' : 'drifted',
  }
  report.push(entry)
  const mark = entry.status === 'ok' ? 'ok  ' : 'FAIL'
  console.log(`${mark} ${entry.plugin}: ${hits}/${all.length} anchors still in the frontend (${(rate * 100).toFixed(0)}%, min ${(min * 100).toFixed(0)}%)`)
  if (missing.length > 0) console.log(`       missing: ${missing.slice(0, 12).join(', ')}${missing.length > 12 ? ` (+${missing.length - 12})` : ''}`)
}

if (jsonOut) {
  writeFileSync(jsonOut, `${JSON.stringify({ generatedAt: new Date().toISOString(), min, corpusRoot, plugins: report }, null, 2)}\n`)
  console.log(`\nwrote ${jsonOut}`)
}

const drifted = report.filter((entry) => entry.status === 'drifted')
if (drifted.length > 0) {
  console.log(`\n${drifted.length} plugin(s) below the ${(min * 100).toFixed(0)}% threshold - re-pin them or switch to the coarser fallback (see docs/adr/0001).`)
  process.exit(1)
}
console.log(`\nall ${report.length} plugin(s) within threshold (worst ${(worst * 100).toFixed(0)}%)`)
