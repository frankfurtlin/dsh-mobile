#!/usr/bin/env node
/**
 * Protocol parity check between the three places an operation has to exist:
 *
 *   Android app  packages/mobile-shell/…/BridgeService.java  (CAPS + switch cases)
 *   server       packages/bridge-server/src/phones.js         (KNOWN_OPS allowlist)
 *   MCP tools    packages/bridge-server/src/tools.js          (tool -> op calls)
 *   test double  packages/bridge-server/tools/fake-phone.mjs  (FAKE_CAPS)
 *
 * A capability that is advertised but not implemented (or implemented but
 * unreachable from any tool) is invisible to a device-free test run unless it is
 * checked mechanically — this is that check.
 *
 * Usage:
 *   node tools/check-protocol-parity.mjs
 *   node tools/check-protocol-parity.mjs --json docs/verification/protocol-parity.json
 *
 * Exit code 1 when any invariant is violated.
 */

import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const APP_SERVICE = join(
  ROOT,
  'packages/mobile-shell/android/app/src/main/java/top/frankfurtlin/dshmobile/BridgeService.java',
)
const PHONES = join(ROOT, 'packages/bridge-server/src/phones.js')
const TOOLS = join(ROOT, 'packages/bridge-server/src/tools.js')
const FAKE = join(ROOT, 'packages/bridge-server/tools/fake-phone.mjs')

/** Ops the server answers by itself, so no tool round-trip is expected. */
const SERVER_SIDE_OPS = new Set(['info'])

const read = (file) => readFileSync(file, 'utf8')

/**
 * Extract every single-quoted or double-quoted string from a `{…}`/`[…]` literal.
 *
 * @param {string} source text to search
 * @param {RegExp} pattern pattern whose first group wraps the literal body
 * @returns {string[]} quoted values, in order
 */
function literals(source, pattern) {
  const body = source.match(pattern)
  if (!body) return []
  return [...body[1].matchAll(/['"]([^'"]+)['"]/g)].map((m) => m[1])
}

const service = read(APP_SERVICE)
const appCaps = literals(service, /String\[\]\s+CAPS\s*=\s*\{([^}]*)\}/s)
const appCases = [...service.matchAll(/case\s+"([^"]+)"/g)].map((m) => m[1])
const knownOps = literals(read(PHONES), /KNOWN_OPS\s*=\s*Object\.freeze\(\[([^\]]*)\]/s)
const fakeCaps = literals(read(FAKE), /FAKE_CAPS\s*=\s*\[([^\]]*)\]/s)
const toolsSource = read(TOOLS)
const toolOps = [...toolsSource.matchAll(/phones\.call\([^,]+,\s*'([^']+)'/g)].map((m) => m[1])
const toolNames = [...toolsSource.matchAll(/name:\s*'(phone_[a-z0-9_]+)'/g)].map((m) => m[1])

const results = []
/**
 * Record one invariant.
 *
 * @param {string} name invariant label
 * @param {boolean} ok whether it holds
 * @param {string} [detail] context shown on failure
 * @returns {void}
 */
function check(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), detail })
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}${detail && !ok ? ` — ${detail}` : ''}`)
}

const setOf = (list) => new Set(list)
const diff = (a, b) => [...setOf(a)].filter((x) => !setOf(b).has(x))

console.log('Protocol parity')
check('all four surfaces were parsed', appCaps.length > 0 && knownOps.length > 0 && fakeCaps.length > 0 && toolNames.length > 0,
  `caps=${appCaps.length} ops=${knownOps.length} fake=${fakeCaps.length} tools=${toolNames.length}`)

const advertisedNotImplemented = diff(appCaps, appCases)
check('every advertised capability has a handler in the app', advertisedNotImplemented.length === 0,
  `advertised but missing case: ${advertisedNotImplemented.join(', ')}`)

const implementedNotAdvertised = diff(appCases, appCaps)
check('every app handler is advertised in CAPS', implementedNotAdvertised.length === 0,
  `handled but not advertised: ${implementedNotAdvertised.join(', ')}`)

const unknownToolOps = diff(toolOps, knownOps)
check('every tool call uses an allowed op', unknownToolOps.length === 0, unknownToolOps.join(', '))

const capsOutsideAllowlist = diff(appCaps, knownOps)
check('the app advertises nothing outside the server allowlist', capsOutsideAllowlist.length === 0, capsOutsideAllowlist.join(', '))

const toolsBeyondAppCaps = diff(toolOps, appCaps)
check('the server only calls ops the app advertises', toolsBeyondAppCaps.length === 0, toolsBeyondAppCaps.join(', '))

check('the fake phone mirrors the app capabilities exactly',
  [...setOf(fakeCaps)].sort().join(',') === [...setOf(appCaps)].sort().join(','),
  `only in fake: ${diff(fakeCaps, appCaps).join(', ') || '-'}; only in app: ${diff(appCaps, fakeCaps).join(', ') || '-'}`)

const duplicateTools = toolNames.filter((n, i) => toolNames.indexOf(n) !== i)
check('tool names are unique', duplicateTools.length === 0, duplicateTools.join(', '))

const unreachable = knownOps.filter((op) => !setOf(toolOps).has(op) && !SERVER_SIDE_OPS.has(op))
check('every allowed op is reachable from a tool', unreachable.length === 0, `no tool calls: ${unreachable.join(', ')}`)

const duplicateOps = toolOps.filter((op, i) => toolOps.indexOf(op) !== i)
console.log(`       ops: ${knownOps.length} allowed, ${setOf(toolOps).size} driven by tools, ${appCaps.length} advertised by the app`)
console.log(`       tools: ${toolNames.length}${duplicateOps.length ? ` (ops used by several tools: ${[...setOf(duplicateOps)].join(', ')})` : ''}`)

const failed = results.filter((r) => !r.ok)
const jsonFlag = process.argv.indexOf('--json')
if (jsonFlag >= 0) {
  const out = process.argv[jsonFlag + 1]
  const { writeFileSync } = await import('node:fs')
  writeFileSync(out, `${JSON.stringify({ generatedAt: new Date().toISOString(), appCaps, knownOps, toolOps, toolNames, checks: results }, null, 2)}\n`)
  console.log(`report: ${out}`)
}
console.log(`\n${results.length - failed.length}/${results.length} parity checks passed`)
process.exit(failed.length === 0 ? 0 : 1)
