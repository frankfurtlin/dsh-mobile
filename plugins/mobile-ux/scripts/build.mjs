#!/usr/bin/env node
/**
 * Generate `client.js` for dsh-mobile-ux.
 *
 * The client half runs in the browser and must be a single self-contained script
 * (DSH loads it through `window.__ModuleLoader__`), so it is assembled here from
 * two parts:
 *
 *   1. the vendored skeleton client (`vendor/dsh-mobile-xc/lib/client.js`,
 *      upstream dsh-mobile-xc 0.6.7, MIT, ids already renamed to
 *      `dsh-mobile-ux`) — overlay drawer, safe areas, PWA hooks, canary audit;
 *   2. the presentation stylesheet (`styles/mobile.css`), inlined into a
 *      `__ModuleLoader__.load` wrapper with the anchor audit.
 *
 * Keeping the CSS in a real `.css` file and generating the wrapper keeps the
 * stylesheet reviewable and lets `--check` catch a stale artifact.
 *
 * Usage:
 *   node scripts/build.mjs           # (re)write client.js
 *   node scripts/build.mjs --check   # exit 1 when client.js is out of date
 */

import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const root = new URL('../', import.meta.url)
const cssPath = fileURLToPath(new URL('styles/mobile.css', root))
const vendorClientPath = fileURLToPath(new URL('vendor/dsh-mobile-xc/lib/client.js', root))
const clientPath = fileURLToPath(new URL('client.js', root))
const check = process.argv.includes('--check')

const css = await readFile(cssPath, 'utf8')
const vendorClient = await readFile(vendorClientPath, 'utf8')

/**
 * Class-name prefixes for UI owned by *other* plugins that this stylesheet only
 * hides on phones. Their absence is not drift: the plugin may simply not be
 * installed, so they are audited separately and never fail the drift check.
 */
const OPTIONAL_PREFIXES = ['dcp-']

/**
 * Every `[class*="…"]` fragment used by the presentation stylesheet,
 * deduplicated in source order. The client half audits these against the live
 * DOM, so the list cannot silently drift out of sync with the CSS. (The
 * vendored skeleton has its own canary self-check for its structural anchors.)
 */
const FRAGMENTS = [...new Set([...css.matchAll(/\[class\*=['"]([^'"]+)['"]\]/g)].map((m) => m[1]))]
const isOptional = (fragment) => OPTIONAL_PREFIXES.some((prefix) => fragment.startsWith(prefix))
const ANCHORS = FRAGMENTS.filter((fragment) => !isOptional(fragment))
const OPTIONAL_ANCHORS = FRAGMENTS.filter(isOptional)

/** The presentation client half. Two placeholders are substituted below. */
const TEMPLATE = `/**
 * dsh-mobile-ux — Presentation client half.
 *
 * GENERATED FILE. Edit \`styles/mobile.css\` and run \`node scripts/build.mjs\`;
 * \`node scripts/build.mjs --check\` fails when this file is out of date.
 *
 * This block only owns LOOK AND FEEL (typography, contrast, touch feedback).
 * Layout/skeleton behaviour (drawer, grid, safe areas) lives in the vendored
 * dsh-mobile-xc block above — do not move layout properties between them.
 *
 * Responsibilities:
 *   1. inject the presentation stylesheet once (desktop is untouched: every
 *      rule sits inside \`@media (max-width: 1023px)\`);
 *   2. expose \`audit()\` so a drifted anchor is a loud failure instead of a
 *      silently missing style after a DSH upgrade.
 *
 * NOTE: this block is self-executing on purpose, not a
 * \`window.__ModuleLoader__.load\` module — the vendored skeleton block above
 * already registers the bundle's module id, and ModuleLoader only honours one
 * id per plugin. Plain side effects are all the presentation half needs.
 */
;(() => {
  if (typeof document === 'undefined') return

  const STYLE_ID = 'dsh-mobile-ux-presentation'
  const MOBILE_QUERY = '(max-width: 1023px)'
  const CSS = __CSS__
  /** Class-name fragments the stylesheet relies on (extracted from the CSS). */
  const ANCHORS = __ANCHORS__
  /** Fragments that belong to optional third-party UI: missing is fine. */
  const OPTIONAL_ANCHORS = __OPTIONAL__

  /** Inject the stylesheet once; returns a disposer. */
  function ensureStyle() {
    const existing = document.querySelector("style[data-mobile-ux='" + STYLE_ID + "']")
    if (existing) return () => {}
    const tag = document.createElement('style')
    tag.setAttribute('data-mobile-ux', STYLE_ID)
    tag.textContent = CSS
    document.head.appendChild(tag)
    return () => tag.remove()
  }

  /**
   * Check every anchor fragment against the live DOM.
   *
   * \`missing\` is drift and must stay empty; \`optionalMissing\` only means the
   * third-party UI we hide is not installed right now.
   *
   * @returns {{narrow: boolean, anchors: number, missing: string[], optionalMissing: string[]}} audit
   */
  function audit() {
    if (typeof document === 'undefined') return { narrow: false, anchors: 0, missing: [], optionalMissing: [] }
    const probe = (fragment, into) => {
      try {
        if (document.querySelector('[class*="' + fragment + '"]') === null) into.push(fragment)
      } catch (error) {
        into.push(fragment)
      }
    }
    const missing = []
    for (const fragment of ANCHORS) probe(fragment, missing)
    const optionalMissing = []
    for (const fragment of OPTIONAL_ANCHORS) probe(fragment, optionalMissing)
    const narrow = typeof window.matchMedia === 'function' && window.matchMedia(MOBILE_QUERY).matches
    return { narrow, anchors: ANCHORS.length, missing, optionalMissing }
  }

  /**
   * Loud, once-per-page drift report. Only runs when explicitly asked for
   * (\`?mobile-ux-debug=1\`), because a view without chat content legitimately
   * has no markdown container to find.
   */
  function reportIfDebugging() {
    let debug = false
    try {
      debug = new URLSearchParams(window.location.search).get('mobile-ux-debug') === '1'
    } catch (error) {
      debug = false
    }
    if (!debug) return
    const wait = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : (fn) => setTimeout(fn, 0)
    wait(() => wait(() => {
      const result = audit()
      const optionalNote = result.optionalMissing.length > 0
        ? '；可选锚点未渲染（第三方 UI 未安装，正常）: ' + result.optionalMissing.join(', ')
        : ''
      if (result.missing.length > 0) {
        console.warn('[dsh-mobile-ux] 锚点失配 ' + (result.anchors - result.missing.length) + '/' + result.anchors + ': ' + result.missing.join(', ') + ' — 移动端表现层可能已部分失效，请重新钉锚点' + optionalNote + '。')
      } else {
        console.info('[dsh-mobile-ux] 表现层 ' + result.anchors + '/' + result.anchors + ' 锚点命中 (narrow=' + result.narrow + ')' + optionalNote)
      }
    }))
  }

  ensureStyle()
  reportIfDebugging()

  // Reachable from devtools and from the acceptance/screenshot tooling.
  window.__dshMobileUx = Object.assign(window.__dshMobileUx ?? {}, {
    presentation: { audit, STYLE_ID, dispose: ensureStyle() },
  })
})()
`

const banner = `// GENERATED for dsh-mobile-ux by scripts/build.mjs — do not edit by hand.\n` +
  `// Part 1: vendored dsh-mobile-xc 0.6.7 skeleton client (MIT, ids renamed to dsh-mobile-ux).\n` +
  `// Part 2: presentation stylesheet wrapper (source: styles/mobile.css).\n`
const indent = (value) => JSON.stringify(value, null, 2).replace(/\n/g, '\n    ')
const generated =
  banner +
  vendorClient.trimEnd() +
  '\n\n' +
  TEMPLATE
    .replace('__CSS__', JSON.stringify(css))
    .replace('__ANCHORS__', indent(ANCHORS))
    .replace('__OPTIONAL__', indent(OPTIONAL_ANCHORS))

if (check) {
  let current = ''
  try {
    current = await readFile(clientPath, 'utf8')
  } catch (error) {
    console.error('client.js missing — run: node scripts/build.mjs')
    process.exit(1)
  }
  if (current !== generated) {
    console.error('client.js is stale — run: node scripts/build.mjs')
    process.exit(1)
  }
  console.log(`client.js in sync (${ANCHORS.length}+${OPTIONAL_ANCHORS.length} anchors, ${(css.length / 1024).toFixed(1)} KiB css, ${(vendorClient.length / 1024).toFixed(1)} KiB skeleton)`)
} else {
  await writeFile(clientPath, generated)
  console.log(`wrote client.js (${ANCHORS.length}+${OPTIONAL_ANCHORS.length} anchors, ${(css.length / 1024).toFixed(1)} KiB css, ${(vendorClient.length / 1024).toFixed(1)} KiB skeleton)`)
}
