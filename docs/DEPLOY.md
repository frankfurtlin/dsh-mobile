# 部署手册（服务端 + 手机端）

本文件描述如何把 dsh-mobile 的两半装到真实环境：服务端（Caddy + dsh-passwords 网关 + bridge 服务 + DSH profile 的 MCP 接入）与手机端（APK）。

> 所有命令都在服务器（本仓库所在机器）上执行，除非标注"手机"。
> 产物清单位于 `deploy/`，源码位于 `packages/bridge-server/` 与 `packages/mobile-shell/`。

## 0. 前置检查

```bash
# 线上 dsh web 与网关在跑
ss -ltnp | grep -E ':(3080|3081|3082)\b'
curl -s http://127.0.0.1:3081/gateway/healthz   # {"ok":true,"service":"dsh-passwords"}
# 工具链（构建 APK 时需要）
source ~/.local/android/env.sh && java -version && aapt2 version
```

## 1. 部署 bridge 服务（Agent ↔ 手机桥）

```bash
# 1) 服务单元
install -m 0644 ~/workspace/dsh-mobile/deploy/dsh-mobile-bridge.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now dsh-mobile-bridge
systemctl status --no-pager dsh-mobile-bridge

# 2) 自检（仅 loopback 可访问）
curl -s http://127.0.0.1:3082/healthz
# {"ok":true,"service":"dsh-mobile-bridge","phones":0,...}
journalctl -u dsh-mobile-bridge -n 30 --no-pager
```

关键环境变量（已写在 unit 里，按需改）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DSH_BRIDGE_PORT` | `3082` | 监听端口（仅 127.0.0.1） |
| `DSH_BRIDGE_UPSTREAM` | `http://127.0.0.1:3081` | 用于核验手机 cookie 的网关地址 |
| `DSH_BRIDGE_PUBLIC_ORIGIN` | `https://dsh.example.com` | 允许的手机 WebSocket Origin |
| `DSH_BRIDGE_ALLOW_UNAUTHENTICATED` | 空 | **仅调试**；设 `1` 会跳过 cookie 核验，勿在生产打开 |

## 2. Caddy：把 `/bridge/*` 交给 bridge

编辑 `/etc/caddy/Caddyfile`，在 `dsh.example.com` 站点的反代规则**之前**加入（片段见 `deploy/caddy-dsh-mobile.snippet`）：

```caddy
dsh.example.com {
    handle /bridge/* {
        reverse_proxy 127.0.0.1:3082
    }
    reverse_proxy 127.0.0.1:3081
}
```

```bash
cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak-$(date +%F-%H%M)"
# 手工插入片段后：
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
curl -s https://dsh.example.com/bridge/healthz   # 经公网验证
```

## 3. 把 MCP 接入 DSH profile

`deploy/dsh-profile-mcp-phone.yml` 是给当前 profile 的 Cordis patch：

```bash
cp /etc/caddy/Caddyfile /dev/null 2>/dev/null || true   # （无副作用，保持段落顺序）
# 在 profile 的 cordis.patch.yml 里插入（或直接复制下面这段）
```

```yaml
- insert:
    - id: mcp-phone
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: phone
        transport: streamable-http
        url: http://127.0.0.1:3082/mcp
```

验证配置能解析（不重启线上，用影子 profile）：

```bash
dsh --profile web --dump-config | grep -A3 mcp-phone   # 已写入线上 profile 时
# 或先验证 patch 文件语法：
dsh --profile mobspike --patch ~/workspace/dsh-mobile/deploy/dsh-profile-mcp-phone.yml --dump-config | grep -c phone
```

> 注意：**重启 dsh web 会中断当前 Web 会话**（包括正在进行的对话）。建议在无人使用时执行，并用
> `curl -s http://127.0.0.1:3082/healthz` 与 `tools/list` 是否出现 `phone_*` 工具来确认接入成功。

## 4. 安装手机端 APK

```bash
# 手机：打开 USB 调试并连接后
adb devices
adb install -r ~/workspace/dsh-mobile/packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk
```

release 包需要先签名（见 `docs/DELIVERY.md`）：`tools/gen-keystore.sh && tools/build-apk.sh --release`。

首次启动流程：

1. 打开 DSH Mobile → `SetupActivity`：填 endpoint（如 `https://dsh.example.com`）→ 点"测试连接"
   （必须返回 `dsh-passwords` 健康体，否则明确报错）。
2. 自动跳到网关登录页 → 用 dsh-passwords 的账号密码登录 → 302 回到 Web UI；
   网关下发的 `dsh_gateway_token`（HttpOnly, 12h）被 `CredentialStore` 用 AndroidKeyStore AES-GCM 加密后写进本地。
3. 之后冷启动直接进 Web UI（凭证回填）；token 过期或 401/403 时自动回登录页。

## 5. 手机端开关（按需开启）

| 能力 | 开关位置 | 说明 |
|---|---|---|
| 手机桥 | App 菜单 → 启动手机桥 | 前台服务保持一条已认证 WSS；通知栏常驻 |
| 无障碍自动化 | App 菜单 → 无障碍自动化（内有开关 + 跳系统设置） | 需**两处**都打开：App 内开关 + 系统"已安装的服务" |
| 通知（Agent 发通知） | 首启时授权 `POST_NOTIFICATIONS` | 拒绝则 `phone_notify` 返回失败 |
| 后台常驻 | 系统设置 → 电池 → 不受限制 | 部分 ROM 会杀前台服务 |

## 6. 常见问题

| 现象 | 排查 |
|---|---|
| 手机桥一直重连 | 公网 `/bridge/healthz` 是否可达；`DSH_BRIDGE_PUBLIC_ORIGIN` 是否与实际域名一致；App 里 endpoint 是否同一个域名 |
| Agent 看不到 `phone_*` 工具 | profile patch 是否生效、dsh web 是否已重启、`phone_list` 是否返回在线设备 |
| `phone_launch` 报 no launchable activity | 该包没有启动器入口（服务类/预装组件）；先用 `phone_apps` 取列表里的包名 |
| 自动化报 "accessibility automation is switched off" | App 内开关没开；报 "not enabled for DSH Mobile" 则系统设置里没启用 |
| 截图失败 | 目标窗口带 `FLAG_SECURE`（银行/支付），属预期限制（ADR-0004） |
