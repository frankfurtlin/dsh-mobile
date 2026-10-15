#!/usr/bin/env node
/**
 * Generate `client.js` from `styles/mobile.css`.
 *
 * The client half runs in the browser and must be a single self-contained script
 * (DSH loads it through `window.__ModuleLoader__`), so the stylesheet has to be
 * inlined. Keeping the CSS in a real `.css` file and generating the wrapper
 * keeps the stylesheet reviewable and lets `--check` catch a stale artifact.
 *
 * Usage:
 *   node scripts/build.mjs           # (re)write client.js
 *   node scripts/build.mjs --check   # exit 1 when client.js is out of date
 */

import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const root = new URL('../', import.meta.url)
const cssPath = fileURLToPath(new URL('styles/mobile.css', root))
const clientPath = fileURLToPath(new URL('client.js', root))
const check = process.argv.includes('--check')

const css = await readFile(cssPath, 'utf8')

/**
 * Every `[class*="…"]` fragment used by the stylesheet, deduplicated in source
 * order. The client half audits these against the live DOM, so the list cannot
 * silently drift out of sync with the CSS.
 */
const ANCHORS = [...new Set([...css.matchAll(/\[class\*=['"]([^'"]+)['"]\]/g)].map((m) => m[1]))]

/** The client half. Two placeholders are substituted below. */
const TEMPLATE = `/**
 * dsh-mobile-presentation — Client half.
 *
 * GENERATED FILE. Edit \`styles/mobile.css\` and run \`node scripts/build.mjs\`;
 * \`node scripts/build.mjs --check\` fails when this file is out of date.
 *
 * Responsibilities, in order:
 *   1. inject the mobile stylesheet once (desktop is untouched: every rule in
 *      the stylesheet sits inside \`@media (max-width: 1023px)\`);
 *   2. expose \`audit()\` so a drifted anchor is a loud failure instead of a
 *      silently missing style after a DSH upgrade.
 */
window.__ModuleLoader__.load({
  id: 'dsh-mobile-presentation',
  factory: (require) => {
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    const STYLE_ID = 'dsh-mobile-presentation'
    const MOBILE_QUERY = '(max-width: 1023px)'
    const CSS = __CSS__
    /** Class-name fragments the stylesheet relies on (extracted from the CSS). */
    const ANCHORS = __ANCHORS__

    /** Inject the stylesheet once; returns a disposer. */
    function ensureStyle() {
      const existing = document.querySelector("style[data-mobile-presentation='" + STYLE_ID + "']")
      if (existing) return () => {}
      const tag = document.createElement('style')
      tag.setAttribute('data-mobile-presentation', STYLE_ID)
      tag.textContent = CSS
      document.head.appendChild(tag)
      return () => tag.remove()
    }

    /**
     * Check every anchor fragment against the live DOM.
     *
     * @returns {{narrow: boolean, anchors: number, missing: string[]}} audit
     */
    function audit() {
      if (typeof document === 'undefined') return { narrow: false, anchors: 0, missing: [] }
      const missing = []
      for (const fragment of ANCHORS) {
        try {
          if (document.querySelector('[class*="' + fragment + '"]') === null) missing.push(fragment)
        } catch (error) {
          missing.push(fragment)
        }
      }
      const narrow = typeof window.matchMedia === 'function' && window.matchMedia(MOBILE_QUERY).matches
      return { narrow, anchors: ANCHORS.length, missing }
    }

    /**
     * Loud, once-per-page drift report. Only runs when explicitly asked for
     * (\`?mobile-presentation-debug=1\`), because a view without chat content
     * legitimately has no markdown container to find.
     */
    function reportIfDebugging() {
      let debug = false
      try {
        debug = new URLSearchParams(window.location.search).get('mobile-presentation-debug') === '1'
      } catch (error) {
        debug = false
      }
      if (!debug) return
      const wait = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : (fn) => setTimeout(fn, 0)
      wait(() => wait(() => {
        const result = audit()
        if (result.missing.length > 0) {
          console.warn('[dsh-mobile-presentation] 锚点失配 ' + result.missing.length + '/' + result.anchors + ': ' + result.missing.join(', ') + ' — 移动端样式可能已部分失效，请重新钉锚点。')
        } else {
          console.info('[dsh-mobile-presentation] ' + result.anchors + '/' + result.anchors + ' 锚点命中 (narrow=' + result.narrow + ')')
        }
      }))
    }

    function apply(ctx) {
      if (typeof document === 'undefined') return
      const dispose = ensureStyle()
      const onConfigChange = ctx && typeof ctx.effect === 'function'
        ? () => { try { ctx.effect(() => dispose, 'dsh-mobile-presentation: styles') } catch (error) { dispose() } }
        : null
      if (onConfigChange) onConfigChange()
      reportIfDebugging()
    }

    exports.apply = apply
    exports.audit = audit
    exports.STYLE_ID = STYLE_ID
    // Reachable from devtools and from the acceptance/screenshot tooling.
    window.__dshMobilePresentation = exports
    return module.exports
  },
})
`

const banner = `// GENERATED from styles/mobile.css by scripts/build.mjs — do not edit by hand.\n`
const generated = banner + TEMPLATE
  .replace('__CSS__', JSON.stringify(css))
  .replace('__ANCHORS__', JSON.stringify(ANCHORS, null, 2).replace(/\n/g, '\n    '))

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
  console.log(`client.js in sync (${ANCHORS.length} anchors, ${(css.length / 1024).toFixed(1)} KiB css)`)
} else {
  await writeFile(clientPath, generated)
  console.log(`wrote client.js (${ANCHORS.length} anchors, ${(css.length / 1024).toFixed(1)} KiB css)`)
}
