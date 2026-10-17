# dsh-mobile-ux

DSH 移动端 **UX 层**插件：骨架 + 观感合一。

- **骨架**（vendor）：[dsh-mobile-xc](https://github.com/xchannel1987/dsh-mobile-xc) 0.6.7（MIT），
  整体收编在 `vendor/dsh-mobile-xc/`，内部 id 已统一改为 `dsh-mobile-ux`。
  负责 overlay 抽屉、内容列占满、安全区、PWA 注入、canary 防升级失效，以及
  设置面板（滑动/轮次导航等行为开关）。
- **观感**（自研）：`styles/mobile.css`，负责正文排版（16px 下限 + 官方标题阶梯、
  代码 13px 下限）、占位文字对比度、原生触感（禁回弹/透明点击高亮/隐藏滚动条/
  按下反馈）。
- 两者都在 `@media (max-width: 1023px)` 内 → **桌面端零影响**（自动化断言守住，
  见 `tools/check-presentation.mjs`）。
- 分工纪律：骨架管布局属性（grid/position/width/height），观感不碰布局；
  颜色/字号/动效一律走 DSH token，不写死值。

> 为什么 vendor 而不是继续依赖上游：后续要在此插件上做深度定制（魔改），
> 收编进本仓库可自由改动、与仓库一起版本化；代价是不再自动跟随上游升级
> （上游升级需手动 diff 回 `vendor/dsh-mobile-xc/`）。

## 装

```sh
dsh plugin --profile web add ./plugins/mobile-ux   # 在仓库根目录执行
# 重启 dsh web 后生效
```

包内 `cordis.patch.yml` 会让 `dsh plugin add` 自动挂进 profile 的 `bundles`，无需手改配置。

## 改样式

`styles/mobile.css` 是观感半的唯一事实来源；`client.js` 是**生成产物**
（浏览器只能加载单文件脚本，所以骨架客户端与样式必须内联拼接）。

```sh
node scripts/build.mjs          # 改完 CSS 重新生成 client.js
node scripts/build.mjs --check  # 校验 client.js 是否过期（CI/验收用）
```

`build.mjs` 会把 `vendor/dsh-mobile-xc/lib/client.js`（骨架）与
`styles/mobile.css`（观感）拼成一个 `client.js`，并从 CSS 里抽出所有
`[class*="…"]` 片段写进锚点清单，供运行时自检使用。

## 验证

```sh
# 影子 profile：改道 3099，不影响线上
dsh --profile mobspike --no-open --port 3099
node tools/check-presentation.mjs --url "http://127.0.0.1:3099/?token=<printed-token>"
```

15 项断言：手机端（样式注入 / 正文 16px / 行高 1.7 / 禁回弹 / 透明点击高亮 /
xc 布局未被破坏 / 锚点无漂移）+ 桌面端（同一元素在「挂载插件」与「移除插件样式」
两种状态下逐项一致）。

锚点自检也可在浏览器控制台手动触发：

```js
window.__dshMobileUx.presentation.audit()   // { narrow, anchors, missing }
```

带 `?mobile-ux-debug=1` 打开页面，失配会直接打印到控制台。

## 落盘清单

| 文件 | 说明 |
|---|---|
| `styles/mobile.css` | 观感半样式事实来源（含每条规则的实测依据注释） |
| `scripts/build.mjs` | 生成 client.js（骨架 + 观感拼接），并抽出锚点清单 |
| `client.js` | 生成产物，勿手改 |
| `index.js` | 宿主半：转出口 vendor 骨架的宿主实现（设置命名空间/PWA） |
| `vendor/dsh-mobile-xc/` | dsh-mobile-xc 0.6.7 的 lib + assets + LICENSE（MIT） |
| `cordis.patch.yml` | bundle 补丁，供 `dsh plugin add` 自动挂载 |

## 边界

能治的：字号/行距/段落间距、占位文字对比度、滚动回弹、点击高亮、滚动条占位、
深色微调、移动布局/抽屉/安全区（骨架半）、移动端隐藏遮挡正文的第三方浮层
（`dsh-codex-pet` 的 `dcp-*` UI，桌面保留）。

治不了的（WebView 固有，需原生实现）：长按弹出的浏览器文本选择菜单、文本选择手柄样式、
原生导航/返回手势过渡、触觉反馈、页面切换动画。

## 许可

- 观感半与仓库其余部分：MIT。
- `vendor/dsh-mobile-xc/`：上游 [dsh-mobile-xc](https://github.com/xchannel1987/dsh-mobile-xc)
  0.6.7，MIT License（Copyright (c) 2026 dsh-mobile-xc contributors），许可证随目录保留。
