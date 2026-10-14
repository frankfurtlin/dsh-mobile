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
      name: 'phone_a11y_status',
      description: 'Report whether the phone can be driven by accessibility automation: whether the Android service is on and whether the user allows it.',
      inputSchema: { type: 'object', properties: { ...DEVICE_PROPERTY }, additionalProperties: false },
      async handler(args) {
        return await phones.call(args.device, 'a11y.status', {})
      },
    },
    {
      name: 'phone_apps',
      description: 'List the apps on the phone that can be launched from its home screen. Call this before phone_launch.',
      inputSchema: { type: 'object', properties: { ...DEVICE_PROPERTY }, additionalProperties: false },
      async handler(args) {
        const data = await phones.call(args.device, 'apps.list', {})
        return { count: data?.apps?.length ?? 0, apps: data?.apps ?? [] }
      },
    },
    {
      name: 'phone_launch',
      description: 'Launch an app on the phone by its package name, taken from phone_apps.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, package: { type: 'string', description: 'Android package name, for example com.tencent.mm.' } },
        required: ['package'],
        additionalProperties: false,
      },
      async handler(args) {
        return await phones.call(args.device, 'apps.launch', { package: String(args.package) })
      },
    },
    {
      name: 'phone_ui_tree',
      description:
        'Read the controls currently on the phone screen: text, content description, view id, class, bounds and tap centre. Call it before phone_tap so coordinates come from the live screen instead of a guess.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, max_nodes: { type: 'integer', description: 'Cap on returned nodes (default 400, max 2000).' } },
        additionalProperties: false,
      },
      async handler(args) {
        const data = await phones.call(args.device, 'a11y.tree', { max_nodes: args.max_nodes })
        return data?.tree ?? data
      },
    },
    {
      name: 'phone_screenshot',
      description: 'Take a screenshot of the phone screen. Returns the image plus its pixel size; use phone_ui_tree when you need tap coordinates.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, max_bytes: { type: 'integer', description: 'Byte budget for the JPEG (default 600000; the transport caps one message at 1 MiB).' } },
        additionalProperties: false,
      },
      async handler(args) {
        const data = await phones.call(args.device, 'a11y.screenshot', { max_bytes: args.max_bytes })
        if (!data?.image) throw new Error('the phone did not return an image')
        return { image: { data: data.image, mimeType: data.mime ?? 'image/jpeg' }, width: data.width, height: data.height, bytes: data.bytes }
      },
    },
    {
      name: 'phone_tap',
      description: 'Tap a point on the phone screen, in screen pixels. Take x and y from phone_ui_tree (each node carries its tap centre).',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, x: { type: 'integer', description: 'Screen x in pixels.' }, y: { type: 'integer', description: 'Screen y in pixels.' } },
        required: ['x', 'y'],
        additionalProperties: false,
      },
      async handler(args) {
        const x = Math.round(Number(args.x))
        const y = Math.round(Number(args.y))
        if (!Number.isFinite(x) || !Number.isFinite(y)) throw new Error('phone_tap needs numeric x and y')
        return await phones.call(args.device, 'a11y.tap', { x, y })
      },
    },
    {
      name: 'phone_swipe',
      description: 'Swipe on the phone screen from one point to another, in screen pixels. Use it to scroll a list or dismiss a card.',
      inputSchema: {
        type: 'object',
        properties: {
          ...DEVICE_PROPERTY,
          x1: { type: 'integer', description: 'Start x in pixels.' },
          y1: { type: 'integer', description: 'Start y in pixels.' },
          x2: { type: 'integer', description: 'End x in pixels.' },
          y2: { type: 'integer', description: 'End y in pixels.' },
          duration_ms: { type: 'integer', description: 'Gesture duration in milliseconds (default 300).' },
        },
        required: ['x1', 'y1', 'x2', 'y2'],
        additionalProperties: false,
      },
      async handler(args) {
        const points = ['x1', 'y1', 'x2', 'y2'].map((key) => Math.round(Number(args[key])))
        if (points.some((value) => !Number.isFinite(value))) throw new Error('phone_swipe needs numeric x1, y1, x2 and y2')
        return await phones.call(args.device, 'a11y.swipe', {
          x1: points[0],
          y1: points[1],
          x2: points[2],
          y2: points[3],
          duration_ms: args.duration_ms,
        })
      },
    },
    {
      name: 'phone_global',
      description: 'Press a system control on the phone: back, home, recents, notifications, quick_settings or lock.',
      inputSchema: {
        type: 'object',
        properties: { ...DEVICE_PROPERTY, action: { type: 'string', enum: ['back', 'home', 'recents', 'notifications', 'quick_settings', 'lock'] } },
        required: ['action'],
        additionalProperties: false,
      },
      async handler(args) {
        const allowed = ['back', 'home', 'recents', 'notifications', 'quick_settings', 'lock']
        const action = String(args.action)
        if (!allowed.includes(action)) throw new Error(`unknown global action: ${action}`)
        return await phones.call(args.device, 'a11y.global', { action })
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
