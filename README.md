# 本地 API 网关（Java / Spring Boot 3）

一个完全本地运行的 API 网关：对外提供**版本化统一入口**，按**调用方身份限流/配额**，并对上游调用做
**失败隔离、安全重试、熔断与降级**。不依赖任何外部服务或凭据；"上游"由进程内脚本模拟器扮演。

## 1. 统一入口

```
POST /gateway/{resource}/{version}
Header: X-Api-Key: <调用方密钥>
Header: X-Test-Scenario: ok | timeout | error | partial | fail:N   （仅本地演练用）
Body:   任意文本
```

统一响应信封：

```json
{
  "success": true,
  "code": "OK",
  "httpStatus": 200,
  "resource": "orders",
  "version": "v3",
  "body": "{...上游业务体...}",
  "notices": [],
  "explain": { "auth": "...", "quota": { "used": 1, "limit": 20, "windowId": 29836477 }, "attempts": 1 },
  "attempts": 1,
  "degraded": false
}
```

处理顺序固定且可解释：**版本解析 → 认证/授权 → 配额判定 → 弹性上游调用**，每一步的决策都会打日志。

## 2. 版本兼容与渐进式下线规则

每个 `(resource, version)` 在 `VersionRegistry` 中登记为三种生命周期状态之一：

| 状态 | 行为 | HTTP | code |
|---|---|---|---|
| `ACTIVE` | 正常服务 | 200 | `OK` |
| `DEPRECATED` | **仍然正常返回**，在 `notices` 中给出可识别的 `DEPRECATED: ...` 下线提示与迁移建议 | 200 | `OK` |
| `REMOVED` | 明确拒绝，返回下线说明 | 410 | `VERSION_REMOVED` |
| 从未登记（资源或版本未知） | 明确拒绝，错误信息列出可用版本 | 404 | `VERSION_UNKNOWN` |

要点：

- 多版本并存：`orders` 的 v2（废弃）与 v3（活跃）可同时访问。
- 废弃 ≠ 拒绝：废弃版本功能不受影响，只增加可机读/人读的提示。
- 两种拒绝原因**可区分**：未知版本 `VERSION_UNKNOWN/404` 与已移除版本 `VERSION_REMOVED/410`。

内置登记：

| 资源 | v1 | v2 | v3 | 是否幂等 |
|---|---|---|---|---|
| orders | REMOVED | DEPRECATED | ACTIVE | 是（可安全重试） |
| payments | ACTIVE | — | — | 否（禁止重试） |

## 3. 限流与配额模型

- 身份来自 `X-Api-Key`，由内存目录 `CallerDirectory` 识别（本地替身，生产可换实现）。
- 每个调用方有**每资源授权集合**和**固定窗口配额**（默认窗口 60s）。
- 配额判定在 `QuotaService` 中按调用方加锁，"窗口滚动检查 + 计数占用"是**同一个原子临界区**：
  - 并发下最多放行 `limit` 个请求，**不会被突破**；
  - 每个被放行的请求恰好计数一次，**不会漏计**；
  - 被拒请求不计数。
- 判定结果可解释：`QuotaDecision{allowed, used, limit, windowId, reason}` 随响应 `explain.quota` 返回并打日志。

三类前置拒绝互相可区分：

| 场景 | HTTP | code |
|---|---|---|
| 无/未知 API Key | 401 | `AUTH_INVALID_IDENTITY` |
| 身份有效但无权调用该资源 | 403 | `AUTH_FORBIDDEN` |
| 超出配额 | 429 | `QUOTA_EXCEEDED` |

内置调用方：`key-premium`（orders+payments，配额 20）、`key-basic`（仅 orders，配额 3）、
`key-zero`（orders，配额 0）。

## 4. 上游失败隔离、重试、熔断与降级

### 4.1 错误归一化（相互可区分）

| 上游情形 | 归一化 code | HTTP | 是否降级 | 说明 |
|---|---|---|---|---|
| 成功 | `OK` | 200 | 否 | 正常业务体 |
| 批次部分失败 | `UPSTREAM_PARTIAL_FAILURE` | 207 | 否 | 保留逐项结果，**不重试**（部分已落库，盲重试会重复） |
| 超过 150ms 截止时间 | `UPSTREAM_TIMEOUT` | 504 | 是 | 网关按自身 deadline 放弃，不等待上游 |
| 上游业务错误（重试耗尽） | `UPSTREAM_ERROR` | 502 | 是 | 返回本地兜底体 |
| 熔断打开中 | `UPSTREAM_CIRCUIT_OPEN` | 503 | 是 | **快速失败，根本不调用上游** |

### 4.2 仅对可安全重试的请求重试

`RetryPolicy` 同时满足以下条件才重试，最多 2 次物理尝试（1 次初始 + 1 次重试），带退避：

1. 该版本登记为**幂等**；
2. 失败类型是 `TIMEOUT` 或 `UPSTREAM_ERROR`（瞬时/结果未知类）。

因此：

- 非幂等操作（如 payments）即使失败也**绝不重试**，保证不会产生重复业务副作用；
- 部分失败不重试；
- 脚本上游对"先失败 N 次再恢复"（`fail:N`）场景只在最终成功时提交 **1 次**副作用，
  失败尝试提交 0 次，测试会直接断言物理调用数与副作用数。

### 4.3 熔断与自动恢复

`CircuitBreaker` 按上游维度维护状态机：

```
CLOSED --连续 3 次逻辑调用失败--> OPEN --冷却 1s 后放行 1 个探活--> HALF_OPEN
HALF_OPEN --探活成功--> CLOSED（自动回到正常路径）
HALF_OPEN --探活失败--> OPEN（重新计时）
```

- OPEN 期间请求立即返回 `UPSTREAM_CIRCUIT_OPEN/503` + 兜底体，不接触上游，故不可能产生副作用；
- 冷却后只放一个探活请求，成功即关闭熔断器，后续流量自动恢复正常路径；
- 熔断按**一次网关调用的最终结果**计数（而非每次物理尝试），语义可解释。

## 5. 错误分类总表

| code | HTTP | 含义 |
|---|---|---|
| `OK` | 200 | 成功 |
| `VERSION_UNKNOWN` | 404 | 资源或版本从未登记 |
| `VERSION_REMOVED` | 410 | 版本曾存在但已移除 |
| `AUTH_INVALID_IDENTITY` | 401 | 凭据缺失或身份未知 |
| `AUTH_FORBIDDEN` | 403 | 身份有效但无资源权限 |
| `QUOTA_EXCEEDED` | 429 | 固定窗口配额用尽 |
| `UPSTREAM_PARTIAL_FAILURE` | 207 | 批次部分成功 |
| `UPSTREAM_TIMEOUT` | 504 | 上游超时（已降级） |
| `UPSTREAM_ERROR` | 502 | 上游错误，重试耗尽（已降级） |
| `UPSTREAM_CIRCUIT_OPEN` | 503 | 熔断打开，快速失败（已降级） |
| `INTERNAL_ERROR` | 500 | 未预期异常，统一信封兜底 |

## 6. 本地验证方法

无需任何外部服务、容器或凭据。

```bash
# 运行全部测试（离线）
mvn -o test

# 启动服务
mvn -o spring-boot:run
```

手工演练（另开终端）：

```bash
# 正常：活跃版本
curl -s -X POST localhost:8080/gateway/orders/v3 -H 'X-Api-Key: key-premium'

# 废弃版本：200 + notices 中的 DEPRECATED 提示
curl -s -X POST localhost:8080/gateway/orders/v2 -H 'X-Api-Key: key-premium'

# 已移除：410 VERSION_REMOVED；未知版本：404 VERSION_UNKNOWN
curl -s -X POST localhost:8080/gateway/orders/v1   -H 'X-Api-Key: key-premium'
curl -s -X POST localhost:8080/gateway/orders/v77  -H 'X-Api-Key: key-premium'

# 401 / 403 / 429
curl -s -X POST localhost:8080/gateway/orders/v3
curl -s -X POST localhost:8080/gateway/payments/v1 -H 'X-Api-Key: key-basic'
for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST localhost:8080/gateway/orders/v3 -H 'X-Api-Key: key-basic'; done

# 上游异常归一化（504 / 207 / 502）
curl -s -X POST localhost:8080/gateway/orders/v3   -H 'X-Api-Key: key-premium' -H 'X-Test-Scenario: timeout'
curl -s -X POST localhost:8080/gateway/orders/v3   -H 'X-Api-Key: key-premium' -H 'X-Test-Scenario: partial'
curl -s -X POST localhost:8080/gateway/payments/v1 -H 'X-Api-Key: key-premium' -H 'X-Test-Scenario: error'
```

## 7. 测试覆盖

共 27 个测试，全部本地完成：

- `QuotaConcurrencyTests`：200 并发 / 限额 10，断言恰好放行 10、拒绝 190，不突破不漏计。
- `GatewayConcurrencyTests`：60 并发打完整网关链路，配额 20 端到端成立，成功副作用恰为 20。
- `VersionRoutingTests`：未知 vs 已移除可区分（404/410、code 不同），废弃版本 200 且带 DEPRECATED 提示。
- `AuthQuotaTests`：401/403/429 三类拒绝可区分，配额与 zero-quota 行为、explain 内容。
- `UpstreamResilienceTests`：超时归一化 504、部分失败 207、幂等失败重试后成功且仅 1 次副作用、
  非幂等失败绝不重试（仅 1 次物理调用、0 副作用）、重试耗尽后降级。
- `CircuitBreakerRecoveryTests`：连续 3 次失败熔断，OPEN 期间 0 新增物理调用/0 副作用、503 快速失败，
  冷却后探活成功并自动恢复正常路径。
- `GatewayHttpTests`：MockMvc 黑盒验证版本化 URL、Header 鉴权与各状态码。
- `GatewayLoggingTests`：断言日志中同时出现**请求输入、限流判定、上游调用结果**三类记录。

日志使用 logger 名 `GATEWAY`，形如：

```
[GATEWAY] request input: resource=orders version=v3 apiKey=key-*** scenario=ok payload=...
[GATEWAY] rate-limit decision: caller=premium-caller allowed=true used=1/20 window=... reason=...
[GATEWAY] upstream result: handler=orders-upstream attempt=1 type=SUCCESS degraded=false sideEffects=1
```

## 8. 代码结构

```
model/       枚举与记录（版本状态、认证结果、配额决策、上游归一化结果、统一响应）
version/     VersionRegistry：版本登记与解析
auth/        CallerDirectory + Authenticator：身份识别与授权
ratelimit/   QuotaService：并发安全的固定窗口配额
upstream/    UpstreamClient/UpstreamRouter + ScriptedUpstreamClient（本地脚本上游）
resilience/  CircuitBreaker / RetryPolicy / ResilientUpstreamInvoker
core/        GatewayService：固定顺序编排
web/         GatewayController + GatewayExceptionHandler
```
