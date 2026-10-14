# 回滚手册

每一步都可单独执行；所有改动都在服务器配置、systemd 单元与手机上，**不触碰工作区数据**。
建议顺序：先回滚手机端（用户侧最直观），再回滚服务端接入，最后按需回滚 Web UI 插件。

## 0. 先备份（部署前应已完成）

```bash
cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak-$(date +%F-%H%M)"
cp ~/.dsh/profiles/web/cordis.patch.yml "~/.dsh/profiles/web/cordis.patch.yml.bak-$(date +%F-%H%M)"
```

## 1. 手机端

```bash
adb uninstall top.frankfurtlin.dshmobile     # 或 手机设置 → 应用 → DSH Mobile → 卸载
```

* 卸载会一并清除本地密文凭证（`AndroidKeyStore` 里的 `dsh_mobile_gateway_token` 与 prefs）。
* 想保留 App 只清凭证：App 菜单 → 退出登录（会 `POST /gateway/logout` 吊销网关 token，并删除本地密文）。
* 想停掉后台常驻：系统设置 → 无障碍 → 关闭 DSH Mobile；应用信息 → 强制停止。

## 2. 服务端 bridge 接入

**只关掉构件下载口**（保留手机桥，无需动 Caddy）：删除单元里的两行环境变量并重启 bridge

```bash
sed -i '/DSH_BRIDGE_DOWNLOAD_/d' /etc/systemd/system/dsh-mobile-bridge.service
systemctl daemon-reload && systemctl restart dsh-mobile-bridge
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3082/bridge/download/DSH-Mobile-1.0.2.apk   # 期望 404
```

**整体移除 bridge**：

```bash
systemctl disable --now dsh-mobile-bridge
rm -f /etc/systemd/system/dsh-mobile-bridge.service
systemctl daemon-reload
```

Caddy：把 `handle /bridge/*` 段从 `/etc/caddy/Caddyfile` 删除（或直接还原备份），然后

```bash
caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy
curl -s -o /dev/null -w '%{http_code}\n' https://dsh.example.com/bridge/healthz   # 期望 404/502 而非 200
```

DSH profile：从 `cordis.patch.yml` 删除 `mcp-phone` 的 `insert` 段（或还原备份），重启 dsh web。
重启后 `tools/list` 里不再出现 `phone_*` 即为回滚成功。

> 移除 MCP 接入不会影响 DSH 原有功能：bridge 是独立进程，且只通过 `127.0.0.1:3082` 暴露。

## 3. Web UI 移动端适配插件（P1）

```bash
dsh plugin --profile web remove dsh-mobile-xc
# 重启 dsh web 后，移动端恢复官方布局（内容列 334px 的窄布局）
```

备选方案 `@qingshanyuluo/dsh-client-ui-mobile` 是纯 CSS 的极简回退，其类名哈希会随前端构建漂移；
若它失效，先执行 `node tools/check-anchors.mjs`（见 ADR-0001）确认命中率再决定是否继续使用。

## 4. 工具链（可选，约 1.2 GB）

```bash
rm -rf ~/.local/android
```

删除后 `tools/build-apk.sh` 会提示先跑 `tools/install-toolchain.sh`。
发布签名材料在 `~/.secrets/`：**不要**为了回滚而删除 keystore，否则已安装的 release 包永远无法原地升级。

## 5. 回滚验收清单

- [ ] 手机已无 DSH Mobile，或至少不再自动保持 WSS 连接
- [ ] `systemctl status dsh-mobile-bridge` → not-found / inactive
- [ ] `https://dsh.example.com/bridge/healthz` 不再返回 bridge 健康体
- [ ] DSH 会话的 `tools/list` 无 `phone_*`
- [ ] （若只关下载口）`/bridge/download/<文件>` 返回 404，手机 WSS 仍正常
- [ ] dsh web + dsh-passwords 网关正常（`curl -s http://127.0.0.1:3081/gateway/healthz`）
- [ ] 移动端 Web 恢复官方布局（若也移除了 UI 插件）
