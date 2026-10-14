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
 * @returns {Promise<{ isError: boolean, text: string, parsed: any, content: any[] }>} result
 */
async function callTool(name, args) {
  const { body } = await rpc('tools/call', { name, arguments: args })
  const content = body?.result?.content ?? []
  const text = content.find((b) => b.type === 'text')?.text ?? ''
  let parsed = null
  try {
    parsed = JSON.parse(text)
  } catch {
    parsed = null
  }
  return { isError: Boolean(body?.result?.isError), text, parsed, content }
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
for (const expected of [
  'phone_list',
  'phone_info',
  'phone_ping',
  'phone_shares',
  'phone_clipboard_get',
  'phone_clipboard_set',
  'phone_notify',
  'phone_open',
  'phone_files_save',
  'phone_files_open',
  'phone_a11y_status',
  'phone_apps',
  'phone_launch',
  'phone_ui_tree',
  'phone_screenshot',
  'phone_tap',
  'phone_swipe',
  'phone_global',
  'phone_call',
]) {
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
const saved = await callTool('phone_files_save', { name: 'report.pdf', mime: 'application/pdf', data: 'aGVsbG8=' })
check('phone_files_save returns a content uri', String(saved.parsed?.uri ?? '').startsWith('content://'), saved.text)
check('phone received the files.save call', phone.calls.some((c) => c.op === 'files.save' && c.args?.name === 'report.pdf'), JSON.stringify(phone.calls.map((c) => c.op)))
const noSource = await callTool('phone_files_save', { name: 'x.txt' })
check('phone_files_save rejects a missing source', noSource.isError, noSource.text)
const openedSaved = await callTool('phone_files_open', { uri: String(saved.parsed?.uri) })
check('phone_files_open opens the saved content uri', openedSaved.parsed?.ok === true && openedSaved.parsed?.uri === saved.parsed?.uri, openedSaved.text)
check('phone received the files.open call', phone.calls.some((c) => c.op === 'files.open' && c.args?.uri === saved.parsed?.uri), JSON.stringify(phone.calls.map((c) => c.op)))
const openedByName = await callTool('phone_files_open', { name: 'report.pdf' })
check('phone_files_open resolves a file by name', String(openedByName.parsed?.uri ?? '').includes('report.pdf'), openedByName.text)
const noFileTarget = await callTool('phone_files_open', {})
check('phone_files_open rejects a missing target', noFileTarget.isError, noFileTarget.text)

console.log('Apps and automation')
const apps = await callTool('phone_apps', {})
check('phone_apps lists the launcher apps', apps.parsed?.count === 2, apps.text)
const launched = await callTool('phone_launch', { package: 'com.example.notes' })
check('phone_launch forwards the package', launched.parsed?.ok === true && launched.parsed?.package === 'com.example.notes', launched.text)
check('phone received the apps.launch call', phone.calls.some((c) => c.op === 'apps.launch' && c.args?.package === 'com.example.notes'), JSON.stringify(phone.calls.map((c) => c.op)))
const a11y = await callTool('phone_a11y_status', {})
check('phone_a11y_status reports the service ready', a11y.parsed?.service === true && a11y.parsed?.allowed === true, a11y.text)
const tree = await callTool('phone_ui_tree', { max_nodes: 50 })
check('phone_ui_tree returns the screen nodes', tree.parsed?.count === 2 && tree.parsed?.nodes?.[1]?.center?.[0] === 540, tree.text)
check('phone received the a11y.tree call', phone.calls.some((c) => c.op === 'a11y.tree' && c.args?.max_nodes === 50), JSON.stringify(phone.calls.map((c) => c.op)))
const shot = await callTool('phone_screenshot', {})
check('phone_screenshot returns an image content block', shot.content.some((b) => b.type === 'image' && b.mimeType === 'image/jpeg' && b.data === 'ZmFrZS1qcGVn'), JSON.stringify(shot.content))
check('phone_screenshot keeps the pixel size in text', shot.parsed?.width === 1080 && shot.parsed?.height === 2400, shot.text)
const tapped = await callTool('phone_tap', { x: 540.4, y: 240.6 })
check('phone_tap rounds to whole pixels', tapped.parsed?.ok === true, tapped.text)
check('phone received the rounded tap', phone.calls.some((c) => c.op === 'a11y.tap' && c.args?.x === 540 && c.args?.y === 241), JSON.stringify(phone.calls.filter((c) => c.op === 'a11y.tap')))
const badTap = await callTool('phone_tap', { x: 'left' })
check('phone_tap rejects a non-numeric point', badTap.isError, badTap.text)
const swiped = await callTool('phone_swipe', { x1: 540, y1: 1800, x2: 540, y2: 400, duration_ms: 250 })
check('phone_swipe succeeds', swiped.parsed?.ok === true, swiped.text)
const home = await callTool('phone_global', { action: 'home' })
check('phone_global presses home', home.parsed?.ok === true, home.text)
const badGlobal = await callTool('phone_global', { action: 'self-destruct' })
check('phone_global rejects an unknown action', badGlobal.isError, badGlobal.text)

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
