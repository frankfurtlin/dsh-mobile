# ADR-0004：无 root 无障碍自动操作的执行器选型

- 状态：已接受（2026-10-14）
- 相关：ADR-0002（原生 WebView 壳）、ADR-0003（Agent ↔ 手机桥）

## 背景

需求是"不 root 的前提下，让 Agent（对话）能自动操作手机上的 App"，并且要有人机双通道：人能自己点，Agent 也能点。
候选执行路径：

| 方案 | 需要什么 | 问题 |
|---|---|---|
| Root / Magisk | 解锁 + 刷机 | 用户明确排除；丢保修、部分机型不可行 |
| Shizuku（借 adb shell 身份） | 每次重启后重新用电脑/无线调试授权 | 每次开机都要一次外部授权，移动端场景不可接受 |
| `AccessibilityService` | 用户在系统设置里手动启用一次 | OEM ROM 可能杀后台；受 `FLAG_SECURE` 限制 |
| UIAutomator / instrumentation | 测试包签名或 `adb` | 无法在普通用户设备上长期驻留 |
| MediaProjection 截图 | 每次会话都要弹投屏授权 | 只解决截图，不解手势；前台服务类型要求更重 |

## 决定

1. **执行器 = `AccessibilityService`（`A11yService`）**，且**所有操作在设备端完成**：Agent 只发语义指令（tap 坐标、swipe、global action、读树、截图），坐标与手势不在服务端计算。
2. **截图**用 `AccessibilityService.takeScreenshot`（API 30+），`HardwareBuffer → Bitmap.wrapHardwareBuffer` 后按 `max_bytes` 逐级降质/降分辨率编码 JPEG，默认预算 600 KB（服务端单条消息上限 1 MiB）。
3. **手势**用 `dispatchGesture(StrokeDescription)`；**系统键**用 `performGlobalAction`（back/home/recents/notifications/quick_settings/lock）。
4. **控件树**用 `getRootInActiveWindow()` 广度优先截断（默认 400，上限 2000），每个节点带 `bounds` 与 `center`，强制"先 `phone_ui_tree` 再 `phone_tap`"，不做坐标记忆。
5. **应用列表/启动**用 `PackageManager.queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER)` + `getLaunchIntentForPackage`，并在 manifest 用 `<queries>` 声明可见性（Android 11+ 包可见性限制）。
6. **三重开关才算授权**：App 内开关（`Settings.a11yEnabled()`，默认 `false`）→ 系统无障碍已启用（`A11yService.ready()`）→ 设备桥在线。任一不满足即返回明确错误，不静默失败。

## 理由

- 唯一"零外部依赖 + 一次授权长期有效"的方案；用户不需要电脑、不需要 root。
- 设备端执行让服务端保持无状态，桥的鉴权模型（ADR-0003：网关 cookie 核验）不用为自动化再开一条权限通道。
- 默认关闭 + 三级门禁，保证"Agent 能操作手机"不会变成默认敞开的远程控制。

## 后果与限制（已知，需在真机验收中确认）

- `FLAG_SECURE` 窗口（银行/支付类）截不到内容，属预期；工具会返回失败而不是黑图。
- 部分 OEM ROM 会杀掉无障碍服务；App 内引导页 + 前台服务通知用于恢复。
- 目前**不做文字注入**（`ACTION_SET_TEXT`）与文件系统遍历：前者受输入法差异影响，后者要走 SAF（需单独授权），留待后续按需扩展。
- 坐标依赖屏幕旋转与分辨率：Agent 侧以 `bounds`/`center` 为准，App 侧不缓存跨会话坐标。
