/**
 * dsh-mobile-ux — Host half.
 *
 * 本插件由两部分合并而成：
 *   - 骨架：原第三方插件 `dsh-mobile-xc` 0.6.7（MIT），整体 vendor 在
 *     `vendor/dsh-mobile-xc/`，内部 id 已统一改为 `dsh-mobile-ux`。它提供
 *     设置命名空间（Config/XcSettings）、PWA 注入与 canary 自检。
 *   - 观感：本仓库自研的表现层，纯浏览器侧（`styles/mobile.css` → `client.js`）。
 *
 * 宿主端直接转出口 xc 的实现，让 DSH 把这些能力挂在本插件名下。
 */

export * from './vendor/dsh-mobile-xc/lib/index.js'
