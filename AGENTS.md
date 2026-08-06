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

## 2. 种子/DoH（铁律：种子只用 IP-DoH，只做 TXT 获取，绝不参与主站解析）

**种子的唯一职责**：从 `ech-config.anglesgirl.eu.org` 的 **TXT 记录** 拉取配置（`doh=`/`doh2=`/`doh3=`/`ip=`），把配置交给 ECH 代理后任务结束。

### 2.1 种子只用 IP 直连 DoH（全部 `https://<IP>/resolve`，禁止域名）
```
https://101.226.4.6/resolve   360         IP（首选）
https://120.53.53.53/resolve  腾讯 DNSPod IP
https://1.12.12.12/resolve    腾讯备用    IP
```
**2026-08-06：移除 alidns（223.5.5.5/223.6.6.6）**——国内频繁超时/抖动，疑似与 429 限流相关。
**禁止 `doh.pub`/`dns.alidns.com`/`cloudflare-dns.com` 等域名做种子**（域名解析可被劫持 → 伪造 TXT）。IP 直连跳过解析环节，防劫持。

### 2.2 种子只做 TXT 获取，不解析主站
- 种子的 IP-DoH **绝不用于解析主站/CDN/IP**。
- **主站解析一律用 TXT 下发的 DoH**（cloudflare-gateway 链）。回头用 alidns/种子解析主站 = 立即污染。

### 2.3 启动顺序（不可"先启动再补丁"）
```
1. 启动 APP → 2. 等待 ECH 启动窗口 → 3. ECH 从种子(纯 IP-DoH)取 TXT 配置
→ 4. 缓存配置(优选 IP)供下次冷启动 → 5. 用 TXT 下发的 DoH 启动/接受连接
```
**严禁**先拿 alidns/默认 DoH 启动再热更新——首请求发生在换掉之前 = 首请求走污染源。

### 2.4 兜底（fail-closed，绝不死回污染源）
- 种子成功 → 用 TXT 配置启动。
- 种子失败 → 用上次缓存的优选 IP/DoH（=上次成功的 TXT 结果）启动。
- 种子失败且无缓存 → **断网（不启动 ECH）**，提示重启 App。
- **严禁退回 `dns.alidns.com`/`doh.pub` 域名 DoH 兜底**。

### 2.5 技术要点
- DoH 查询必须显式 `Proxy.NO_PROXY`（防止 ECH 开启时递归走本机代理）。
- alidns/360/腾讯 IP 的 JSON 端点是 `/resolve`；`/dns-query` 仅 RFC 8484 二进制 POST。
- TXT 的 `doh=` 通常为 cloudflare-gateway（大陆可能被墙）。
- 目标域名本身污染（alidns 曾给 hanime1.me 返回 Facebook 段假 IP）靠 TXT 下发的 DoH 解析规避。

---

## 3. 依赖清单（版本与崩溃陷阱）

| 依赖 | 规则 |
|---|---|
| firebase-perf | **不在 CI 占位 API key 时引入**。移除 `implementation(libs.firebase.perf)` 和 `alias(com.google.firebase.firebase.pref)` 插件。 |
| echproxy AAR | 由 CI 从 `anglesgirl/ech-proxy-go` gomobile bind 生成，放 `app/libs/echproxy.aar`；本地缺文件时 `if (exists)` 兜底。 |
| appIcon | debug 用 `@mipmap/ic_launcher_debug`，release 用 `@mipmap/ic_launcher_new`（build.gradle.kts manifestPlaceholders）。 |

### 3.1 【致命】OkHttp 版本必须 4.12.0，禁止升 5.x

- **`gradle/libs.versions.toml` 的 `okhttp = "4.12.0"` 不许改**。项目里 Retrofit 3.0.0 和 PostHog SDK（posthog-android 3.58.0，内部 core 6.29.0）都依赖 OkHttp **4.x**。
- **2026-08-06 实测教训**：某次把 okhttp 升到 **5.3.2** → PostHog SDK（按 4.x API 编译）运行时调用 5.x 已删除的内部 API（`okhttp3.internal.Util` 等）→ 抛 **`NoSuchMethodError`（Error 不是 Exception）**：
  - `PostHogManager.track()` 里 `catch (Exception)` 抓不住 → **debug/release 都崩**（与 R8 混淆无关，当时误判成混淆问题浪费了多轮）；
  - HCrashHandler 里 PostHog 上报在写崩溃日志**之前** → Error 把崩溃处理链打断 → **崩溃日志永远写不出来**（用户报告"没有日志"就是它）。
- **降级副作用**：OkHttp 4.x 的 `Response.body` 是 **nullable**（5.x 非空），凡 `response.body.xxx` 处要 `?.` 或判空（SpeedLimitInterceptor / NetworkSettingsRoute / HanimeDownloadWorker 都改过）。

### 3.2 PostHog 统计（正确接入方式，2026-08-06 定稿）

- **用 SDK**（`posthog-android:3.58.0`），不要自研 HTTP 直连（CO3 早期直连版产生大量无效数据）。CO3 新版也用 SDK。
- **初始化放后台线程**（`PostHogManager.init` 里 `Thread{...}.start()`），setup 内部有磁盘 IO/反射，主线程调用会卡启动。
- **`track()` 必须 `catch (Throwable)` 而非 `catch (Exception)`**——SDK 在依赖冲突时抛 Error，Exception 抓不住。
- `PostHogAndroid.setup(context, config)` + `PostHog.capture(event, distinctId=null, properties)`；config 里 `captureScreenViews/captureApplicationLifecycleEvents/captureDeepLinks = false`（只手动埋点）。
- key 与 CO3 共用同一 PostHog 项目（免费版仅 1 项目），事件属性带 `app: "han1meviewer"` 区分（CO3=`"co3"`）。

### 3.3 HCrashHandler 顺序铁律

崩溃处理里 **先写崩溃日志文件 → 再 PostHog 上报**（PostHog 上报用 `runCatching` 包住）。顺序反了 = 统计 SDK 出错时崩溃日志写不出，等于没有崩溃证据。

---

## 4. 崩溃安全（Android 侧）

- `HanimeApplication.appContext` 用于崩溃时写日志。
- `HCrashHandler` 崩溃时先把 `DiagnosticsLog.writeCrashReportToDownloads()`（App 起不来也能从文件管理器拿到 `Han1meViewer-crash-*.txt`），再 `ActivityManager.restart`。
- 用户反映"**应用/首页无限重复重启**" = 进程级崩溃循环，优先查：gomobile panic、firebase-perf 占位 key。
- **"播放就没了界面 + 无崩溃日志"** ≠ native 崩溃，先查统计 SDK 抛 Error（见 3.1）——Error 会绕过 catch(Exception) 并打断崩溃处理链。

### 4.1 播放器网络栈（ExoPlayer 不走 ECH 代理的坑）

- `HMediaKernel.kt` 里 ExoPlayer 用 **`DefaultHttpDataSource.Factory()`**（Media3 自带 HTTP 栈），**不经过 OkHttp 的 EchInterceptor** → 视频流（m3u8/ts）**直连**视频 CDN。
- javchu.com 的视频源是独立 CDN 域名（如 `t33.cdn2020.com`），直连国内可达但**某些视频文件返回 404**（视频被删/失效，不是 App 问题）。
- 别用 `OkHttpDataSource` 换掉默认栈——经实测那会引入其它兼容问题，且视频 404 是源站问题，App 侧只要**给出明确提示**即可（见 4.2）。
- MPV 内核有 `http-proxy` 指向 ECH 代理；系统 MediaPlayer 直连。

### 4.2 播放失败提示（2026-08-06 实现）

- **ExoPlayer** `onPlayerError`：递归 cause 链找 `HttpDataSource.InvalidResponseCodeException`：
  - `404/410/403` → Toast **"视频不存在或已被删除（HTTP 404）"**（源站问题，不是 App 问题）；
  - 其他 → Toast **"视频加载失败，请检查网络后重试"**。
- **MPV**：加载失败时也会触发 `MPV_EVENT_END_FILE`，会被误当"正常播放结束"（无提示）。用 `mpvFileLoaded` 标志（FILE_LOADED 置 true，START_FILE 清零）区分：END_FILE 且从未加载成功 → 按 404 类失败提示。
- 排查顺序：先确认是**视频 404（源站）**还是**播放器/网络（App 侧）**，再决定改哪里。

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