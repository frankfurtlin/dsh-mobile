# ADR-0006：把安装包送到手机——复用网关会话的构件下载口

- 状态：已接受（2026-10-15）
- 相关：ADR-0003（Agent ↔ 手机桥）、ADR-0002（原生 WebView 壳）

## 背景

App 只有装到手机上才有意义，但部署环境是**远程云服务器**：服务器上能用 `adb`，用户手上没有数据线，
也拿不到服务器文件系统——用户能触达服务器的唯一通道就是 dsh web（Caddy → dsh-passwords 网关 → dsh web）。
`phone_files_save` 也不行：它要求手机**已经**装了 App 并连上 bridge，鸡生蛋问题。

所以需要一个"手机浏览器能打开、且只有已登录用户能拿到文件"的投递通道。

## 备选

1. **临时 Caddy 目录服务**（`file_server` 挂在 `dsh.example.com` 下）：要改 Caddy 并重启。
   本机 Caddyfile 带 `admin off`，`caddy reload` 不可用，改一次就得 restart 一次线上入口——
   用户在会话里，这个中断代价不可接受；而且文件默认对公网匿名开放。
2. **第三方网盘 / 传输服务**：把 APK 上传到外部站点。引入外部依赖、泄漏构建产物、且用户不一定能访问。
3. **复用 bridge 已公开的 `/bridge/*` 路由，加一个鉴权下载口**（采纳）：Caddy 已经
   `handle /bridge/*` → `127.0.0.1:3082`，bridge 已经会用网关 cookie 做核验（ADR-0003 的 WS 路径同款逻辑）。
   手机浏览器访问 `https://dsh.example.com/bridge/download/<文件名>` 时会自动带上
   `dsh_gateway_token`（`Path=/`、`SameSite=Lax`，顶层导航仍会发送），bridge 回环探网关确认为 `200` 后
   才把文件流出去。**零 Caddy 改动、零额外端口、零第三方**。

## 决策

在 bridge 里增加 `GET/HEAD /bridge/download/<文件名>`：

- 目录由 `DSH_BRIDGE_DOWNLOAD_DIR` 指定（本机为 `dist/`），**留空即整条路由关闭**（404）；
- 只服务该目录**那一层**：文件名须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]*$`，客户端永远不提供路径，
  `..`、`/`、隐藏文件返回 400，目录或缺失文件返回 404，非 `GET`/`HEAD` 返回 405；
- 未带 `dsh_gateway_token` 或 cookie 无效 → `401`（与手机 WSS 用同一个 `createSessionVerifier`）；
- 响应带 `content-disposition: attachment`、`cache-control: no-store`、`x-content-type-options: nosniff`，
  扩展名映射内容类型（`.apk` → `application/vnd.android.package-archive`）。

## 后果

- 用户装 App 的路径变成：手机浏览器登录一次 dsh web → 打开下载链接 → 系统安装器接管。不需要数据线、
  不需要外部服务，也不动正在服务的 Caddy / dsh web（只重启 `dsh-mobile-bridge`）。
- 暴露面很小：能拿到文件的权限**等价于已经在浏览器里登录过网关**；目录还是白名单的一层，
  即使误把私钥放进 `dist/` 也只会被已登录的自己下载到。
- `dist/` 与发布包必须**同一份字节**，否则用户装到的就不是签过名的那个包——`tools/acceptance.mjs`
  把「`dist/*.apk` 的 sha256 == `app-release.apk` 的 sha256」变成构建期断言，`tools/smoke.mjs`
  用 14 项断言覆盖下载口的鉴权与路径边界。
- 回退：删掉单元里的 `DSH_BRIDGE_DOWNLOAD_DIR` 并 `systemctl restart dsh-mobile-bridge` 即关闭下载口，
  不必动 Caddy 或 profile（见 [ROLLBACK.md](../ROLLBACK.md)）。
