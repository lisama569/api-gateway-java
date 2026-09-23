# 本地 API 网关（Java / Spring Boot）

为调用方提供**版本化统一入口**的本地 API 网关，聚焦三件事：

1. 多版本接口并存与渐进式下线；
2. 按调用方身份的限流与配额（并发安全）；
3. 上游调用的失败隔离、安全重试、熔断降级与自动恢复。

不依赖任何外部服务或凭据，所有上游均由内存模拟器承担，`mvn test` 即可完成全部验证。

---

## 1. 统一入口

```
/api/gateway/{resource}/{version}            # GET/POST/PUT/DELETE/PATCH
/api/gateway/{resource}/{version}/batch      # POST，批量子调用（演示部分失败）
```

- 请求头 `X-Caller-Id`：调用方身份。
- 请求头 `X-Batch: true`：走批量路径；请求体为子请求条数（默认 3）。

统一响应信封：

```json
{
  "success": true,
  "httpStatus": 200,
  "message": "调用成功",
  "data": { "...": "..." },
  "deprecated": false,
  "degraded": false
}
```

失败时 `success=false` 并携带**可区分**的 `errorCode` 与原因说明 `message`；
废弃版本成功时 `deprecated=true` 并携带 `deprecationNotice`；
熔断降级时 `degraded=true`。

---

## 2. 版本兼容与下线规则

每个资源版本处于三种状态之一：`ACTIVE -> DEPRECATED -> REMOVED`。

| 状态 | 行为 | HTTP |
|---|---|---|
| `ACTIVE` | 正常服务 | 200 |
| `DEPRECATED` | **仍正常返回**，响应中 `deprecated=true` 且带可识别的迁移提示 | 200 |
| `REMOVED` | 明确拒绝，消息说明移除时间与替代版本 | **410** `VERSION_REMOVED` |
| 从未登记 | 明确拒绝（与已移除严格区分） | **404** `VERSION_UNKNOWN` |

关键点：「未知版本」与「已移除版本」是两种不同的拒绝原因（404 vs 410），
调用方可以据此判断是拼写错误还是需要主动迁移。

预置版本（见 `InMemoryVersionRegistry`）：

| 资源 | 版本 | 状态 |
|---|---|---|
| orders | v1, v2 | ACTIVE（多版本并存） |
| orders | v0 | DEPRECATED |
| orders | v-beta | REMOVED |
| users | v1 | ACTIVE；v0 DEPRECATED |
| payments | v1 | ACTIVE |

---

## 3. 调用方身份、限流与配额模型

请求依次经过三段短路判定，三类拒绝互不混淆：

| 顺序 | 判定 | 失败错误码 | HTTP | 含义 |
|---|---|---|---|---|
| 1 | 身份认证 | `IDENTITY_INVALID` | **401** | 缺少/空白/未登记的 `X-Caller-Id` |
| 2 | 资源授权 | `IDENTITY_FORBIDDEN` | **403** | 身份有效，但无权访问该资源 |
| 3 | 配额判定 | `QUOTA_EXCEEDED` | **429** | 授权有效，但当前窗口配额已用尽 |

- 配额模型：**固定时间窗计数器**（默认 60s，可配置），每个调用方独立窗口，
  上限来自调用方档案 `CallerProfile.quotaLimit`。
- 并发安全：窗口翻转与「检查 + 自增」在同一把 per-caller 锁内原子完成，
  因此并发下**通过数绝不超过配额（不突破），每个放行请求都计入（不漏计）**。
  由 `QuotaConcurrencyTest`（200 线程 / 双调用方各 50 配额）验证。
- 可解释：每次判定返回 `used / limit / reason`，日志打印 `[限流判定]`。

预置调用方（`InMemoryCallerDirectory`）：

| 调用方 | 可访问资源 | 每窗口配额 |
|---|---|---|
| caller-a | 全部（`*`） | 20 |
| caller-b | orders, users | 5 |
| caller-c | payments | 3 |

---

## 4. 上游错误分类、重试与熔断

### 4.1 错误分类（归一化、可区分、状态一致）

| errorCode | HTTP | 触发条件 |
|---|---|---|
| `UPSTREAM_TIMEOUT` | **504** | 上游在 `gateway.upstream.timeout-millis`（默认 300ms）内未返回 |
| `UPSTREAM_ERROR` | **502** | 上游确定性 5xx / 执行异常 |
| `UPSTREAM_PARTIAL_FAILURE` | **207** | 批量调用中部分子请求失败，成功项结果保留，失败项逐条隔离占位 |
| `UPSTREAM_UNAVAILABLE` | **503** | 熔断器打开，请求被快速失败/降级，**未触达上游** |

### 4.2 安全重试（不产生重复业务副作用）

- 仅对**幂等请求**（GET/PUT/HEAD 等）重试，默认最多 2 次尝试；
- **非幂等请求（POST/PATCH/DELETE）只尝试一次**——即使中途超时也不会重复写；
- 熔断打开期间直接快速失败，`attemptCount=0`，同样不会触达上游。

### 4.3 熔断与自动恢复（按上游 key 隔离）

每个 `resource:version` 对应一个独立熔断器：

```
CLOSED --连续失败达到阈值(默认3)--> OPEN
OPEN   --等待 open-duration(默认5s)--> HALF_OPEN（只放行 1 个探测请求）
HALF_OPEN --探测成功--> CLOSED（自动恢复正常路径）
HALF_OPEN --探测失败--> OPEN（重新计时）
```

单条上游故障不会影响其他资源（故障隔离）；恢复后无需人工介入自动回到正常路径。

---

## 5. 配置项（application.properties，均有默认值）

| 键 | 默认 | 说明 |
|---|---|---|
| `gateway.quota.window-millis` | 60000 | 配额固定窗口长度 |
| `gateway.upstream.max-attempts` | 2 | 幂等请求最大尝试次数 |
| `gateway.upstream.timeout-millis` | 300 | 单次上游调用超时 |
| `gateway.circuit.failure-threshold` | 3 | 连续失败多少次后熔断 |
| `gateway.circuit.open-duration-millis` | 5000 | 熔断打开持续多久后进入半开探测 |

---

## 6. 本地验证方法

需要 JDK 21+（已在 JDK 上验证编译目标 21），无需数据库、网络或凭据：

```bash
mvn test            # 运行全部 24 个测试
mvn spring-boot:run # 可选：启动后用 curl 手工验证
```

手工验证示例：

```bash
# 正常 + 废弃提示
curl -i -H 'X-Caller-Id: caller-a' http://localhost:8080/api/gateway/orders/v0
# 未知版本 404 / 已移除 410
curl -i -H 'X-Caller-Id: caller-a' http://localhost:8080/api/gateway/orders/v99
curl -i -H 'X-Caller-Id: caller-a' http://localhost:8080/api/gateway/orders/v-beta
# 401 / 403 / 429
curl -i http://localhost:8080/api/gateway/users/v1
curl -i -H 'X-Caller-Id: caller-b' http://localhost:8080/api/gateway/payments/v1
for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-Caller-Id: caller-c' http://localhost:8080/api/gateway/payments/v1; done
```

### 测试覆盖

| 测试类 | 覆盖场景 |
|---|---|
| `QuotaConcurrencyTest` | 200 线程并发配额不突破/不漏计；窗口翻转恢复 |
| `VersionRoutingTest` | 多版本并存、未知 404、已移除 410、废弃仍服务且带提示 |
| `AccessControlTest` | 401 / 403 / 429 三类拒绝可区分、配额原因可解释 |
| `UpstreamResilienceTest` | 幂等重试、写请求不重试、超时 504、熔断打开 503 不触达上游、半开探测自动恢复、批量部分失败 207 |
| `GatewayHttpIntegrationTest` | MockMvc 端到端校验 HTTP 状态码与响应信封一致 |

测试日志按要求打印三类关键信息（中文前缀便于检索）：
`[请求输入]`（调用方/资源/版本/方法）、`[限流判定]`（是否放行/错误码/used-limit/原因）、
`[上游调用结果]`（成功/故障类型/真实尝试次数/是否降级）。

---

## 7. 代码结构

```
version/   版本注册表（ACTIVE/DEPRECATED/REMOVED）
identity/  调用方档案（身份、授权资源、配额上限）
quota/     并发安全固定窗口配额
access/    认证 -> 授权 -> 配额 三段判定
upstream/  内存后端、超时控制、安全重试、熔断器、部分失败归一化
api/       统一入口 Controller、编排 GatewayService、统一结果与异常处理
core/      错误码与网关注定异常
```
