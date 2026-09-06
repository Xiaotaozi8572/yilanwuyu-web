# M4-8 隐私验收证据（本地）

## 范围与状态

本报告记录 M4 Task 8（隐私基线与可审计控制）在本地工作区的 RED→最小修复→GREEN 证据。验证未连接真实密钥、生产数据库或生产服务；Java 集成测试使用本地 Testcontainers。

M4 仍为 **OPEN**。本报告不关闭 M4，亦不表示可以开始 M5；阶段选择、最终只读审查、全量门禁与 `STATUS.md` 更新由根 Agent 负责。

## 本任务修改范围

修改：

- `src/agent/langgraph_runtime.py`
- `src/core/tracing.py`
- `services/memory-service/src/main/java/com/yilan/memory/adapter/postgres/JdbcCandidateRepository.java`
- `services/memory-service/src/main/java/com/yilan/memory/adapter/redis/OutboxReconciler.java`
- `services/memory-service/src/test/java/com/yilan/memory/ArchitectureTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/application/async/AsyncRecoveryTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/adapter/postgres/MemoryHistoryIntegrationTest.java`
- `tests/integration/voice_loop/test_voice_trace_privacy.py`
- `tests/integration/memory_privacy/test_privacy_fallback.py`
- `tests/integration/answer_pipeline/test_answer_generation_regressions.py`

新增：

- `services/memory-service/src/test/java/com/yilan/memory/privacy/PrivacyAcceptanceTest.java`
- 本报告。

未新增公开 Proto/Python 字段、数据库迁移、服务端口、外部依赖或事实来源；PostgreSQL 仍是长期记忆权威，Neo4j 没有成为 authority writer。

## 已验证的隐私与审计边界

- 公共运行追踪保留既有 `user_query` 键但其值固定为 `null`；请求原文仅用于本次进程内编排。
- `append_error` 仅记录有限诊断码和固定文案 `details redacted`，不持久化传入的错误详情。
- 兼容性的本地 `query_audit` **payload** 仅记录 `query_audit_version` 与可选的不透明 `scene_state_id`、`turn_id`；payload 不记录原始查询、`query_object`、用户 ID 或会话 ID。该事件不是长期记忆回退或权威来源。
- 候选治理审计仅记录候选 ID、记忆类型、规则集版本、决定结果、有限 reason codes；不记录主体、来源或候选密文。
- outbox 协调审计只记录 outbox ID、阶段/版本、结果和有限诊断码。
- 语音公开追踪摘要有正向字段白名单，拒绝转录、来源、身份、令牌、密钥等内容。
- ArchUnit 明确禁止 REST、gRPC、Redis 与 worker adapter 依赖私密负载的密钥/保护器/加密负载类型；PostgreSQL 适配器保留受控加密职责。
- Testcontainers 验证 `memory_audit_event` 的必要字段和拒绝 UPDATE 的追加式约束。
- candidate governance 与 async requeue 的 `memory_audit_event.redacted_metadata` 均解析为 JSON，对 key 集合做精确白名单断言，并分别验证有限值与私密哨兵排除。

测试使用下列哨兵作为拒绝输入，并断言它们不会出现在持久化的 payload、公共 trace 或导出的报告中：`M4_PRIVATE_RAW_QUERY`、`M4_PRIVATE_LEGACY_QUERY`、`M4_PRIVATE_IDENTITY`、`M4_PRIVATE_SESSION`、`M4_PRIVATE_SOURCE`、`M4_PRIVATE_MEMORY_PAYLOAD`、`M4_PRIVATE_VOICE_TRANSCRIPT`、`M4_PRIVATE_VOICE_SOURCE`、`M4_PRIVATE_VOICE_IDENTITY`、`M4_PRIVATE_VOICE_TOKEN`、`M4_PRIVATE_VOICE_KEY`、`M4_PRIVATE_SOURCE_PAYLOAD`、`M4_PRIVATE_CANDIDATE_CIPHERTEXT`、`m4-private-subject-sentinel`。

## RED：先复现的缺口

初始 Python 隐私 RED：

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests\integration\memory_privacy\test_privacy_fallback.py tests\integration\voice_loop\test_voice_trace_privacy.py -q
```

退出码 `1`：3 个测试中 2 个失败，分别证明公共 trace 保留了原始查询、错误事件保留了原始错误详情。

初始 Java 审计 RED：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=PrivacyAcceptanceTest,MemoryHistoryIntegrationTest,ArchitectureTest' test
```

退出码 `1`：21 个测试中 2 个失败（0 errors/skips）；候选审计元数据缺少规则版本、结果和原因码。Flyway 已执行 V1–V11。

持久化 `query_audit` 的后续 RED：

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider 'tests\integration\memory_privacy\test_privacy_fallback.py::test_degraded_memory_keeps_evidence_answer_while_public_trace_and_report_are_content_free' 'tests\integration\answer_pipeline\test_answer_generation_regressions.py::test_graph_non_unsafe_request_records_the_legacy_query_audit_event' -q
```

退出码 `1`：2 个测试均失败，复现旧事件 payload 含原始查询、`query_object`、用户 ID 和会话 ID。随后以最小修改限定为版本与可选不透明标识。

独立审查后的非人工 RED 覆盖补齐：先将 candidate governance 与 async requeue 审计的断言收紧为 JSON 精确 key 集合。首轮定向命令退出码 `1`，但 `PrivacyAcceptanceTest` 的新白名单断言通过；`AsyncRecoveryTest` 的 3 个既有测试在新审计断言执行前被 V9 `interaction_event_protected_payload_shape_check` 拒绝。根因是该测试夹具将事件标为 `SENSITIVE`，却没有写入既有约束要求的本地受保护 payload 的 key、nonce、algorithm、version。它不是 `OutboxReconciler` metadata writer 违反规格。夹具随后仅使用现有 `InMemoryTestKeyProvider` 与 `PayloadProtector` 生成本地测试密文和必需元数据；未改生产代码、迁移或公开契约。因此这是一项已有正确 writer 的非人工 RED 覆盖补齐，而不是伪造的 writer RED。

## GREEN：新鲜本地验证

Python（上游公开回答、memory-not-fact、Java 降级连续回答、M4 隐私与语音追踪）：

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests\integration\memory_privacy\test_privacy_fallback.py tests\integration\answer_pipeline\test_answer_generation_regressions.py tests\integration\memory_service\test_java_failure_answer_continues.py tests\integration\answer_pipeline\test_memory_cannot_be_fact_source.py tests\integration\voice_loop\test_voice_trace_privacy.py -q
```

退出码 `0`：`23 passed`。

Java 先执行 Maven Wrapper clean：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml clean
```

退出码 `0`，`BUILD SUCCESS`。

Java 隐私/审计选择器：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=PrivacyAcceptanceTest,MemoryHistoryIntegrationTest,AsyncRecoveryTest,ArchitectureTest,NoPlaintextLeakTest,CachePrivacyTest,ConsentServiceTest,MemoryManagementServiceTest,ForgetServiceTest,DeletionProcessorTest,RetentionSchedulerTest,ReconfirmationSchedulerTest' test
```

退出码 `0`：`44 tests, 0 failures, 0 errors, 0 skipped`，`BUILD SUCCESS`；Flyway V1–V11 成功应用。构建中仅有已有的 deprecated API 与 Mockito dynamic-agent 警告。

审查收紧后的定向 Java 验证：

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=PrivacyAcceptanceTest,AsyncRecoveryTest' test
```

夹具修复后的退出码 `0`：`5 tests, 0 failures, 0 errors, 0 skipped`。可核对的审计/结构验证数量：`PrivacyAcceptanceTest` 2 项（1 个精确候选 metadata 白名单与 1 个追加式拒绝更新检查）、`MemoryHistoryIntegrationTest` 14 项、`AsyncRecoveryTest` 3 项（含 1 个精确 requeue metadata 白名单）、`ArchitectureTest` 5 项；均包含在上述 Java 44 测试选择器中。

## 延后项与限制

- 未实施 M5 的迁移、影子读、切换、回滚或故障演练。
- 未把本地兼容事件升级为长期记忆、事实来源、公开接口或数据库权威。
- 未作外部服务、真实凭据、生产数据库或部署操作。
- 仍须由根 Agent 依序完成 M4 余项、只读代码审查、阶段门禁和状态记录后，才能判断后续阶段。
## M4-8R full-Maven fixture correction (2026-07-22)

This evidence-maintenance correction remains limited to four Java test fixtures/test-only wiring points. M4 remains **OPEN**. M5 is **not started** and this report does not authorize it.

### RED and root cause

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=MemoryEventConsumerTest,RedisOutboxPublisherTest,MemoryEventGrpcServiceTest,EventIngestIntegrationTest' test
```

Exit code: `1`; `48 tests`, `2 failures`, `14 errors`, `0 skipped`.

- Direct `SENSITIVE` rows in `MemoryEventConsumerTest` and `RedisOutboxPublisherTest` omitted V9's required locally protected payload metadata: key reference, nonce, `AES-256-GCM`, and crypto version `1`.
- `MemoryEventGrpcServiceTest` and `EventIngestIntegrationTest` created source seals through a `PayloadProtector`, but constructed `SubmitMemoryEventsUseCase` with its disabled source-envelope store; the intended source-envelope write therefore surfaced Java `UNAVAILABLE`.
- After the V9 fixture shape was repaired, `MemoryEventConsumerTest` exposed its previously masked SENSITIVE candidate fixture path; its two direct adapter compatibility constructors were disabled by design. The test now provides the same existing local test protector to both candidate and history repositories.

Production constraints, migrations, schemas, dependencies, and public contracts were not changed.

### Changed files

- `services/memory-service/src/test/java/com/yilan/memory/application/async/MemoryEventConsumerTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/adapter/redis/RedisOutboxPublisherTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/adapter/grpc/MemoryEventGrpcServiceTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/application/event/EventIngestIntegrationTest.java`
- `docs/评测与验收/追踪报告/memory_m4_privacy_acceptance.md`

The direct SENSITIVE fixtures now use the existing `InMemoryTestKeyProvider`/`PayloadProtector` pattern and persist the resulting V9 metadata. Source-seal test use cases use the existing `InMemoryInteractionPayloadKeyEnvelopeStore` via the five-argument constructor.

### GREEN evidence

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=MemoryEventConsumerTest,RedisOutboxPublisherTest,MemoryEventGrpcServiceTest,EventIngestIntegrationTest' test
```

Exit code: `0`; `48 tests`, `0 failures`, `0 errors`, `0 skipped`.

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml '-Dtest=PrivacyAcceptanceTest,MemoryHistoryIntegrationTest,AsyncRecoveryTest,ArchitectureTest,NoPlaintextLeakTest,CachePrivacyTest,ConsentServiceTest,MemoryManagementServiceTest,ForgetServiceTest,DeletionProcessorTest,RetentionSchedulerTest,ReconfirmationSchedulerTest' test
```

Exit code: `0`; `44 tests`, `0 failures`, `0 errors`, `0 skipped`; Testcontainers applied Flyway V1--V11.

Pending root-agent independent complete Maven gate and review: M4 remains **OPEN**; `M5 may start: false`.

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml clean
```

Exit code: `0`; `BUILD SUCCESS`.

## M4-8R2 stage-closure remediation and final local gate (2026-07-23)

M4 is **CLOSED**.  The final M4 read-only review returned **APPROVE** with no P0/P1/P2 findings; M5 was not started before this record.

### Closed defects

- Current consent reads now require an `ACTIVE` learner subject.  Full forget hides historical policy from both repository lookup and the existing disabled version-zero `ConsentService.current` view.
- The frozen `PUT /v1/me/memory-consent` now persists its required idempotency behavior in V12.  `consent_action_receipt` contains only UUID/FK, SHA-256 key/request digests, result revision and timestamp; it is unique per subject/key, append-only, and protected by the existing authority gate.  Same canonical request replays the original policy without a new policy/epoch/outbox/cache invalidation; changed same-key body or `If-Match` returns the existing `IDEMPOTENCY_CONFLICT`; full forget prevents a receipt replay.
- RAG audit summaries are value-sanitized at construction and Markdown-export boundaries.  Only finite value domains, bounded numeric values and opaque candidate identifiers remain; free-form missing/contradiction/replan values are discarded.  Existing top-level trace and RAG-audit field names are unchanged.

### Fresh verification

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml clean test
```

Exit code: `0`; Surefire: `341 tests`, `0 failures`, `0 errors`, `0 skipped`; Flyway applied V1--V12 in the authority integration tests.

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests\integration\memory_privacy tests\integration\answer_pipeline\test_memory_cannot_be_fact_source.py tests\integration\voice_loop\test_voice_trace_privacy.py -q
& .\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests\contracts\test_memory_proto_contract.py tests\integration\app_loop\test_cli_pipeline.py tests\integration\app_loop\test_answer_contract_compatibility.py tests\integration\memory_service\test_java_failure_answer_continues.py tests\integration\answer_pipeline\test_memory_cannot_be_fact_source.py -q
```

Exit code: `0`; `7 passed` for the M4 privacy matrix and `26 passed` for upstream public-answer, Java-unavailable continuity and memory-not-fact regression.

```powershell
$env:JAVA_HOME='D:\APP\IntelliJ IDEA 2025.1\jbr'
& services\memory-service\mvnw.cmd -f services\memory-service\pom.xml clean
$env:PYTHONDONTWRITEBYTECODE='1'
& .\.venv\Scripts\python.exe scripts\check_memory_workspace_cleanliness.py --stage m4 --check
```

Both commands exited `0`; Maven reported `BUILD SUCCESS` and cleanliness reported `memory workspace clean for stage m4`.

### Harness and deferrals

- Harness verdict: compliant. PostgreSQL + pgvector remains the sole long-term authority; Redis/Neo4j stay non-authoritative and Python has no authority credential or promotion right.
- No public Proto/OpenAPI/Python field, route, real key, production database/service, deployment, Compose/Kubernetes operation or M5 implementation was added.
- Production KMS/backup deployment remains explicitly deferred. `M5 may start: true`.
