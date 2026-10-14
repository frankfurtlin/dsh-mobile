/**
 * Runtime configuration for the bridge. Every deployment-varying value is an
 * explicit field here; nothing is defaulted deep inside a request path.
 *
 * @module dsh-mobile-bridge/config
 */

/** @typedef {{ port: number, host: string, gatewayBase: string, publicOrigin: string, wsPath: string, mcpPath: string, callTimeoutMs: number, heartbeatMs: number, maxMessageBytes: number, sessionCacheMs: number, allowUnauthenticated: boolean }} Config */

/** Values used when the environment leaves a field unset. */
export const DEFAULTS = Object.freeze({
  port: 3082,
  host: '127.0.0.1',
  gatewayBase: 'http://127.0.0.1:3081',
  publicOrigin: '',
  wsPath: '/bridge/ws',
  mcpPath: '/mcp',
  callTimeoutMs: 30_000,
  heartbeatMs: 25_000,
  maxMessageBytes: 1024 * 1024,
  sessionCacheMs: 60_000,
  allowUnauthenticated: false,
})

/**
 * Read one integer setting, rejecting anything unparsable.
 *
 * @param {Record<string, string | undefined>} env environment source
 * @param {string} key environment variable name
 * @param {number} fallback value used when the key is absent
 * @returns {number} parsed value
 */
function intFrom(env, key, fallback) {
  const raw = env[key]
  if (raw === undefined || raw === '') return fallback
  const parsed = Number(raw)
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${key} must be a positive number, got ${JSON.stringify(raw)}`)
  }
  return parsed
}

/**
 * Build the configuration from environment variables.
 *
 * @param {Record<string, string | undefined>} env environment source
 * @returns {Config} resolved configuration
 */
export function loadConfig(env = process.env) {
  const gatewayBase = (env.DSH_BRIDGE_GATEWAY ?? DEFAULTS.gatewayBase).replace(/\/+$/, '')
  return {
    port: intFrom(env, 'DSH_BRIDGE_PORT', DEFAULTS.port),
    host: env.DSH_BRIDGE_HOST ?? DEFAULTS.host,
    gatewayBase,
    publicOrigin: (env.DSH_BRIDGE_PUBLIC_ORIGIN ?? DEFAULTS.publicOrigin).replace(/\/+$/, ''),
    wsPath: env.DSH_BRIDGE_WS_PATH ?? DEFAULTS.wsPath,
    mcpPath: env.DSH_BRIDGE_MCP_PATH ?? DEFAULTS.mcpPath,
    callTimeoutMs: intFrom(env, 'DSH_BRIDGE_CALL_TIMEOUT_MS', DEFAULTS.callTimeoutMs),
    heartbeatMs: intFrom(env, 'DSH_BRIDGE_HEARTBEAT_MS', DEFAULTS.heartbeatMs),
    maxMessageBytes: intFrom(env, 'DSH_BRIDGE_MAX_MESSAGE_BYTES', DEFAULTS.maxMessageBytes),
    sessionCacheMs: intFrom(env, 'DSH_BRIDGE_SESSION_CACHE_MS', DEFAULTS.sessionCacheMs),
    // Local development only: accepts phone sockets without a gateway session.
    allowUnauthenticated: env.DSH_BRIDGE_ALLOW_NO_AUTH === '1',
  }
}
