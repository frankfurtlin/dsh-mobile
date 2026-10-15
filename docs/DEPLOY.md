# 部署手册（服务端 + 手机端）

本文件描述如何把 dsh-mobile 的两半装到真实环境：服务端（Caddy + dsh-passwords 网关 + bridge 服务 + DSH profile 的 MCP 接入）与手机端（APK）。

> 所有命令都在服务器（本仓库所在机器）上执行，除非标注"手机"。
> 产物清单位于 `deploy/`，源码位于 `packages/bridge-server/` 与 `packages/mobile-shell/`。

## 0. 本机部署现状（2026-10-15）

| 项 | 状态 | 证据/命令 |
|---|---|---|
| bridge 服务 | **已装并运行** | `systemctl is-active dsh-mobile-bridge` → active；`curl -s http://127.0.0.1:3082/healthz` → `{"ok":true,...,"phones":0}` |
| Caddy 路由 | **已加** | `/etc/caddy/Caddyfile` 含 `handle /bridge/*`；`caddy validate` 通过；Caddy 已重启加载（本机 Caddyfile 带 `admin off`，`caddy reload` 不可用） |
| 线上 profile MCP 接入 | **已生效（无需重启）** | `~/.dsh/profiles/web/cordis.patch.yml` 含 `mcp-phone`（serverName `phone` → `http://127.0.0.1:3082/mcp`）；本会话里 `phone_list`/`phone_ping` 已可用；回滚副本 `cordis.patch.yml.rollback-no-mcp-phone` |
| 移动端 UI 插件 | **已生效** | `package.json` 的 `dsh.profile.bundles` 含 `dsh-mobile-xc`；23:18 重启成功（见 `docs/verification/last-restart.log`） |
| 手机 WSS 鉴权门（公网实测） | **4/4 符合预期** | `node tools/probe-ws-auth.mjs` → 无 Origin 403 / 无 cookie 401 / 假 cookie 401 / 错 Origin 403 |
| 构件下载口 | **已生效** | `https://dsh.example.com/bridge/download/DSH-Mobile-1.0.2.apk` → 无 cookie `401`；`dist/` 里的 APK 与 release 构建 sha256 一致 |
| dsh web 重启 | **已完成（23:18，无需再做）** | `docs/verification/last-restart.log` → `RESULT: SUCCESS`，之后又跑过一次 `MODE=check` 校验 |

## 0.1 让 Agent 侧 `phone_*` 工具生效（延迟重启）

重启会中断当前 dsh web 进程（也就是当前 GUI 会话与本 agent），所以用 `systemd-run` 在延迟后执行，
并把结果写进 `docs/verification/last-restart.log`：

```bash
systemd-run --unit=dsh-mobile-restart-live --on-active=40 --collect \
  bash ~/dsh-mobile/tools/restart-live.sh
```

脚本行为：重启 `deepseek-harness.service` → 等 3080/3081 恢复 → 打印 bridge 单元、`/healthz` 与一次真实
MCP `initialize` 握手 → 失败则自动恢复 `package.json`（去掉 `dsh-mobile-xc`）与 `cordis.patch.yml`
（去掉 `mcp-phone`）并再次重启。只想检查不改动时：`MODE=check bash tools/restart-live.sh`。

重启后验证（新会话里）：

```bash
journalctl -u dsh-mobile-bridge -n 20 --no-pager | grep -i "mcp\|initialize"
tail -12 ~/dsh-mobile/docs/verification/last-restart.log
```

## 0.1b 升级到 1.0.2（文件浏览）后要做什么

`fs.*` 是**新增的 op**：App 与服务端必须同时升级，否则一致性检查会失败。

```bash
# 1) 服务端：让 bridge 重新加载 src/tools.js（新增 5 个 fs 工具）
systemctl restart dsh-mobile-bridge
curl -s http://127.0.0.1:3082/healthz                      # {"ok":true,...}
cd ~/dsh-mobile && node tools/check-protocol-parity.mjs   # 9/9
# 2) 手机端：安装 1.0.2 release APK（覆盖安装即可，profile 不变）
#    adb install -r packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk
# 3) 手机上：App 菜单 →「选择可浏览的文件夹」，把要交给 Agent 的文件夹选上
```

`systemctl restart dsh-mobile-bridge` **不会**影响线上 dsh web / 当前会话（两者是不同 systemd 单元）；
只有 `deepseek-harness.service` 的重启才会中断本会话。

## 0.1c 把 APK 送到手机（远程场景）

手机不在服务器旁边、只能经 dsh web 访问服务器时，用 bridge 的构件下载口：手机浏览器里先登录
`https://dsh.example.com`（拿到网关 cookie），再打开

```
https://dsh.example.com/bridge/download/DSH-Mobile-1.0.2.apk
```

装新版本时把仓库根的最新签名包复制进 `dist/` 并覆盖下载（`tools/build-apk.sh --release` 之后）：

```bash
cd ~/dsh-mobile
tools/build-apk.sh --release
cp packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk dist/DSH-Mobile-1.0.2.apk
sha256sum dist/DSH-Mobile-1.0.2.apk packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk  # 两行必须一致
```

下载口的两个环境变量在 `deploy/dsh-mobile-bridge.service` 里：

| 变量 | 值 | 作用 |
|---|---|---|
| `DSH_BRIDGE_DOWNLOAD_DIR` | `~/dsh-mobile/dist` | 允许下载的**那一层**目录；留空则整条路由关闭（404） |
| `DSH_BRIDGE_DOWNLOAD_PATH` | `/bridge/download` | URL 前缀，必须落在 Caddy 已代理的 `/bridge/*` 下 |

行为边界（`packages/bridge-server/tools/smoke.mjs` 14 项断言覆盖）：必须是 `GET`/`HEAD`（否则 405）；
文件名须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]*$`（`..`、`/`、隐藏文件 → 400）；缺失文件或目录 → 404；
不带 `dsh_gateway_token` 或 cookie 无效 → 401；响应带 `content-disposition: attachment`、
`cache-control: no-store`、`x-content-type-options: nosniff`。

## 0.2 前置检查

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
install -m 0644 ~/dsh-mobile/deploy/dsh-mobile-bridge.service /etc/systemd/system/
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
dsh --profile mobspike --patch ~/dsh-mobile/deploy/dsh-profile-mcp-phone.yml --dump-config | grep -c phone
```

> 注意：**重启 dsh web 会中断当前 Web 会话**（包括正在进行的对话）。建议在无人使用时执行，并用
> `curl -s http://127.0.0.1:3082/healthz` 与 `tools/list` 是否出现 `phone_*` 工具来确认接入成功。

## 4. 安装手机端 APK

**方式 A（远程，手机只能上网）**：见 §0.1c，手机浏览器打开 `https://dsh.example.com/bridge/download/DSH-Mobile-1.0.2.apk`。

**方式 B（手边能连 USB）**：

```bash
# 手机：打开 USB 调试并连接后
adb devices
adb install -r ~/dsh-mobile/packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk
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
