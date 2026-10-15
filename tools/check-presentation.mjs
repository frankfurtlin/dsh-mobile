#!/usr/bin/env node
/**
 * Runtime verification for `dsh-mobile-ux` (the merged skeleton+presentation
 * plugin; formerly dsh-mobile-xc + dsh-mobile-presentation).
 *
 * Two things must hold, and neither can be proven by reading the stylesheet:
 *   1. the mobile rules actually land on the real DSH DOM at phone width;
 *   2. the desktop rendering is bit-identical with the plugin stylesheet in and
 *      out — the project's "desktop zero impact" hard constraint (ADR-0001).
 * Point 2 is checked by measuring the same element twice, with the plugin's
 * <style> tag present and then removed, so the assertion does not hard-code any
 * DSH default value that could legitimately change between releases.
 *
 * Usage:
 *   node tools/check-presentation.mjs --url "http://127.0.0.1:3099/?token=..."
 *   node tools/check-presentation.mjs --url ... --json docs/verification/presentation.json
 *
 * Exit code is non-zero when any check fails. Requires a shadow profile that has
 * the plugin mounted (the vendored dsh-mobile-xc skeleton, which owns the
 * mobile layout).
 */

import { writeFileSync } from 'node:fs'
import { resolvePlaywright } from './lib/playwright.mjs'

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const url = arg('url')
const jsonOut = arg('json')
if (!url) {
  console.error('missing --url')
  process.exit(2)
}

/**
 * The profile URL carries a live `?token=` credential. Never write it to the
 * report: the report is committed as evidence.
 */
const redact = (value) => value.replace(/([?&]token=)[^&\s]*/gi, '$1<redacted>')

let chromium
try {
  ;({ chromium } = resolvePlaywright())
} catch (error) {
  console.error(`cannot load playwright: ${error.message}`)
  process.exit(3)
}

const results = []
const check = (group, name, ok, detail) => {
  results.push({ group, name, ok, detail })
  console.log(`${ok ? 'ok  ' : 'FAIL'} [${group}] ${name}${detail ? ` — ${detail}` : ''}`)
}

/** Open a session so real chat prose (and therefore the markdown anchor) is in the DOM. */
async function openConversation(page, narrow) {
  const cont = page.locator('button:has-text("Continue")')
  if (await cont.count()) {
    await cont.first().click()
    await page.waitForTimeout(500)
  }
  if (narrow) {
    const ham = page.locator('.dsh-xc-ham')
    if (await ham.count()) {
      await ham.first().click()
      await page.waitForTimeout(700)
    }
  }
  const entry = page.locator('text=阅读 scan-b 项目').first()
  if (await entry.count()) {
    await entry.click({ timeout: 5000 }).catch(() => {})
    await page.waitForTimeout(3000)
  } else {
    // DSH 0.2.1+ 的侧栏把会话与工作区平铺进同一棵树（role="treeitem"），不再有
    // 平铺的会话标题链接。会话条目有两个可判别特征：不带行内按钮（工作区行自带
    // aria-label "New session in …" 的新建按钮），文本也不是 "New Session" 占位。
    // 点树里第一条这样的条目即可打开最近会话 —— role 是语义属性，哈希无关。
    const opened = await page.evaluate(() => {
      const row = [...document.querySelectorAll('[role="treeitem"]')].find((el) => {
        const text = (el.textContent ?? '').trim()
        return text && text !== 'New Session' && !el.querySelector('button[aria-label^="New session in"]')
      })
      if (!row) return null
      row.click()
      return (row.textContent ?? '').trim().slice(0, 40)
    })
    console.log(`openConversation: ${opened ? `opened session "${opened}"` : 'no session entry found in sidebar tree'}`)
    await page.waitForTimeout(3000)
  }
  await page.evaluate(() => {
    const frame = document.querySelector('[data-mobile-nav="frame"]')
    if (frame) {
      frame.setAttribute('data-sidebar-collapsed', '')
      frame.setAttribute('data-details-collapsed', '')
    }
  })
  await page.waitForTimeout(500)
}

/** Measurements taken with the plugin stylesheet in place. */
const MEASURE = () => {
  const frame = [...document.querySelectorAll('[class*="_frame"]')].find((el) => getComputedStyle(el).display.includes('grid'))
  const prose = [...document.querySelectorAll('[class*="markdown"] p')].find((el) => (el.textContent ?? '').trim().length > 25)
  const placeholder = document.querySelector("[class*='_placeholder']")
  return {
    styleInjected: document.querySelector('style[data-mobile-ux]') !== null,
    grid: frame ? getComputedStyle(frame).gridTemplateColumns : null,
    proseFont: prose ? getComputedStyle(prose).fontSize : null,
    proseLineHeight: prose ? getComputedStyle(prose).lineHeight : null,
    proseColor: prose ? getComputedStyle(prose).color : null,
    placeholderColor: placeholder ? getComputedStyle(placeholder).color : null,
    overscroll: getComputedStyle(document.documentElement).overscrollBehaviorY,
    tapHighlight: getComputedStyle(document.body).webkitTapHighlightColor,
    audit: window.__dshMobileUx?.presentation ? window.__dshMobileUx.presentation.audit() : null,
  }
}

const browser = await chromium.launch()
let mobile
let desktop
try {
  // ---- phone -------------------------------------------------------------
  const phone = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })
  await phone.goto(url, { waitUntil: 'networkidle' })
  await phone.waitForTimeout(2000)
  await openConversation(phone, true)
  mobile = await phone.evaluate(MEASURE)
  await phone.screenshot({ path: arg('shots', 'docs/verification/presentation') + '-390x844.png' })
  await phone.close()

  // ---- desktop -----------------------------------------------------------
  const wide = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  await wide.goto(url, { waitUntil: 'networkidle' })
  await wide.waitForTimeout(2000)
  await openConversation(wide, false)
  desktop = await wide.evaluate(MEASURE)
  // Same element, plugin stylesheet removed: any difference is desktop impact.
  const withoutPlugin = await wide.evaluate(() => {
    document.querySelector('style[data-mobile-ux]').remove()
    const frame = [...document.querySelectorAll('[class*="_frame"]')].find((el) => getComputedStyle(el).display.includes('grid'))
    const prose = [...document.querySelectorAll('[class*="markdown"] p')].find((el) => (el.textContent ?? '').trim().length > 25)
    const placeholder = document.querySelector("[class*='_placeholder']")
    return {
      grid: frame ? getComputedStyle(frame).gridTemplateColumns : null,
      proseFont: prose ? getComputedStyle(prose).fontSize : null,
      proseLineHeight: prose ? getComputedStyle(prose).lineHeight : null,
      proseColor: prose ? getComputedStyle(prose).color : null,
      placeholderColor: placeholder ? getComputedStyle(placeholder).color : null,
      overscroll: getComputedStyle(document.documentElement).overscrollBehaviorY,
      tapHighlight: getComputedStyle(document.body).webkitTapHighlightColor,
    }
  })
  await wide.screenshot({ path: arg('shots', 'docs/verification/presentation') + '-1440x900.png' })
  await wide.close()

  // ---- assertions --------------------------------------------------------
  check('mobile', 'stylesheet injected', mobile.styleInjected === true)
  check('mobile', 'prose body text is 16px', mobile.proseFont === '16px', `got ${mobile.proseFont}`)
  check('mobile', 'prose line-height follows the 1.7 rhythm', Number.parseFloat(mobile.proseLineHeight) / 16 > 1.65, `got ${mobile.proseLineHeight}`)
  check('mobile', 'overscroll bounce disabled', mobile.overscroll === 'none', `got ${mobile.overscroll}`)
  check('mobile', 'tap highlight transparent', /rgba\(0, 0, 0, 0\)|transparent/.test(mobile.tapHighlight ?? ''), `got ${mobile.tapHighlight}`)
  check('mobile', 'content column still full width (xc intact)', mobile.grid === '390px 0px 0px', `got ${mobile.grid}`)
  check('mobile', 'anchor audit available', mobile.audit !== null)

  if (mobile.audit) {
    check('mobile', 'no drifted anchors', mobile.audit.missing.length === 0, `missing: ${mobile.audit.missing.join(', ') || 'none'}`)
  }

  const KEY = ['grid', 'proseFont', 'proseLineHeight', 'proseColor', 'placeholderColor', 'overscroll', 'tapHighlight']
  for (const key of KEY) {
    check('desktop', `${key} unchanged with/without the plugin`, desktop[key] === withoutPlugin[key], `${desktop[key]} vs ${withoutPlugin[key]}`)
  }
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
if (jsonOut) {
  writeFileSync(jsonOut, `${JSON.stringify({ generatedAt: new Date().toISOString(), url: redact(url), mobile, desktop, results }, null, 2)}\n`)
  console.log(`\nwrote ${jsonOut}`)
}
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
process.exit(failed.length === 0 ? 0 : 1)
