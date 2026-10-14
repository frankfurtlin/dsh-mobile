/**
 * HTTP + WebSocket surface: the MCP endpoint for the DSH agent and the
 * authenticated WebSocket endpoint phones connect to.
 *
 * @module dsh-mobile-bridge/http
 */

import http from 'node:http'
import { createRequire } from 'node:module'
import { WebSocketServer } from 'ws'
import { PhoneRegistry } from './phones.js'
import { createTools } from './tools.js'
import { createMcpHandler } from './mcp.js'
import { createSessionVerifier, tokenFromCookie } from './session.js'
import { serveDownload } from './download.js'

const require = createRequire(import.meta.url)
const { version } = require('../package.json')

/**
 * Compose the server without binding it (used by tests and by `listen`).
 *
 * @param {import('./config.js').Config} config resolved configuration
 * @param {{ log?: (line: string) => void }} [options] optional logger
 * @returns {{ server: import('node:http').Server, phones: PhoneRegistry, wss: WebSocketServer }} composed pieces
 */
export function createServer(config, options = {}) {
  const log = options.log ?? ((line) => console.log(`[bridge] ${line}`))
  const phones = new PhoneRegistry({ callTimeoutMs: config.callTimeoutMs, maxMessageBytes: config.maxMessageBytes })
  const sessions = createSessionVerifier({ gatewayBase: config.gatewayBase, sessionCacheMs: config.sessionCacheMs })
  const mcp = createMcpHandler({ tools: createTools(phones), serverInfo: { name: 'dsh-mobile-bridge', version } })

  const server = http.createServer((req, res) => {
    const path = (req.url ?? '/').split('?')[0]
    if (path === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: true, service: 'dsh-mobile-bridge', phones: phones.list().length, version }))
      return
    }
    if (path === config.mcpPath) {
      mcp(req, res).catch((error) => {
        log(`mcp failure: ${String(error)}`)
        if (!res.headersSent) res.writeHead(500).end()
      })
      return
    }
    if (config.downloadDir && (path === config.downloadPath || path.startsWith(`${config.downloadPath}/`))) {
      serveDownload(req, res, {
        dir: config.downloadDir,
        basePath: config.downloadPath,
        verify: sessions.verify,
        allowUnauthenticated: config.allowUnauthenticated,
        log,
      }).catch((error) => {
        log(`download failure: ${String(error)}`)
        if (!res.headersSent) res.writeHead(500).end()
      })
      return
    }
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(
      JSON.stringify({
        error: 'not found',
        routes: ['/healthz', config.mcpPath, config.wsPath, ...(config.downloadDir ? [`${config.downloadPath}/<file>`] : [])],
      }),
    )
  })

  const wss = new WebSocketServer({ noServer: true, maxPayload: config.maxMessageBytes })

  server.on('upgrade', (req, socket, head) => {
   try {
    const url = new URL(req.url ?? '/', 'http://localhost')
    if (url.pathname !== config.wsPath) {
      socket.destroy()
      return
    }
    if (config.publicOrigin && req.headers.origin !== config.publicOrigin) {
      log(`reject upgrade: origin ${JSON.stringify(req.headers.origin ?? null)} != ${config.publicOrigin}`)
      socket.write('HTTP/1.1 403 Forbidden\r\n\r\n')
      socket.destroy()
      return
    }
    const token = tokenFromCookie(req.headers.cookie)
    if (config.allowUnauthenticated && !token) {
      wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req))
      return
    }
    sessions.verify(token)
      .then((verdict) => {
        if (!verdict.ok) {
          log(`reject upgrade: ${verdict.reason ?? 'unauthorized'}${verdict.status ? ` (status ${verdict.status})` : ''}`)
          socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
          socket.destroy()
          return
        }
        wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req))
      })
      .catch((error) => {
        log(`upgrade error: ${String(error)}`)
        socket.destroy()
      })
   } catch (error) {
     // A throw here must never take the whole bridge down.
     log(`upgrade handler threw: ${String(error)}`)
     socket.destroy()
   }
  })

  wss.on('connection', (ws) => {
    /** @type {object | null} */
    let conn = null
    ws.isAlive = true
    ws.on('pong', () => {
      ws.isAlive = true
      if (conn) phones.touch(conn)
    })
    ws.on('message', (data) => {
      const text = data.toString()
      if (!conn) {
        let msg
        try {
          msg = JSON.parse(text)
        } catch {
          return
        }
        if (msg?.t !== 'hello') return
        conn = phones.attach(ws, msg)
        log(`phone connected: ${conn.id} (${conn.name}, ${conn.model}) caps=${conn.caps.join(',') || 'none'}`)
        ws.send(JSON.stringify({ t: 'welcome', id: conn.id, heartbeat_ms: config.heartbeatMs }))
        return
      }
      phones.onMessage(conn, text)
    })
    const fail = (reason) => () => {
      if (conn) log(`phone disconnected: ${conn.id} (${reason})`)
      phones.detach(conn ?? { id: 'unknown', ws, pending: new Map() }, reason)
    }
    ws.on('close', fail('closed'))
    ws.on('error', fail('error'))
  })

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) {
        ws.terminate()
        continue
      }
      ws.isAlive = false
      ws.ping()
    }
  }, config.heartbeatMs)
  heartbeat.unref?.()

  server.on('close', () => {
    clearInterval(heartbeat)
    for (const ws of wss.clients) ws.terminate()
  })

  return { server, phones, wss }
}

/**
 * Bind the bridge to its configured host and port.
 *
 * @param {import('./config.js').Config} config resolved configuration
 * @param {{ log?: (line: string) => void }} [options] optional logger
 * @returns {Promise<{ server: import('node:http').Server, phones: PhoneRegistry, url: string, close: () => Promise<void> }>} listening handle
 */
export function listen(config, options = {}) {
  const { server, phones } = createServer(config, options)
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(config.port, config.host, () => {
      const url = `http://${config.host}:${config.port}`
      resolve({
        server,
        phones,
        url,
        close: () =>
          new Promise((done) => {
            server.close(() => done())
            server.closeAllConnections?.()
          }),
      })
    })
  })
}
