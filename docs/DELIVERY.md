# 交付与验收说明

## 1. 交付物清单

| 路径 | 内容 |
|---|---|
| `packages/mobile-shell/` | Android App 源码（Capacitor 壳 + 原生 WebView + 登录/凭证/桥/无障碍） |
| `packages/mobile-shell/android/app/build/outputs/apk/debug/app-debug.apk` | 调试包（自签名 debug key，可随时覆盖安装） |
| `packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk` | 发布包 `1.0.7`（versionCode 10007，用 `~/.secrets/dsh-mobile-release.jks` 签名），3,653,589 B，sha256 `8cad0eb2568941aa…` |
| `dist/DSH-Mobile-1.0.7.apk` | 与上面发布包 sha256 完全一致的副本，供 bridge 的下载口直接发给手机（`https://dsh.example.com/bridge/download/DSH-Mobile-1.0.7.apk`，需先登录网关；见 [DEPLOY.md §0.1c](DEPLOY.md)） |
| `packages/bridge-server/` | Agent ↔ 手机桥（MCP over Streamable HTTP + 手机 WSS，24 个 `phone_*` 工具） |
| `tools/` | `install-toolchain.sh`、`build-apk.sh`、`gen-keystore.sh`、`acceptance.mjs`、`check-anchors.mjs`、`check-protocol-parity.mjs`、`probe-ws-auth.mjs`、`restart-live.sh`、`shoot.mjs` |
| `deploy/` | systemd 单元、Caddy 片段、DSH profile 的 MCP patch |
| `docs/` | PLAN / DEPLOY / ROLLBACK / 本文件 / ADR-0001–0006 / 验证报告 |
| `docs/verification/` | 自动化验收 JSON 与移动端布局截图 |

## 2. 构建

```bash
# 一次性：JDK 21 + Android SDK 35（约 1.2 GB，装到 ~/.local/android）
tools/install-toolchain.sh && source ~/.local/android/env.sh

# 调试包（增量构建秒级）
tools/build-apk.sh

# 发布包（首次需生成签名材料；keystore 只保存在 ~/.secrets，勿入库）
tools/gen-keystore.sh
tools/build-apk.sh --release
```

校验产物：

```bash
source ~/.local/android/env.sh
apksigner verify --print-certs packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk
aapt2 dump badging  packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk | head -5
```

> `tools/build-apk.sh --release` 会把 `DSH_KEYSTORE*` 变量以 `-Pandroid.injected.signing.*` 传给 Gradle，
> 因此 `build.gradle` 里不需要写任何密码。**keystore 丢失 = 已安装的 release 包无法原地升级**，请连同
> `~/.secrets/dsh-mobile-signing.properties` 一起备份。

当前包版本 `1.0.2`：在 `1.0.1` 基础上加了**用户授权文件夹的文件浏览**——App 菜单「选择可浏览的文件夹」
（`ACTION_OPEN_DOCUMENT_TREE` + 持久授权）把某个文件夹交给 Agent，配合始终可用的 `Downloads/DSH`，
工具为 `phone_fs_roots/fs_list/fs_read/fs_write/fs_mkdir`，路径形如 `tree:/Documents/report.pdf`；
另加 6 项 `FileBrowserTest` JVM 单测（`..` 越界、绝对路径、`content://` 一律拒绝），详见 [ADR-0005](adr/0005-browsable-folders.md)。

`1.0.1` 补上了 `files.open` 的实现（App 侧此前只在 `CAPS` 里声明、没有对应的 `case`，`phone_files_open` 会失败）；
`tools/check-protocol-parity.mjs` 现在把这类「声明了但没实现」的缺口变成构建期可检出的失败。

## 3. 自动化验收

```bash
node tools/acceptance.mjs                      # 全部检查（当前 80/80）；结果写 docs/verification/acceptance-<时间戳>.json
node tools/acceptance.mjs --skip-gradle        # 跳过需要 Android SDK 的检查（CI / 无工具链机器）
node tools/acceptance.mjs --skip-dsh           # 跳过依赖本机 ~/.dsh 与 /etc 部署状态的检查
node tools/check-protocol-parity.mjs           # App / 服务端 / 假手机 三方 op 一致性（当前 9/9）
node tools/probe-ws-auth.mjs                   # 公网手机 WSS 鉴权门（当前 4/4）
cd packages/bridge-server && node tools/smoke.mjs   # bridge 端到端自测（当前 91/91，含构件下载口 14 项）
```

覆盖：工具链可用性 → APK 产物存在 + release 签名校验 → APK 清单（5 个组件 / 无障碍权限 / 前台服务类型 /
包可见性 `<queries>`）→ bridge 端到端自测（`91/91`）+ 24 个工具 + op 一致性（`9/9`）+ DSH 同款 MCP SDK
客户端连通 → App 侧 JVM 单测（`24/24`，端点解析 + 文件浏览路径寻址 + 凭证 blob 编解码）→ DSH 侧配置（web profile 已装 `dsh-mobile-xc`、
deploy 产物齐全、MCP patch 能被 `dsh --dump-config` 解析、**线上 bridge 单元 / Caddy `/bridge/*` 路由 /
profile `mcp-phone` 与回滚副本已就位**、`dist/` 里的 APK 与 release 构建 sha256 一致）→ 文档齐备。

移动端 Web 适配的回归（需图形环境）：

```bash
node tools/shoot.mjs --url "http://127.0.0.1:3099/?token=<token>" --out docs/verification/mobile --widths 390x844,430x932,1440x900
node tools/check-anchors.mjs --json docs/verification/anchors.json
```

## 4. 真机验收

按 [device-checklist.md](verification/device-checklist.md) 逐项走；服务端装配见 [DEPLOY.md](DEPLOY.md)，
出问题按 [ROLLBACK.md](ROLLBACK.md) 回退。

## 5. iOS / iPadOS

本轮先交付 Android（用户选择 APK 优先）。iOS 走 **PWA**，不做原生打包：

1. Safari 打开 `https://dsh.example.com` → 分享 → 添加到主屏幕；
2. 用 dsh-passwords 账号登录；凭证由 Safari 的 Cookie 保存（`HttpOnly; SameSite=Lax; Max-Age=43200`，12 小时）；
3. 之后从主屏幕图标进入即可，功能与手机浏览器一致。

iOS 上的能力边界（与 Android 包的差异）：

| 能力 | iOS PWA | Android 包 |
|---|---|---|
| 全部 Web 功能 | ✅ | ✅ |
| 凭证本地持久 | Safari Cookie（12h 后重新登录） | AndroidKeyStore 加密密文 + 自动回填 |
| 手机桥（Agent 直接读写手机） | ❌ 无长连前台服务 | ✅ |
| 浏览用户授权文件夹（`tree:`/`downloads:`） | ❌ | ✅ |
| 无障碍自动操作 | ❌ 无对应系统能力 | ✅ |
| 拍照/选择文件上传 | ✅（Safari 原生对话框） | ✅（含 SAF/拍照回填） |
| 系统分享进 DSH | ❌ 需 Shortcuts 间接实现 | ✅ `ACTION_SEND` 收件箱 |

若以后要做原生 iOS：需要一台 Mac + Xcode，`npx cap add ios`，再补 `WKWebView` 的 cookie 同步
（原生侧与 `WKWebsiteDataStore` 共享）；无障碍自动化在 iOS 上只有 XCUITest 路径（需开发者签名 + 常驻测试进程），
不建议纳入本期范围。
