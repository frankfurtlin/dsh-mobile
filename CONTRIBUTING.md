# 贡献指南

欢迎 issue 与 PR。这个项目横跨 Android 原生、Capacitor 壳、Node 服务和 DSH 前端插件，
改动前建议先读一遍 [`docs/adr/`](docs/adr/) —— 那里记录了每个关键取舍的**理由**，
很多看起来"绕"的设计是被约束逼出来的（例如为什么桥只监听回环、为什么表现层用 CSS 而不是重写）。

## 环境

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Android SDK | platform 36 / build-tools 35.0.0（`tools/install-toolchain.sh` 一键装） |
| Node | 22+ |
| 构建工具 | `packages/mobile-shell/package-lock.json` 里锁定的 Capacitor 版本 |

```bash
tools/install-toolchain.sh && source ~/.local/android/env.sh
cd packages/mobile-shell && npm ci
```

## 本地验证

跑之前先确认能过，PR 会被 CI 用同一套检查卡住：

```bash
node tools/acceptance.mjs              # 全量验收（含 Gradle 单测）
node tools/acceptance.mjs --skip-gradle  # 没装 Android SDK 时
```

单独跑某一项：

```bash
node tools/check-protocol-parity.mjs   # App / 服务端 / 假手机的 op 一致性
node tools/check-anchors.mjs           # 移动插件的 CSS 哈希漂移
cd packages/bridge-server && npm test  # 桥的自测套件
```

## 约定

**决策要落 ADR。** 凡是"为什么不那样做"值得解释的改动，在 `docs/adr/` 加一条编号记录，
说清候选方案、代价和选它的理由，而不只是描述做了什么。

**提交信息**沿用仓库风格：阶段改动用 `P<n>: 摘要`，其余用 `docs:` / `tools:` / `ci:` / `fix:` / `test:` 前缀，
正文说明**为什么**而不是重复 diff。

**移动端插件的锚点必须哈希无关。** DSH 前端的 CSS-module 类名带构建哈希
（如 `pI_x6G_frame`），DSH 一升级就全变。插件请改成锚语义类名片段（`[class*="markdown"]`）
或元素选择器，并保证桌面端零影响——所有规则放进 `@media (max-width: 1023px)`。
`tools/check-presentation.mjs` 会断言这件事。

**仓库必须与机器无关。** 提交前自查：

```bash
git grep -n "/root/\|/home/"          # 不该有绝对家目录
git grep -n "https://[a-z0-9.-]*\.[a-z]\{2,\}" | grep -v example.com   # 不该有真实域名
```

`deploy/` 里的模板用 `@DOMAIN@` / `@INSTALL_DIR@` / `@NODE_BIN@` 占位符，
`docs/` 里统一用 `dsh.example.com` 和 `~`。

**不要提交本机产物。** keystore、APK、截图、逐次验收 JSON 都已在 `.gitignore` 里——
它们要么是凭据，要么含会话画面，要么只是这台机器的运行留痕。

## PR 清单

- [ ] `node tools/acceptance.mjs` 通过
- [ ] 涉及界面行为的改动，附上手机端与桌面端的对比截图或指标
- [ ] 涉及取舍的改动，补了 ADR 或更新了已有的那一条
- [ ] 没有引入绝对家目录、真实域名、凭据或本机产物
- [ ] 文档（README / PLAN / DEPLOY / ROLLBACK）与实际行为一致
