# 安全策略

## 报告漏洞

请**不要**公开开 issue。用 GitHub 的
[私密漏洞报告](https://github.com/frankfurtlin/dsh-mobile/security/advisories/new)，
或在仓库 Security 标签页点 "Report a vulnerability"。

请附上：复现步骤、影响范围、受影响的组件（App / bridge / DSH 插件 / 部署配置）。
这是个人项目，不承诺响应时限，但会认真处理。

## 这个项目的安全面

它把一台手机的控制权交给了 Agent，所以默认假设是**攻击者能访问公网**。
理解边界有助于判断一个问题是漏洞还是预期行为：

**手机侧（`packages/mobile-shell/`）**

- 无 root，不申请设备管理员权限。自动化依赖用户显式开启的无障碍服务，用户可随时关闭。
- 日志**刻意不记录**剪贴板与输入内容；崩溃日志也不外传。
- 网关 cookie 用 AndroidKeyStore（AES-GCM）加密落盘；卸载即随之失效。
- 站内链接留在 WebView，站外链接交给系统浏览器——避免在壳内被钓鱼页套取会话。

**桥（`packages/bridge-server/`）**

- MCP 端点**只监听 `127.0.0.1`**，不经公网暴露；它面向的是同机的 DSH 进程。
- 公网只暴露手机 WebSocket（`/bridge/ws`）。握手必须同时满足：
  携带网关 cookie，且 bridge 回调网关核验通过（200）；并校验 `Origin` 与
  `DSH_BRIDGE_PUBLIC_ORIGIN` 一致。三者缺一即拒。
- 手机在 NAT 后主动外连，服务端不发起任何到手机的入站连接。
- `/bridge/download/<file>` 只服务 `DSH_BRIDGE_DOWNLOAD_DIR` 的**单层目录**，
  同样要求有效的网关 cookie，防止目录穿越与匿名拉取。

**已知的、有意为之的取舍**

- `allowUnauthenticated` 只应在本地调试时打开，默认关闭；它一旦开启，任何能连上
  bridge 的进程都可以操作手机。
- 出于"无 root"的基调，本项目的自动化**不可能完全静默**：安装 APK 会弹系统确认框，
  无障碍操作期间系统会显示提示。这不是缺陷。

## 部署者注意

- **keystore 就是发布身份**。`~/.secrets/dsh-mobile-release.jks` 泄露 = 别人能签出可覆盖安装的包；
  丢失 = 已安装的 release 包无法原地升级。它不应进入任何仓库或备份到公开位置。
- 网关（dsh-passwords）是唯一的前置认证。bridge 信任它，因此**网关必须只对内网或
  经 Caddy 的 TLS 暴露**，不要直接开放端口。
- Caddy 配置里 `/bridge/*` 必须与 DSH Web UI **同一 origin**，否则 cookie 不会带上——
  这既是功能要求，也保证了鉴权面不会被拆到另一个（可能配置更弱的）主机上。
