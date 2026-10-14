# ADR-0002：App 外壳用原生 WebView，不加载 Capacitor 的远端页面

- 日期：2026-10-14
- 状态：已实施（P2）

## 背景

项目脚手架由 `npx cap init` + `cap add android` 生成（Capacitor 8.5.3），MainActivity 默认
`extends BridgeActivity`。但 App 要加载的不是打包进 APK 的 `www/`，而是**用户服务器上的 DSH SPA**。

## 决策

`MainActivity` 改为 `extends AppCompatActivity`，自带一个 `WebView` 直连远端；Capacitor 的
`BridgeActivity` 不再使用，`@capacitor/*` 依赖暂留（后续清理）。

## 理由（实测/推理，不是偏好）

1. **Capacitor 的插件 JS 注入只在本地资源域生效。** `server.url` 指向远端时，`capacitor.js`
   与插件 JS 不会注入到服务端返回的页面里 —— 服务端页面的 HTML 由 DSH 产出，我们无法往里插
   `<script src="capacitor.js">`。所以即使走了 Capacitor，也拿不到 `window.Capacitor`，
   `Filesystem` / `Share` / `App` 这些现成插件在真机上全部不可用。
2. **需要的原生能力本来就要自己写。** 文件选择（`WebChromeClient.onShowFileChooser`）、
   下载（`DownloadListener` + `DownloadManager`）、外部 App 打开（`ACTION_VIEW`）、后续的无障碍
   操作，都是几行原生代码，不需要 Capacitor 插件层。
3. **少一层就少一层不确定性。** 登录流程对 cookie 的时机很敏感（见下文），直接控制
   `WebViewClient` 比通过 Capacitor 的 server 配置间接控制更可预测。

代价：自己维护 JS bridge（先用 `addJavascriptInterface` 暴露 `DshMobile`，后续按需迁移到
`androidx.webkit` 的 `WebMessageListener`）；iOS 端后面要另写一套壳。

## 认证流程的落地细节（易踩坑，记下来）

实测 `http://127.0.0.1:3081`（本机 dsh-passwords 网关）：

| 请求 | 结果 |
|---|---|
| `GET /gateway/healthz` | 200 `{"ok":true,"service":"dsh-passwords"}` → 探活就认 `dsh-passwords` 字样 |
| `GET /` 无会话 | 302 → `Location: /gateway/login`（**不带 next**） |
| `GET /gateway/login` | 200 表单：`method=POST action=/gateway/login`，隐藏域 `csrf`、`next`（默认 `value="/"`） |
| CSRF cookie | `dsh_csrf`，`Path=/gateway`，HttpOnly |
| `POST /gateway/logout` | 只接受 POST；校验 `Origin` 主机（同源），**不校验 CSRF**；服务端 `revokeToken` 后 302 → `/gateway/login` |

对应实现：

- 启动先 `CookieManager.setCookie(base, "dsh_gateway_token=…; Path=/")` 再 `loadUrl(base + "/")`，
  这是"冷启动免登录"的关键（服务端 `Set-Cookie` 带 `Max-Age=43200`，即 12h）。
- 保存 token 时**必须读两个路径的 cookie**：token 在 `Path=/`，CSRF 在 `Path=/gateway`，
  `CookieManager.getCookie(url)` 按路径匹配，只问 `/` 拿不到 CSRF（见 `MainActivity.cookieJar()`）。
- 主框架收到 401/403 → 清本地 token + 让 cookie 过期 → 跳登录页。
- 登出：`POST /gateway/logout` 带上 cookie 与 `Origin: <base>`（否则 403），失败也无妨，本地已清。
- 明文 http / 自签证书需用户显式勾选才放行（`onReceivedSslError` + `allowInsecure`），
  Manifest 里 `usesCleartextTraffic="true"` 只为局域网调试留门。

## 备选方案

- 让 Capacitor 加载本地 `www/index.html`，页面里再跳转到远端：跳转后同样丢失插件注入，且多一次
  导航，登录 cookie 时机更难控。
- 用 iframe 套远端：跨源，cookie 与 JS 都不可控。均否决。
