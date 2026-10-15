# ADR-0001：移动端 Web 适配的选型与落地方式

- 状态：已决定（运行时验证通过）
- 日期：2026-10-14
- 目标 DSH 版本：`0.2.1-alpha.2`（本机全局安装）

## 背景

本机 `dsh web`（127.0.0.1:3080）在手机浏览器上体验差：主壳自带的窄屏处理
只是在左侧保留一条 **56px 固定 rail**，内容列被压到 `viewport - 56px`。

期望：手机端侧栏改为抽屉/浮层，内容列占满宽度；**桌面端零影响**。

## 候选方案（生态现有插件，均为 MIT）

| 候选 | 版本 | 体积 | 断点/生效条件 | 注入目标 | 结论 |
|---|---|---|---|---|---|
| `dsh-mobile-xc` | 0.6.7 | client 98KB + 宿主半 | `max-width:1023px`（对齐 vendor `SIDEBAR_AUTO_COLLAPSE=1024`） | `dsh-client-locale`、`dsh-client-ui-settings`（均存在） | **采用** |
| `@canary-builds/dsh-mobile-ui` | 1.1.2 | client 11KB | `display-mode: standalone` / `hover:none`+`pointer:coarse` | `dsh-client-runtime`（**本机缺失**）、`dsh-client-ui-layout` | 排除：注入目标不存在 |
| `@qingshanyuluo/dsh-client-ui-mobile` | 0.1.3 | CSS 2.7KB | `max-width:1023px` | 无（纯 CSS，需手工 insert 到 patch） | 保留为极简回退 |

## 兼容性实测（关键证据）

两个插件都用**编译期 CSS-module 哈希类名**锚定 DOM，哈希会随 DSH 前端构建漂移：

- `dsh-mobile-xc`：35 个哈希类名中 **30 个命中**当前构建（86%）；
- `@qingshanyuluo/dsh-client-ui-mobile`：11 个中 8 个命中（`pI_x6G_detailsCol`、
  `Md3f7G_scroll`、`bqrRRG_root` 缺失）。

因此**不能只看静态命中率**，必须在运行时验证。验证方式：影子 profile
（`mobspike` / `mobspike2`，从 `web` 模板创建）+ 无头 Chromium 采集布局指标。

### 390×844（手机）实测

| 指标 | 无插件（baseline） | `dsh-mobile-xc` | `@qingshanyuluo/...` |
|---|---|---|---|
| grid-template-columns | `56px 334px 0px` | `390px 0px 0px` | `0px 390px 0px` |
| 内容列宽度 | **334px** | **390px** | **390px** |
| 侧栏定位 | `static`（占位 56px） | `absolute`，画外 `x=-62` | `absolute`，`x=8,y=8,57×56`（浮丸） |
| 注入控件 | — | `button.dsh-xc-ham` 44×44 @ (10,10) | 侧栏浮丸 |
| 横向溢出 | 0 | 0 | 0 |
| 页面错误 | 0 | 0 | 0 |

### 1440×900（桌面）实测

三者一致：`280px 1160px 0px`，均无横向溢出 → **桌面端零影响**。

## 决定

1. **采用 `dsh-mobile-xc` 作为移动端布局主力**：功能最全（抽屉+遮罩+手势、安全区、
   `dvh` 动态视口、轮次刻度、PWA 资源），且自带版本失配软告警与降级。
2. `@qingshanyuluo/dsh-client-ui-mobile` 作为**极简回退**（纯 CSS、2.7KB、
   与 `dsh-better-sidebar`/`dshmarket` 无交互）。
3. `@canary-builds/dsh-mobile-ui` 排除（注入目标 `dsh-client-runtime` 在本机不存在）。
4. 由于两者都靠哈希类名锚定，**自研插件 `mobile-ui-plugin` 的职责**是：
   - 用**哈希无关的锚点**（DOM 结构/`data-*`/ARIA/计算样式）补齐两家都没覆盖的缺口
     （手机端详情面板、轨迹视图、标题栏溢出）；
   - 提供 `tools/check-anchors.mjs`：每次 DSH 升级后校验锚点，漂移即失败，
     避免"静默失效"。

## 复现方式

```bash
# 影子 profile（不影响线上 web profile）
dsh --profile mobspike --from-default-profile web --dump-config
dsh plugin --profile mobspike add dsh-mobile-xc
dsh --profile mobspike --no-open --port 3099

# 采证（截图 + 布局诊断）
node tools/shoot.mjs --url "http://127.0.0.1:3099/?token=..." \
  --out docs/verification/spike-xc --label xc
```

报告落在 `--out` 指定的目录下（截图 + `*-report.json`）。这些是本机留痕，已在
`.gitignore` 里排除，因此仓库中不提供原始文件——按上面的命令重跑即可复现。
