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
/api/gateway/{resource}/{version}/batch      # POST，批量子调用
```

- 请求头 `X-Caller-Id`：调用方身份。
- 请求头 `X-Batch: true`：走批量路径；请求体为子请求条数（默认 3）。
- **配额成本**：单请求成本为 1；批量入口按子请求条数一次性扣减配额，
  额度不足以覆盖整批时整批拒绝（429 `QUOTA_EXCEEDED`），账目不做部分扣减。
- **批量结果三态 + 降级**：全部成功 200 / 部分成功 207 `UPSTREAM_PARTIAL_FAILURE` /
  整批失败 502 `UPSTREAM_BATCH_FAILURE`，另在熔断期整体降级 503；批量响应统一带
  `batchStatus` 标识（`ALL_SUCCEEDED` / `PARTIAL_FAILURE` / `ALL_FAILED` / `DEGRADED`），
  成功项与失败项逐条保留在 `batchData`（含整批降级，逐条占位可归因）。

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

请求依次经过短路判定，各类拒绝互不混淆：

| 顺序 | 判定 | 失败错误码 | HTTP | 含义 |
|---|---|---|---|---|
| 0 | 在途并发上限 | `CONCURRENCY_LIMIT_EXCEEDED` | **429** | 同一调用方+资源的在途请求数达上限，快速失败不排队 |
| 1 | 身份认证 | `IDENTITY_INVALID` | **401** | 缺少/空白/未登记的 `X-Caller-Id` |
| 2 | 资源授权 | `IDENTITY_FORBIDDEN` | **403** | 身份有效，但无权访问该资源 |
| 3 | 配额判定 | `QUOTA_EXCEEDED` | **429** | 授权有效，但当前窗口配额不足以覆盖本次成本 |

- 配额模型：**固定时间窗计数器**（默认 60s，可配置），每个调用方独立窗口，
  上限来自调用方档案 `CallerProfile.quotaLimit`。
- **批量调用的配额成本模型**：批量入口一次携带多个子请求，配额按
  **实际子请求数**一次性扣减（单个请求成本保持为 1）。扣减是原子的：
  剩余额度足以覆盖整批 → 整批扣减并放行；不足 → **整批拒绝且一分不扣**，
  绝不会出现「只扣了一部分却放行了整批」的错乱账目。判定日志
  （`[限流判定]`）打印本次消耗、窗口已用与剩余额度，账目可复现。
- 并发安全：窗口翻转与「检查 + 按成本扣减」在同一把 per-caller 锁内原子完成，
  因此并发下**放行的子请求总量绝不超过配额（不突破），每个放行请求都计入
  （不漏计）**。由 `QuotaConcurrencyTest`（单请求）与 `BatchQuotaTest`
  （批量成本）共同验证。
- 可解释：每次判定返回 `used / limit / consumed / reason`，日志打印 `[限流判定]`。

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
| `CONCURRENCY_LIMIT_EXCEEDED` | **429** | 在途并发达上限，快速失败不排队（与配额耗尽不同，见 §4.4） |
| `UPSTREAM_TIMEOUT` | **504** | 上游在 `gateway.upstream.timeout-millis`（默认 300ms）内未返回 |
| `UPSTREAM_ERROR` | **502** | 上游确定性 5xx / 执行异常 |
| `UPSTREAM_BATCH_FAILURE` | **502** | 批量调用**全部**子请求失败（无成功项），建议整批重试 |
| `UPSTREAM_PARTIAL_FAILURE` | **207** | 批量调用中**部分**子请求失败，成功项结果保留，失败项逐条隔离占位 |
| `UPSTREAM_UNAVAILABLE` | **503** | 熔断器 **OPEN 隔离期**，请求被快速失败/降级，**未触达上游** |
| `UPSTREAM_RECOVERY_LIMITED` | **503** | 熔断器 **HALF_OPEN 恢复期**探测名额已用完：请求被快速失败，**未触达上游**。与上一行严格区分——收到本错误码说明上游正在恢复探测，可缩短退避、准备重试 |

### 4.2 批量调用的结果语义

| 结果 | `batchStatus` | `success` | HTTP / errorCode | 调用方动作 | 兼容性 |
|---|---|---|---|---|---|
| 全部成功 | `ALL_SUCCEEDED` | `true` | **200**（无 errorCode） | 直接使用 `batchData` 全部条目 | 与历史行为一致 |
| 部分成功 | `PARTIAL_FAILURE` | `false` | **207** `UPSTREAM_PARTIAL_FAILURE` | **只需重试失败项** | 与历史行为一致；成功项/失败项均逐条保留在 `batchData` |
| 整批失败 | `ALL_FAILED` | `false` | **502** `UPSTREAM_BATCH_FAILURE` | **整批重试**，不得伪装成部分成功 | 历史版本把整批失败也报 207，升级后严格区分 |
| 整体降级（熔断隔离期） | `DEGRADED` | `false` | **503** `UPSTREAM_UNAVAILABLE` | 继续退避后重试 | 历史版本整批降级不带逐条结果，升级后逐条保留 |
| 整体降级（恢复期名额耗尽） | `DEGRADED` | `false` | **503** `UPSTREAM_RECOVERY_LIMITED` | 上游正在恢复，可缩短退避、尽快重试 | 新增 |

- 无需逐条比对：批量响应统一带 `batchStatus` 字段，一眼分清
  `ALL_SUCCEEDED` / `PARTIAL_FAILURE` / `ALL_FAILED` / `DEGRADED`。
- `batchData` 始终与请求的子请求**逐条对齐、数量相等**：成功项为上游响应，
  失败项/降级项为带 `{"error":"..."}` 的归一化占位（如 `CIRCUIT_OPEN`、
  `HALF_OPEN_PROBE_LIMIT`、`TIMEOUT`、`ERROR_5XX`），可按位置归因。
  **整批降级时每条子结果也保留**，不会因为降级而丢掉逐条明细。
- 整批失败不会产生重试策略之外的重复上游调用（幂等子请求最多
  `max-attempts` 次尝试，非幂等只 1 次），无重复业务副作用。
- 熔断期间的批量调用整体返回 **503** 且 `degraded=true`，所有子请求均不触达上游，
  同样不计入任何额外尝试；错误码按「隔离期 / 恢复期名额耗尽」区分（见 §4.5）。

### 4.3 安全重试（不产生重复业务副作用）

- 仅对**幂等请求**（GET/PUT/HEAD 等）重试，默认最多 2 次尝试；
- **非幂等请求（POST/PATCH/DELETE）只尝试一次**——即使中途超时也不会重复写；
- 熔断打开期间直接快速失败，`attemptCount=0`，同样不会触达上游。

### 4.4 在途并发上限（过载保护）

- 按 **调用方 + 资源**（key 形如 `caller-a:orders`）统计在途请求数，
  超过 `gateway.concurrency.max-in-flight`（**默认 4**）的请求**立即快速失败**
  并返回 `CONCURRENCY_LIMIT_EXCEEDED`，不排队、不堆积线程；
  默认值刻意取小，保证**不改任何配置、本地直接压测就能稳定复现过载**
  （`DefaultConfigReproducibilityTest` 已固化），生产环境可按需调大（如 100）；
- 请求结束（正常或异常）后在 `finally` 中立即释放名额，
  压力回落后无需任何干预自动恢复放行；
- **与配额耗尽的区别**：配额是「时间窗内总量」，耗尽后需等窗口翻转，
  错误码为 `QUOTA_EXCEEDED`；在途上限是「同一时刻并发数」，
  名额随请求结束立即可用，错误码为 `CONCURRENCY_LIMIT_EXCEEDED`，
  两者同为 429 但错误码、原因消息与恢复语义均不同。过载请求在配额判定之前
  被拒绝，因此**不会消耗配额**。

### 4.5 熔断与自动恢复（按上游 key 隔离）

每个 `resource:version` 对应一个独立熔断器：

```
CLOSED --连续失败达到阈值(默认3)--> OPEN
OPEN   --等待 open-duration(默认5s)--> HALF_OPEN
HALF_OPEN --探测成功--> CLOSED（自动恢复正常路径）
HALF_OPEN --探测失败--> OPEN（重新计时，继续隔离）
```

**恢复期并发规则**：进入 HALF_OPEN 后，最多同时放行
`gateway.circuit.half-open-max-probes`（默认 1）个探测请求真正触达上游，
其余并发请求立即返回降级结果（不触达上游、无副作用），
避免上游刚恢复就被并发流量再次打垮；探测成功后熔断器回到 CLOSED，
自动恢复正常放行；探测失败则重新 OPEN 并重新计时，继续保持隔离。

**两类快速失败严格可区分**（错误分类与日志一一对应，便于调用方决定退避还是重试）：

| 熔断器状态 | 被拒请求拿到的结果 | 日志（ResilientUpstreamClient） |
|---|---|---|
| OPEN（隔离期，上游仍在故障） | 503 `UPSTREAM_UNAVAILABLE`（`degraded=true`，未触达上游） | `[上游结果] key=... 熔断打开（隔离期），快速失败（不触达上游）kind=CIRCUIT_OPEN` |
| HALF_OPEN（恢复期探测名额已用完） | 503 `UPSTREAM_RECOVERY_LIMITED`（`degraded=true`，未触达上游） | `[上游结果] key=... 半开恢复期探测名额已满，快速失败（不触达上游）kind=HALF_OPEN_PROBE_LIMIT` |

- 两者 HTTP 同为 503，但 **errorCode 不同、message 不同、日志不同**：
  前者表示上游仍在故障，应继续退避；后者表示上游已在恢复探测，可缩短退避、准备重试。
- 恢复期只有拿到名额的探测请求真正触达上游；名额耗尽的请求全部快速返回，
  全过程触达次数精确可断言（3 次打开 + N 个探测），**不会多打上游、
  不会产生重复业务副作用**。

单条上游故障不会影响其他资源（故障隔离）；恢复后无需人工介入自动回到正常路径。

---

## 5. 配置项（application.properties，均有默认值）

| 键 | 默认 | 说明 |
|---|---|---|
| `gateway.quota.window-millis` | 60000 | 配额固定窗口长度 |
| `gateway.concurrency.max-in-flight` | **4** | 按调用方+资源的在途并发上限，超出快速失败；默认取小便于本地复现，生产可按需调大 |
| `gateway.upstream.max-attempts` | 2 | 幂等请求最大尝试次数 |
| `gateway.upstream.timeout-millis` | 300 | 单次上游调用超时 |
| `gateway.circuit.failure-threshold` | 3 | 连续失败多少次后熔断 |
| `gateway.circuit.open-duration-millis` | 5000 | 熔断打开持续多久后进入半开探测 |
| `gateway.circuit.half-open-max-probes` | 1 | 半开期最多同时放行的探测请求数 |

---

## 6. 本地验证方法

需要 JDK 21+（已在 JDK 上验证编译目标 21），无需数据库、网络或凭据：

```bash
mvn test            # 运行全部 39 个测试
mvn spring-boot:run # 可选：启动后用 curl 手工验证
```

**默认配置（不改任何配置）即可稳定复现两类关键现象**，对应自动化用例
`DefaultConfigReproducibilityTest`（断言全部基于状态/计数的条件轮询，
不靠固定等待掐时间，不依赖外部服务）：

- **过载快速拒绝**：默认在途上限 4，对慢资源（本地后端有内置延迟注入）
  齐发 12 个并发请求，必然有请求立刻拿到 429 `CONCURRENCY_LIMIT_EXCEEDED`，
  压力回落后自动恢复；
- **恢复期有限探测**：默认连续失败 3 次打开熔断，冷却 5s 后进入 HALF_OPEN，
  默认只放 1 个探测真正触达上游，其余请求立刻拿到 503
  `UPSTREAM_RECOVERY_LIMITED`（与隔离期的 `UPSTREAM_UNAVAILABLE` 可区分），
  探测成功后自动恢复。

单测是更直接的本地复现入口（时钟、故障、延迟均可注入，秒级完成、
无外部依赖）：`ConcurrencyLimitTest`、`HalfOpenRecoveryTest`、
`DegradationClassificationTest`、`BatchResultStatusTest`。

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
# caller-c 配额 3：单请求成功 3 次后耗尽
for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-Caller-Id: caller-c' http://localhost:8080/api/gateway/payments/v1; done
# 批量按子请求数计费：caller-c 已耗尽，整批 429 且账目不变
curl -s -H 'X-Caller-Id: caller-a' \
  -X POST -d '3' http://localhost:8080/api/gateway/orders/v1/batch
```

### 测试覆盖

| 测试类 | 覆盖场景 |
|---|---|
| `QuotaConcurrencyTest` | 200 线程并发配额不突破/不漏计；窗口翻转恢复 |
| `BatchQuotaTest` | 批量按子请求数并发计费不突破/不漏计；额度不足整批拒绝、账目一致 |
| `VersionRoutingTest` | 多版本并存、未知 404、已移除 410、废弃仍服务且带提示 |
| `AccessControlTest` | 401 / 403 / 429 三类拒绝可区分、配额原因可解释 |
| `UpstreamResilienceTest` | 幂等重试、写请求不重试、超时 504、熔断打开 503 不触达上游、半开探测自动恢复、批量部分失败 207 |
| `BatchResultStatusTest` | 批量全成功 200 / 部分成功 207 / 整批失败 502 三态可区分（`batchStatus` 标识）、失败逐条归因、熔断期批量 503 不触达上游且逐条结果保留 |
| `ConcurrencyLimitTest` | 过载 429 与配额耗尽可区分、不排队、名额立即释放、压力回落自动恢复 |
| `HalfOpenRecoveryTest` | 恢复期并发只放行有限探测、其余快速降级；探测成功恢复 / 探测失败继续隔离；两类降级分类（`HALF_OPEN_PROBE_LIMIT` vs `CIRCUIT_OPEN`）可区分；触达次数精确可断言 |
| `DegradationClassificationTest` | 网关层验证 503 `UPSTREAM_UNAVAILABLE`（隔离期）与 503 `UPSTREAM_RECOVERY_LIMITED`（恢复期名额耗尽）返回结果可区分，探测成功自动恢复 |
| `DefaultConfigReproducibilityTest` | **零配置覆盖**的 Spring 上下文测试：默认阈值即可端到端复现过载快速拒绝与恢复期有限探测；阈值仍可配置；条件轮询断言、无固定 sleep |
| `GatewayHttpIntegrationTest` | MockMvc 端到端校验 HTTP 状态码与响应信封一致 |

测试日志按要求打印关键信息（中文前缀便于检索）：
`[请求输入]`（调用方/资源/版本/方法）、`[限流判定]`（是否放行/错误码/
本次消耗/已用/剩余/原因）、`[过载判定]`（在途数与上限）、
`[上游调用结果]`（成功/故障类型/真实尝试次数/是否降级）、
`[上游结果]`（熔断器视角：`CIRCUIT_OPEN` 隔离期拒绝 / `HALF_OPEN_PROBE_LIMIT`
恢复期名额耗尽拒绝 / 成功 attempt 次数）、
`[降级分类]`（网关视角：最终错误码与逐条结果数）。

---

## 7. 代码结构

```
version/   版本注册表（ACTIVE/DEPRECATED/REMOVED）
identity/  调用方档案（身份、授权资源、配额上限）
quota/     并发安全固定窗口配额（单请求/批量成本，原子 check-and-add）
access/    在途并发上限 InFlightLimiter；认证 -> 授权 -> 配额 三段判定
upstream/  内存后端、超时控制、安全重试、熔断器（半开有限探测）、批量三态归一化
api/       统一入口 Controller、编排 GatewayService、统一结果与异常处理
core/      错误码与网关注定异常
```
