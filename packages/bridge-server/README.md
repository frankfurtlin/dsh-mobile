# dsh-mobile-bridge

Agent ↔ 手机桥。一个进程，两个端点：

- `/mcp`（Streamable HTTP，只监听 `127.0.0.1`）—— 供 DSH 的 `dsh-mcp-client` 连接，暴露 `phone_*` 工具。
- `/bridge/ws`（经 Caddy 发布到公网）—— 手机主动连上来的 WebSocket，桥用它下发操作。

设计见 [../../docs/adr/0003-agent-phone-bridge.md](../../docs/adr/0003-agent-phone-bridge.md)。

## 跑起来

```sh
cd packages/bridge-server
npm install
DSH_BRIDGE_PUBLIC_ORIGIN=https://dsh.example.com node src/index.js
```

| 环境变量 | 默认 | 含义 |
| --- | --- | --- |
| `DSH_BRIDGE_PORT` | `3082` | 监听端口（只监听 loopback） |
| `DSH_BRIDGE_HOST` | `127.0.0.1` | 监听地址 |
| `DSH_BRIDGE_GATEWAY` | `http://127.0.0.1:3081` | dsh-passwords 网关，用于核验会话 |
| `DSH_BRIDGE_PUBLIC_ORIGIN` | 空 | 公网来源；设置后强制校验 WebSocket 的 `Origin` 头同源 |
| `DSH_BRIDGE_CALL_TIMEOUT_MS` | `30000` | 单次手机调用超时 |
| `DSH_BRIDGE_HEARTBEAT_MS` | `25000` | WebSocket ping 间隔 |
| `DSH_BRIDGE_MAX_MESSAGE_BYTES` | `1048576` | 单帧上限 |
| `DSH_BRIDGE_SESSION_CACHE_MS` | `60000` | 会话核验成功结果的缓存时长 |
| `DSH_BRIDGE_ALLOW_NO_AUTH` | 未设置 | 设为 `1` 时不校验会话（**仅本地开发**） |

## 认证

手机握手时带 `Cookie: dsh_gateway_token=<token>`。桥用 loopback 探活向网关核验
（带该 cookie 请求 `GET /`：已认证 200，未认证 302 到 `/gateway/login`），失败即拒绝 upgrade（HTTP 401）。
成功结果按 token 指纹缓存 60s。

## 协议

- 手机 → 桥：`{t:'hello', device:{id,name,model}, caps:[...]}`
- 桥 → 手机：`{t:'call', id, op, args}`
- 手机 → 桥：`{t:'res', id, ok, data?}` / `{t:'res', id, ok:false, error}`
- v1 op：`ping` / `info` / `shares` / `clipboard.get` / `clipboard.set` / `notify` / `open`

## 自测

```sh
node tools/smoke.mjs      # 进程内起服务 + 假手机，跑 29 项断言
node tools/sdk-check.mjs  # 用 DSH 同款 MCP SDK 客户端（@modelcontextprotocol/client 2.0.0）连通性验证
node tools/fake-phone.mjs # 手动起一个假手机，连本地已运行的桥
```

## 已知限制

- 单用户服务：所有在线手机共用一个命名空间，工具不区分登录用户。
- 手机离线时调用立即失败，不排队。
- 不支持 MCP 的 SSE 长流（只回 JSON），不支持 resources/prompts。
