// GENERATED from styles/mobile.css by scripts/build.mjs — do not edit by hand.
/**
 * dsh-mobile-presentation — Client half.
 *
 * GENERATED FILE. Edit `styles/mobile.css` and run `node scripts/build.mjs`;
 * `node scripts/build.mjs --check` fails when this file is out of date.
 *
 * Responsibilities, in order:
 *   1. inject the mobile stylesheet once (desktop is untouched: every rule in
 *      the stylesheet sits inside `@media (max-width: 1023px)`);
 *   2. expose `audit()` so a drifted anchor is a loud failure instead of a
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
    const CSS = "/*\n * dsh-mobile-presentation — 移动端表现层。\n *\n * 定位：`dsh-mobile-xc` 负责“骨架”（抽屉、内容列占满、安全区），本插件负责\n * “观感”——正文排版、留白、对比度、触感。两者互补，互不覆盖同一条属性。\n *\n * 两条硬约束（与 0001 ADR 一致）：\n *   1. 所有规则必须落在 @media (max-width: 1023px) 内 —— 桌面端零影响。\n *   2. 锚点必须哈希无关：优先用语义类名片段（[class*=\"markdown\"]）、元素选择器\n *      与 DSH 设计 token；**不要**写 `pI_x6G_frame` 这类带构建哈希的完整类名。\n *      运行时自检见 client.js 的 audit()，清单见 client.js 的 ANCHORS。\n *\n * 注意：不要在本文件里改布局属性（grid/position/width）——那是 xc 的职责，\n * 两边都改会互相打架。\n */\n\n@media (max-width: 1023px) {\n  /* ==========================================================\n   * 1. 原生触感：消除 WebView 的“网页味”\n   * ========================================================== */\n\n  html,\n  body {\n    /* 滚动到边界时的橡皮筋回弹是浏览器特征，原生列表不会这样 */\n    overscroll-behavior: none;\n    /* 禁止系统按视口宽度擅自放大字号（横屏/大屏手机下字会突然变大） */\n    -webkit-text-size-adjust: 100%;\n  }\n\n  *,\n  *::before,\n  *::after {\n    /* 点击瞬间的灰色/蓝色方块是浏览器的高亮反馈 */\n    -webkit-tap-highlight-color: transparent;\n  }\n\n  /* 触屏没有常驻滚动条的必要，隐掉以免挤压内容宽度 */\n  ::-webkit-scrollbar {\n    width: 0 !important;\n    height: 0 !important;\n  }\n\n  /* ==========================================================\n   * 2. 正文排版\n   *\n   * 锚点 `[class*=\"markdown\"]`：DSH 渲染 markdown 的容器语义名\n   * （实测为 `_markdown_1ypvv_5`，前缀是语义词、后缀才是构建哈希），\n   * 因此哈希漂移不影响命中。这里只调元素（p/li/h/pre/code），不碰容器。\n   * ========================================================== */\n\n  /* 正文实测 14px/24px，手机上偏小；放到 16px 并按 1.7 行高排 */\n  [class*='markdown'] p,\n  [class*='markdown'] li {\n    font-size: 16px !important;\n    line-height: 1.7 !important;\n    letter-spacing: 0.01em;\n  }\n\n  /* 段落之间给足呼吸，否则多段正文挤成一坨 */\n  [class*='markdown'] p + p,\n  [class*='markdown'] li + li {\n    margin-top: 0.8em !important;\n  }\n\n  /* 标题层级拉开，避免 h1/h2 与正文糊在一起 */\n  [class*='markdown'] h1 {\n    font-size: 22px !important;\n    line-height: 1.4 !important;\n  }\n  [class*='markdown'] h2 {\n    font-size: 19px !important;\n    line-height: 1.45 !important;\n  }\n  [class*='markdown'] h3 {\n    font-size: 17px !important;\n    line-height: 1.5 !important;\n  }\n  [class*='markdown'] h4 {\n    font-size: 16px !important;\n    line-height: 1.5 !important;\n  }\n\n  /* 行内代码实测 12.25px、代码块 13px 上下，手机上偏小且行距紧 */\n  [class*='markdown'] code {\n    font-size: 13.5px !important;\n  }\n  [class*='markdown'] pre {\n    font-size: 13px !important;\n    line-height: 1.6 !important;\n  }\n\n  /* ==========================================================\n   * 3. 留白\n   * ========================================================== */\n\n  /* 输入区与底部手势条之间留出安全距离（xc 之外的一层保险） */\n  [class*='_composerStack'] {\n    padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)) !important;\n  }\n\n  /* ==========================================================\n   * 4. 对比度\n   * ========================================================== */\n\n  /* 输入框占位文字实测 rgb(173,178,184)，白底上对比度约 2:1，\n     低于 WCAG AA 正文要求的 4.5:1，室外/低亮度屏上几乎看不见。 */\n  [class*='_placeholder'] {\n    color: #8b929c !important;\n  }\n\n  /* 模型名/模式名等次要标签实测 rgb(97,102,107)，同样偏浅 */\n  [class*='_triggerLabel'] {\n    color: #575e68 !important;\n  }\n}\n\n/* ==========================================================\n * 5. 深色微调\n *\n * DSH 自身走 `prefers-color-scheme` 出深色；这里只把深色下\n * 最容易刺眼的两处（占位文字过亮、正文纯白）压一档。\n * ========================================================== */\n@media (max-width: 1023px) and (prefers-color-scheme: dark) {\n  [class*='_placeholder'] {\n    color: #7e848e !important;\n  }\n  [class*='_triggerLabel'] {\n    color: #a3a9b3 !important;\n  }\n  [class*='markdown'] p,\n  [class*='markdown'] li {\n    color: #e4e6ea !important;\n  }\n}\n"
    /** Class-name fragments the stylesheet relies on (extracted from the CSS). */
    const ANCHORS = [
      "markdown",
      "_composerStack",
      "_placeholder",
      "_triggerLabel"
    ]

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
     * (`?mobile-presentation-debug=1`), because a view without chat content
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
