/**
 * Authenticated static downloads: a browser that already holds the gateway
 * session cookie can pull build artifacts (the APK) from the bridge without a
 * separate upload channel. Files are restricted to one flat directory — the
 * request never supplies a path, only a file name.
 *
 * @module dsh-mobile-bridge/download
 */

import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import path from 'node:path'
import { tokenFromCookie } from './session.js'

/** Extension -> content type for the artifacts worth handing out. */
const TYPES = Object.freeze({
  '.apk': 'application/vnd.android.package-archive',
  '.aab': 'application/octet-stream',
  '.zip': 'application/zip',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
})

/** A file name is flat, non-empty and starts with an alphanumeric character. */
const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/

/**
 * Pick a content type from the file name.
 *
 * @param {string} name file name
 * @returns {string} MIME type
 */
export function contentTypeFor(name) {
  return TYPES[path.extname(name).toLowerCase()] ?? 'application/octet-stream'
}

/**
 * Turn a request path into an absolute file path inside `dir`.
 *
 * @param {string} dir directory that is allowed to be served
 * @param {string} rawName still percent-encoded file name from the URL
 * @returns {string} absolute path
 * @throws {Error} when the name is malformed or escapes the directory
 */
export function resolveDownload(dir, rawName) {
  let name
  try {
    name = decodeURIComponent(rawName)
  } catch {
    throw new Error('malformed percent-encoding in file name')
  }
  if (!SAFE_NAME.test(name) || name.includes('..')) {
    throw new Error(`unsafe file name: ${JSON.stringify(name)}`)
  }
  const base = path.resolve(dir)
  const target = path.resolve(base, name)
  if (path.dirname(target) !== base) {
    throw new Error(`file name escapes the download directory: ${JSON.stringify(name)}`)
  }
  return target
}

/**
 * Answer one download request. Sends the response itself; never throws.
 *
 * @param {import('node:http').IncomingMessage} req request
 * @param {import('node:http').ServerResponse} res response
 * @param {{ dir: string, basePath: string, verify: (token: string | undefined) => Promise<{ ok: boolean, reason?: string }>, allowUnauthenticated: boolean, log: (line: string) => void }} options wiring
 * @returns {Promise<void>} resolves once the response is handed off
 */
export async function serveDownload(req, res, options) {
  const { dir, basePath, verify, allowUnauthenticated, log } = options
  const send = (status, body, headers = {}) => {
    if (res.headersSent) {
      res.end()
      return
    }
    res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', ...headers })
    res.end(body)
  }

  try {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      send(405, 'method not allowed\n', { allow: 'GET, HEAD' })
      return
    }

    const token = tokenFromCookie(req.headers.cookie)
    if (!(allowUnauthenticated && !token)) {
      const verdict = await verify(token)
      if (!verdict.ok) {
        log(`reject download: ${verdict.reason ?? 'unauthorized'}`)
        send(401, 'dsh web session required: open this link from a browser logged into the gateway\n')
        return
      }
    }

    let target
    try {
      target = resolveDownload(dir, (req.url ?? '').split('?')[0].slice(basePath.length + 1))
    } catch (error) {
      send(400, `${String(error.message)}\n`)
      return
    }

    let info
    try {
      info = await stat(target)
    } catch {
      send(404, `no such file in the download directory: ${path.basename(target)}\n`)
      return
    }
    if (!info.isFile()) {
      send(404, `not a file: ${path.basename(target)}\n`)
      return
    }

    const headers = {
      'content-type': contentTypeFor(target),
      'content-length': String(info.size),
      'content-disposition': `attachment; filename="${path.basename(target)}"`,
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    }
    if (req.method === 'HEAD') {
      res.writeHead(200, headers)
      res.end()
      return
    }
    res.writeHead(200, headers)
    const stream = createReadStream(target)
    stream.on('error', (error) => {
      log(`download stream failed: ${String(error)}`)
      res.destroy()
    })
    res.on('close', () => stream.destroy())
    stream.pipe(res)
    log(`served ${path.basename(target)} (${info.size} bytes)`)
  } catch (error) {
    log(`download handler threw: ${String(error)}`)
    send(500, 'download failed\n')
  }
}
