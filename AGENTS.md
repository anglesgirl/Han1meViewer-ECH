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
https://223.5.5.5/resolve     阿里 alidns  IP
https://101.226.4.6/resolve   360         IP
https://120.53.53.53/resolve  腾讯 DNSPod IP
https://223.6.6.6/resolve     阿里备用    IP
```
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