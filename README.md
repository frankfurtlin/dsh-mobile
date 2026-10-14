# dsh-mobile

把 DeepSeek Harness（DSH）搬到手机上：**只需填 endpoint 的 Android App** + **移动端 Web 界面适配**。

- App 首次启动只填服务器地址 → 跳转 dsh-passwords 网关登录页（与浏览器完全同一套登录）→
  登录凭证加密保存在手机本地 → 重开免登录 → 直接使用官方 Web UI 的全部功能。
- 顺带治好"手机浏览器用 DSH 难受"：侧栏变抽屉、内容列占满宽度、安全区/键盘适配，**桌面端零影响**。
- 额外提供 Web 端没有的手机能力：浏览/上传手机文件、用手机 App 打开文件、系统分享进 DSH，
  以及让 Agent 通过工具读写手机文件；后续再加无 root 无障碍自动操作。

## 目录结构

```
dsh-mobile/
├─ docs/
│  ├─ PLAN.md                     # 开发计划与当前进度
│  ├─ adr/0001-...md              # 移动端 UI 选型决策（含实测数据）
│  └─ verification/               # 无头 Chromium 采证：截图 + 布局诊断 JSON
├─ packages/
│  ├─ mobile-shell/               # Capacitor Android App（包名 top.frankfurtlin.dshmobile）
│  ├─ mobile-ui-plugin/           # 自研 DSH 客户端插件（补生态插件缺口，哈希无关锚点）
│  └─ phone-bridge-plugin/        # DSH 宿主插件：phone.* 工具 + 外连 WSS RPC
├─ tools/
│  ├─ install-toolchain.sh        # JDK 21 + Android cmdline-tools（装 ~/.local，不碰系统）
│  ├─ shoot.mjs                   # 无头截图 + 移动端布局诊断
│  └─ build-apk.sh                # 一键构建（后续）
└─ tests/
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
cd packages/mobile-shell
npm install
npx cap sync android
cd android && ./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

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

## 当前状态

| 阶段 | 状态 |
|---|---|
| P0 工具链 + Android 工程 | **完成**：JDK 21 + Android SDK 35 在 `~/.local/android`，`assembleDebug` 可构建 |
| P1 移动端 UI 选型 + 线上安装 | **完成**：选定 `dsh-mobile-xc`（回退 `@qingshanyuluo/dsh-client-ui-mobile`）；已装进 `web` profile，**重启 dsh web 后生效** |
| P2 App 骨架 + endpoint 登录 | **代码完成，待真机验证**：调试 APK 已产出 |
| P3 原生能力 | **代码完成，待真机验证** |
| P4 Agent ↔ 手机桥 | **代码完成，待真机联调**：`packages/bridge-server`（MCP `/mcp` 仅 loopback + 手机 WSS `/bridge/ws`，网关 cookie 核验，55/55 自测）；App 侧 `BridgeService`（前台服务 + OkHttp WSS + 指数退避重连）；工具 `phone_list/ping/info/shares/clipboard_get/set/notify/open/files_save/call` |
| P5 无 root 无障碍自动操作 | **代码完成，待真机联调**：`A11yService`（截图取控件树、点击/滑动/系统键，均在设备端执行）+ 应用 `phone_apps`/`phone_launch`/`phone_ui_tree`/`phone_screenshot`/`phone_tap`/`phone_swipe`/`phone_global`/`phone_a11y_status`，三重开关（App 内开关 + 系统无障碍 + 设备在线） |
| P6 验证/签名/交付 | 未开始 |

详见 [docs/PLAN.md](docs/PLAN.md)。

## 装到手机

```bash
cd ~/workspace/dsh-mobile
tools/build-apk.sh                       # → packages/mobile-shell/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r packages/mobile-shell/android/app/build/outputs/apk/debug/app-debug.apk
```

首次启动只填 endpoint（如 `https://dsh.example.com`），App 会 `GET /gateway/healthz`
探活并打开网关登录页；登录后凭证加密落盘，杀进程重开免登录（服务端 cookie 12h 有效期）。

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
| 手机桥 | App 前台服务保持一条已认证 WSS `/bridge/ws`；Agent 侧经 MCP 工具 `phone_*` 访问手机（剪贴板读写、通知、打开链接、保存文件到 Downloads/DSH、通用直通 `phone_call`）；`DshMobile.bridgeStatus()` 可查连接状态 |
| 无障碍自动化 | 手机端 `A11yService` 在设备上执行：`phone_ui_tree` 读控件树（含 tap 中心点）、`phone_screenshot` 截图、`phone_tap`/`phone_swipe`/`phone_global` 操作、`phone_apps`/`phone_launch` 打开应用；需用户在 App 内打开开关并在系统设置里启用该服务 |
| JS bridge | `DshMobile.getEndpoint/getAppVersion/openExternal/copyText/getClipboard/setKeepAwake/keepAwake/shares/saveBase64/bridgeStatus` |

## 安全

- 凭证只存网关 `dsh_gateway_token`，落盘用 AndroidKeystore AES-GCM 封存，不进日志、不进 git。
- 默认要求 HTTPS；局域网明文/自签证书需用户显式开启。
- `phone.*` 工具受路径白名单 + App 内确认约束。
