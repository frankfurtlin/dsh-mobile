/**
 * Local end-to-end check: boots the bridge in-process, attaches a fake phone,
 * and drives the MCP endpoint the way dsh-mcp-client does.
 *
 * Usage: node tools/smoke.mjs
 */

import { loadConfig } from '../src/config.js'
import { listen } from '../src/http.js'
import { connectFakePhone } from './fake-phone.mjs'

let failures = 0
let checks = 0

/**
 * Record one assertion.
 *
 * @param {string} name assertion label
 * @param {boolean} ok whether it passed
 * @param {string} [detail] extra context printed on failure
 * @returns {void}
 */
function check(name, ok, detail = '') {
  checks += 1
  if (ok) {
    console.log(`  ok   ${name}`)
    return
  }
  failures += 1
  console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
}

const config = { ...loadConfig({}), port: 0, allowUnauthenticated: true }
const handle = await listen(config, { log: () => {} })
const port = handle.server.address().port
const base = `http://127.0.0.1:${port}`
const phone = await connectFakePhone({ url: `ws://127.0.0.1:${port}${config.wsPath}` })

let seq = 0
/**
 * Send one JSON-RPC request to the MCP endpoint.
 *
 * @param {string} method JSON-RPC method
 * @param {Record<string, unknown>} [params] method params
 * @returns {Promise<{ status: number, body: any, sessionId: string | null }>} response
 */
async function rpc(method, params) {
  seq += 1
  const res = await fetch(`${base}${config.mcpPath}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: seq, method, params }),
  })
  const body = await res.json()
  return { status: res.status, body, sessionId: res.headers.get('mcp-session-id') }
}

/**
 * Call a tool and unwrap its text payload.
 *
 * @param {string} name tool name
 * @param {Record<string, unknown>} args tool arguments
 * @returns {Promise<{ isError: boolean, text: string, parsed: any }>} result
 */
async function callTool(name, args) {
  const { body } = await rpc('tools/call', { name, arguments: args })
  const text = body?.result?.content?.[0]?.text ?? ''
  let parsed = null
  try {
    parsed = JSON.parse(text)
  } catch {
    parsed = null
  }
  return { isError: Boolean(body?.result?.isError), text, parsed }
}

console.log('MCP handshake')
const init = await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smoke', version: '0' } })
check('initialize answers 200', init.status === 200, `status ${init.status}`)
check('initialize names the server', init.body?.result?.serverInfo?.name === 'dsh-mobile-bridge', JSON.stringify(init.body))
check('initialize assigns a session id', Boolean(init.sessionId), String(init.sessionId))
const notified = await fetch(`${base}${config.mcpPath}`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': init.sessionId ?? '' },
  body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
})
check('notifications/initialized answers 202', notified.status === 202, `status ${notified.status}`)

console.log('Tool discovery')
const listed = await rpc('tools/list', {})
const names = (listed.body?.result?.tools ?? []).map((t) => t.name)
check('tools/list answers 200', listed.status === 200)
for (const expected of ['phone_list', 'phone_info', 'phone_ping', 'phone_shares', 'phone_clipboard_get', 'phone_clipboard_set', 'phone_notify', 'phone_open', 'phone_call']) {
  check(`tools/list includes ${expected}`, names.includes(expected), names.join(','))
}
check('every tool declares an input schema', (listed.body?.result?.tools ?? []).every((t) => t.inputSchema?.type === 'object'))

console.log('Phone round-trips')
const list = await callTool('phone_list', {})
check('phone_list reports one phone', list.parsed?.online === 1, list.text)
check('phone_list names the fake device', list.parsed?.phones?.[0]?.id === 'fake-phone', list.text)
const ping = await callTool('phone_ping', {})
check('phone_ping returns rtt_ms', Number.isFinite(ping.parsed?.rtt_ms), ping.text)
const clip = await callTool('phone_clipboard_get', {})
check('phone_clipboard_get returns clipboard text', clip.parsed?.text === 'clipboard text from the fake phone', clip.text)
const shares = await callTool('phone_shares', { limit: 5 })
check('phone_shares returns two items', shares.parsed?.count === 2, shares.text)
const opened = await callTool('phone_open', { url: 'https://example.com' })
check('phone_open succeeds', opened.parsed?.ok === true, opened.text)
check('phone received the open call', phone.calls.some((c) => c.op === 'open' && c.args?.url === 'https://example.com'), JSON.stringify(phone.calls.map((c) => c.op)))
const passthrough = await callTool('phone_call', { op: 'info', args: {} })
check('phone_call passthrough returns device info', passthrough.parsed?.data?.model === 'Pixel (fake)', passthrough.text)

console.log('Failure paths')
const ghost = await callTool('phone_list', { device: 'ghost' })
check('phone_list ignores the device filter and lists everything', ghost.parsed?.online === 1, ghost.text)
const unknown = await rpc('tools/call', { name: 'phone_nope', arguments: {} })
check('unknown tool is reported, not thrown', unknown.body?.result?.isError === true, JSON.stringify(unknown.body))
const badMethod = await rpc('does/not/exist', {})
check('unknown method answers -32601', badMethod.body?.error?.code === -32601, JSON.stringify(badMethod.body))
const offline = await callTool('phone_ping', { device: 'ghost' })
check('offline device is reported', offline.isError, offline.text)

console.log('HTTP surface')
const health = await fetch(`${base}/healthz`).then((r) => r.json())
check('/healthz reports one phone', health.ok === true && health.phones === 1, JSON.stringify(health))
const missing = await fetch(`${base}/nope`)
check('unknown route answers 404', missing.status === 404)

phone.close()
await handle.close()
console.log(`\n${checks - failures}/${checks} checks passed`)
process.exit(failures === 0 ? 0 : 1)
