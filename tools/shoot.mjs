#!/usr/bin/env node
// shoot.mjs — 用无头 Chromium 对 DSH Web UI 取证：截图 + 移动端布局诊断。
//
// 用途：验证「移动端适配插件」是否真的生效、桌面端是否零影响，
// 并给出可比对的 before/after 证据（供 docs/verification/ 归档）。
//
// 用法：
//   node tools/shoot.mjs --url "http://127.0.0.1:3099/?token=..." --out docs/verification/spike-xc --label xc
//   node tools/shoot.mjs --url ... --out ... --label base --widths 390x844,1440x900
//
// 依赖：playwright（默认取本机 deepseek-harness 的 pnpm store 版本，
//       可用 PLAYWRIGHT_PATH 覆盖；浏览器取自 ~/.cache/ms-playwright）。
import { mkdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import path from 'node:path'

const require = createRequire(import.meta.url)
const DEFAULT_PW = '~/deepseek-harness/node_modules/.pnpm/playwright@1.63.0-alpha-2026-08-31/node_modules/playwright'

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const url = arg('url')
const outDir = path.resolve(arg('out', 'docs/verification/shots'))
const label = arg('label', 'shot')
const widths = arg('widths', '390x844,1440x900').split(',').map((s) => {
  const [w, h] = s.split('x').map(Number)
  return { w, h }
})
if (!url) {
  console.error('missing --url')
  process.exit(2)
}

const pwPath = process.env.PLAYWRIGHT_PATH || DEFAULT_PW
let chromium
try {
  ;({ chromium } = require(pwPath))
} catch (error) {
  console.error(`无法加载 playwright（${pwPath}）：${error.message}`)
  process.exit(3)
}

/** 在页面里采集移动端布局诊断数据。 */
function collectDiagnostics() {
  const de = document.documentElement
  const frame = [...document.querySelectorAll('[class*="_frame"]')].find((el) => {
    const s = getComputedStyle(el)
    return s.display.includes('grid')
  })
  const styleTags = [...document.querySelectorAll('style')]
    .map((s) => s.getAttribute('data-xc') || s.getAttribute('data-mobile-ux') || null)
    .filter(Boolean)
  const overlayNodes = document.querySelectorAll('[data-shell-overlay],[data-xc-drawer],[data-xc-pill]').length
  const inspector = [...document.querySelectorAll('[data-xc-version],[data-xc-compat]')].map((el) => ({
    tag: el.tagName,
    version: el.getAttribute('data-xc-version'),
    compat: el.getAttribute('data-xc-compat'),
  }))
  return {
    title: document.title,
    viewport: { w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio },
    overflowX: de.scrollWidth - de.clientWidth,
    gridTemplateColumns: frame ? getComputedStyle(frame).gridTemplateColumns : null,
    frameClass: frame ? frame.className.slice(0, 120) : null,
    injectedStyleTags: styleTags,
    overlayNodes,
    inspector,
    mobileQueryMatches: window.matchMedia('(max-width: 1023px)').matches,
    hasViewportMeta: !!document.querySelector('meta[name="viewport"]'),
  }
}

await mkdir(outDir, { recursive: true })
const browser = await chromium.launch()
const report = { url: url.replace(/token=[^&]+/, 'token=***'), label, at: new Date().toISOString(), runs: [] }

for (const { w, h } of widths) {
  const context = await browser.newContext({
    viewport: { width: w, height: h },
    deviceScaleFactor: 2,
    isMobile: w < 1024,
    hasTouch: w < 1024,
    userAgent: w < 1024
      ? 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36'
      : undefined,
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e).slice(0, 300)))
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 200)}`) })
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  // 等插件按 matchMedia 装载完毕
  await page.waitForTimeout(6000)
  const file = path.join(outDir, `${label}-${w}x${h}.png`)
  await page.screenshot({ path: file, fullPage: false })
  const diag = await page.evaluate(collectDiagnostics)
  report.runs.push({ viewport: `${w}x${h}`, screenshot: path.relative(process.cwd(), file), ...diag, errors: errors.slice(0, 8) })
  console.log(`[${label} ${w}x${h}] overflowX=${diag.overflowX} grid="${diag.gridTemplateColumns}" styleTags=${JSON.stringify(diag.injectedStyleTags)} overlays=${diag.overlayNodes} errors=${errors.length}`)
  await context.close()
}

await browser.close()
await writeFile(path.join(outDir, `${label}-report.json`), JSON.stringify(report, null, 2))
console.log(`报告：${path.join(outDir, `${label}-report.json`)}`)
