/**
 * Model-facing tool surface. Each tool is a thin adapter over one phone
 * operation so the model never sees transport vocabulary.
 *
 * @module dsh-mobile-bridge/tools
 */

/** JSON Schema for the optional device selector shared by every phone tool. */
const DEVICE_PROPERTY = {
  device: { type: 'string', description: 'Target phone id from phone_list. Omit when exactly one phone is online.' },
}

/**
 * Build the tool list bound to a registry.
 *
 * @param {import('./phones.js').PhoneRegistry} phones live phone registry
 * @returns {Array<{ name: string, description: string, inputSchema: object, handler: (args: Record<string, unknown>) => Promise<unknown> }>} tools
 */
export function createTools(phones) {
  return [
    {
      name: 'phone_list',
      description:
        'List phones that are currently connected to this server. Call this first to learn which phone ids are online and which operations they support.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      async handler() {
        const list = phones.list()
        return { online: list.length, phones: list }
      },
    },
    {
      name: 'phone_info',
      description: 'Describe one connected phone: model, declared capabilities, connection age and last activity.',
      inputSchema: { type: 'object', properties: { ...DEVICE_PROPERTY }, additionalProperties: false },
      async handler(args) {
        const conn = phones.resolve(args.device)
        return { id: conn.id, name: conn.name, model: conn.model, caps: conn.caps, since_ms: Date.now() - conn.since }
      },
    },
    {
      name: 'phone_ping',
      description: 'Round-trip one call to a phone to measure reachability and latency in milliseconds.',
      inputSchema: { type: 'object', properties: { ...DEVICE_PROPERTY }, additionalProperties: false },
      async handler(args) {
        const started = Date.now()
        const data = await phones.call(args.device, 'ping', {}, 10_000)
        return { ok: true, rtt_ms: Date.now() - started, phone: data ?? null }
      },
    },
    {
      name: 'phone_shares',
      description: 'List items the user shared into DSH Mobile from other apps (files and text), newest first.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, limit: { type: 'integer', minimum: 1, maximum: 50, description: 'Maximum items to return (default 20).' } },
        additionalProperties: false,
      },
      async handler(args) {
        const limit = Number(args.limit ?? 20)
        const data = await phones.call(args.device, 'shares', { limit })
        const items = Array.isArray(data?.items) ? data.items : Array.isArray(data) ? data : []
        return { count: items.length, items: items.slice(0, limit) }
      },
    },
    {
      name: 'phone_clipboard_get',
      description: 'Read the current text clipboard of the phone.',
      inputSchema: { type: 'object', properties: { ...DEVICE_PROPERTY }, additionalProperties: false },
      async handler(args) {
        const data = await phones.call(args.device, 'clipboard.get', {})
        return { text: typeof data?.text === 'string' ? data.text : '' }
      },
    },
    {
      name: 'phone_clipboard_set',
      description: 'Put text on the phone clipboard so the user can paste it anywhere.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, text: { type: 'string', description: 'Text to place on the clipboard.' } },
        required: ['text'],
        additionalProperties: false,
      },
      async handler(args) {
        await phones.call(args.device, 'clipboard.set', { text: String(args.text) })
        return { ok: true, bytes: Buffer.byteLength(String(args.text)) }
      },
    },
    {
      name: 'phone_notify',
      description: 'Show a notification on the phone (and vibrate) so the user notices an agent result.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, title: { type: 'string' }, text: { type: 'string' } },
        required: ['text'],
        additionalProperties: false,
      },
      async handler(args) {
        await phones.call(args.device, 'notify', { title: String(args.title ?? 'DSH'), text: String(args.text) })
        return { ok: true }
      },
    },
    {
      name: 'phone_files_save',
      description:
        'Save a file onto the phone so the user can open it: into Downloads/DSH (default), Pictures/DSH, Movies/DSH, Music/DSH or Documents/DSH. Provide exactly one source: base64 `data`, a URL the phone downloads, or a `path` on the phone. Returns the content URI, which phone_open can open.',
      inputSchema: {
        type: 'object',
        properties: {
          ...DEVICE_PROPERTY,
          name: { type: 'string', description: 'File name with extension, for example report.pdf.' },
          mime: { type: 'string', description: 'MIME type; guessed from the name when omitted.' },
          dest: { type: 'string', enum: ['downloads', 'pictures', 'movies', 'audio', 'documents'], description: 'Public collection to save into.' },
          data: { type: 'string', description: 'Base64 file content (data: URLs accepted).' },
          url: { type: 'string', description: 'URL the phone downloads, using its own DSH session.' },
          path: { type: 'string', description: 'Absolute path on the phone.' },
        },
        additionalProperties: false,
      },
      async handler(args) {
        const sources = ['data', 'url', 'path'].filter((key) => typeof args[key] === 'string' && args[key].length > 0)
        if (sources.length !== 1) throw new Error('provide exactly one of data, url or path')
        const result = await phones.call(args.device, 'files.save', {
          name: args.name,
          mime: args.mime,
          dest: args.dest,
          [sources[0]]: args[sources[0]],
        })
        return { ok: true, ...result }
      },
    },
    {
      name: 'phone_open',
      description: 'Open a URL on the phone in its browser or in the app that handles the scheme (http, https, or an app scheme).',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, url: { type: 'string', description: 'Absolute URL or app scheme URI to open.' } },
        required: ['url'],
        additionalProperties: false,
      },
      async handler(args) {
        const url = String(args.url)
        if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(url)) throw new Error('url must be absolute, for example https://example.com')
        await phones.call(args.device, 'open', { url })
        return { ok: true, url }
      },
    },
    {
      name: 'phone_call',
      description:
        'Escape hatch: invoke any operation the connected phone declares, by name with a JSON argument object. Prefer the dedicated tools; use this for newly added phone capabilities.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, op: { type: 'string', description: 'Operation name, for example "shares".' }, args: { type: 'object', description: 'Arguments passed to the operation.' } },
        required: ['op'],
        additionalProperties: false,
      },
      async handler(args) {
        const data = await phones.call(args.device, String(args.op), args.args ?? {})
        return { ok: true, data: data ?? null }
      },
    },
  ]
}
