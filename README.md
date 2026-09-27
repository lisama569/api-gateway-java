# 本地 API 网关（Java / Spring Boot）

为调用方提供**版本化统一入口**的本地 API 网关，聚焦四件事：

1. 多版本接口并存与渐进式下线；
2. 按调用方身份的限流与配额（**批量按子请求数原子计费**，并发安全）；
3. **按调用方（或调用方+资源）的在途并发上限**，过载快速失败、压力回落自动恢复；
4. 上游调用的失败隔离、安全重试、熔断降级、**恢复期有限探测**与自动恢复。

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

请求依次经过四段短路判定，拒绝原因互不混淆：

| 顺序 | 判定 | 失败错误码 | HTTP | 含义 |
|---|---|---|---|---|
| 1 | 身份认证 | `IDENTITY_INVALID` | **401** | 缺少/空白/未登记的 `X-Caller-Id` |
| 2 | 资源授权 | `IDENTITY_FORBIDDEN` | **403** | 身份有效，但无权访问该资源 |
| 3 | 在途并发 | `CALLER_OVERLOADED` | **429** | 身份/授权/配额都有效，但在途并发已达上限（快速过载，不排队、**不扣配额**） |
| 4 | 配额判定 | `QUOTA_EXCEEDED` | **429** | 授权有效，但当前窗口剩余额度不足以覆盖本次成本 |

> 第 3、4 步同为 HTTP 429，但 `errorCode` 明确区分：`CALLER_OVERLOADED` 表示
> 「在途请求太多、请稍后立刻重试，本次未消耗配额」；`QUOTA_EXCEEDED` 表示
> 「窗口额度已被用完」。两者在消息文案与判定日志中也分别说明。

- 配额模型：**固定时间窗计数器**（默认 60s，可配置），每个调用方独立窗口，
  上限来自调用方档案 `CallerProfile.quotaLimit`。
- **成本模型：单请求成本恒为 1（行为不变）；批量入口成本 = 子请求实际条数。**
- **整笔原子计费（all-or-nothing）**：批量请求在同一把 per-caller 锁内一次性完成
  「剩余额度检查 + 按条数计入」。只有剩余额度 ≥ 子请求数时才放行整批并整体扣减；
  额度不足以覆盖整批时**整笔拒绝、计数器保持不变**——绝不会出现
  「扣了一部分额度却放行了整批」或「子请求执行了却没被计入」的错乱，
  且被拒批量不会触达任何上游（无业务副作用）。
- 并发安全：窗口翻转与「检查 + 增加 cost」原子完成，因此无论多少批量并发打进来，
  **被放行并真正执行的子请求总量绝不超过当前窗口额度（不突破），每个放行子请求都计入（不漏计）**。
  由 `BatchQuotaTest`（40 批 × 3 子请求 / 配额 10）与 `QuotaConcurrencyTest` 验证。
- 可解释：每次判定返回 `used / limit / cost / remaining / reason`，
  `[限流判定]` 日志打印「本次批量消耗多少、窗口已用多少、还剩多少」；
  整笔拒绝的 reason 明确写出「本次需要 N、已用 x/limit、剩余 r、整批拒绝未扣减」。

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
| `UPSTREAM_PARTIAL_FAILURE` | **207** | 批量调用中**部分**子请求失败（有成功也有失败），成功项保留、失败项逐条隔离 |
| `UPSTREAM_BATCH_ALL_FAILED` | **502** | 批量调用中**全部**子请求失败，与「部分成功」严格区分 |
| `UPSTREAM_UNAVAILABLE` | **503** | 熔断器打开，请求被快速失败/降级，**未触达上游** |
| `UPSTREAM_RECOVERY_LIMITED` | **503** | 熔断恢复期（HALF_OPEN）探测名额已满，请求被快速降级，**未触达上游** |
| `CALLER_OVERLOADED` | **429** | 在途并发达到上限的快速过载（与 `QUOTA_EXCEEDED` 429 用 errorCode 区分） |

### 4.2 批量三种结果状态（语义与兼容范围）

批量响应在统一信封外额外携带 `batchStatus`、`batchItems`（逐条）与
`batchTotal / batchSucceeded / batchFailed` 计数：

| batchStatus | success | HTTP / errorCode | 语义与调用方动作 |
|---|---|---|---|
| `ALL_SUCCESS` | `true` | **200**，无 errorCode | 全部子请求成功，无需重试 |
| `PARTIAL_SUCCESS` | `false` | **207** `UPSTREAM_PARTIAL_FAILURE` | 有成功有失败：成功项结果保留、失败项逐条带 `errorCode/status/degraded`，**只需重试失败项** |
| `ALL_FAILED` | `false` | **502** `UPSTREAM_BATCH_ALL_FAILED` | 全部失败，不伪装成部分成功；若失败项均为降级（`degraded=true`）则说明均未触达上游，可稍后**整批重试** |

- 逐条视图 `batchItems[i]`：`index / success / status / errorCode / degraded / data`，
  成功失败都保留、失败可归因；原 `batchData` 字段继续保留（逐条原始体/占位），向后兼容。
- **不产生重复副作用**：批量不做整体重试；非幂等子请求（POST/PATCH/DELETE）只尝试一次；
  熔断/恢复期被降级的子请求 `attemptCount=0`、完全不触达上游。

### 4.3 安全重试（不产生重复业务副作用）

- 仅对**幂等请求**（GET/PUT/HEAD 等）重试，默认最多 2 次尝试；
- **非幂等请求（POST/PATCH/DELETE）只尝试一次**——即使中途超时也不会重复写；
- 熔断打开期间直接快速失败，`attemptCount=0`，同样不会触达上游。

### 4.4 熔断与自动恢复（按上游 key 隔离）

每个 `resource:version` 对应一个独立熔断器：

```
CLOSED --连续失败达到阈值(默认3)--> OPEN
OPEN   --等待 open-duration(默认5s)--> HALF_OPEN（只发放 half-open-permits 个探测名额，默认 1）
HALF_OPEN --探测成功--> CLOSED（自动恢复正常路径）
HALF_OPEN --探测失败--> OPEN（重新计时，继续隔离）
```

**恢复期并发规则（防止刚恢复就被一拥而上打垮）**：冷却结束进入 HALF_OPEN 的那一刻，
在同一把 per-key 锁内一次性发放 `gateway.circuit.half-open-permits`（默认 **1**）个探测名额。
恢复期内的并发请求中，只有拿到名额的有限数量会真正触达上游；其余请求立即快速降级为
`UPSTREAM_RECOVERY_LIMITED`（503，`degraded=true`，**不触达上游、attemptCount=0、无副作用**），
不会排队堆积。探测成功后自动回到 CLOSED 正常放量；探测失败则重新 OPEN 并重新计时。
由 `RecoveryProbeTest`（探测在途时 5 个并发请求全部降级、后端触达数不增加）确定性验证。

单条上游故障不会影响其他资源（故障隔离）；恢复后无需人工介入自动回到正常路径。

### 4.5 在途并发上限（过载快速失败与自动恢复）

- 维度可配：`gateway.concurrency.scope=caller`（默认，按调用方）或 `caller_resource`（按调用方+资源）。
- 每个维度持有一个容量为 `gateway.concurrency.max-in-flight`（默认 8）的信号量。
- 请求在「身份/授权通过后、配额扣减前」尝试**非阻塞**获取名额：
  拿不到立即返回 `CALLER_OVERLOADED`（429），**不排队、不堆积、不扣减配额、不触达上游**；
  请求结束（无论成功/失败/降级）在 `finally` 中**立即释放**名额，压力回落后自动恢复放行。
- 与配额耗尽的区别：在途限流管的是「此刻同时有多少请求在处理」（瞬时并发），
  配额管的是「一个时间窗内累计能消耗多少子请求额度」（窗口总量）；两者错误码、文案、日志均不同。
- 由 `ConcurrencyOverloadTest` 验证：占满名额后第 3/4 个请求快速过载且不触达上游、
  在途请求结束后立即恢复、上游失败也释放名额、`caller_resource` 维度各资源独立计量。

---

## 5. 配置项（application.properties，均有默认值）

| 键 | 默认 | 说明 |
|---|---|---|
| `gateway.quota.window-millis` | 60000 | 配额固定窗口长度 |
| `gateway.upstream.max-attempts` | 2 | 幂等请求最大尝试次数 |
| `gateway.upstream.timeout-millis` | 300 | 单次上游调用超时 |
| `gateway.circuit.failure-threshold` | 3 | 连续失败多少次后熔断 |
| `gateway.circuit.open-duration-millis` | 5000 | 熔断打开持续多久后进入半开探测 |
| `gateway.circuit.half-open-permits` | 1 | 恢复期最多放行多少个探测真正触达上游，其余快速降级 |
| `gateway.concurrency.max-in-flight` | 8 | 每个维度的在途请求上限，超限快速过载 |
| `gateway.concurrency.scope` | `caller` | 在途计量维度：`caller` 或 `caller_resource` |

> 想在本地立刻复现过载与恢复期行为，可把 `gateway.concurrency.max-in-flight` 调小（如 2）、
> 把 `gateway.circuit.open-duration-millis` 调小（如 500）；单测则通过阻塞式内存后端
> （`InMemoryBackend.block/awaitEntered/releaseBlock`）确定性地制造并发窗口，无需 sleep、不依赖外部服务。

---

## 6. 本地验证方法

需要 JDK 21+（已在 JDK 上验证编译目标 21），无需数据库、网络或凭据：

```bash
mvn test            # 运行全部 40 个测试
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

# 批量：body 为子请求条数；成功返回 batchStatus=ALL_SUCCESS
curl -s -H 'X-Caller-Id: caller-a' -H 'X-Batch: true' \
  -d 3 http://localhost:8080/api/gateway/orders/v1
# 批量按子请求数计费：caller-c 额度 3，一批 3 个打满，再来一批（哪怕只要 2 个）整笔 429
curl -s -H 'X-Caller-Id: caller-c' -d 3 http://localhost:8080/api/gateway/payments/v1/batch
curl -i -H 'X-Caller-Id: caller-c' -d 2 http://localhost:8080/api/gateway/payments/v1/batch
```

### 测试覆盖

| 测试类 | 覆盖场景 |
|---|---|
| `QuotaConcurrencyTest` | 200 线程并发单请求配额不突破/不漏计；窗口翻转恢复 |
| `BatchQuotaTest` | 单请求成本=1；批量按子请求数计费与账目日志；额度不足整批拒绝且不预扣、不触达上游；40 批×3 子请求并发下放行子请求总量恰为 9（不突破/不漏计） |
| `BatchResultStatusTest` | ALL_SUCCESS / PARTIAL_SUCCESS(207) / ALL_FAILED(502) 三态可区分、逐条可归因；熔断打开的整批 0 触达上游；非幂等整批失败不重试无重复副作用 |
| `ConcurrencyOverloadTest` | 占满在途名额后 `CALLER_OVERLOADED`（与 `QUOTA_EXCEEDED` 区分、不触达上游/不扣配额）；结束即释放并恢复；失败也释放；`caller_resource` 维度独立 |
| `RecoveryProbeTest` | 恢复期探测在途时并发请求仅 1 个探测触达、其余 `UPSTREAM_RECOVERY_LIMITED` 降级；探测成功自动恢复；探测失败重新打开继续隔离 |
| `VersionRoutingTest` | 多版本并存、未知 404、已移除 410、废弃仍服务且带提示 |
| `AccessControlTest` | 401 / 403 / 429 三类拒绝可区分、配额原因可解释 |
| `UpstreamResilienceTest` | 幂等重试、写请求不重试、超时 504、熔断打开 503 不触达上游、半开探测自动恢复、批量部分失败 207 |
| `GatewayHttpIntegrationTest` | MockMvc 端到端校验 HTTP 状态码、响应信封、批量三态与按子请求数 429 |

测试日志按要求打印三类关键信息（中文前缀便于检索）：
`[请求输入]`（调用方/资源/版本/方法/批量标记）、
`[限流判定]`（是否放行/错误码/**本次消耗/已用/剩余**/原因，含在途过载）、
`[上游调用结果]`（成功/故障类型/真实尝试次数/是否降级/批量成功失败计数）。

---

## 7. 代码结构

```
version/     版本注册表（ACTIVE/DEPRECATED/REMOVED）
identity/    调用方档案（身份、授权资源、配额上限）
quota/       并发安全固定窗口配额（支持按成本整笔原子计费）
concurrency/ 在途并发限流器（信号量，按调用方/调用方+资源，快速过载、即用即放）
access/      认证 -> 授权 ->（在途）-> 配额 判定
upstream/    内存后端、超时控制、安全重试、熔断器（恢复期有限探测）、批量逐条归一化
api/         统一入口 Controller、编排 GatewayService、批量三态、统一结果与异常处理
core/        错误码与网关注定异常
```
