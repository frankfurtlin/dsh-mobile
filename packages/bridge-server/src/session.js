/**
 * Gateway session verification. The bridge never mints or parses DSH tokens:
 * it asks the dsh-passwords gateway on loopback whether the presented
 * `dsh_gateway_token` still authenticates.
 *
 * Observed gateway behaviour (localhost:3081): an unauthenticated `GET /`
 * answers `302` to `/gateway/login`; an authenticated one is proxied to the
 * upstream web app and answers `200`.
 *
 * @module dsh-mobile-bridge/session
 */

import { createHash } from 'node:crypto'

/** Name of the cookie holding the gateway session token. */
export const TOKEN_COOKIE = 'dsh_gateway_token'

/**
 * Extract the gateway token from a `Cookie` header.
 *
 * @param {string | undefined} header raw `Cookie` header value
 * @returns {string} token, or an empty string when absent
 */
export function tokenFromCookie(header) {
  if (!header) return ''
  for (const part of header.split(';')) {
    const eq = part.indexOf('=')
    if (eq < 0) continue
    if (part.slice(0, eq).trim() === TOKEN_COOKIE) return part.slice(eq + 1).trim()
  }
  return ''
}

/**
 * Create a verifier with a short positive-result cache.
 *
 * @param {{ gatewayBase: string, sessionCacheMs: number }} options gateway endpoint and cache lifetime
 * @returns {{ verify: (token: string) => Promise<{ ok: boolean, status?: number, reason?: string }> }} verifier
 */
export function createSessionVerifier({ gatewayBase, sessionCacheMs }) {
  /** @type {Map<string, number>} */
  const okUntil = new Map()

  /**
   * Ask the gateway whether this token is still authenticated.
   *
   * @param {string} token gateway session token
   * @returns {Promise<{ ok: boolean, status?: number, reason?: string }>} verdict
   */
  async function verify(token) {
    if (!token) return { ok: false, reason: 'missing-token' }
    const key = createHash('sha256').update(token).digest('hex').slice(0, 16)
    const cached = okUntil.get(key)
    if (cached !== undefined && cached > Date.now()) return { ok: true, status: 200 }

    let status = 0
    try {
      const res = await fetch(`${gatewayBase}/`, {
        method: 'GET',
        redirect: 'manual',
        headers: { cookie: `${TOKEN_COOKIE}=${token}` },
      })
      status = res.status
      // Drain the body so the socket is released even on a rejected verdict.
      await res.arrayBuffer().catch(() => undefined)
    } catch (error) {
      return { ok: false, reason: `gateway-unreachable: ${String(error)}` }
    }

    if (status === 200) {
      okUntil.set(key, Date.now() + sessionCacheMs)
      return { ok: true, status }
    }
    return { ok: false, status, reason: status === 302 || status === 401 ? 'session-expired' : `unexpected-status-${status}` }
  }

  return { verify }
}
