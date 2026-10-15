#!/usr/bin/env node
/**
 * Probe the phone WebSocket auth gate of a deployed bridge.
 *
 * Sends four upgrade attempts and prints the HTTP status the bridge answered
 * with (a rejected upgrade comes back as `unexpected-response`, never as a
 * socket error): no Origin, correct Origin without a cookie, correct Origin
 * with a bogus cookie, and a wrong Origin. The first three must be rejected
 * (403 for the missing Origin, 401 for the others) and only a real phone
 * holding a valid `dsh_gateway_token` may reach `welcome`.
 *
 * Usage:
 *   node tools/probe-ws-auth.mjs --url wss://<your-host>/bridge/ws
 *   node tools/probe-ws-auth.mjs --url ws://127.0.0.1:3082/bridge/ws   # loopback
 *   node tools/probe-ws-auth.mjs --url ... --json out.json
 *
 * --url is required: the public host is deployment-specific, so there is no
 * sensible default and a wrong one would probe the wrong server.
 *
 * Exit code 1 when any expectation fails.
 */

import { writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
// `ws` belongs to bridge-server; resolve it from there so this tool has no
// dependencies of its own.
const require = createRequire(join(ROOT, 'packages/bridge-server/package.json'))
const WebSocket = require('ws')

const argv = process.argv.slice(2)
const urlFlag = argv.indexOf('--url')
const url = urlFlag >= 0 ? argv[urlFlag + 1] : null
if (!url) {
  console.error('missing --url (e.g. --url wss://<your-host>/bridge/ws)')
  process.exit(2)
}
// A browser sends the page's http(s) origin, not the ws(s) URL, so swap the
// scheme instead of reading URL.origin (which would yield wss://…).
const origin = new URL(url).origin.replace(/^ws/, 'http').replace(/\/$/, '')

/**
 * Try one upgrade and report how the server answered.
 *
 * @param {string} label case name
 * @param {Record<string, string>} headers extra headers
 * @param {number} expected expected HTTP status, or 101 for "accepted"
 * @returns {Promise<{label: string, expected: number, status: number|string, ok: boolean, detail: string}>} result
 */
function attempt(label, headers, expected) {
  return new Promise((resolve) => {
    const socket = new WebSocket(url, { headers, handshakeTimeout: 8000 })
    let settled = false
    /**
     * Finish the case exactly once.
     *
     * @param {number|string} status observed status
     * @param {string} detail extra context
     * @returns {void}
     */
    const done = (status, detail) => {
      if (settled) return
      settled = true
      try {
        socket.terminate()
      } catch {
        /* already closed */
      }
      resolve({ label, expected, status, ok: Number(status) === expected, detail })
    }
    socket.on('unexpected-response', (_req, res) => done(res.statusCode, res.statusMessage ?? ''))
    socket.on('open', () => done(101, 'upgraded'))
    socket.on('error', (error) => done('error', String(error.message)))
  })
}

const cases = [
  ['no Origin', {}, 403],
  ['origin only, no cookie', { Origin: origin }, 401],
  ['origin + bogus cookie', { Origin: origin, Cookie: 'dsh_gateway_token=bogus' }, 401],
  ['wrong Origin', { Origin: 'https://evil.example', Cookie: 'dsh_gateway_token=bogus' }, 403],
]

console.log(`Phone WebSocket auth gate — ${url}`)
const results = []
for (const [label, headers, expected] of cases) {
  const result = await attempt(label, headers, expected)
  results.push(result)
  console.log(`  ${result.ok ? 'ok  ' : 'FAIL'} ${label}: got ${result.status}, expected ${expected}${result.detail ? ` (${result.detail})` : ''}`)
}

const failed = results.filter((r) => !r.ok)
const jsonFlag = argv.indexOf('--json')
if (jsonFlag >= 0) {
  const out = argv[jsonFlag + 1]
  writeFileSync(out, `${JSON.stringify({ generatedAt: new Date().toISOString(), url, results }, null, 2)}\n`)
  console.log(`report: ${out}`)
}
console.log(`\n${results.length - failed.length}/${results.length} auth cases behaved as expected`)
process.exit(failed.length === 0 ? 0 : 1)
