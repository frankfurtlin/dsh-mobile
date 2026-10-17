# dsh-mobile

[![android release](https://github.com/frankfurtlin/dsh-mobile/actions/workflows/android-release.yml/badge.svg)](https://github.com/frankfurtlin/dsh-mobile/actions/workflows/android-release.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![release](https://img.shields.io/github/v/release/frankfurtlin/dsh-mobile?display_name=tag)](https://github.com/frankfurtlin/dsh-mobile/releases)

把 DeepSeek Harness（DSH）搬到手机上：**只需填 endpoint 的 Android App** + **移动端 Web 界面适配**。

- App 首次启动只填服务器地址 → 跳转 dsh-passwords 网关登录页（与浏览器完全同一套登录）→
  登录凭证加密保存在手机本地 → 重开免登录 → 直接使用官方 Web UI 的全部功能。
- 顺带治好"手机浏览器用 DSH 难受"：侧栏变抽屉、内容列占满宽度、安全区/键盘适配，**桌面端零影响**。
- 额外提供 Web 端没有的手机能力：浏览/上传手机文件、用手机 App 打开文件、系统分享进 DSH，
  以及让 Agent 通过工具读写手机文件；并支持**无 root 的无障碍自动操作**（截图、控件树、点击/滑动、启动应用）。

## 文档约定

仓库里的部署配置与文档全部与具体机器无关，看到这些占位符请替换成你自己的：

| 占位符 | 含义 |
|---|---|
| `dsh.example.com` | 你对外提供 DSH Web UI 的域名（手机连的就是它） |
| `~` | 运行 dsh 的那个用户的 home，通常就是你的登录用户 |
| `@DOMAIN@` / `@INSTALL_DIR@` / `@NODE_BIN@` | `deploy/` 里的模板占位符，见文件头部注释 |
| `top.frankfurtlin.dshmobile` | Android 的 `applicationId`，是 App 的身份而非配置，一般不改 |

## 目录结构

```
dsh-mobile/
├─ docs/
│  ├─ PLAN.md                     # 开发计划与当前进度
│  ├─ DEPLOY.md                   # 服务端 + 手机端部署手册
│  ├─ ROLLBACK.md                 # 回滚手册（手机端 / bridge / UI 插件）
│  ├─ DELIVERY.md                 # 交付物清单、构建与签名、iOS/PWA 说明
│  ├─ adr/0001-...0006-...md      # 决策记录：UI 选型 / 原生壳 / 手机桥 / 无障碍 / 可浏览文件夹 / 构件下载口
│  └─ verification/               # 布局截图与诊断 JSON、验收报告、真机清单
├─ packages/
│  ├─ mobile-shell/               # Capacitor Android App（包名 top.frankfurtlin.dshmobile）
│  ├─ mobile-presentation/        # 自研 DSH 插件：移动端排版/留白/对比度/触感（桌面零影响）
│  └─ bridge-server/              # Agent ↔ 手机桥（MCP + 手机 WSS + 构件下载口，24 个 phone_* 工具）
├─ dist/                          # 供手机浏览器下载的签名包（bridge 的 /bridge/download/ 从这里取）
├─ deploy/                        # systemd 单元、Caddy 片段、DSH profile 的 MCP patch
├─ tools/
│  ├─ install-toolchain.sh        # JDK 21 + Android cmdline-tools（装 ~/.local，不碰系统）
│  ├─ build-apk.sh                # 一键构建（debug / --release 签名包）
│  ├─ gen-keystore.sh             # 生成发布签名材料（存 ~/.secrets，不入库）
│  ├─ acceptance.mjs              # 自动化验收（80 项）
│  ├─ check-anchors.mjs           # 移动插件 CSS 哈希漂移检测
│  ├─ check-presentation.mjs      # 表现层插件：窄屏生效 + 宽屏零影响（15 项）
│  ├─ check-mobile-layout.mjs     # 移动端体检：遮挡/溢出/小目标/伪状态 + 插件源码归因
│  ├─ check-protocol-parity.mjs   # App/服务端/假手机三方 op 一致性检查
│  ├─ probe-ws-auth.mjs           # 探测手机 WebSocket 鉴权门（4 种情况）
│  ├─ restart-live.sh             # 延迟重启线上 dsh 并自动回滚
│  └─ shoot.mjs                   # 无头截图 + 移动端布局诊断
```

## 环境

工具链装在用户空间，无需 root、不写 `/usr`：

```bash
bash tools/install-toolchain.sh          # JDK 21 + Android SDK（约 1.2GB）
source ~/.local/android/env.sh       # JAVA_HOME / ANDROID_HOME / PATH
```

已安装版本：JDK `21.0.12.1+1`、Android platform `android-35`、build-tools `35.0.0`、platform-tools（adb `1.0.41`）。

## 构建 App

```bash
source ~/.local/android/env.sh
tools/build-apk.sh                       # 调试包（增量构建秒级）
tools/gen-keystore.sh                    # 一次性：生成发布签名材料
tools/build-apk.sh --release             # 发布包（已签名，约 3.5 MB）
```

产物在 `packages/mobile-shell/android/app/build/outputs/apk/{debug,release}/`；脚本会打印大小与 sha256。
签名细节与校验命令见 [docs/DELIVERY.md](docs/DELIVERY.md)。

## 自动化验收

```bash
node tools/acceptance.mjs                # 80 项：工具链 / APK 与清单 / bridge 自测 / op 一致性 / 单测 / DSH 接线与部署 / 文档
node tools/acceptance.mjs --skip-gradle  # 无 Android SDK 的机器上跳过构建相关检查
node tools/acceptance.mjs --skip-dsh     # 跳过依赖本机 ~/.dsh 与 /etc 部署状态的检查
node tools/check-protocol-parity.mjs     # 只跑 App / 服务端 / 假手机 的三方 op 一致性（9 项）

# 移动端体检（需要一台带目标插件集的实例在跑；脚本会读 profile 的 bundles 做源码扫描）
node tools/check-mobile-layout.mjs --url "http://127.0.0.1:3098/?token=..." \
  --profile layoutcheck --json docs/verification/mobile-layout.json
node tools/probe-ws-auth.mjs             # 线上手机 WSS 鉴权门：无 Origin / 无 cookie / 假 cookie / 错 Origin（4 项）
cd packages/bridge-server && node tools/smoke.mjs                 # bridge 端到端自测（91 项）
cd packages/mobile-shell/android && ./gradlew testDebugUnitTest   # 端点/路径解析 + 凭证 blob 单测（24 项）
```

报告写入 `docs/verification/acceptance-<时间戳>.json`。真机项目见
[真机验收清单](docs/verification/device-checklist.md)。

## 移动端布局验证

```bash
# 影子 profile，不影响线上 web profile
dsh --profile mobspike --from-default-profile web --dump-config
dsh plugin --profile mobspike add dsh-mobile-xc
dsh --profile mobspike --no-open --port 3099

node tools/shoot.mjs --url "http://127.0.0.1:3099/?token=<printed-token>" \
  --out docs/verification/spike-xc --label xc
```

`shoot.mjs` 会输出并归档：截图、横向溢出、`grid-template-columns`、注入的样式/控件、页面错误。
选型结论与 390×844 / 1440×900 实测对比见 [ADR-0001](docs/adr/0001-mobile-ui-plugin-selection.md)。

### 表现层验证

```bash
dsh --profile mobspike --no-open --port 3099
node tools/check-presentation.mjs --url "http://127.0.0.1:3099/?token=<printed-token>" \
  --json docs/verification/presentation.json
```

15 项：手机端（正文 16px、行高 1.7、禁回弹、透明点击高亮、xc 布局未被破坏、锚点零漂移）
＋ 桌面端（同一元素在「挂载插件」与「移除插件样式」两种状态下逐项一致）。见
[ADR-0007](docs/adr/0007-mobile-presentation-layer.md)。

## 当前状态

| 阶段 | 状态 |
|---|---|
| P0 工具链 + Android 工程 | **完成**：JDK 21 + Android SDK 35 在 `~/.local/android`，`assembleDebug` 可构建 |
| P1 移动端 UI 选型 + 线上安装 | **完成**：选定 `dsh-mobile-xc`（回退 `@qingshanyuluo/dsh-client-ui-mobile`）；已装进 `web` profile 并**已重启生效**（2026-10-14 23:18，390×844 内容列占满、桌面布局不变） |
| P2 App 骨架 + endpoint 登录 | **代码完成，待真机验证**：调试 APK 已产出 |
| P3 原生能力 | **代码完成，待真机验证** |
| P4 Agent ↔ 手机桥 | **代码完成，待真机联调**：`packages/bridge-server`（MCP `/mcp` 仅 loopback + 手机 WSS `/bridge/ws` + 已登录浏览器下载口 `/bridge/download/<file>`，网关 cookie 核验，91/91 自测 + 9/9 op 一致性）；App 侧 `BridgeService`（前台服务 + OkHttp WSS + 指数退避重连）；工具 `phone_list/ping/info/shares/clipboard_get/set/notify/open/files_save/files_open/fs_roots/fs_list/fs_read/fs_write/fs_mkdir/call` |
| P5 无 root 无障碍自动操作 | **代码完成，待真机联调**：`A11yService`（截图取控件树、点击/滑动/系统键，均在设备端执行）+ 应用 `phone_apps`/`phone_launch`/`phone_ui_tree`/`phone_screenshot`/`phone_tap`/`phone_swipe`/`phone_global`/`phone_a11y_status`，三重开关（App 内开关 + 系统无障碍 + 设备在线） |
| P7 文件浏览（用户授权文件夹） | **代码完成，待真机验证**：`tree:`（用户在系统选择器里挑的文件夹，授权跨重启保留）+ `downloads:`（`Downloads/DSH`）两个根；工具 `phone_fs_roots/fs_list/fs_read/fs_write/fs_mkdir`，路径形如 `tree:/Documents/report.pdf`，`..` 越界一律拒绝；见 [ADR-0005](docs/adr/0005-browsable-folders.md) |
| P6 验证/签名/交付 | **完成**：`tools/acceptance.mjs` 80/80（含 24 项 JVM 单测、9 项 op 一致性、5 项线上部署断言）；发布包 `1.0.7` 已签名（`~/.secrets`）；bridge 已装成 systemd 单元、Caddy 已加 `/bridge/*`、线上 profile 已加 `mcp-phone`（**重启 dsh web 后 Agent 侧 `phone_*` 工具生效**）；`dist/DSH-Mobile-1.0.7.apk` 可经 `/bridge/download/` 直接下到手机；DEPLOY/ROLLBACK/DELIVERY/真机清单齐全；iOS 走 PWA 说明 |
| P9 移动端表现层 | **完成并已生效**：自研插件 `packages/mobile-presentation`（正文 16px/1.7、段落间距、占位文字对比度、禁滚动回弹、透明点击高亮、隐藏滚动条、深色微调）；锚点哈希无关 + 运行时 audit 自检；`tools/check-presentation.mjs` **15/15**（窄屏生效 + 宽屏与关掉插件时逐项一致）；见 [ADR-0007](docs/adr/0007-mobile-presentation-layer.md)。已装进线上 `web` profile 并**于 2026-10-15 23:05 重启生效** |
| 待用户参与 | ① 手机浏览器打开上文的 `/bridge/download/DSH-Mobile-1.0.7.apk` 下载并安装；② 按 [真机验收清单](docs/verification/device-checklist.md) 逐项验收（含 P7 文件浏览 10 项）；③ 需要 Agent 读写某个文件夹时，在 App 菜单「选择可浏览的文件夹」里授权（P9 表现层已在线上生效，App 端刷新页面即可看到） |

详见 [docs/PLAN.md](docs/PLAN.md)。

## 装到手机

**手机不在服务器旁边时（推荐）**：用手机浏览器登录 `dsh.example.com` 的 dsh web，再打开

```
https://dsh.example.com/bridge/download/DSH-Mobile-1.0.7.apk
```

浏览器会带上已有的 `dsh_gateway_token` cookie，bridge 核验通过后直接下载（无 cookie 或假 cookie → `401`）。
这个下载口由 `DSH_BRIDGE_DOWNLOAD_DIR` 指定目录，只服务该目录里的文件，不接受子目录/路径。

**手边能连 USB 时**：

```bash
cd ~/dsh-mobile
tools/build-apk.sh --release             # 或 tools/build-apk.sh 出调试包
adb install -r packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk
```

首次启动只填 endpoint（如 `https://dsh.example.com`），App 会 `GET /gateway/healthz`
探活并打开网关登录页；登录后凭证加密落盘，杀进程重开免登录（服务端 cookie 12h 有效期）。

服务端（bridge + Caddy + DSH profile 接入）与手机端开关说明见 [docs/DEPLOY.md](docs/DEPLOY.md)，
回退步骤见 [docs/ROLLBACK.md](docs/ROLLBACK.md)。

### 已实现的原生能力

| 能力 | 说明 |
|---|---|
| 安全区 | edge-to-edge + 手写 inset：顶栏避让状态栏/刘海，页面避让手势条 |
| 附件/拍照 | `<input type=file>` 走 SAF；带 `capture` 的走系统相机并回填 URI（FileProvider） |
| 下载 | `DownloadManager` → `Downloads/DSH/`，带 cookie，完成后有通知（已申请通知权限） |
| 外部 App 打开 | 非本服务器的链接交给系统；收件箱文件用 `ACTION_VIEW` 打开 |
| 分享进 DSH | 系统分享面板里出现「分享到 DSH」，文本/图片/多文件进收件箱（菜单里可打开、复制、清空） |
| 剪贴板 | `DshMobile.copyText/getClipboard` |
| 屏幕常亮 | 菜单开关 + `DshMobile.setKeepAwake(bool)`，长回答流式期间不被息屏打断 |
| 手机桥 | App 前台服务保持一条已认证 WSS `/bridge/ws`；Agent 侧经 MCP 工具 `phone_*` 访问手机（剪贴板读写、通知、打开链接、保存文件到 Downloads/DSH、用手机自带 App 打开文件 `phone_files_open`、通用直通 `phone_call`）；`DshMobile.bridgeStatus()` 可查连接状态 |
| 文件浏览 | App 菜单「选择可浏览的文件夹」把某个文件夹授权给 DSH（`tree:` 根，授权跨重启保留，可随时清除）；`Downloads/DSH`（`downloads:` 根）始终可用。Agent 侧 `phone_fs_roots/list/read/write/mkdir` 列目录、读文本或 base64、写文件、建目录；网页可用 `DshMobile.pickFilesRoot()` 触发同一个选择器 |
| 无障碍自动化 | 手机端 `A11yService` 在设备上执行：`phone_ui_tree` 读控件树（含 tap 中心点）、`phone_screenshot` 截图、`phone_tap`/`phone_swipe`/`phone_global` 操作、`phone_apps`/`phone_launch` 打开应用；需用户在 App 内打开开关并在系统设置里启用该服务 |
| JS bridge | `DshMobile.getEndpoint/getAppVersion/openExternal/copyText/getClipboard/setKeepAwake/keepAwake/shares/saveBase64/bridgeStatus/filesRoot/filesRootLabel/pickFilesRoot` |

## 安全

- 凭证只存网关 `dsh_gateway_token`，落盘用 AndroidKeyStore AES-GCM 封存，不进日志、不进 git。
- 默认要求 HTTPS；局域网明文/自签证书需用户显式开启。
- **手机桥**：MCP 端点只监听 `127.0.0.1:3082`；手机连 `/bridge/ws` 必须带网关 cookie，bridge 用
  回环请求 `dsh-passwords` 网关核验（`200` 才算通过，`302/401` 一律拒），并校验 Origin。
  线上经 Caddy 实测（`node tools/probe-ws-auth.mjs --url wss://<你的域名>/bridge/ws`）：
  无 Origin → 403、有 Origin 无 cookie → 401、假 cookie → 401、错 Origin → 403，4/4 符合预期。
- **构件下载口** `/bridge/download/<文件名>` 同样要求网关 cookie（无 cookie / 假 cookie → 401，实测于公网
  `https://dsh.example.com`），只服务 `DSH_BRIDGE_DOWNLOAD_DIR` 那一层目录：文件名必须匹配
  `^[A-Za-z0-9][A-Za-z0-9._-]*$`，`..`、子目录、隐藏文件、目录本身一律 400/404，非 GET/HEAD 一律 405；
  未配置目录时该路由整体关闭（`404`）。
- **无障碍自动化**默认关闭，需「App 内开关 + 系统无障碍服务」两处都打开；关闭时工具立即返回明确错误。
- 发布签名材料（keystore + 口令）存放在仓库外的 `~/.secrets`，`.gitignore` 覆盖 `*.jks`/`*.keystore`。
- `phone_files_save` 只写 `Downloads/DSH`（MediaStore），不接受任意路径写入；`phone_files_open` 只打开 App 能授出读权限或 MediaStore 里的文件，不接受 shell 命令；`phone_apps` 只返回有启动器入口的应用。
- **文件浏览**只发生在两个根内：`tree:` 是用户在系统选择器里**亲手授权**的那个文件夹（SAF 持久授权，可清除），
  `downloads:` 是 `Downloads/DSH`。路径必须带根前缀，绝对路径 / `content://` / `file://` 与 `..` 越界全部拒绝
  （JVM 单测 `FileBrowserTest` 覆盖）；为遵循最小权限**不申请** `MANAGE_EXTERNAL_STORAGE`，见 [ADR-0005](docs/adr/0005-browsable-folders.md)。

## 参与

- 动手前请读 [CONTRIBUTING.md](CONTRIBUTING.md)：环境、验证方式与仓库约定。
- 安全问题请走**私密渠道**，见 [SECURITY.md](SECURITY.md)——这个项目把手机的控制权交给 Agent，
  默认假设是攻击者能访问公网，边界值得先读一遍。
- 变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

[MIT](LICENSE) © 2026 frankfurtlin
