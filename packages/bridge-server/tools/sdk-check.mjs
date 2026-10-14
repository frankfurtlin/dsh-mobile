/**
 * Compatibility check with the exact MCP client DSH uses
 * (`@modelcontextprotocol/client` via `@deepseek-ai/dsh-mcp-client`):
 * boots the bridge, attaches a fake phone, and drives it through the SDK.
 *
 * Usage: node tools/sdk-check.mjs
 */

import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client'
import { loadConfig } from '../src/config.js'
import { listen } from '../src/http.js'
import { connectFakePhone } from './fake-phone.mjs'

const config = { ...loadConfig({}), port: 0, allowUnauthenticated: true }
const handle = await listen(config, { log: () => {} })
const port = handle.server.address().port
const phone = await connectFakePhone({ url: `ws://127.0.0.1:${port}${config.wsPath}` })

const client = new Client({ name: 'dsh-mobile-bridge-sdk-check', version: '0.0.0' }, { capabilities: {} })
const transport = new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}${config.mcpPath}`))
await client.connect(transport)

const { tools } = await client.listTools()
console.log('tools:', tools.map((t) => t.name).join(', '))
const list = await client.callTool({ name: 'phone_list', arguments: {} })
console.log('phone_list:', JSON.stringify(list.content))
const shares = await client.callTool({ name: 'phone_shares', arguments: { limit: 2 } })
console.log('phone_shares:', JSON.stringify(shares.content))

await client.close()
phone.close()
await handle.close()
console.log('sdk-check: ok')
