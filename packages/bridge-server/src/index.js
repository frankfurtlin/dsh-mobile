#!/usr/bin/env node
/**
 * Bridge entry point: MCP endpoint for the DSH agent plus the phone WebSocket.
 *
 * @module dsh-mobile-bridge
 */

import { loadConfig } from './config.js'
import { listen } from './http.js'

const config = loadConfig()
const handle = await listen(config)

console.log(`[bridge] ${handle.url}${config.mcpPath}  (MCP, loopback only)`)
console.log(`[bridge] ${handle.url}${config.wsPath}  (phone WebSocket, published through Caddy)`)
console.log(`[bridge] gateway session checks go to ${config.gatewayBase}`)
if (config.allowUnauthenticated) {
  console.warn('[bridge] WARNING: DSH_BRIDGE_ALLOW_NO_AUTH=1 — phone sockets are accepted without a gateway session')
}

let closing = false
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (closing) process.exit(1)
    closing = true
    console.log(`[bridge] ${signal} received, closing`)
    handle.close().finally(() => process.exit(0))
  })
}
