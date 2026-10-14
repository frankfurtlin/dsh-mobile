# ADR-0005：Agent 浏览手机文件的方式——两个受限根 + 用户亲自授权

- 状态：已接受（2026-10-15）
- 相关：ADR-0003（Agent ↔ 手机桥）、ADR-0004（无 root 自动化）

## 背景

用户要求「全盘文件浏览」：Agent 不只写 `Downloads/DSH`，还要能列目录、读文件、把结果写回去，
覆盖手机上的真实文件（文档、下载、截图、项目目录）。

约束：

- 目标路线是**无 root**（ADR-0004 同款前提），所以不能靠 `su` / shell 直接读 `/data`。
- Android 10+ 分区存储（scoped storage）下，普通应用即使有 `READ_EXTERNAL_STORAGE` 也**读不到任意路径**。
- `MANAGE_EXTERNAL_STORAGE`（"所有文件访问权限"）能干这个，但它要用户去深层设置里单独授权、
  上架 Google Play 需要额外申报，而且它对所有目录一次性放权——与本项目"最小权限"的取向冲突。
- 手机上的文件是**用户的**，不是 App 的；授权动作应该由用户在系统 UI 里完成，而不是由 Agent 或 App 猜。

## 决策

Agent 只能看见**两个根（root）**，每个根都来自一条明确的授权路径：

| 根 | 后端 | 谁授权 | 能力 |
| --- | --- | --- | --- |
| `tree:` | SAF 目录树 URI（`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`） | 用户在系统选择器里亲手挑一个文件夹（App 菜单「选择可浏览的文件夹」/ 网页调 `DshMobile.pickFilesRoot()`） | 列目录、读、写、建目录；授权跨重启保留，可随时清除 |
| `downloads:` | MediaStore `Downloads/DSH` | 始终可用（App 自己创建的公共目录） | 列目录、读、写、建目录 |

路径语法统一为 `根:/相对路径`，例如 `tree:/Documents/report.pdf`、`downloads:/notes/todo.md`：

- 必须带 `tree:` 或 `downloads:` 前缀；绝对路径（`/sdcard/...`）、`content://`、`file://` 一律拒绝
  （见 `FileBrowser.parsePath`，JVM 单测覆盖）。
- 相对路径里的 `..` 会被解析掉，若解析后仍试图越出根，则**拒绝**，永远不构造越界路径。
- 根目录本身写作 `tree:/`、`downloads:/`。

Agent 侧工具：`phone_fs_roots`（先问有哪些根）、`phone_fs_list`、`phone_fs_read`、`phone_fs_write`、`phone_fs_mkdir`。
读文本文件返回 `text`（UTF-8），二进制返回 base64；单次读取上限 1 MiB（超出截断并在结果里标注）。

## 为什么不是别的方案

- **`MANAGE_EXTERNAL_STORAGE` 一把梭**：拒绝作为默认。它要用户跳到"所有文件访问"设置页手动开，
  等于把所有目录一次性交出去，且无法在不重装的情况下精细收回。若将来真需要，应作为**单独的可选开关**再引入，
  并保持 `tree:` 语义不变。
- **只给 App 私有目录 / FileProvider**：范围固定、不需要用户参与，但满足不了"浏览手机上的真实文件"。
  因此保留为 `downloads:` 根（MediaStore 公共目录），作为零配置的兜底。
- **让 Agent 传绝对路径 + App 直接 `File` 读写**：在 scoped storage 下会大面积失败（除 App 自己目录），
  而且把"写哪里"的决定权完全交给远端，安全边界消失。
- **把整块内部存储当默认根**：多数 ROM 的选择器允许用户选到存储根，用户可以自己这么做；
  但默认不替用户做这个决定。

## 后果

- Agent 必须先 `phone_fs_roots` 再操作：没有 `tree:` 根时只能写 `downloads:`，错误信息会明确说明。
- 所有读写都在两个根内，越界路径在解析阶段被拒；App 不需要也不申请 `MANAGE_EXTERNAL_STORAGE`。
- SAF 目录树用 `DocumentsContract`（document id、`createDocument`、`findDocumentPath`）实现，
  比 `File` API 啰嗦，但换来了真实可用的读写与持久授权。
- 用户随时可在 App 菜单里查看当前授权的文件夹并「清除授权」。
- 传输走 ADR-0003 的桥：`fs.*` 是普通 op，因此**服务端与 App 必须同步升级**（一致性由
  `tools/check-protocol-parity.mjs` 守住：CAPS、handler、server allowlist、fake phone 四处必须一致）。
