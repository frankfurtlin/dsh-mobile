/**
 * Stand-in phone used for local end-to-end checks: speaks the bridge wire
 * protocol over WebSocket without Android in the loop.
 *
 * Usage: node tools/fake-phone.mjs [ws-url]
 */

import WebSocket from 'ws'

/** Operations the fake phone answers, mirroring the v1 app contract. */
export const FAKE_CAPS = ['ping', 'info', 'shares', 'clipboard.get', 'clipboard.set', 'notify', 'open', 'files.save', 'files.open']

/**
 * Open a fake phone connection and complete the hello handshake.
 *
 * @param {{ url: string, deviceId?: string, name?: string, model?: string, headers?: Record<string, string> }} options connection target and identity
 * @returns {Promise<{ ws: WebSocket, id: string, calls: Array<object>, close: () => void }>} live fake phone
 */
export function connectFakePhone({ url, deviceId = 'fake-phone', name = 'Fake Phone', model = 'Pixel (fake)', headers = {} }) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers })
    /** @type {Array<object>} */
    const calls = []
    const phone = { ws, id: deviceId, calls, close: () => ws.close() }
    ws.on('error', reject)
    ws.on('open', () => {
      ws.send(JSON.stringify({ t: 'hello', device: { id: deviceId, name, model }, caps: FAKE_CAPS }))
    })
    ws.on('message', (data) => {
      const msg = JSON.parse(data.toString())
      if (msg.t === 'welcome') {
        phone.id = msg.id
        resolve(phone)
        return
      }
      if (msg.t !== 'call') return
      calls.push(msg)
      const answer = (ok, payload) => ws.send(JSON.stringify({ t: 'res', id: msg.id, ok, ...payload }))
      switch (msg.op) {
        case 'ping':
          return answer(true, { data: { pong: true, at: Date.now() } })
        case 'info':
          return answer(true, { data: { id: deviceId, model, caps: FAKE_CAPS } })
        case 'shares':
          return answer(true, {
            data: {
              items: [
                { id: 's1', name: 'report.pdf', mime: 'application/pdf', at: Date.now() },
                { id: 's2', name: 'note', mime: 'text/plain', text: 'shared from another app', at: Date.now() },
              ],
            },
          })
        case 'clipboard.get':
          return answer(true, { data: { text: 'clipboard text from the fake phone' } })
        case 'clipboard.set':
          return answer(true, { data: { ok: true, bytes: String(msg.args?.text ?? '').length } })
        case 'notify':
        case 'open':
          return answer(true, { data: { ok: true } })
        case 'files.save':
          return answer(true, {
            data: {
              uri: `content://media/external/downloads/${msg.args?.name ?? 'file'}`,
              name: msg.args?.name ?? 'file',
              dest: msg.args?.dest ?? 'downloads',
              size: String(msg.args?.data ?? '').length,
            },
          })
        case 'files.open':
          return answer(true, { data: { ok: true } })
        default:
          return answer(false, { error: `fake phone has no op ${msg.op}` })
      }
    })
  })
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const url = process.argv[2] ?? 'ws://127.0.0.1:3082/bridge/ws'
  const phone = await connectFakePhone({ url })
  console.log(`[fake-phone] connected as ${phone.id}; waiting for calls`)
  phone.ws.on('close', () => console.log('[fake-phone] closed'))
}
