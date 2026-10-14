# dsh-mobile 开发计划

> 用户已确认：项目名 `dsh-mobile`；包名 `top.frankfurtlin.dshmobile`；应用名 `DSH Mobile`；
> **纳入**无 root 无障碍自动操作；APK 在本机构建；移动端 UI 先复用生态插件再按需自研。

## 1. 现状核查（实测）

| 事实 | 证据 |
|---|---|
| dsh web 在 `127.0.0.1:3080`，自身要求 token（裸访问 401） | `curl 127.0.0.1:3080` → 401 |
| Caddy 终结 TLS，`dsh.example.com` → `127.0.0.1:3081` | [Caddyfile](/etc/caddy/Caddyfile) |
| 登录：`GET /gateway/login`（HTML 表单 + CSRF 双提交）；`POST /gateway/login`（`username/password/csrf/next`，要求同源）；成功 → `Set-Cookie: dsh_gateway_token=…; HttpOnly; SameSite=Lax; Max-Age=43200`（12h）→ 302；失败 401/429 | `dsh-passwords/dist/gateway.js` :3087, 3195–3244 |
| 登出 `POST /gateway/logout` 服务端吊销；`GET /gateway/healthz` `/gateway/readyz` 可探活 | 同上 :3265–3289, 1292–1297 |
| 网关剥掉客户端自带的 `dsh-auth-*`/`dsh_gateway_token`，**在服务端注入上游 dsh 凭据** | 同上 :776, 1414, 1460–1486 |
| 移动端适配缺口：主壳窄屏仅保留 56px 固定 rail，内容列被压到 `viewport-56px` | 影子 profile 实测（见 ADR-0001） |
| 工具链：node 24.21 / pnpm 12.8.1 可用；无 JDK/SDK → 已装到 `~/.local/android` | 本仓库 `tools/install-toolchain.sh` |

**关键结论**：App 不需要重写认证——内嵌网关登录页即可，凭证就是一个 HttpOnly cookie。

## 2. 目标

1. 手机装 APK，首次只填 **endpoint**；走 dsh-passwords 原版登录页；凭证本地加密保存，重开免登录，12h 过期自动回登录页。
2. 登录后 **web 全部功能**可用（会话、工具审批、文件预览、设置、插件页）。
3. 补齐 web 没有的手机能力，人机双通道：
   - 人：浏览/上传手机文件到 workspace、下载并用手机 App 打开、系统分享进 DSH、启动已安装 App。
   - Agent：服务端 `phone.*` 工具（列目录/读写/列应用/启动/剪贴板），经 App 主动外连的通道回传。
4. 移动端布局适配：抽屉侧栏、无横向溢出、键盘不遮输入区、安全区。
5. 无 root 无障碍自动操作：截图、点击、滑动、返回、启动应用（需用户在系统设置里显式授权）。

**非目标（本期）**：iOS 原生包（走 PWA「添加到主屏幕」）；root/Magisk 方案。

## 3. 架构

```
手机 (Capacitor Android)                     服务器
 Setup: endpoint + /gateway/healthz 探活
 WebView: 网关登录页（原样复用）  ──HTTPS──▶  Caddy :443
 Cookie 捕获 → EncryptedSharedPreferences      └ dsh-passwords :3081（登录/12h cookie/注入上游凭据）
 WebView: DSH SPA + 移动端 UI 插件             └ dsh web :3080
 PhoneBridgeClient ──── 外连 WSS ────────────▶ 宿主插件 phone-bridge：phone.* 工具
 AccessibilityService（本地，无 root）         （截图/点击/滑动/开应用）
```

三个关键决策：
- **不重写认证**：内嵌网关登录页；登录后从原生 CookieManager 取 `dsh_gateway_token`，加密落盘、启动回填。
- **不重写前端**：WebView 加载官方 SPA，功能 100% 对齐；移动体验靠服务端客户端插件，浏览器与 App 同时受益。
- **手机能力靠外连通道**：手机在 NAT 后无入站端口，由 App 主动 WSS 连服务器反向 RPC；三重闸门＝权限 + 路径白名单 + App 内确认。

## 4. 阶段计划与进度

| 阶段 | 内容 | 验收 | 估时 | 状态 |
|---|---|---|---|---|
| **P0 工具链脚手架** | JDK21 + cmdline-tools 装 `~/.local/android`；Capacitor 工程 + `assembleDebug` | 产出可安装 APK | 0.5–1d | ✅ 首次构建成功（3h35m，冷启动下 Gradle+依赖）；增量构建 2–6s |
| **P1 移动端 UI 选型** | 影子 profile 实测 3 个候选；选主力、记缺口；再装进 `web` profile | 手机端内容列占满、桌面零变化（截图/指标对比） | 1d | ✅ 选型完成（ADR-0001）；`dsh-mobile-xc` 已装进线上 `web` profile，**重启 dsh web 后生效** |
| **P2 App 骨架 + endpoint 登录** | endpoint 归一化与探活、WebView 登录、cookie 捕获/加密持久化/回填、401 自动回登录、登出、局域网 HTTP/自签证书 | 冷启动重开免登录；错 endpoint 有明确报错；过期自动回登录 | 1.5–2d | ✅ 代码完成 + 调试 APK 已产出（4.4MB）；**待真机验证** |
| **P3 原生能力** | 安全区、返回键、键盘 resize、下载拦截、外部 App 打开、附件选择/拍照上传、系统分享进 DSH、剪贴板、流式期间常亮、长任务通知 | 每项真机可走通；附件在会话正常预览 | 2–3d | ✅ 代码完成（edge-to-edge inset / 拍照回填 / `ACTION_SEND` 收件箱 / 剪贴板双向 / 常亮开关 / 通知权限），**待真机验证** |
| **P4 Agent ↔ 手机桥** | 服务端 bridge（MCP over Streamable HTTP + 手机 WSS，网关 cookie 核验）+ App 侧前台服务 WSS 客户端（OkHttp，指数退避重连）；工具 `phone_list/ping/info/shares/clipboard_get/set/notify/open/files_save/files_open/call`；后续 `phone.list_dir / read_file / write_file / list_apps / launch_app`；SAF/MediaStore 授权、路径白名单、危险操作确认、重连与超时 | 对话中让 Agent 读到手机上的内容；未认证连接被拒 | 3–5d | ✅ 代码完成 + 自测 60/60 与 op 一致性 9/9（ADR-0003；三条鉴权路径实测 + DSH 同款 MCP SDK 客户端连通；APK 含 `BridgeService`/`Files`），**待真机联调** |
| **P5 无 root 无障碍自动操作** | `AccessibilityService`：截图（MediaProjection/无障碍）、点击、滑动、返回、启动应用；敏感操作 App 内二次确认与审计；系统设置引导页 | 在手机上通过对话完成"打开某 App 并点击某按钮"；未授权时优雅降级 | 2–3d | ✅ 代码完成 + 自测 60/60（`A11yService` 截图/控件树/手势 + `phone_apps`/`phone_launch`/`phone_ui_tree`/`phone_screenshot`/`phone_tap`/`phone_swipe`/`phone_global`/`phone_a11y_status`；App 内开关 + 系统设置引导），**待真机验证** |
| **P6 验证与交付** | 自动化验收脚本 + 真机清单、release 签名（keystore 存 `~/.secrets`）、PWA/iOS 说明、一键构建与回滚文档 | 全部验收项通过，第三方可照文档装起来 | 1d | ✅ 完成：`tools/acceptance.mjs` **52/52**（含 10 项端点单测、9 项 op 一致性、4 项线上部署断言）；`tools/gen-keystore.sh` + `--release` 签名包 `1.0.1`（apksigner 校验通过）；`docs/DEPLOY.md`、`docs/ROLLBACK.md`、`docs/DELIVERY.md`、`docs/verification/device-checklist.md`；`tools/check-anchors.mjs` 漂移检测（xc 28/33=85%）；**剩余动作**：延迟重启线上 dsh web（让 `phone_*` 工具与移动布局生效）+ 真机逐项验收 |

总计约 **11–15 个工作日**。P2 结束即可日常使用；P3/P4/P5 为增强。

## 5. 安全

- 凭证只存网关 `dsh_gateway_token`，用 AndroidKeyStore（AES-GCM）加密后写入私有 prefs；不上传、不进日志、不进 git。
- 默认要求 HTTPS；局域网 http/自签证书需显式开关并给出警告。
- 桥通道：WSS 必须携带网关 cookie，bridge 回环向 `dsh-passwords` 核验并校验 Origin；MCP 端点只监听 loopback。
- `phone_files_save` 只写 `Downloads/DSH`（MediaStore），不接受任意路径写入；`phone_files_open` 只打开 App 能授出读权限或 MediaStore 里的文件。
- 无障碍服务是高敏感权限：默认关闭，需 App 内开关 + 系统设置两处开启，App 内提供系统设置引导与即时关闭入口。
- 复用第三方插件前核对许可证（`dsh-passwords` 为 GPL-3.0；本仓库自研部分保持独立实现）。

## 6. 验收标准

自动化部分（`node tools/acceptance.mjs`，当前 **52/52**）：

1. 工具链可用（JDK 21 / aapt2 / apksigner）、调试包与签名发布包均产出并通过 `apksigner verify`。
2. APK 清单正确：5 个组件（MainActivity / SetupActivity / ShareTargetActivity / A11yService / BridgeService）、
   无障碍绑定权限、前台服务类型 `connectedDevice`、`<queries>` 包可见性、`ACTION_SEND` 分享目标。
3. bridge 端到端自测 **60/60**（MCP 握手、19 个工具、文件保存/打开、鉴权失败路径、HTTP 面）+ 三方 op 一致性
   **9/9**（`tools/check-protocol-parity.mjs`，覆盖「CAPS 声明了但 switch 没实现」这类缺口）+ DSH 同款 MCP SDK 客户端连通。
4. App 侧 JVM 单测 **10/10**（`./gradlew testDebugUnitTest`，端点解析：scheme 归一、IDN、IPv6、端口边界、
   网关 URL、bridge URL、`owns()` 判定、百分号编码）。
5. DSH 侧接线：web profile 依赖与 bundles 含 `dsh-mobile-xc`；`deploy/` 三个产物齐全；MCP patch 能被
   `dsh --patch … --dump-config` 解析（影子 profile 验证，不动线上）。
6. 线上部署已就位：`/etc/systemd/system/dsh-mobile-bridge.service` 与 `deploy/` 副本一致；
   `/etc/caddy/Caddyfile` 含 `handle /bridge/*`；线上 `cordis.patch.yml` 含 `mcp-phone` 且留有回滚副本。
7. 手机 WSS 鉴权门（公网）：`node tools/probe-ws-auth.mjs` **4/4**（无 Origin 403 / 无 cookie 401 / 假 cookie 401 / 错 Origin 403）。
8. 交付文档齐全；`tools/check-anchors.mjs` 漂移检测可复现（xc 28/33 = 85%，qingshanyuluo 4/6 = 67%）。

真机部分（见 [verification/device-checklist.md](verification/device-checklist.md)，46 项）：

9. 手机浏览器 `https://dsh.example.com`：移动端布局生效（内容列占满、抽屉可开、无横向溢出），桌面端指标与改动前一致。
10. APK：只填 endpoint → 登录 → 全功能可用；杀进程重开免登录；登出后必须重新认证；token 过期自动回登录。
11. 手机文件双向：选择手机文件上传到 workspace；workspace 文件下载并用外部 App 打开；Agent 可保存文件到 `Download/DSH` 并可用 `phone_files_open` 在手机上打开。
12. Agent 工具：对话中成功读取手机剪贴板/收件箱/通知；伪造 cookie 的连接被拒。
13. 无障碍：授权后可完成一次"启动 App + 点击"任务；未授权时立即返回明确错误。
14. 回滚：按 [ROLLBACK.md](ROLLBACK.md) 卸载 App、停 bridge、移除 MCP 接入与 UI 插件后，DSH 恢复原状。

## 7. 风险与预案

| 风险 | 预案 |
|---|---|
| 现成移动插件靠 CSS-module 哈希锚定 DOM，DSH 升级后静默失效 | `tools/check-anchors.mjs` 可复现漂移检测（当前 xc 28/33 = 85%、qingshanyuluo 4/6 = 67%）；低于阈值即换回退方案或重钉锚点（ADR-0001） |
| `@canary-builds/dsh-mobile-ui` 注入目标 `dsh-client-runtime` 在本机不存在 | 已排除 |
| 个别 ROM WebView 对 HttpOnly cookie 持久化不稳 | 捕获后加密存储 + 启动回填；必要时 `@capacitor/cookies` 双写；P2 首日 spike |
| WebView 与浏览器在下载/文件选择/剪贴板行为上不同 | P3 全部走原生拦截，不依赖 Web 默认行为 |
| 无障碍权限被厂商 ROM 限制（后台被杀、权限收紧） | P5 提供前台常驻通知 + 引导页；失败时明确降级提示 |
| 首次 Gradle 构建需下载 Gradle 8.14.3 与依赖（数百 MB） | 本机网络可达；构建缓存在 `~/.gradle` |
