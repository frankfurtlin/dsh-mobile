/**
 * Minimal MCP server over Streamable HTTP (JSON responses, no SSE stream).
 * Supports exactly what dsh-mcp-client needs: `initialize`, `tools/list`,
 * `tools/call`, `ping`, and the `notifications/initialized` notification.
 *
 * @module dsh-mobile-bridge/mcp
 */

import { randomUUID } from 'node:crypto'

const JSONRPC = '2.0'
/** Fallback protocol revision when the client does not request one. */
const DEFAULT_PROTOCOL = '2025-06-18'

/** Errors that mean "bad request from the client". */
const PARSE_ERROR = -32700
const INVALID_REQUEST = -32600
const METHOD_NOT_FOUND = -32601

/**
 * Create a request handler for the MCP endpoint.
 *
 * @param {{ tools: Array<object>, serverInfo: { name: string, version: string } }} options tool table and identity
 * @returns {(req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse) => Promise<boolean>} handler returning true when it answered
 */
export function createMcpHandler({ tools, serverInfo }) {
  const byName = new Map(tools.map((t) => [t.name, t]))

  /**
   * Run one tool and shape the answer for the model.
   *
   * @param {string} name tool name
   * @param {Record<string, unknown>} args tool arguments
   * @returns {Promise<{ content: Array<{ type: string, text: string }>, isError: boolean }>} MCP tool result
   */
  async function callTool(name, args) {
    const tool = byName.get(name)
    if (!tool) return { content: [{ type: 'text', text: `unknown tool ${name}` }], isError: true }
    try {
      const data = await tool.handler(args ?? {})
      if (data && typeof data === 'object' && data.image && typeof data.image.data === 'string') {
        const { image, ...rest } = data
        return {
          content: [
            { type: 'text', text: JSON.stringify(rest, null, 2) },
            { type: 'image', data: image.data, mimeType: image.mimeType ?? 'image/jpeg' },
          ],
          isError: false,
        }
      }
      const text = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
      return { content: [{ type: 'text', text }], isError: false }
    } catch (error) {
      return { content: [{ type: 'text', text: String(error instanceof Error ? error.message : error) }], isError: true }
    }
  }

  /**
   * Answer one JSON-RPC request.
   *
   * @param {any} msg parsed message
   * @returns {Promise<object | null>} JSON-RPC response, or null for notifications
   */
  async function dispatch(msg) {
    if (!msg || typeof msg !== 'object' || msg.jsonrpc !== JSONRPC || typeof msg.method !== 'string') {
      return { jsonrpc: JSONRPC, id: msg?.id ?? null, error: { code: INVALID_REQUEST, message: 'invalid JSON-RPC request' } }
    }
    const { id, method, params } = msg
    switch (method) {
      case 'initialize':
        return {
          jsonrpc: JSONRPC,
          id,
          result: {
            protocolVersion: typeof params?.protocolVersion === 'string' ? params.protocolVersion : DEFAULT_PROTOCOL,
            capabilities: { tools: { listChanged: false } },
            serverInfo,
          },
        }
      case 'tools/list':
        return {
          jsonrpc: JSONRPC,
          id,
          result: {
            tools: tools.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })),
          },
        }
      case 'tools/call':
        return { jsonrpc: JSONRPC, id, result: await callTool(String(params?.name ?? ''), params?.arguments ?? {}) }
      case 'ping':
        return { jsonrpc: JSONRPC, id, result: {} }
      default:
        if (id === undefined) return null // unknown notification: drop it
        return { jsonrpc: JSONRPC, id, error: { code: METHOD_NOT_FOUND, message: `method not found: ${method}` } }
    }
  }

  return async function handle(req, res) {
    const url = new URL(req.url ?? '/', 'http://localhost')
    if (req.method === 'GET') {
      res.writeHead(405, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ error: 'this server answers MCP over POST only' }))
      return true
    }
    if (req.method === 'DELETE') {
      res.writeHead(204).end()
      return true
    }
    if (req.method !== 'POST') return false

    const chunks = []
    let bytes = 0
    for await (const chunk of req) {
      bytes += chunk.length
      if (bytes > 4 * 1024 * 1024) {
        res.writeHead(413).end()
        return true
      }
      chunks.push(chunk)
    }
    let msg
    try {
      msg = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    } catch {
      res.writeHead(400, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ jsonrpc: JSONRPC, id: null, error: { code: PARSE_ERROR, message: 'invalid JSON body' } }))
      return true
    }

    const sessionId = req.headers['mcp-session-id'] ?? randomUUID()
    const out = Array.isArray(msg) ? await Promise.all(msg.map(dispatch)) : await dispatch(msg)
    const headers = { 'content-type': 'application/json', 'mcp-session-id': String(sessionId) }
    if (out === null || (Array.isArray(out) && out.every((o) => o === null))) {
      res.writeHead(202, headers).end()
      return true
    }
    res.writeHead(200, headers)
    res.end(JSON.stringify(out))
    return true
  }
}
