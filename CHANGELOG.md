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
- App 菜单新增 **「手机桥」**（`MainActivity.showBridgeDialog()`）：显示前台服务的实时状态、
  可手动启停，并提供「重新获取凭证」——WebView 与 `CredentialStore` 是两份独立的 token
  副本，前者可用而后者丢失时，「桥连不上」现在可以自助修好。

### 变更

- 仓库与部署配置去个人化：域名与绝对路径改为占位符 / 环境变量，`deploy/` 三个文件变为模板。
- `tools/` 的路径改从 `$HOME` 与脚本自身位置推导；playwright 由 `tools/lib/playwright.mjs` 自动探测。
- `docs/verification/` 移除界面截图与逐次验收报告（体积大且含会话画面），
  仅保留 `device-checklist.md`、`protocol-parity.json`、`presentation.json` 作为可核对证据。
- Android 的 `versionCode` / `versionName` 支持由 `-PdshVersionCode` / `-PdshVersionName` 覆盖，
  CI 可据此保证每个发布包单调递增，否则 Android 会拒绝覆盖安装。
- 为这次暴露的三个坑补上守卫：`CredentialStoreTest`（8 项，跑在 JVM 上——`seal`/`open` 的往返、
  IV 必须每次由 cipher 生成、截断/篡改/换 key 一律拒绝、blob 布局）；`tools/acceptance.mjs` 新增三条
  断言——APK 清单里 `connectedDevice` 前台服务必须带设备连接类前置权限、凭证加密不得自带 IV、
  工具栏必须能随状态栏 inset 长高，以及两个 `package-lock.json` 的 `resolved` 必须来自公开
  registry。单测总数 16 → 24。

### 修复

- **CI 第一次运行就死在 `Sync Capacitor`**：两个 `package-lock.json` 的 `resolved` 全部指向
  `http://mirrors.tencentyun.com/npm`（腾讯云内网镜像，在本机解析成 link-local 的 `169.254.0.3`），
  GitHub runner 解析不到这个主机名，`npm ci` 直接失败。它死在签名之前，看起来像 secret 配错了。
  现已把两处改为 `https://registry.npmjs.org`（版本号与 `integrity` 未动），并把 `npm ci` 从
  `Sync Capacitor` 里拆成独立步骤，以后再出问题能直接落到具体一步；验收新增两条断言，禁止
  lockfile 指向非公开 registry。
- **登录凭证其实从来没写进过存储**：`CredentialStore.encrypt()` 自己生成 IV 再传给 `Cipher.init`，
  但 AndroidKeyStore 的密钥默认 `setRandomizedEncryptionRequired(true)`，会以
  `InvalidAlgorithmParameterException: Caller-provided IV not permitted` 拒绝调用方提供的 IV。
  于是 `save()` 一直失败、凭证永远是空的——**「重开免登录」和「Agent 手机桥」这两件依赖落盘凭证的
  功能在所有设备上都不成立**（WebView 有自己的 cookie，所以界面看起来一切正常，掩盖了这一点）。
  现在加密不传 IV，由密钥库生成后经 `Cipher.getIV()` 取回，与密文一起存放。
- **手机桥前台服务一启动就把整个 App 崩掉**：Android 14+ 要求 `connectedDevice` 类型的前台服务
  同时持有设备连接类权限之一（`BLUETOOTH_*` / `NFC` / `USB_*` / `CHANGE_NETWORK_STATE` 等），
  而清单里一个都没有，于是 `BridgeService.onCreate()` 里的 `startForeground()` 抛
  `SecurityException`，被 `ActivityThread.handleCreateService` 包成 `RuntimeException` 直接崩进程。
  现在清单声明了 `CHANGE_NETWORK_STATE`（normal 权限，安装即授予、不会弹窗），并且 `onCreate()`
  把 `startForeground()` 包进 try/catch——将来系统再改规则时只会降级成「App 退到后台可能被断开」，
  不会再把 App 带崩。
- `tools/check-presentation.mjs` 曾把带 `?token=` 的 profile URL 原样写入报告——那是活凭据。
  报告现已脱敏，已提交的报告也已清理。
- Android 工具栏的标题与溢出菜单被状态栏 inset 挤出了可视区：`activity_main.xml` 用固定
  `?attr/actionBarSize`，而 `applySystemBars()` 又把状态栏高度加成 `padding-top`，内容被压到
  可视区外，**整个菜单变得不可达**。改为 `wrap_content` + `android:minHeight="?attr/actionBarSize"`。
- `MainActivity.startBridge()` 的三处失败都是静默的（endpoint 为空、`creds.load()` 为空、
  `startForegroundService` 抛异常），于是「App 界面正常但手机桥从未连接、`/healthz` 里
  `phones` 恒为 0」几乎无从诊断。现在原因会写进 `bridgeError` 并显示在「手机桥」对话框里，
  前台服务启动失败还会直接弹 toast。
- 「手机桥」对话框最初用 `setItems` 列出动作，消息较长时「重新获取凭证」会被挤出内容区、点不到；
  改为对话框按钮（启动/停止 ＋ 重新获取凭证 ＋ 关闭）。对话框同时显示 **网页 cookie 里究竟有没有
  网关 token** 和 `CredentialStore.lastError`——「凭证缺失」有两种成因完全不同的情况
  （cookie 里没有 token，还是加密落盘失败），此前从界面上无法区分。

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
