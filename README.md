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
- **批量结果三态**：全部成功 200 / 部分成功 207 `UPSTREAM_PARTIAL_FAILURE` /
  整批失败 502 `UPSTREAM_BATCH_FAILURE`；整批快速降级另为 503
  （`UPSTREAM_UNAVAILABLE` / `UPSTREAM_RECOVERY_LIMITED`，见 §4.5）。
  任何一种结果里成功项与失败项都**按 index 逐条保留在 `batchData`**，逐条归因。

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
熔断/恢复期降级时 `degraded=true`，并额外携带机器可读的 `degradeReason`：

| `degradeReason` | 含义 | 调用方建议 |
|---|---|---|
| `CIRCUIT_OPEN` | 熔断器 OPEN，上游仍在隔离期 | 继续退避，等下一个冷却周期 |
| `RECOVERY_PROBE_CAPACITY_FULL` | 恢复期(HALF_OPEN)本轮探测名额已用尽 | 极短退避后**尽快重试**，无需等冷却 |

> 两类降级 HTTP 均为 503、均**不触达上游**，但 `errorCode` / `degradeReason` /
> 消息 / 日志都一一对应、明确区分（见 §4.5）。

批量响应没有顶层 `data`，而是 `batchData`：一个**与子请求按位置逐条对齐**的数组，
每个元素结构为：

```json
{
  "index": 0,
  "success": false,
  "httpStatus": 502,
  "data": null,
  "errorCode": "ERROR_5XX",
  "message": "第 0 条子请求上游失败：ERROR_5XX，真实尝试 2 次",
  "attempts": 2,
  "degraded": false
}
```

成功项 `success=true, httpStatus=200, data=<上游返回>`；
失败项携带该条独立的 `errorCode / httpStatus / message / attempts / degraded`。
**整批被快速降级时同样保留每一条**（503 归因，`attempts=0, degraded=true`），
绝不会整批降级后把逐条结果丢掉。

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
| `UPSTREAM_UNAVAILABLE` | **503** | 熔断器 **OPEN**（上游仍在隔离期）被挡回，**未触达上游**；`degradeReason=CIRCUIT_OPEN`，应继续退避 |
| `UPSTREAM_RECOVERY_LIMITED` | **503** | 熔断器处于**恢复期 HALF_OPEN**、本轮有限探测名额已用尽，**未触达上游**；`degradeReason=RECOVERY_PROBE_CAPACITY_FULL`，可极短退避后尽快重试 |

> 后两行同为 503、同样不触达上游，但 `errorCode`、`degradeReason`、`message`
> 与日志 `[降级判定]` 四个层面都对得上、可明确区分：前者告诉调用方「继续退避」，
> 后者告诉调用方「可以赶紧重试」。

### 4.2 批量调用的三种结果语义

| 结果 | `success` | HTTP / errorCode | 调用方动作 | 兼容性 |
|---|---|---|---|---|
| 全部成功 | `true` | **200**（无 errorCode） | 直接使用 `batchData` 全部条目 | 与历史行为一致 |
| 部分成功 | `false` | **207** `UPSTREAM_PARTIAL_FAILURE` | **只需重试失败项** | 成功项/失败项均逐条保留，结构升级为带归因的条目 |
| 整批失败 | `false` | **502** `UPSTREAM_BATCH_FAILURE` | **整批重试**，不得伪装成部分成功 | 历史版本把整批失败也报 207，升级后严格区分 |

- `batchData` 始终与请求的子请求**按 index 逐条对齐**，成功/失败/降级都不缺项：
  - 成功项：`{index, success:true, httpStatus:200, data:<上游返回>, attempts}`；
  - 失败项：`{index, success:false, httpStatus, errorCode, message, attempts, degraded}`，
    `httpStatus` 随该条原因归一化（502 `ERROR_5XX` / 504 `TIMEOUT` / 503 两类降级）。
- **整批被快速降级也不丢明细**：整批 503 时每个子请求仍是一个完整的降级条目
  （`attempts=0, degraded=true`，各自的 `errorCode` 为
  `UPSTREAM_UNAVAILABLE` 或 `RECOVERY_LIMITED`），可按位置定位是哪条、因为什么。
- 恢复期批量顺序执行时：第 1 条拿到名额去探测；探测若失败熔断器重新 OPEN，
  其余条目立即得到 `UPSTREAM_UNAVAILABLE` 逐条归因，整批仍是 **502**（无成功项），
  绝不被当成「部分成功」；只有真正有成功项的批量才是 207。
- 整批失败/降级不会产生重试策略之外的重复上游调用（幂等子请求最多
  `max-attempts` 次尝试，非幂等只 1 次，降级条目 0 次），无重复业务副作用。

### 4.3 安全重试（不产生重复业务副作用）

- 仅对**幂等请求**（GET/PUT/HEAD 等）重试，默认最多 2 次尝试；
- **非幂等请求（POST/PATCH/DELETE）只尝试一次**——即使中途超时也不会重复写；
- 熔断路径上直接快速失败，`attemptCount=0`，同样不会触达上游。

### 4.4 在途并发上限（过载保护）

- 按 **调用方 + 资源**（key 形如 `caller-a:orders`）统计在途请求数，
  超过 `gateway.concurrency.max-in-flight`（**默认 8，本地开箱即可复现过载**；
  生产可调大）的请求**立即快速失败**
  并返回 `CONCURRENCY_LIMIT_EXCEEDED`，不排队、不堆积线程；
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
其余并发请求**立即返回 503 且不触达上游、无副作用**，并与 OPEN 挡回严格区分：

| 场景 | HTTP | errorCode | degradeReason | 日志 | 调用方动作 |
|---|---|---|---|---|---|
| 隔离期 OPEN 挡回 | 503 | `UPSTREAM_UNAVAILABLE` | `CIRCUIT_OPEN` | `[降级判定] ... reason=CIRCUIT_OPEN` | 继续退避 |
| 恢复期名额用尽 | 503 | `UPSTREAM_RECOVERY_LIMITED` | `RECOVERY_PROBE_CAPACITY_FULL` | `[降级判定] ... reason=RECOVERY_PROBE_CAPACITY_FULL` | 极短退避后尽快重试 |

恢复期里**只有拿到名额的那几个请求真正打到上游**（熔断器 per-key 锁内原子
分配名额，并发下绝不超发），其余请求在到达上游前就被快速返回；探测成功后熔断器
回到 CLOSED 自动恢复正常放行；探测失败则重新 OPEN、重新计时，等下一个冷却
周期再放量。整个过程不多打上游（触达次数可精确断言）、不产生重复业务副作用。

单条上游故障不会影响其他资源（故障隔离）；恢复后无需人工介入自动回到正常路径。

---

## 5. 配置项（application.properties，均有默认值）

| 键 | 默认 | 说明 |
|---|---|---|
| `gateway.quota.window-millis` | 60000 | 配额固定窗口长度 |
| `gateway.concurrency.max-in-flight` | **8** | 按调用方+资源的在途并发上限，超出快速失败；**默认本地即可复现过载**，生产可调大（如 100） |
| `gateway.upstream.max-attempts` | 2 | 幂等请求最大尝试次数 |
| `gateway.upstream.timeout-millis` | 300 | 单次上游调用超时 |
| `gateway.circuit.failure-threshold` | 3 | 连续失败多少次后熔断 |
| `gateway.circuit.open-duration-millis` | 5000 | 熔断打开持续多久后进入半开探测 |
| `gateway.circuit.half-open-max-probes` | 1 | 半开期最多同时放行的探测请求数 |

所有阈值仍可用 `-Dkey=value` / `application.properties` / 环境变量覆盖。

---

## 6. 本地验证方法

需要 JDK 21+（已在 JDK 上验证编译目标 21），无需数据库、网络或凭据：

```bash
mvn test            # 运行全部 49 个测试（秒级完成，不依赖真实等待/外部服务）
mvn spring-boot:run # 可选：启动后用 curl 手工验证
```

### 6.1 不改任何配置即可复现（验收路径）

- **过载快速拒绝**：默认在途上限 8。并发发起 >8 个较慢请求（见
  `X-Demo-Latency-Millis`），即可稳定看到恰好 8 个在途、其余 429
  `CONCURRENCY_LIMIT_EXCEEDED`（被拒请求不触达上游、不耗配额）。
- **恢复期只放量少数请求**：默认连续失败 3 次打开熔断、冷却 5000ms、恢复期
  探测名额 1。冷却后并发发起多个请求：恰好 1 个真正打到上游，其余立刻收到
  503 `UPSTREAM_RECOVERY_LIMITED`（`degradeReason=RECOVERY_PROBE_CAPACITY_FULL`）；
  隔离期内则是 503 `UPSTREAM_UNAVAILABLE`（`degradeReason=CIRCUIT_OPEN`）。
- 自动化入口：`DefaultsReproducibleTest` 用**默认配置**启动 Spring 上下文，
  并发由阻塞闸门确定性同步、冷却由可控时钟推进，不需要固定 sleep 掐时间。

### 6.2 本地演示/压测用请求头（不影响正常请求）

| 头 | 含义 |
|---|---|
| `X-Demo-Fault: ERROR_5XX` | 本次/本子请求真实触达时抛 5xx（`TIMEOUT` 同理） |
| `X-Demo-Fault: ERROR_5XX:1` | 仅批量：只给前 1 条子请求注入故障（制造部分成功 207） |
| `X-Demo-Latency-Millis: 500` | 本次调用在内存后端停留 500ms（制造在途并发/超时） |

不带这些头的正常请求行为完全不变。

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

# === 本轮新增：不改配置复现三类批量结果 ===
curl -s -H 'X-Caller-Id: caller-a' -X POST -d '3' \
  http://localhost:8080/api/gateway/orders/v1/brown  # （示例）正常 200
# 部分成功 207（第 0 条失败，逐条归因）
curl -s -H 'X-Caller-Id: caller-a' -H 'X-Demo-Fault: ERROR_5XX:1' \
  -X POST -d '3' http://localhost:8080/api/gateway/orders/v1/batch
# 整批失败 502（每条 ERROR_5XX，不伪装成部分成功）
curl -s -H 'X-Caller-Id: caller-a' -H 'X-Demo-Fault: ERROR_5XX' \
  -X POST -d '3' http://localhost:8080/api/gateway/orders/v1/batch

# === 过载：默认上限 8，12 个慢请求并发，约 8 个 200、其余 429 ===
for i in $(seq 1 12); do
  curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Caller-Id: caller-a' \
    -H 'X-Demo-Latency-Millis: 500' \
    http://localhost:8080/api/gateway/orders/v1 &
done; wait

# === 熔断两类 503 的区分 ===
# 1) 连续 3 个故障写请求打开熔断
for i in 1 2 3; do curl -s -o /dev/null -H 'X-Caller-Id: caller-a' \
  -H 'X-Demo-Fault: ERROR_5XX' -X POST http://localhost:8080/api/gateway/orders/v1; done
# 2) 隔离期：503 UPSTREAM_UNAVAILABLE / CIRCUIT_OPEN
curl -s -H 'X-Caller-Id: caller-a' http://localhost:8080/api/gateway/orders/v1
# 3) 冷却后（默认 5s）并发多个：1 个 200 探测，其余 503
#    UPSTREAM_RECOVERY_LIMITED / RECOVERY_PROBE_CAPACITY_FULL
sleep 5
for i in 1 2 3; do curl -s -H 'X-Caller-Id: caller-a' \
  -H 'X-Demo-Latency-Millis: 300' \
  http://localhost:8080/api/gateway/orders/v1 & done; wait
```

### 测试覆盖

| 测试类 | 覆盖场景 |
|---|---|
| `QuotaConcurrencyTest` | 200 线程并发配额不突破/不漏计；窗口翻转恢复 |
| `BatchQuotaTest` | 批量按子请求数并发计费不突破/不漏计；额度不足整批拒绝、账目一致 |
| `VersionRoutingTest` | 多版本并存、未知 404、已移除 410、废弃仍服务且带提示 |
| `AccessControlTest` | 401 / 403 / 429 三类拒绝可区分、配额原因可解释 |
| `UpstreamResilienceTest` | 幂等重试、写请求不重试、超时 504、熔断打开 503 不触达上游、半开探测自动恢复、批量部分失败 207 |
| `BatchResultStatusTest` | 批量全成功 200 / 部分成功 207 / 整批失败 502 三态可区分、失败逐条归因、熔断期批量 503 **逐条归因不触达上游** |
| `BatchItemAttributionTest` | **新增**：批量每条 index/data/errorCode/attempts/degraded 逐条断言；超时条 504；探测失败后整批 502（1 条真实失败+其余 OPEN 挡回）可归因；无额外触达 |
| `ConcurrencyLimitTest` | 过载 429 与配额耗尽可区分、不排队、名额立即释放、压力回落自动恢复（阻塞闸门确定性同步） |
| `HalfOpenRecoveryTest` | 恢复期并发只放行有限探测、其余快速降级；探测成功恢复 / 探测失败继续隔离；触达次数精确可断言 |
| `RecoveryDegradeClassificationTest` | **新增**：OPEN 挡回与恢复期名额用尽在错误码/HTTP/degradeReason/日志上可区分；整批降级逐条归因且不多打上游 |
| `DefaultsReproducibleTest` | **新增**：**零配置覆盖**复现过载 429 与恢复期放量 1+名额用尽 503；演示头复现批量三态；可控时钟+闸门，无固定 sleep |
| `GatewayHttpIntegrationTest` | MockMvc 端到端校验 HTTP 状态码、响应信封与批量逐条结构一致 |

测试日志按要求打印关键信息（中文前缀便于检索）：
`[请求输入]`（调用方/资源/版本/方法）、`[限流判定]`（是否放行/错误码/
本次消耗/已用/剩余/原因）、`[过载判定]`（在途数与上限）、
`[上游结果]`/`[上游调用结果]`（成功/故障类型/真实尝试次数/是否降级）、
`[降级判定]`（503 时明确打印 OPEN/CIRCUIT_OPEN 还是
HALF_OPEN/RECOVERY_PROBE_CAPACITY_FULL）。

---

## 7. 代码结构

```
version/   版本注册表（ACTIVE/DEPRECATED/REMOVED）
identity/  调用方档案（身份、授权资源、配额上限）
quota/     并发安全固定窗口配额（单请求/批量成本，原子 check-and-add）
access/    在途并发上限 InFlightLimiter；认证 -> 授权 -> 配额 三段判定
upstream/  内存后端（演示故障/延迟/阻塞闸门）、超时控制、安全重试、
           熔断器（半开有限探测，OPEN/HALF_OPEN 两类拒绝可区分）、
           批量三态归一化、逐条结果 BatchItem
api/       统一入口 Controller（含 X-Demo-* 复现头）、编排 GatewayService、
           统一结果/降级原因与批量逐条视图 BatchItemView、异常处理
core/      错误码与网关注定异常；time/ 统一可注入时钟 GatewayClock
```
