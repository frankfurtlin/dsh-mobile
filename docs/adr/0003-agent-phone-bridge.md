# ADR-0003：Agent ↔ 手机桥（P4）走 MCP over Streamable HTTP

- 状态：已接受（2026-10-14）
- 关联：ADR-0002（原生 WebView 壳）、PLAN.md P4

## 背景

P4 要让 Agent（运行在 dsh web 宿主进程里）能读写手机上的文件、打开手机 App。手机在 NAT 之后，
服务端无法主动发起连接，只能由手机主动连上来维持一条长连接。

候选集成方式：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| A. 自研 DSH 宿主插件 | `ctx.tools.register(defineTool(...))`，插件内直接持有手机长连接 | 零网络跳数，但要写一个 DSH 插件包（TS、构建产物、`@deepseek-ai/dsh-tools` API 随版本漂移），还要在每次 DSH 升级后重新验证 |
| B. 独立 bridge 服务 + MCP（**选定**） | 独立 Node 进程暴露 `/mcp`（Streamable HTTP）与 `/bridge/ws`（手机 WebSocket）；DSH 侧只加一段 `dsh-mcp-client` 配置 | DSH 侧零代码；MCP 是 DSH 已支持的稳定集成面（`@deepseek-ai/dsh-mcp-client`，工具名 `mcp__<serverName>__<tool>`）；bridge 可独立重启、独立调试 |
| C. bridge 直接挂到 3080 上游 | 让 DSH web 进程反向代理到 bridge | 侵入 web app，升级即失效；否决 |

## 决策

采用 B。

- bridge 只监听 `127.0.0.1:<port>`（默认 3082）。`/mcp` 永不经公网暴露。
- `/bridge/ws` 由 Caddy 在 `dsh.example.com` 上反代到 loopback，手机从公网连 `wss://dsh.example.com/bridge/ws`。
- bridge 不自己实现 MCP SDK，而是手写最小 Streamable HTTP JSON-RPC（initialize / tools/list / tools/call / ping）。
  理由：SDK 2.0.0 的服务端 API（`McpServer`、`createMcpHandler`）与 1.x 差异大且文档缺失，手写 150 行可
  避免版本漂移，且协议面只有四个方法。
- 手机侧 WebSocket 用 OkHttp（`com.squareup.okhttp3:okhttp`），握手时手动带上 `Cookie: dsh_gateway_token=...`。

## 认证

1. 手机在 WS upgrade 请求里带 `Cookie: dsh_gateway_token=<token>`（App 从 `CookieManager` 读出）。
2. bridge 用 loopback 探活向网关核验：`GET http://127.0.0.1:3081/` 带该 cookie、`redirect: 'manual'`。
   - `200` → 已认证（网关会代注入上游凭据并回 SPA）
   - `302 → /gateway/login` / `401` → 拒绝握手（1008 policy violation）
   - 实测：无 cookie 访问 3081 即 302 到 `/gateway/login`
3. 成功结果按 token 的 sha256 前缀缓存 60s，避免每次重连都打一次网关。
4. 若配置 `publicOrigin`，校验 WS 的 `Origin` 头同源（防跨站 WS 劫持）。

为什么不用 Caddy `forward_auth`：那需要网关提供一个"已认证 200 / 未认证非 302"的端点，
实测网关只有 302 语义；把判定放在 bridge 里更直白，也不依赖 Caddy 的 `handle_response` 行为。

## 协议（bridge ↔ 手机，JSON over WS）

- 手机 → 服务端：`{t:'hello', device:{id,name,model}, caps:[...]}`
- 服务端 → 手机：`{t:'call', id, op, args}`
- 手机 → 服务端：`{t:'res', id, ok, data?}` / `{t:'res', id, ok:false, error:'...'}`
- 心跳：服务端每 25s 发 WebSocket ping；两次无 pong 视为掉线。
- 上限：单条消息 1 MiB，单次调用默认 30s 超时。

v1 op：`ping` / `info` / `shares` / `clipboard.get` / `clipboard.set` / `notify` / `open` / `files.save` / `files.open`。
工具 `phone_call` 是通用直通口，新增 op 不必改 bridge。

## App 侧实现（P4.2）

- `BridgeService`（前台服务，`foregroundServiceType=connectedDevice`）持有一条 OkHttp WebSocket：握手带 `Cookie: dsh_gateway_token=<token>`（来自 `CredentialStore`）与 `Origin: <endpoint base>`；连上后发 `hello`，收 `welcome`；掉线按 2/4/8/15/30/60s 退避重连；`MainActivity` 在登录后启动、登出与 401 时停止，token 变化时重启。
- 通知：常驻「DSH 手机桥」状态通知（渠道 `dsh_bridge_status`，低优先级）+ Agent 触发的提醒（渠道 `dsh_agent_messages`）。`DshMobile.bridgeStatus()` 让 Web 端读到状态字符串。
- `files.save`：三种取数方式任选其一——`data`（base64 或 `data:` URL）、`url`（带网关 cookie 下载，32 MiB 上限）、`path`（本机可读路径）；落盘走 `Files.save` 的 MediaStore（API 29+，`Downloads/DSH`、`Pictures/DSH`、`Movies/DSH`、`Music/DSH`、`Documents/DSH`，写入期间 `IS_PENDING`），低于 Q 回退到应用外部目录；**不弹系统对话框**，因此 Agent 可在无人值守时写入。`files.open` 交给 `ACTION_VIEW`（带读授权 flag）。
- 工具名：MCP 侧 `phone_files_save`（`data`/`url`/`path` 三选一，`dest` 决定落盘集合），`phone_call` 可直通 `files.open` 等新 op。

## 已知限制

- bridge 是单用户个人服务：所有已连接手机在同一命名空间下，工具不区分登录用户（v1 只在本人服务器使用）。
- 手机离线时工具调用直接报"无在线设备"，不做队列。
- P5（无 root 无障碍自动操作）复用同一条 WS 通道，新增 `a11y.*` op。
