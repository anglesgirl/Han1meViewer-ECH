# AGENTS.md — Han1meViewer-ECH 操作约束（给 AI 的提示）

> 防止 AI/人类在改这个 App 的 ECH 集成时反复犯错的硬性约束。**改任何 ECH 相关代码、CI、依赖、或排查网络问题前先读完。**
> 完整代理职责在 `ech-proxy-go` 仓库（其 AGENTS.md 必读），本文件专注 Android 侧集成。

---

## 0. 为什么连续失败（历史教训汇总）

之前多轮"ECH 不工作"的根因，全部是以下某一条被违反：

1. 用 **CONNECT 隧道** 接入 OkHttp（`HProxySelector` 返回代理地址）→ 双重 TLS → `Unable to parse TLS packet header`。
2. ECH 启动太慢，首请求在代理就绪前**直连真实 IP** → 被墙。
3. **单一固定 alidns** 解析目标 → 返回被污染 IP（Facebook 段）→ 假 `no ECHConfig / plain TLS`。
4. gomobile panic 未 recover → **进程 abort → App 无限重启**。
5. CI 占位 firebase `google-services.json` API key 非法 → Firebase Performance 初始化抛异常 → 无限重启。

**照抄下面规则，就能避免重蹈覆辙。**

---

## 1. 接入模型（最重要）

### 1.1 OkHttp 必须走「应用层 `X-Ech-Target`」，绝不走 CONNECT

- `EchInterceptor` 把 `https://host/path` → `http://127.0.0.1:<port>/path` + `X-Ech-Target: host`。
- `ServiceCreator.buildHClient()` / `buildGetchuClient()` 都挂 `EchInterceptor`。
- **`HProxySelector.select()` 在 ECH 开启时（`EchProxyManager.port > 0`）必须返回 `Proxy.NO_PROXY`** —— 让改写后的请求直连本机代理。**严禁返回 `127.0.0.1:port` 代理**（那会进 CONNECT 隧道，SNI 无法隐藏）。
- WebView 站点资源通过 `EchWebViewClient.intercept()`（`shouldInterceptRequest`）走 ECH。

### 1.2 首请求必须等 ECH 就绪

`EchInterceptor.intercept()` 开头：`port <= 0` 时**阻塞等待最多 10 秒**直到代理就绪，再走代理；不要放行直连（直连被墙）。`EchProxyManager.startAsync` 延迟 ≤ 500ms。

---

## 2. 种子/DoH（DNS 解析策略）

**按约定：种子（bootstrap）阶段只查 `ech-config.anglesgirl.eu.org` 的 TXT 记录，用 TXT 返回的 `doh`/`doh2`/`doh3`/`ip` 启动/热更新 ECH 代理。**

- 不要写死单一 alidns 解析目标站点。
- TXT 的 `doh=` 是 cloudflare-gateway（大陆可能被墙），但作为下发端点是约定。
- alidns JSON 端点 = `/resolve`；`/dns-query` 仅 RFC 8484 二进制 POST。
- DoH 查询用 `Proxy.NO_PROXY` 直连（避免 ECH 开启时递归走本机代理）。

---

## 3. 依赖清单（版本与崩溃陷阱）

| 依赖 | 规则 |
|---|---|
| firebase-perf | **不在 CI 占位 API key 时引入**。移除 `implementation(libs.firebase.perf)` 和 `alias(com.google.firebase.firebase.pref)` 插件。 |
| echproxy AAR | 由 CI 从 `anglesgirl/ech-proxy-go` gomobile bind 生成，放 `app/libs/echproxy.aar`；本地缺文件时 `if (exists)` 兜底。 |
| appIcon | debug 用 `@mipmap/ic_launcher_debug`，release 用 `@mipmap/ic_launcher_new`（build.gradle.kts manifestPlaceholders）。 |

---

## 4. 崩溃安全（Android 侧）

- `HanimeApplication.appContext` 用于崩溃时写日志。
- `HCrashHandler` 崩溃时先把 `DiagnosticsLog.writeCrashReportToDownloads()`（App 起不来也能从文件管理器拿到 `Han1meViewer-crash-*.txt`），再 `ActivityManager.restart`。
- 用户反映"**应用/首页无限重复重启**" = 进程级崩溃循环，优先查：gomobile panic、firebase-perf 占位 key。

---

## 5. CI / 构建事实

- 仓库：`anglesgirl/Han1meViewer-ECH`，CI workflow：`CI`。
- **arm64-only**：`app/build.gradle.kts` 的 `splits.abi` 已 `include("arm64-v8a")`，APK 约 74MB，含 `lib/arm64-v8a/libgojni.so`、`libmpv.so`。
- 占位 `google-services.json` API key 必须合法格式：`AIza` + 35 个 `[0-9A-Za-z_-]`（否则 FirebaseInstallations 抛异常）。
- 本地 Go 工具链：`export PATH=/tmp/go-toolchain/go/bin:$PATH`。

---

## 6. 排查网络问题 checklist

| 症状 | 看哪 |
|---|---|
| `Unable to parse TLS packet header` | CONNECT 返回了已握手 TLS（违反 1.1） |
| `no ECHConfig ... plain TLS` 但该站点其实支持 ECH | alidns 解析污染（违反 2），核对 IP 是否 Facebook 段 |
| 直连 IP 超时/RST | 拦截器未生效 / select 返回代理 / 站点本身不支持 ECH 且被墙 |
| 无限重启 | gomobile panic / firebase-perf 占位 key（违反 3、4） |
| Go 日志 `ech=true` 但 WebView/OkHttp 失败 | 接入模型用错（违反 1.1） |

---

## 7. 诊断入口

- 设置 → 网络设置 → 导出诊断日志（或桌面「诊断日志」图标）。
- 内容：事件日志 + ECH 代理 Go 侧日志（DNS 源 / ECH accept-reject / 降级 / 路由 / 上游错误）。
- 崩溃时自动：`Han1meViewer-crash-*.txt` 写 Downloads。