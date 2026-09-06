# M3 Task 8-R 异步架构纠偏验收记录

## 结论边界

本记录只覆盖用户批准的 Task 8-R Redis Streams adapter boundary 纠偏、Java 行为测试证据，以及 Task 8 的 Python 本地 fake 诊断/回答边界矩阵。它不声明 M3 已关闭，也不授权 M4/M5；最终关闭与后续阶段授权由根 Agent 独立复核。

本次未连接真实 Redis、PostgreSQL、Neo4j、模型服务或生产环境，未使用密钥，未启动生产端口，未修改部署、Compose、Proto、Python 公共接口、数据库迁移或缓存行为。

证据边界必须分开解释：Java Testcontainers 行为测试证明实际 checkpoint、ACK、retry/DLQ、duplicate 与 reconciliation 语义；Python fake 矩阵只证明当内存态 `MemoryPort` 边界返回有界 `EMPTY`/`DEGRADED` 结果时，回答生成、证据绑定和公开回答契约与可选记忆隔离。Python 用例不证明真实 PostgreSQL/Redis/Neo4j、服务重启或基础设施恢复行为。

## 环境版本

- OS：Windows 11 10.0 amd64
- Java：JetBrains Runtime 21.0.6，`JAVA_HOME=D:\APP\IntelliJ IDEA 2025.1\jbr`
- Maven Wrapper：Apache Maven 3.9.16
- Python：项目 `.venv` 的 Python 3.11.9

版本命令：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml -version
& .\.venv\Scripts\python.exe --version
```

结果：退出码 0。

## 架构门禁 RED 证据

修改前执行：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=ArchitectureTest' test
```

结果：退出码 1；ArchitectureTest 共 4 项，1 失败、0 error、0 skipped。失败规则报告 39 个违规，均来自以下 `application.async` 类直接引用 Spring Data Redis 类型：

- `MemoryEventConsumer`
- `PendingEntryReclaimer`
- `OutboxReconciler`

根因与 Task 8-R 批准的 Redis adapter boundary 冲突一致，不是环境或无关规则失败。

## 最小纠偏

- 新增 Redis-free `application.async.AsyncStageProcessor`，仅持有既有 stage processor 合同、identifier-only `EventContext` 和 source-text-free `PreparedAttempt` marker。
- `CandidateProcessingService` 改为实现 `AsyncStageProcessor`；其读取、worker 调用、Java 治理、checkpoint 与事务顺序未改变。
- Redis Streams 消费、pending reclaim、ACK/DLQ 和 PostgreSQL-to-Redis reconciliation 三个类原样迁至 `adapter.redis`，改为依赖应用端口。
- Redis envelope 仍只含标识符字段；未增加 source plaintext、调度器、生产端口、worker 权限或 authority writer。

## 架构门禁 GREEN 证据

纠偏后再次执行同一 ArchitectureTest 命令。

结果：退出码 0；4/4 通过，0 failure、0 error、0 skipped。已验证：

- domain/application 不直接依赖 Redis、Redis client、Neo4j、Spring Data Neo4j、gRPC 或生成的 memory contract 类型；
- worker adapter 不依赖 PostgreSQL、Redis、cache 或 Neo4j data adapter；
- cache/Neo4j adapter 不依赖列明的 PostgreSQL authority writer adapter。

## Java 行为保持证据

命令：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=ArchitectureTest,MemoryEventConsumerTest,AsyncRecoveryTest,CandidateProcessingServiceTest' test
```

结果：退出码 0；31/31 通过，0 failure、0 error、0 skipped：

- ArchitectureTest：4
- MemoryEventConsumerTest：10
- AsyncRecoveryTest：3
- CandidateProcessingServiceTest：14

Redis restart 测试期间出现预期的 Lettuce reconnect warning，测试仍全部通过。这里的 Java Testcontainers 行为测试是实际 checkpoint 后 ACK、bounded retry/DLQ diagnostic、duplicate/recovery、reconciliation、source closure、worker deadline 与 Java governance 语义的证据。

全量回归命令：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml clean test
```

结果：退出码 0；222/222 通过，0 failure、0 error、0 skipped。运行中出现 Testcontainers 关闭/重连相关日志，但未形成测试失败。

## Python 审查修复 RED 证据

审查前的测试只比较手工构造的 `AnswerEnvelope`、`EvidencePackage`、`MemoryContext` 和字典/列表故障状态。加入“每个场景必须返回真实 `MemoryReadResult`”的最小断言后执行：

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests\integration\memory_async\test_answer_survives_async_failures.py -q
```

结果：退出码 1；12/12 失败。每个失败均显示旧 `_exercise_fault()` 返回的是普通 durable-event `dict`，不是 `MemoryReadResult`；旧手工对象没有经过 `MemoryPort.resolve()`，也没有进入 `GenerationRequest` / `AnswerGenerationPipeline.generate()`，因此不能支持原有回答链路声明。

## Python fake-only 诊断与回答边界矩阵

修复后的诊断矩阵只使用项目已有 `FakeCandidateProvider`、`FakeEmbeddingProvider`、`ModelHealth` 和内存态容器；回答边界矩阵使用内存态 `FakeFaultMemoryPort`。它不访问真实服务，也不模拟或证明真实基础设施生命周期。

每个故障名称保留两个彼此独立的检查：第一组只验证本地 fake 状态与有限诊断名称；第二组让 fake `MemoryPort.resolve()` 返回有界的空 `MemoryContext` 和 `EMPTY`/`DEGRADED` `MemoryReadResult`，再把该返回对象中的 `MemoryContext` 传入真实 `GenerationRequest`，由现有 `build_generation_pipeline(runtime_settings(...))` 构建的 `AnswerGenerationPipeline.generate()` 生成回答。生成使用固定 mock 模型响应、稳定的 reviewed `EvidencePackage`，不发起模型网络调用。

| 故障名称 | 本地 fake 诊断 | fake MemoryPort 结果 |
| --- | --- | --- |
| Redis absent | `REDIS_UNAVAILABLE` | `DEGRADED` + 空 context |
| Redis restarted / flushed | `RECONCILED_FROM_POSTGRES` | `EMPTY` + 空 context |
| Worker absent | `MODEL_UNAVAILABLE_RETRYABLE` | `DEGRADED` + 空 context |
| Worker corrupt | `INVALID_SOURCE_CLOSURE` | `DEGRADED` + 空 context |
| Worker slow | `WORKER_DEADLINE_EXCEEDED` | `DEGRADED` + 空 context |
| Model unavailable | `MISSING_HEALTH_LEASE` | `DEGRADED` + 空 context |
| Model OOM | `CUDA_OOM` | `DEGRADED` + 空 context |
| Neo4j absent | `PROJECTION_UNAVAILABLE` | `DEGRADED` + 空 context |
| Neo4j rebuilt | `PROJECTION_REBUILT` | `EMPTY` + 空 context |
| Duplicate delivery | `DUPLICATE_COLLAPSED` | `EMPTY` + 空 context |
| Service restart | `RECOVERED_FROM_DURABLE_STATE` | `EMPTY` + 空 context |

上述名称是本地 fake 的有限诊断标签，不代表 Python 测试执行了真实 Redis flush/restart、PostgreSQL durable recovery、Neo4j rebuild、重复投递基础设施或服务进程重启。

单文件命令：

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests\integration\memory_async\test_answer_survives_async_failures.py -q
```

结果：退出码 0；24/24 通过，其中 12 项为独立本地 fake 诊断检查，12 项为 `MemoryPort.resolve()` 到真实回答生成管线的边界隔离检查。

联合公共边界命令：

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests\integration\memory_async tests\integration\answer_pipeline\test_memory_cannot_be_fact_source.py tests\integration\app_loop\test_answer_contract_compatibility.py -q
```

结果：退出码 0；44/44 通过。每个 fake 故障结果都经真实 `GenerationRequest` / `AnswerGenerationPipeline.generate()`，并与健康 memory baseline 比较稳定公开回答字段（排除每次运行生成的 `answer_id` 和 trace 标识）、公开字段顺序、同一 evidence package 和同一 evidence binding。健康 baseline 中的 memory hit 不进入 `evidence_refs`；所有事实 claim 的 `source_origin` 保持 `EVIDENCE`。联合测试同时验证 memory/prompt/model-prior 作为事实来源会触发 rewrite-only，以及 TextQuery/Answer/CLI 公共 schema 保持兼容。该结果仍只属于 Python fake answer/evidence/public-contract isolation，不是实际异步基础设施恢复证据。

## 文件清单

本次审查修复仅修改：

- `tests/integration/memory_async/test_answer_survives_async_failures.py`
- `docs/评测与验收/追踪报告/memory_m3_async_acceptance.md`

以下为本记录此前已验收的 Task 8-R Redis adapter boundary 纠偏文件，未在本次审查修复中改动。

修改：

- `services/memory-service/src/main/java/com/yilan/memory/application/async/CandidateProcessingService.java`
- `services/memory-service/src/test/java/com/yilan/memory/application/async/MemoryEventConsumerTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/application/async/AsyncRecoveryTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/application/async/CandidateProcessingServiceTest.java`

新增：

- `services/memory-service/src/main/java/com/yilan/memory/application/async/AsyncStageProcessor.java`
- `services/memory-service/src/main/java/com/yilan/memory/adapter/redis/MemoryEventConsumer.java`
- `services/memory-service/src/main/java/com/yilan/memory/adapter/redis/PendingEntryReclaimer.java`
- `services/memory-service/src/main/java/com/yilan/memory/adapter/redis/OutboxReconciler.java`
- `tests/integration/memory_async/test_answer_survives_async_failures.py`
- `docs/评测与验收/追踪报告/memory_m3_async_acceptance.md`

删除（迁移前路径）：

- `services/memory-service/src/main/java/com/yilan/memory/application/async/MemoryEventConsumer.java`
- `services/memory-service/src/main/java/com/yilan/memory/application/async/PendingEntryReclaimer.java`
- `services/memory-service/src/main/java/com/yilan/memory/application/async/OutboxReconciler.java`

`ArchitectureTest.java` 的 Task 8 规则在本次开始时已经存在；本次仅执行其 RED/GREEN 门禁，未改写该文件。未修改任何其他文件。

## Harness 与剩余项

- Harness 违反：否。
- 真实模型手工检查：未执行；按本次边界只使用已有 fakes，Qwen/BGE/显卡 OOM 的真实硬件检查仍是人工剩余项。
- 根 Agent 阶段关闭复核（2026-07-21）：Python 联合门禁 `44 passed`、Java `clean test` 为 `222` tests / `0` failures / `0` errors、Maven Wrapper `clean` 及 M3 cleanliness 均为 exit 0；M3 阶段只读审查结论为 `APPROVE`。
- M3 closed：是。本报告的 Java Testcontainers 证据与 Python fake-only 边界证据仍按上文的范围解释，阶段关闭结论不把 fake 矩阵扩大为真实基础设施恢复证明。
- M4 may start：是；仅可按已批准的 M4 计划、白名单和阶段门禁继续，不能由本报告扩展 M4/M5 的实现范围。
