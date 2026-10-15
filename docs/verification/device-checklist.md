# 真机验收清单（Android）

在手机上逐项执行，把"实测"栏填上结果与现象。带 ⚠️ 的项是当前代码里最容易受 ROM 差异影响的点。
准备：手机与服务器能互相访问（同一网络或公网域名）、服务器已按 [DEPLOY.md](../DEPLOY.md) 装配好、发布包已到手
（远程下载见 P0，或 `adb install -r packages/mobile-shell/android/app/build/outputs/apk/release/app-release.apk`）。

```
设备型号：____________   Android 版本：__________   ROM：____________
App 版本：1.0.2（versionCode 3）       验收日期：__________
```

## P0 把安装包送到手机

| # | 步骤 | 期望 | 实测 |
|---|---|---|---|
| 0.1 | 手机浏览器先登录 `https://dsh.example.com`，再打开 `https://dsh.example.com/bridge/download/DSH-Mobile-1.0.7.apk` | 直接下载 3.48 MiB 的 APK；未登录/登出状态下同一链接返回 401 | |
| 0.2 | 点开下载好的 APK 安装 | 系统允许安装（必要时开"允许安装未知应用"）；装完桌面出现 "DSH Mobile" | |

## P2 endpoint 登录与凭证（核心）

| # | 步骤 | 期望 | 实测 |
|---|---|---|---|
| 2.1 | 首次启动 App | 进入"服务器设置"页，只要求填 endpoint | |
| 2.2 | 填 `https://dsh.example.com` → 测试连接 | 提示连接正常（探到 `dsh-passwords` 健康体） | |
| 2.3 | 填一个错误地址（如 `http://127.0.0.1:9`）→ 测试连接 | 明确报错，不静默失败 | |
| 2.4 | 正确地址 → 保存并继续 | 自动跳到 dsh-passwords 登录页 | |
| 2.5 | 用账号密码登录 | 302 回到 DSH Web UI，正常出会话界面 | |
| 2.6 | 完全杀掉 App → 重新打开 | **免登录**直接进 Web UI（凭证回填） | |
| 2.7 | 菜单 → 退出登录 → 重新打开 | 回到登录页；服务端 token 已吊销 | |
| 2.8 | 菜单 → 更换服务器 → 换另一个 endpoint | 正常登录；旧 endpoint 的凭证互不干扰 | |
| 2.9 | 服务器上 `curl -s http://127.0.0.1:3081/gateway/healthz` 后重启 dsh web，再在 App 里操作 | 401/403 时自动回登录页，重新登录后继续可用 | |

## P3 原生体验

| # | 步骤 | 期望 | 实测 |
|---|---|---|---|
| 3.1 | 打开任意长对话 | 顶部状态栏/底部输入框不被系统栏遮挡（edge-to-edge inset 生效） | |
| 3.2 | 按系统返回键 | 先退回 Web 历史，历史到底后再退出 App（不直接闪退） | |
| 3.3 | 点输入框 | 键盘弹出时输入框可见、不被键盘盖住 | |
| 3.4 | 会话里触发一次文件下载 | 走系统下载（通知栏可见），文件在"下载"里 | |
| 3.5 | 回复里的外链 | 用外部浏览器/对应 App 打开 | |
| 3.6 | 附件 → 选择文件 | 系统文件选择器可选手机文件并上传成功 | |
| 3.7 | 附件 → 拍照 | 系统相机拍照后作为附件上传（缩略图正常） | |
| 3.8 | 在别的 App 里"分享"一段文字/一个文件到 DSH Mobile | 进入 DSH Mobile，菜单出现"分享收件箱（n）"，内容可预览 | |
| 3.9 | 菜单 → 分享收件箱 | 能看到分享进来的文本/文件名，不被拒 | |
| 3.10 | 复制 Web 里的内容 → 让 Agent 调 `phone_clipboard_get` | 返回刚复制的文本 | |
| 3.11 | 让 Agent 调 `phone_clipboard_set` | 手机剪贴板变成设置的内容 | |
| 3.12 | 菜单 → 保持屏幕常亮（勾选）→ 等 5 分钟 | 屏幕不自动熄灭；取消勾选后恢复系统行为 | |
| 3.13 | 让 Agent 调 `phone_notify` | 通知栏出现通知（需已授权通知权限） | |

## P4 手机桥（Agent ↔ 手机）

前置：服务器 bridge 已起（`systemctl status dsh-mobile-bridge`）、profile 已接入 `mcp-phone`、dsh web 已重启。

| # | 步骤 | 期望 | 实测 |
|---|---|---|---|
| 4.1 | 打开 App 并保持前台 | `curl -s http://127.0.0.1:3082/healthz` → `"phones":1` | |
| 4.2 | 在 DSH 对话里让 Agent 调 `phone_list` | 列出你的手机（型号、能力列表） | |
| 4.3 | 让 Agent 调 `phone_info` | 返回设备信息 | |
| 4.4 | 让 Agent 调 `phone_ping` | 返回 `rtt_ms`（十几到几十毫秒） | |
| 4.5 | 让 Agent 保存一个文件到手机（`phone_files_save`） | 文件出现在 `Download/DSH/`，可打开 | |
| 4.5b | 让 Agent 用上一步返回的 uri 调 `phone_files_open` | 手机上用对应 App 打开该文件；传不存在的名字时返回明确错误 | |
| 4.6 | 让 Agent 调 `phone_shares` 读取最近分享 | 返回步骤 3.8 分享过的内容 | |
| 4.7 | ⚠️ 关掉 App（从最近任务划掉）后再调 `phone_list` | 手机离线（前台服务随进程结束；这是预期行为） | |
| 4.8 | 断网 30 秒再恢复 | 自动重连（≤60s 内 `phone_list` 恢复在线），通知栏状态同步 | |
| 4.9 | 让 Agent 调 `phone_open` 打开一个网址 | 手机跳出浏览器/对应 App | |
| 4.10 | 伪造测试：`curl -s -H 'Cookie: dsh_gateway_token=bogus' -H 'Origin: https://dsh.example.com' https://dsh.example.com/bridge/healthz` | 仍返回健康体（healthz 无需鉴权）；用 WebSocket 连 `/bridge/ws` 且 cookie 伪造时被拒（401） | |

## P5 无 root 无障碍自动化

前置：App 菜单 → 无障碍自动操作 → 打开"允许 Agent 使用"，并在系统设置里启用"DSH 自动操作"。

| # | 步骤 | 期望 | 实测 |
|---|---|---|---|
| 5.1 | 未开系统服务时让 Agent 调 `phone_ui_tree` | 明确报错（not enabled / switched off），不静默返回空 | |
| 5.2 | 两处都开启后调 `phone_ui_tree` | 返回当前屏幕控件（text/class/bounds/center） | |
| 5.3 | 调 `phone_screenshot` | 返回截图（宽度/高度与屏幕一致，JPEG ≤ 600 KB） | |
| 5.4 | 让 Agent 按 `phone_ui_tree` 里的 center 调 `phone_tap` | 屏幕上对应位置被点击 | |
| 5.5 | 调 `phone_swipe` 上滑 | 列表/页面滚动 | |
| 5.6 | 调 `phone_global`（back/home/recents/notifications） | 对应系统动作发生 | |
| 5.7 | 调 `phone_apps` | 返回可启动的应用列表（含中文应用名） | |
| 5.8 | 调 `phone_launch`（从列表里挑一个包名） | 该应用被打开 | |
| 5.9 | ⚠️ 让 Agent 打开一个带 `FLAG_SECURE` 的界面（如银行 App）再截图 | 返回失败/空，而不是黑图；`phone_ui_tree` 同样受限 | |
| 5.10 | 综合任务：让 Agent"打开某 App 并点击某按钮" | 全流程在对话里完成，不需要人手 | |
| 5.11 | 关掉 App 内"允许 Agent 使用"开关后再调 | 立刻拒绝（明确错误） | |

## P7 文件浏览（用户授权文件夹）

前置：App 已升级到 `1.0.2`（含 `fs.*` op）；bridge 已用新代码重启（`systemctl restart dsh-mobile-bridge`）。

| # | 步骤 | 期望 | 实测 |
|---|---|---|---|
| 7.1 | App 菜单 →「选择可浏览的文件夹」 | 系统文件夹选择器出现；选「文档」后提示已授权，再打开菜单显示当前文件夹名 | |
| 7.2 | 让 Agent 调 `phone_fs_roots` | 返回两个根：`tree:`（label 为刚才选的文件夹）与 `downloads:` | |
| 7.3 | 让 Agent 调 `phone_fs_list {path:"tree:/"}` | 条目与手机文件管理器里该文件夹一致（含目录/文件、大小、时间） | |
| 7.4 | 新建一个 txt 放进该文件夹，让 Agent 调 `phone_fs_read {path:"tree:/xxx.txt"}` | 返回 `encoding:"utf8"` 与正确内容 | |
| 7.5 | 让 Agent 调 `phone_fs_write {path:"tree:/notes/hello.md", data:"# hi"}` | 文件出现在文件管理器里，内容正确，父目录被自动创建 | |
| 7.6 | 让 Agent 调 `phone_fs_mkdir {path:"tree:/notes/2026"}` | 目录出现 | |
| 7.7 | ⚠️ 越界测试：`phone_fs_read {path:"tree:/../etc/passwd"}` | 明确失败（越界被拒），**不**返回任何文件内容 | |
| 7.8 | ⚠️ 其他寻址方式：`phone_fs_list {path:"/sdcard"}`、`content://...`、`file://...` | 均明确失败（只接受 `tree:` / `downloads:` 前缀） | |
| 7.9 | 杀掉 App 再打开 → 调 `phone_fs_roots`；然后菜单「清除授权」→ 再调一次 | 重启后 `tree:` 仍在（持久授权）；清除后 `tree:` 消失 | |
| 7.10 | 不做任何授权，直接 `phone_fs_write {path:"downloads:/hello.txt", data:"hi"}` | 成功（`Downloads/DSH` 根始终可用） | |

## 移动端 Web 样式（P1 回归）

| # | 步骤 | 期望 | 实测 |
|---|---|---|---|
| 1.1 | 手机浏览器打开 `https://dsh.example.com`（横屏/竖屏各看一次） | 内容列占满宽度、无横向滚动条、汉堡菜单可开合侧栏 | |
| 1.2 | 桌面浏览器 1440×900 打开同一地址 | 布局与装插件前完全一致（三栏、280px 侧栏） | |
| 1.3 | 服务器上 `node tools/check-anchors.mjs` | 所有插件命中率 ≥ 70%（当前 xc 85%） | |

## 汇总

```
P0 安装：____/2                 P2 endpoint 登录：____/9
P3 原生体验：____/13            P4 手机桥：____/10
P5 无障碍：____/11              P7 文件浏览：____/10
Web 样式回归：____/3            合计：____/58
阻断项（必须修复才能交付）：______________________________________
后续项（可改进，不阻断）：________________________________________
```

**已知非阻断项**：Android 13+ 侧载安装时，系统设置里的无障碍开关可能被"受限设置"挡住，需在系统设置右上角
允许受限设置（App 内引导文案已提示）；部分 ROM 会在 App 被划掉后杀掉前台服务（4.7 预期行为）。
