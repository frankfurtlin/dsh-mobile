/**
 * Registry of phones holding an open WebSocket, plus request/response
 * correlation for bridge → phone calls.
 *
 * Wire format (JSON over WebSocket):
 * - phone → bridge: `{t:'hello', device:{id,name,model}, caps:string[]}`
 * - bridge → phone: `{t:'call', id, op, args}`
 * - phone → bridge: `{t:'res', id, ok, data?}` / `{t:'res', id, ok:false, error}`
 *
 * @module dsh-mobile-bridge/phones
 */

import { randomUUID } from 'node:crypto'

/** Operations a v1 phone implements. */
export const KNOWN_OPS = Object.freeze(['ping', 'info', 'shares', 'clipboard.get', 'clipboard.set', 'notify', 'open'])

/**
 * Live phone registry.
 */
export class PhoneRegistry {
  /**
   * @param {{ callTimeoutMs: number, maxMessageBytes: number }} options call defaults
   */
  constructor({ callTimeoutMs, maxMessageBytes }) {
    this.callTimeoutMs = callTimeoutMs
    this.maxMessageBytes = maxMessageBytes
    /** @type {Map<string, object>} */
    this.byDevice = new Map()
    /** @type {Map<object, object>} */
    this.bySocket = new Map()
  }

  /**
   * Register a socket after a successful `hello`.
   *
   * @param {import('ws').WebSocket} ws accepted socket
   * @param {{ device?: { id?: string, name?: string, model?: string }, caps?: string[] }} hello hello payload
   * @returns {object} connection record
   */
  attach(ws, hello = {}) {
    const device = hello.device ?? {}
    const id = String(device.id || randomUUID())
    const existing = this.byDevice.get(id)
    if (existing) this.detach(existing, 'replaced')
    /** @type {object} */
    const conn = {
      id,
      name: String(device.name || device.model || id),
      model: String(device.model || 'unknown'),
      caps: Array.isArray(hello.caps) ? hello.caps.filter((c) => typeof c === 'string') : [],
      ws,
      since: Date.now(),
      lastSeenAt: Date.now(),
      /** @type {Map<string, { resolve: Function, reject: Function, timer: NodeJS.Timeout }>} */
      pending: new Map(),
    }
    this.byDevice.set(id, conn)
    this.bySocket.set(ws, conn)
    return conn
  }

  /**
   * Remove a connection and fail everything waiting on it.
   *
   * @param {object} conn connection record
   * @param {string} reason why the connection ended
   * @returns {void}
   */
  detach(conn, reason = 'closed') {
    if (!conn) return
    this.byDevice.delete(conn.id)
    this.bySocket.delete(conn.ws)
    for (const [id, entry] of conn.pending) {
      clearTimeout(entry.timer)
      entry.reject(new Error(`phone ${conn.id} disconnected (${reason}) while call ${id} was in flight`))
    }
    conn.pending.clear()
  }

  /**
   * Mark a phone as alive.
   *
   * @param {object} conn connection record
   * @returns {void}
   */
  touch(conn) {
    conn.lastSeenAt = Date.now()
  }

  /**
   * Summarize every connected phone.
   *
   * @returns {Array<{ id: string, name: string, model: string, caps: string[], since_ms: number, last_seen_ms: number, pending: number }>} snapshot
   */
  list() {
    return [...this.byDevice.values()].map((c) => ({
      id: c.id,
      name: c.name,
      model: c.model,
      caps: c.caps,
      since_ms: Date.now() - c.since,
      last_seen_ms: Date.now() - c.lastSeenAt,
      pending: c.pending.size,
    }))
  }

  /**
   * Resolve one phone by id, or the only connected phone.
   *
   * @param {string | undefined} deviceId requested device id
   * @returns {object} connection record
   */
  resolve(deviceId) {
    if (deviceId) {
      const conn = this.byDevice.get(String(deviceId))
      if (!conn) {
        const known = [...this.byDevice.keys()].join(', ') || 'none'
        throw new Error(`no phone connected with id ${JSON.stringify(deviceId)}; online: ${known}`)
      }
      return conn
    }
    if (this.byDevice.size === 0) throw new Error('no phone is online; open DSH Mobile and let it connect')
    if (this.byDevice.size > 1) {
      const ids = [...this.byDevice.keys()].join(', ')
      throw new Error(`${this.byDevice.size} phones are online; pass device (one of: ${ids})`)
    }
    return [...this.byDevice.values()][0]
  }

  /**
   * Invoke an operation on a phone and wait for its result.
   *
   * @param {string | undefined} deviceId target device, omitted for the only online phone
   * @param {string} op operation name
   * @param {Record<string, unknown>} args operation arguments
   * @param {number} [timeoutMs] override for the default call timeout
   * @returns {Promise<unknown>} the phone's `data` payload
   */
  call(deviceId, op, args = {}, timeoutMs = this.callTimeoutMs) {
    const conn = this.resolve(deviceId)
    if (conn.ws.readyState !== 1) {
      this.detach(conn, 'socket-not-open')
      throw new Error(`phone ${conn.id} socket is not open; it will reconnect`)
    }
    const id = randomUUID()
    const payload = JSON.stringify({ t: 'call', id, op, args })
    if (Buffer.byteLength(payload) > this.maxMessageBytes) {
      throw new Error(`call payload exceeds ${this.maxMessageBytes} bytes`)
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        conn.pending.delete(id)
        reject(new Error(`phone ${conn.id} did not answer ${op} within ${timeoutMs}ms`))
      }, timeoutMs)
      conn.pending.set(id, { resolve, reject, timer })
      conn.ws.send(payload, (error) => {
        if (!error) return
        clearTimeout(timer)
        conn.pending.delete(id)
        reject(new Error(`send to phone ${conn.id} failed: ${String(error)}`))
      })
    })
  }

  /**
   * Route one inbound message from a phone.
   *
   * @param {object} conn connection record
   * @param {string} text raw frame text
   * @returns {{ hello?: object } | null} hello payload when the message was a hello
   */
  onMessage(conn, text) {
    this.touch(conn)
    let msg
    try {
      msg = JSON.parse(text)
    } catch {
      return null
    }
    if (!msg || typeof msg !== 'object') return null
    if (msg.t === 'res') {
      const entry = conn.pending.get(String(msg.id))
      if (!entry) return null
      clearTimeout(entry.timer)
      conn.pending.delete(String(msg.id))
      if (msg.ok) entry.resolve(msg.data)
      else entry.reject(new Error(String(msg.error || `phone reported failure for ${msg.id}`)))
      return null
    }
    if (msg.t === 'hello') return { hello: msg }
    return null
  }
}
