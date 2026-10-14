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
| P0 工具链 + Capacitor 骨架 | 工具链完成；Android 工程已生成，首次 `assembleDebug` 构建中 |
| P1 移动端 UI 选型 | **已完成**：选定 `dsh-mobile-xc`，回退 `@qingshanyuluo/dsh-client-ui-mobile` |
| P2 App 骨架 + endpoint 登录 | 未开始 |
| P3 原生能力 | 未开始 |
| P4 Agent ↔ 手机桥 | 未开始 |
| P5 无 root 无障碍自动操作 | 未开始 |
| P6 验证/签名/交付 | 未开始 |

详见 [docs/PLAN.md](docs/PLAN.md)。

## 安全

- 凭证只存网关 `dsh_gateway_token`，落盘用 Android `EncryptedSharedPreferences`，不进日志、不进 git。
- 默认要求 HTTPS；局域网明文/自签证书需用户显式开启。
- `phone.*` 工具受路径白名单 + App 内确认约束。
