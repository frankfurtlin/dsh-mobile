#!/usr/bin/env node
/**
 * Mobile layout health check for a running DSH instance.
 *
 * Why this exists: dozens of third-party plugins are written for the desktop
 * three-column layout. On a 390px screen some of them cover prose, push the page
 * sideways, ship 8px tap targets or depend on :hover — but "which plugin is
 * ugly" is guesswork until it is measured. This tool measures.
 *
 * It answers three questions in one run:
 *   1. WHAT is wrong on the live DOM   — overflow, floating overlays covering the
 *      prose, tiny tap targets, tiny text, horizontal scrollers, hover-only
 *      affordances, missing :active feedback;
 *   2. WHO is responsible             — every offending element's class tokens are
 *      grepped against the installed plugin packages, so the report names a
 *      plugin instead of "something";
 *   3. WHAT ELSE is likely to break   — each installed plugin's shipped CSS/JS is
 *      scanned for the patterns that caused (1) above, including on pages this
 *      run never opened (settings dialogs, panels, ...).
 *
 * Pseudo-state analysis is static (stylesheets matched against the element), not
 * simulated: pressing real buttons during a health check would send messages and
 * toggle settings.
 *
 * Usage:
 *   node tools/check-mobile-layout.mjs --url "http://127.0.0.1:3098/?token=..."
 *   node tools/check-mobile-layout.mjs --url ... --json docs/verification/mobile-layout.json
 *   node tools/check-mobile-layout.mjs --url ... --profile web --skip-source
 *
 * Exit code is always 0 unless --fail-on <severity> is given and exceeded.
 */

import { existsSync, readdirSync, readFileSync, realpathSync, statSync, writeFileSync, mkdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, relative } from 'node:path'
import { resolvePlaywright } from './lib/playwright.mjs'

/* ------------------------------------------------------------------ args */

const args = process.argv.slice(2)
const flag = (name) => args.includes(`--${name}`)
const value = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback
}

const url = value('url')
if (!url) {
  console.error('usage: node tools/check-mobile-layout.mjs --url <dsh url> [--json <out>] [--profile web] [--skip-source]')
  process.exit(2)
}
const jsonOut = value('json')
const profileName = value('profile', 'web')
const skipSource = flag('skip-source')
const [vw, vh] = value('viewport', '390x844').split('x').map(Number)
const failOn = value('fail-on', '')

/* --------------------------------------------------------------- helpers */

const SEVERITY_ORDER = { critical: 3, high: 2, medium: 1, low: 0 }
const severity = { critical: [], high: [], medium: [], low: [] }
const note = (level, message) => severity[level].push(message)

const shorten = (text, max = 70) => (text.length > max ? `${text.slice(0, max - 1)}…` : text)

/* ---------------------------------------------------- plugin source scan */

/**
 * Patterns that keep showing up as the root cause of mobile breakage. Each one
 * carries the reason it matters, so the report is readable without the code.
 */
const SOURCE_PATTERNS = [
  { key: 'position:fixed', re: /position:\s*fixed/g, level: 'high', why: '固定浮层在窄屏上容易压住正文' },
  { key: '100vh', re: /\b100vh\b/g, level: 'high', why: '手机可视区小于 100vh（地址栏/手势条），弹层会被裁' },
  { key: 'min-width:NNNpx', re: /min-width:\s*([3-9]\d{2,})px/g, level: 'high', why: '最小宽度超过 390px 会顶出横向滚动' },
  { key: 'width:NNNpx', re: /(?<!min-)(?<!max-)width:\s*([3-9]\d{2,})px/g, level: 'medium', why: '固定宽度可能撑破窄屏' },
  { key: 'font-size<12px', re: /font-size:\s*(?:[6-9]|1[01])(?:\.\d+)?px/g, level: 'medium', why: '小于 12px 的文字在手机上难读' },
  { key: 'z-index>=100', re: /z-index:\s*([1-9]\d{2,})/g, level: 'low', why: '高 z-index 叠加容易互相盖住' },
  { key: ':hover', re: /:hover/g, level: 'low', why: '触屏没有 hover，只靠它显示的操作等于不可用' },
]

/** Where a plugin keeps its shipped code. */
function pluginDirs(name, profileDir) {
  const base = join(profileDir, 'node_modules', name)
  if (!existsSync(base)) return []
  const dirs = [base]
  for (const sub of ['lib', 'dist', 'build', 'styles', 'assets']) {
    const p = join(base, sub)
    if (existsSync(p) && statSync(p).isDirectory()) dirs.push(p)
  }
  return dirs
}

function listFiles(dir, depth = 0, out = []) {
  if (depth > 3) return out
  let entries
  try {
    entries = readdirSync(dir)
  } catch {
    return out
  }
  for (const entry of entries) {
    const p = join(dir, entry)
    let st
    try {
      st = statSync(p)
    } catch {
      continue
    }
    if (st.isDirectory()) listFiles(p, depth + 1, out)
    else if (/\.(js|mjs|cjs|css)$/.test(entry) && st.size < 4 * 1024 * 1024) out.push(p)
  }
  return out
}

/** Read a file as text (plugins ship UTF-8; binary getters are skipped by extension). */
function readText(path) {
  try {
    return readFileSync(path, 'utf8')
  } catch {
    return ''
  }
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length
}

/** Scan every installed plugin for the patterns above, and keep its text for attribution. */
function scanPlugins(profileDir, bundles) {
  const results = []
  const texts = new Map() // plugin name -> shipped source text (for class-name attribution)

  for (const name of bundles) {
    const dirs = pluginDirs(name, profileDir)
    if (dirs.length === 0) continue
    const files = [...new Set(dirs.flatMap((d) => listFiles(d)))]
    const pkgPath = join(profileDir, 'node_modules', name, 'package.json')
    let version = null
    try {
      version = JSON.parse(readText(pkgPath)).version ?? null
    } catch {}

    const hits = new Map()
    let bytes = 0
    let blob = ''
    for (const file of files) {
      const text = readText(file)
      bytes += text.length
      if (blob.length < 12_000_000) blob += text
      for (const pattern of SOURCE_PATTERNS) {
        pattern.re.lastIndex = 0
        let match
        while ((match = pattern.re.exec(text)) !== null) {
          const existing = hits.get(pattern.key) ?? { key: pattern.key, level: pattern.level, why: pattern.why, count: 0, samples: [] }
          existing.count += 1
          if (existing.samples.length < 2) {
            existing.samples.push({ file: relative(profileDir, file), line: lineOf(text, match.index), text: shorten(match[0].replace(/\s+/g, ' '), 48) })
          }
          hits.set(pattern.key, existing)
        }
      }
    }
    texts.set(name, blob)

    if (hits.size > 0) {
      const merged = [...hits.values()].sort((a, b) => SEVERITY_ORDER[b.level] - SEVERITY_ORDER[a.level] || b.count - a.count)
      results.push({ name, version, files: files.length, kb: Math.round(bytes / 1024), hits: merged })
    }
  }

  return { results, texts }
}

/* ----------------------------------------------------------- DOM probing */

/**
 * Full-screen fixed layers that swallow taps. A plugin that opens a dialog (or
 * leaves a mask behind) on load makes the whole page feel broken on a phone,
 * and it also blocks this tool — so it is measured before anything is clicked.
 */
const BLOCKERS = () => {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const out = []
  for (const el of document.querySelectorAll('body *')) {
    const s = getComputedStyle(el)
    if (s.position !== 'fixed') continue
    const r = el.getBoundingClientRect()
    if (r.width < vw * 0.8 || r.height < vh * 0.8) continue
    if (s.pointerEvents === 'none') continue
    const classes = typeof el.className === 'string' ? el.className : ''
    const tappables = el.querySelectorAll('button, a[href], input, textarea, [contenteditable="true"]').length
    out.push({
      classes: classes.slice(0, 100),
      id: el.id || null,
      role: el.getAttribute('role'),
      ariaLabel: el.getAttribute('aria-label'),
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 120),
      zIndex: s.zIndex,
      background: s.backgroundColor,
      tappables,
    })
  }
  return out
}

/** Everything the page-side scan collects in one pass. */
const SCAN = () => {
  const cs = (el) => getComputedStyle(el)
  const vw = window.innerWidth
  const vh = window.innerHeight

  const visible = (el) => {
    const s = cs(el)
    if (s.display === 'none' || s.visibility === 'hidden' || Number(s.opacity) === 0) return false
    const r = el.getBoundingClientRect()
    return r.width > 1 && r.height > 1
  }
  const classes = (el) => {
    const c = el.className
    return typeof c === 'string' ? c : (c && c.baseVal) || ''
  }
  const pathOf = (el) => {
    const parts = []
    let cur = el
    for (let i = 0; i < 3 && cur && cur !== document.body; i += 1) {
      const own = classes(cur).trim().split(/\s+/).filter(Boolean).slice(0, 2).join('.')
      parts.unshift(cur.tagName.toLowerCase() + (own ? `.${own}` : ''))
      cur = cur.parentElement
    }
    return parts.join(' > ')
  }
  const labelOf = (el) =>
    (el.getAttribute('aria-label') || el.getAttribute('title') || (el.textContent || '').trim().slice(0, 24) || el.tagName.toLowerCase()).trim()

  const all = [...document.querySelectorAll('body *')]

  // Real safe-area value, so a 20px composer inset can be judged as right or excessive.
  const probe = document.createElement('div')
  probe.style.cssText = 'position:fixed;left:0;bottom:0;width:0;height:0;padding-bottom:env(safe-area-inset-bottom,0px)'
  document.body.appendChild(probe)
  const safeAreaBottom = Math.round((parseFloat(cs(probe).paddingBottom) || 0) * 10) / 10
  probe.remove()

  /**
   * Text wider than its box is normal when an ancestor clips or scrolls it
   * (ellipsis, horizontal scroller). Only unclipped overflow actually makes the
   * page scroll sideways.
   */
  const clippedByAncestor = (el) => {
    let cur = el.parentElement
    for (let i = 0; i < 8 && cur && cur !== document.documentElement; i += 1) {
      const s = cs(cur)
      if (/(hidden|auto|scroll|clip)/.test(`${s.overflowX}${s.overflow}`)) return true
      if (s.textOverflow === 'ellipsis') return true
      cur = cur.parentElement
    }
    return false
  }

  // 1) horizontal overflow
  const overflow = []
  for (const el of all) {
    if (!visible(el)) continue
    const r = el.getBoundingClientRect()
    const over = Math.round(Math.max(r.right - vw, -r.left))
    if (over <= 1) continue
    if (clippedByAncestor(el)) continue
    overflow.push({ path: pathOf(el), classes: classes(el).slice(0, 100), over, width: Math.round(r.width) })
  }
  overflow.sort((a, b) => b.over - a.over)

  // 2) horizontal scrollers (wide tables / code blocks: acceptable but worth knowing)
  const scrollers = []
  for (const el of all) {
    if (!visible(el)) continue
    const s = cs(el)
    if (!/(auto|scroll)/.test(s.overflowX)) continue
    if (el.scrollWidth <= el.clientWidth + 4) continue
    scrollers.push({ path: pathOf(el), scrollWidth: el.scrollWidth, clientWidth: el.clientWidth })
  }

  // 3) floating layers vs the prose column
  const proseEl = [...document.querySelectorAll('[class*="markdown"]')]
    .filter((el) => {
      const r = el.getBoundingClientRect()
      return r.width > 120 && r.height > 80
    })
    .sort((a, b) => b.getBoundingClientRect().height - a.getBoundingClientRect().height)[0]
  const proseRect = proseEl ? proseEl.getBoundingClientRect() : null

  /**
   * How much of the prose column this layer actually intercepts. Geometry alone
   * lies: panels parked off-screen or wrappers with pointer-events:none still
   * report a full-viewport rect. Hit-testing nine points inside the prose tells
   * us whether a tap there would land on this layer.
   */
  const tapShare = (el, rect) => {
    if (!rect || rect.width < 20 || rect.height < 20) return 0
    let hits = 0
    let total = 0
    for (let i = 0; i < 3; i += 1) {
      for (let j = 0; j < 3; j += 1) {
        const x = rect.left + (rect.width * (i + 0.5)) / 3
        const y = rect.top + (rect.height * (j + 0.5)) / 3
        if (x < 0 || y < 0 || x > vw || y > vh) continue
        const hit = document.elementFromPoint(x, y)
        if (!hit) continue
        total += 1
        if (hit === el || el.contains(hit)) hits += 1
      }
    }
    return total === 0 ? 0 : hits / total
  }

  const overlays = []
  for (const el of all) {
    if (!visible(el)) continue
    const s = cs(el)
    if (s.position !== 'fixed' && s.position !== 'sticky') continue
    // A fixed ancestor that *contains* the prose is the page frame, not a floating layer.
    if (proseEl && el.contains(proseEl)) continue
    const r = el.getBoundingClientRect()
    const areaRatio = (r.width * r.height) / (vw * vh)
    if (areaRatio < 0.02) continue
    let proseOverlap = 0
    if (proseRect) {
      const ox = Math.max(0, Math.min(r.right, proseRect.right) - Math.max(r.left, proseRect.left))
      const oy = Math.max(0, Math.min(r.bottom, proseRect.bottom) - Math.max(r.top, proseRect.top))
      proseOverlap = (ox * oy) / (proseRect.width * proseRect.height)
    }
    const intercepted = tapShare(el, proseRect)
    if (intercepted < 0.15) continue
    overlays.push({
      path: pathOf(el),
      classes: classes(el).slice(0, 100),
      id: el.id || null,
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 60),
      tappables: el.querySelectorAll('button, a[href], input, textarea').length,
      position: s.position,
      areaRatio: Math.round(areaRatio * 1000) / 1000,
      proseOverlap: Math.round(proseOverlap * 1000) / 1000,
      intercepted: Math.round(intercepted * 100) / 100,
      zIndex: s.zIndex,
    })
  }
  overlays.sort((a, b) => b.proseOverlap - a.proseOverlap)

  // 4) tap targets below 44x32 (a full-width 36px list row is fine to hit;
  //    a 28x28 icon button is not)
  //
  // The box is only the *visual* size: an ::after overlay can extend the tappable
  // area without changing it (that is how the 44px floor is implemented here), so
  // probe outward with elementFromPoint and measure the real reach.
  const INTERACTIVE = 'button, a[href], [role="button"], input, select, textarea, summary'
  const reach = (el, cx, cy, dx, dy) => {
    let max = 0
    let crowded = false
    for (let d = 3; d <= 24; d += 3) {
      const px = cx + dx * d
      const py = cy + dy * d
      if (px < 0 || py < 0 || px > vw || py > vh) break
      const hit = document.elementFromPoint(px, py)
      if (hit && (hit === el || el.contains(hit))) {
        max = d
        continue
      }
      // Stopped by another control: expanding here would steal its taps, so a
      // small box is the right call rather than a defect to fix.
      if (hit && hit.closest && hit.closest(INTERACTIVE)) crowded = true
      break
    }
    return { max, crowded }
  }

  /** Growable only if no ancestor clips overflow — otherwise the extra hit area is cut off. */
  const clippedAncestor = (el) => {
    let cur = el.parentElement
    for (let i = 0; i < 4 && cur && cur !== document.body; i += 1) {
      const s = cs(cur)
      if (/(hidden|auto|scroll|clip)/.test(`${s.overflowX}${s.overflow}`)) return true
      cur = cur.parentElement
    }
    return false
  }

  const smallTargets = []
  for (const el of document.querySelectorAll('button, a[href], [role="button"], input, select, textarea, summary')) {
    if (!visible(el)) continue
    const r = el.getBoundingClientRect()
    if (r.width >= 40 && r.height >= 32) continue
    const cx = r.left + r.width / 2
    const cy = r.top + r.height / 2
    const left = reach(el, cx, cy, -1, 0)
    const right = reach(el, cx, cy, 1, 0)
    const up = reach(el, cx, cy, 0, -1)
    const down = reach(el, cx, cy, 0, 1)
    const effW = r.width + left.max + right.max
    const effH = r.height + up.max + down.max
    if (effW >= 40 && effH >= 32) continue
    const clipped = clippedAncestor(el)
    smallTargets.push({
      path: pathOf(el),
      w: Math.round(r.width),
      h: Math.round(r.height),
      hitW: Math.round(effW),
      hitH: Math.round(effH),
      crowded: left.crowded || right.crowded || up.crowded || down.crowded,
      clipped,
      label: labelOf(el),
    })
  }

  // 5) text below 12px
  const tinyText = []
  for (const el of all) {
    if (!visible(el)) continue
    const hasOwnText = [...el.childNodes].some((n) => n.nodeType === 3 && (n.textContent || '').trim().length > 1)
    if (!hasOwnText) continue
    const fontSize = parseFloat(cs(el).fontSize)
    if (!(fontSize > 0) || fontSize >= 12) continue
    tinyText.push({ path: pathOf(el), fontSize, text: (el.textContent || '').trim().slice(0, 28) })
  }

  return {
    viewport: { w: vw, h: vh },
    pageOverflow: document.documentElement.scrollWidth - vw,
    safeAreaBottom,
    metaViewport: [...document.querySelectorAll('meta[name="viewport"]')].map((m) => m.content),
    overflow: overflow.slice(0, 12),
    scrollers: scrollers.slice(0, 8),
    overlays: overlays.slice(0, 10),
    smallTargets: smallTargets.slice(0, 15),
    tinyText: tinyText.slice(0, 10),
  }
}

/**
 * Match stylesheet rules against interactive elements: does this control have
 * any :active rule (press feedback), any :hover rule (dead on touch), and is the
 * hover rule guarded by @media (hover: hover)?
 */
const PSEUDO_AUDIT = (limit) => {
  const rules = []
  const walk = (list, media) => {
    for (const rule of list) {
      if (rule.cssRules && rule.cssRules.length) {
        walk(rule.cssRules, media || (rule.conditionText || ''))
        continue
      }
      if (!rule.selectorText) continue
      rules.push({ selector: rule.selectorText, media, css: rule.style ? rule.style.cssText.slice(0, 120) : '' })
    }
  }
  for (const sheet of document.styleSheets) {
    try {
      walk(sheet.cssRules, '')
    } catch {
      /* cross-origin sheet, skip */
    }
  }

  const matchesBase = (el, selector, pseudoWord) => {
    for (const part of selector.split(',')) {
      const base = part.replace(new RegExp(`:${pseudoWord}\\b`, 'g'), '').trim()
      if (!base) continue
      try {
        if (el.matches(base)) return true
      } catch {
        /* unsupported selector, ignore */
      }
    }
    return false
  }

  const classes = (el) => {
    const c = el.className
    return typeof c === 'string' ? c : (c && c.baseVal) || ''
  }
  const pathOf = (el) => {
    const own = classes(el).trim().split(/\s+/).filter(Boolean).slice(0, 2).join('.')
    return el.tagName.toLowerCase() + (own ? `.${own}` : '')
  }

  const candidates = [...document.querySelectorAll('button, a[href], [role="button"], summary')]
    .filter((el) => {
      const s = getComputedStyle(el)
      const r = el.getBoundingClientRect()
      return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 1 && r.height > 1
    })
    .slice(0, limit)

  return candidates.map((el) => {
    const active = rules.filter((r) => matchesBase(el, r.selector, 'active'))
    const hover = rules.filter((r) => matchesBase(el, r.selector, 'hover'))
    const transition = getComputedStyle(el).transitionDuration
    return {
      path: pathOf(el),
      label: (el.getAttribute('aria-label') || (el.textContent || '').trim().slice(0, 20) || el.tagName.toLowerCase()).trim(),
      activeRules: active.length,
      activeCss: active.slice(0, 2).map((r) => r.css),
      hoverRules: hover.length,
      hoverOnly: hover.length > 0 && active.length === 0,
      hoverMediaGuarded: hover.length > 0 ? hover.every((r) => /hover/.test(r.media)) : true,
      transition: transition === '0s' ? 'none' : transition,
    }
  })
}

/* ------------------------------------------------------------------- run */

const profileDir = join(homedir(), '.dsh', 'profiles', profileName)
const pkgPath = join(profileDir, 'package.json')
let bundles = []
if (existsSync(pkgPath)) {
  try {
    bundles = JSON.parse(readFileSync(pkgPath, 'utf8'))?.dsh?.profile?.bundles ?? []
  } catch {}
}

const { chromium } = resolvePlaywright()
const browser = await chromium.launch()
const context = await browser.newContext({
  viewport: { width: vw, height: vh },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
  timezoneId: 'Asia/Shanghai',
})
const page = await context.newPage()
const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push(String(e).slice(0, 200)))

await page.goto(url, { waitUntil: 'networkidle' }).catch(() => {})
await page.waitForTimeout(2500)

// Anything covering the viewport on load is a finding *and* would block the rest
// of the run, so measure it, then try to get it out of the way (Escape, then a
// click on the mask itself).
const blockersOnLoad = await page.evaluate(BLOCKERS)
if (blockersOnLoad.length > 0) {
  // Evidence first: a screenshot of the offending state beats any description.
  await page.screenshot({ path: '/tmp/mobile-layout-blocker.png' }).catch(() => {})
  await page.keyboard.press('Escape').catch(() => {})
  await page.waitForTimeout(600)
  if ((await page.evaluate(BLOCKERS)).length > 0) {
    await page.evaluate(() => {
      const mask = [...document.querySelectorAll('body *')].find((el) => {
        const s = getComputedStyle(el)
        if (s.position !== 'fixed') return false
        const r = el.getBoundingClientRect()
        if (r.width < window.innerWidth * 0.8 || r.height < window.innerHeight * 0.8) return false
        return el.querySelectorAll('button, a[href], input, textarea').length === 0
      })
      mask?.click()
    })
    await page.waitForTimeout(600)
  }
}

// Mobile drawer: open it, then open the most recent conversation so real prose
// and the composer are in the DOM (a fresh session has neither). JS click: a
// leftover overlay would make a hit-tested click time out for 30s.
await page.evaluate(() => document.querySelector('.dsh-xc-ham')?.click())
await page.waitForTimeout(800)
await page.evaluate(() => {
  const row = [...document.querySelectorAll('[role="treeitem"]')].find((el) => {
    const text = (el.textContent ?? '').trim()
    return text && text !== 'New Session' && !el.querySelector('button[aria-label^="New session in"]')
  })
  row?.click()
})
await page.waitForTimeout(2500)

const dom = await page.evaluate(SCAN)
const pseudo = await page.evaluate(PSEUDO_AUDIT, 24)

/* --------------------------------------------------------- attribution */

/**
 * Map the class names of offending elements onto the plugin whose shipped code
 * contains them. Hashed CSS-module names work too (`_root_o6lrb_6` is literally
 * present in that plugin's bundle), which is what makes "which plugin is it"
 * answerable without guessing from the name.
 */
function attribute(scan, texts, blockers) {
  const tokens = new Set()
  const collect = (classes) => {
    for (const token of (classes || '').split(/\s+/)) {
      if (token.length > 4 && !/^[a-z]{1,2}$/.test(token)) tokens.add(token)
    }
  }
  for (const blocker of blockers) collect(blocker.classes)
  for (const item of scan.overlays) collect(item.classes)
  for (const item of scan.overflow) collect(item.classes)

  const attribution = []
  for (const token of tokens) {
    for (const [name, text] of texts) {
      if (text.includes(token)) {
        attribution.push({ token, plugin: name })
        break
      }
    }
  }
  return attribution
}

const source = skipSource ? { results: [], texts: new Map() } : scanPlugins(profileDir, bundles)

/* ------------------------------------------------------------- scoring */

for (const blocker of blockersOnLoad) {
  const kind = blocker.tappables === 0 ? '遮罩（无任何可点内容）' : `弹层（内含 ${blocker.tappables} 个可点元素）`
  // Only trust the hit test: a layer still fading out has a full-size rect but
  // no longer receives taps.
  const stillBlocking = dom.overlays.some((overlay) => overlay.intercepted >= 0.15)
  if (stillBlocking) note('high', `加载后${kind}持续拦截点击 · ${blocker.classes.slice(0, 60)}`)
  else note('low', `加载后出现${kind}，可关闭、不影响点击（多为首次运行提示）· ${blocker.classes.slice(0, 60)}`)
}

for (const item of dom.overflow) {
  const level = item.over > 8 ? 'high' : 'medium'
  note(level, `横向溢出 ${item.over}px · ${item.path}`)
}
if (dom.pageOverflow > 1) note('high', `整页可横向滚动（scrollWidth 超出 ${dom.pageOverflow}px）`)

for (const item of dom.overlays) {
  if (item.intercepted >= 0.15) {
    note(item.intercepted >= 0.5 ? 'critical' : 'high', `浮层拦截正文区的点击 ${Math.round(item.intercepted * 100)}% · ${item.path}`)
  }
}

const fixableTargets = dom.smallTargets.filter((item) => !item.crowded && !item.clipped)
const constrainedTargets = dom.smallTargets.filter((item) => item.crowded || item.clipped)
if (fixableTargets.length > 0) note(fixableTargets.length >= 8 ? 'high' : 'medium', `${fixableTargets.length} 个点击目标小于 40px 且可安全扩大`)
if (constrainedTargets.length > 0) note('low', `${constrainedTargets.length} 个点击目标小于 40px 但受结构限制（父级 overflow 裁切或紧邻控件），不扩大是正确取舍`)
if (dom.tinyText.length > 0) note(dom.tinyText.length >= 5 ? 'medium' : 'low', `${dom.tinyText.length} 处文字小于 12px`)

const hoverOnly = pseudo.filter((p) => p.hoverOnly)
if (hoverOnly.length > 0) note('medium', `${hoverOnly.length}/${pseudo.length} 个可点元素只有 :hover 规则、没有 :active（触屏等于没反馈）`)
const noTransition = pseudo.filter((p) => p.activeRules === 0 && p.transition === 'none')
if (noTransition.length > 0) note('low', `${noTransition.length} 个可点元素既无 :active 也无过渡，按下无反馈`)

for (const plugin of source.results) {
  const worst = plugin.hits.find((h) => h.level === 'high')
  if (worst) note('medium', `插件 ${plugin.name} 源码含 ${worst.key} ×${worst.count}（${worst.why}）`)
}

/* -------------------------------------------------------------- report */

const line = (text = '') => console.log(text)
const rule = (title) => {
  line()
  line(`── ${title} ${'─'.repeat(Math.max(0, 66 - title.length))}`)
}

line(`移动端体检 · ${vw}×${vh} · ${url.replace(/token=[^&]+/, 'token=***')}`)
line(`profile=${profileName} · 已装插件 ${bundles.length} 个 · safe-area-inset-bottom=${dom.safeAreaBottom}px`)

rule('结论')
for (const level of ['critical', 'high', 'medium', 'low']) {
  for (const message of severity[level]) line(`  [${level.toUpperCase().padEnd(8)}] ${message}`)
}
if (Object.values(severity).every((list) => list.length === 0)) line('  没有发现移动端问题。')

rule('加载后全屏遮挡')
if (blockersOnLoad.length === 0) line('  （无）')
for (const blocker of blockersOnLoad) {
  line(`  z=${String(blocker.zIndex).padStart(5)} · 可点元素 ${String(blocker.tappables).padStart(2)} · ${shorten(blocker.classes, 46) || '(无类名)'}`)
  if (blocker.text) line(`        内容: "${shorten(blocker.text, 64)}"`)
}

rule('浮层（position: fixed/sticky，按拦截正文点击的比例排序）')
if (dom.overlays.length === 0) line('  （无）')
for (const item of dom.overlays.slice(0, 8)) {
  line(`  拦截 ${String(Math.round(item.intercepted * 100)).padStart(3)}% · 占屏 ${String(Math.round(item.areaRatio * 100)).padStart(3)}% · z=${item.zIndex} · ${shorten(item.path, 56)}`)
  if (item.text || item.id) line(`        标识: ${item.id ? `#${item.id} ` : ''}${item.text ? `"${shorten(item.text, 50)}"` : ''} · 可点 ${item.tappables}`)
}

rule('横向溢出的元素')
if (dom.overflow.length === 0) line('  （无）')
for (const item of dom.overflow.slice(0, 8)) line(`  +${String(item.over).padStart(4)}px 宽 ${item.width}px · ${shorten(item.path, 64)}`)

rule('横向滚动容器（表格/代码，通常可接受）')
if (dom.scrollers.length === 0) line('  （无）')
for (const item of dom.scrollers.slice(0, 6)) line(`  ${item.clientWidth}→${item.scrollWidth}px · ${shorten(item.path, 64)}`)

rule('点击目标 < 44px')
if (dom.smallTargets.length === 0) line('  （无）')
for (const item of dom.smallTargets.slice(0, 12)) line(`  ${item.w}×${item.h}（命中 ${item.hitW}×${item.hitH}）${item.clipped ? ' 裁切' : item.crowded ? ' 拥挤' : ' 可扩'} · ${shorten(item.label, 28)} · ${shorten(item.path, 44)}`)

rule('文字 < 12px')
if (dom.tinyText.length === 0) line('  （无）')
for (const item of dom.tinyText.slice(0, 8)) line(`  ${item.fontSize}px · "${shorten(item.text, 26)}" · ${shorten(item.path, 50)}`)

rule('伪状态（触屏反馈）')
line(`  采样 ${pseudo.length} 个可点元素；只有 :hover 无 :active 的 ${hoverOnly.length} 个`)
for (const item of pseudo.slice(0, 10)) {
  const flags = [item.activeRules > 0 ? `active×${item.activeRules}` : 'active✗', item.hoverRules > 0 ? `hover×${item.hoverRules}` : 'hover✗', `transition:${shorten(item.transition, 18)}`]
  line(`  ${shorten(item.label, 22).padEnd(24)} ${flags.join(' ')}`)
}

if (source.results.length > 0) {
  rule('插件源码扫描（预判未打开页面上的问题）')
  for (const plugin of source.results) {
    const own = plugin.name === 'dsh-mobile-ux' ? '  ← 本插件（骨架的抽屉/浮层属预期）' : ''
    line(`  ${plugin.name}@${plugin.version ?? '?'}  ${plugin.files} 文件 / ${plugin.kb}KB${own}`)
    for (const hit of plugin.hits.slice(0, 4)) line(`    ${hit.level.padEnd(6)} ${hit.key} ×${hit.count} — ${hit.why}`)
  }
}

const attribution = attribute(dom, source.texts, blockersOnLoad)
if (attribution.length > 0) {
  rule('归因（DOM 上的类名 → 插件）')
  for (const item of attribution.slice(0, 12)) line(`  ${item.token} ← ${item.plugin}`)
}

if (consoleErrors.length > 0) {
  rule('页面报错')
  for (const message of consoleErrors.slice(0, 5)) line(`  ${message}`)
}

/* ----------------------------------------------------------------- json */

const summary = {
  critical: severity.critical.length,
  high: severity.high.length,
  medium: severity.medium.length,
  low: severity.low.length,
}
line()
line(`合计：critical ${summary.critical} · high ${summary.high} · medium ${summary.medium} · low ${summary.low}`)

if (jsonOut) {
  mkdirSync(dirname(jsonOut), { recursive: true })
  writeFileSync(
    jsonOut,
    `${JSON.stringify(
      {
        checkedAt: new Date().toISOString(),
        url: url.replace(/token=[^&]+/, 'token=***'),
        viewport: dom.viewport,
        profile: profileName,
        plugins: bundles,
        safeAreaBottom: dom.safeAreaBottom,
        metaViewport: dom.metaViewport,
        blockersOnLoad,
        findings: { overflow: dom.overflow, pageOverflow: dom.pageOverflow, scrollers: dom.scrollers, overlays: dom.overlays, smallTargets: dom.smallTargets, tinyText: dom.tinyText, pseudo },
        source: source.results,
        attribution,
        summary,
      },
      null,
      2,
    )}\n`,
  )
  line(`wrote ${jsonOut}`)
}

await browser.close()

if (failOn) {
  const threshold = SEVERITY_ORDER[failOn] ?? 99
  const failing = Object.entries(severity).some(([level, list]) => list.length > 0 && SEVERITY_ORDER[level] >= threshold)
  if (failing) process.exit(1)
}
