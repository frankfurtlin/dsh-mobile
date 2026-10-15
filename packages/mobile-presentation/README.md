# dsh-mobile-presentation

DSH 移动端**表现层**插件：正文排版、留白、对比度、原生触感与深色微调。

- 与 [`dsh-mobile-xc`](../..) 互补：xc 管**骨架**（抽屉、内容列占满、安全区），
  本插件管**观感**。两者不覆盖同一条属性。
- 所有规则都在 `@media (max-width: 1023px)` 内 → **桌面端零影响**（有自动化断言，见下）。
- 锚点**哈希无关**：只用语义类名片段（`[class*="markdown"]`）、元素选择器与 DSH 设计
  token，不写带构建哈希的完整类名，因此 DSH 升级不会静默失效。

## 装

```sh
dsh plugin --profile web add ~/workspace/dsh-mobile/packages/mobile-presentation
# 重启 dsh web 后生效
```

包内 `cordis.patch.yml` 会让 `dsh plugin add` 自动挂进 profile 的 `bundles`，无需手改配置。

## 改样式

`styles/mobile.css` 是唯一事实来源；`client.js` 是**生成产物**（浏览器只能加载单文件脚本，
所以样式必须内联）。

```sh
node scripts/build.mjs          # 改完 CSS 重新生成 client.js
node scripts/build.mjs --check  # 校验 client.js 是否过期（CI/验收用）
```

`build.mjs` 会顺便从 CSS 里抽出所有 `[class*="…"]` 片段，写进 client.js 的 `ANCHORS`，
供运行时自检使用——锚点清单不可能和 CSS 脱节。

## 验证

```sh
# 影子 profile：改道 3099，不影响线上
dsh --profile mobspike --no-open --port 3099
node tools/check-presentation.mjs --url "http://127.0.0.1:3099/?token=<printed-token>"
```

15 项断言：手机端（样式注入 / 正文 16px / 行高 1.7 / 禁回弹 / 透明点击高亮 / xc 布局未被破坏 /
锚点无漂移）+ 桌面端（同一元素在「挂载插件」与「移除插件样式」两种状态下逐项一致）。

锚点自检也可在浏览器控制台手动触发：

```js
window.__dshMobilePresentation.audit()   // { narrow, anchors, missing }
```

带 `?mobile-presentation-debug=1` 打开页面，失配会直接打印到控制台。

## 落盘清单

| 文件 | 说明 |
|---|---|
| `styles/mobile.css` | 样式事实来源（含每条规则的实测依据注释） |
| `scripts/build.mjs` | 生成 client.js，并抽出锚点清单 |
| `client.js` | 生成产物，勿手改 |
| `index.js` | 宿主半：本插件纯浏览器侧，宿主为空实现 |
| `cordis.patch.yml` | bundle 补丁，供 `dsh plugin add` 自动挂载 |

## 边界

能治的：字号/行距/段落间距、次要文字对比度、滚动回弹、点击高亮、滚动条占位、深色微调。

治不了的（WebView 固有，需原生实现）：长按弹出的浏览器文本选择菜单、文本选择手柄样式、
原生导航/返回手势过渡、触觉反馈、页面切换动画。
