# Changelog

所有值得注意的变更都记录在这里。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- **移动端表现层插件** `dsh-mobile-presentation`：正文 16px/1.7 与段落节奏、次要文字对比度、
  禁用滚动回弹、透明点击高亮、隐藏滚动条、深色微调。锚点哈希无关并带运行时自检，
  桌面端零影响（`tools/check-presentation.mjs` 15/15 断言）。
- **CI 出包**：推送 `v*` tag 即由 GitHub Actions 构建、签名并发布 Release APK
  （`.github/workflows/android-release.yml`）。
- 项目级文件：LICENSE、CHANGELOG、CONTRIBUTING、SECURITY、Issue/PR 模板、Dependabot。

### 变更

- 仓库与部署配置去个人化：域名与绝对路径改为占位符 / 环境变量，`deploy/` 三个文件变为模板。
- `tools/` 的路径改从 `$HOME` 与脚本自身位置推导；playwright 由 `tools/lib/playwright.mjs` 自动探测。
- `docs/verification/` 移除界面截图与逐次验收报告（体积大且含会话画面），
  仅保留 `device-checklist.md`、`protocol-parity.json`、`presentation.json` 作为可核对证据。
- Android 的 `versionCode` / `versionName` 支持由 `-PdshVersionCode` / `-PdshVersionName` 覆盖，
  CI 可据此保证每个发布包单调递增，否则 Android 会拒绝覆盖安装。

### 修复

- `tools/check-presentation.mjs` 曾把带 `?token=` 的 profile URL 原样写入报告——那是活凭据。
  报告现已脱敏，已提交的报告也已清理。

## [1.0.2] — 2026-10-15

首个发布版本。完整覆盖 P0–P7。

### 新增

- Capacitor WebView 壳：首次启动只填 endpoint，复用网关登录，凭证加密落盘、401 自动回登录页。
- 移动端 Web 适配：侧栏变抽屉、内容列占满宽度、安全区与键盘适配，桌面端不受影响。
- Agent ↔ 手机桥：MCP over Streamable HTTP + 经网关 cookie 鉴权的手机 WebSocket，
  24 个 `phone_*` 工具（剪贴板、通知、打开链接、保存/打开文件、应用列表与启动等）。
- 无 root 无障碍自动化：截图、控件树、点击、滑动、启动应用。
- 文件夹浏览：App 侧 SAF 授权后，Agent 可读写 `tree:` / `downloads:` 两个根下的文件。
- `/bridge/download/<file>` 鉴权下载口，登录网关后可直接把 APK 下到手机。
- 自动化验收 `tools/acceptance.mjs`（56 项）、协议一致性、CSS 哈希漂移检查、真机验收清单。

## [1.0.1] / [1.0]

开发期内部迭代，未单独发布。

[Unreleased]: https://github.com/frankfurtlin/dsh-mobile/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/frankfurtlin/dsh-mobile/releases/tag/v1.0.2
