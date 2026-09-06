# harness.md — 翼览无余修复约束规范（R0 审阅修复专项逐任务白名单）

> 本文件是 R0 审阅修复专项的逐任务文件白名单与验证检查项，由 `docs/项目总控/harness.md` 的“R0 审阅修复专项门禁”段引用。
> 治理层级：本逐任务白名单是 R0 任务修改范围的唯一权威；同时 R0 任务触及的每个模块仍须遵守该模块原 P harness 的不变量（如 P1 Prompt 边界、P2 记忆边界、P3 证据边界、P5 自检边界、P7 语音边界、P8 评测边界）。
> 来源：《最终修复计划.md》《task.md》《spec.md》
> 目的：每个任务定义文件白名单、回滚、验证检查、依赖/架构约束，禁止越权改动。
> 引用：任务 ID 与 `task.md`/`spec.md` 一一对应；每任务开始前必读本文件对应小节。

---

## 一、全局硬约束（适用于所有任务，不可越权）

### G1 架构与依赖
- **禁止引入新依赖**：`pyproject.toml` 仅允许 T4-1 的元数据/分组调整与 T0-7 可能的版本号修正；不新增任何运行期依赖包。
- **禁止改变现有架构模式**：不重构 SQLite 全局锁架构（连接池/aiosqlite）、不接 FastAPI/HTTP 层、不集成 OCR、不切 Java memory-service 灰度（保持 LOCAL 口径）。
- **禁止绕过既定接口直接调底层模块**：遵守 AGENTS.md 第 12 条；Agent 调度/RAG/记忆/业务模块不得混写在同一文件。
- **禁止硬编码核心业务规则**：所有别名/白名单/阈值须配置化（T3-5 落地）。
- **禁止跳过阶段或工作包**：按 P0→P5 与阶段零→五顺序；G 工作包受原 P harness 与本文件双重约束。

### G2 改动范围与回滚
- **单次提交不得超出当前任务定义的边界**：本任务"文件白名单"之外的文件一律不动。
- **改动前必须保留原始代码备份或确保可回滚**：每任务开始前 `git stash` 或 `git commit` 当前状态（项目已在 git 下）；高风险改动先 `cp` 到 `tmp/backup/<task_id>/<file>.bak`。
- **越权改动=立即回滚**：发现触碰白名单外文件，立即 `git checkout -- <file>` 回滚并在 STATUS.md 记录违规。
- **每步改动后须通过该任务的验证检查项**（见各任务"验证检查"），不带着失败进下一步。

### G3 文档与日志
- 每任务完成后必须更新 `docs/项目总控/STATUS.md`：阶段编号/任务 ID/修改文件/新增文件/删除文件/测试命令/测试结果/是否违反 harness/未完成事项/下一任务可否开始（AGENTS.md"每阶段完成标准"）。
- 若文档信息不足，在 STATUS.md 记"待确认"，不凭空扩展需求（AGENTS.md 第 8 条）。
- 测试连续三次失败且无法定位 → 停止（AGENTS.md 自动开发停止条件 1）。

### G4 安全边界
- 不允许访问密钥、真实生产数据库、真实外部服务（AGENTS.md 停止条件 6）。
- 真实 DeepSeek 线上验收仍属后续范围，本次仅 mock 闭环 + 单测隔离。
- `.workbuddy/` 目录是项目数据目录，非临时缓存，**禁止删除**。

---

## 二、任务文件白名单与验证检查

> 格式：任务 ID | 允许修改文件 | 禁止触碰（示例） | 验证检查项 | 回滚策略
> 白名单外的文件视为禁止触碰。

### 阶段零 · 事实核查

| 任务 | 允许修改文件 | 禁止触碰 | 验证检查 | 回滚策略 |
|---|---|---|---|---|
| T0-1 | 无（只读）；临时脚本可写 `tmp/verify_*.py` | src/、tests/、configs/ | 输出证实/证伪结论+行号 | 只读无需回滚；tmp 脚本不入库 |
| T0-2 | 同上 | 同上 | 输出冲突判定+task.md 原文引用 | 同上 |
| T0-3 | `tmp/verify_semantic_check.py` | src/self_check/ 实际代码 | 释义型 claim 判定结果 | 删除 tmp 脚本 |
| T0-4 | `tmp/verify_prompt_messages.py` | src/prompts/ 实际代码 | 最终 messages 是否含 query | 同上 |
| T0-5 | 无 | src/ | 读写点对照输出 | 只读 |
| T0-6 | 无 | src/feedback/ | grep+读结论 | 只读 |
| T0-7 | `tmp/verify_venv/`（临时 venv） | pyproject.toml（本任务不改） | uv sync 安装结果 | 删除 tmp venv |
| T0-8 | 无 | src/knowledge/ | cross_encoder 是否传入结论 | 只读 |

### 阶段一 · 阻断修复

| 任务 | 允许修改文件 | 禁止触碰 | 验证检查 | 回滚策略 |
|---|---|---|---|---|
| T1-1 | `scripts/generate_terms_graph_data.py`；可选 `scripts/_archive/`（新增）、`scripts/_archive/README.md`（新增） | src/、tests/ | `python -m compileall -q src scripts` 退出码 0 | git checkout scripts/；或移回原位 |
| T1-2 | `tests/unit/services/test_deepseek_client.py`；可选 `tests/conftest.py` | src/services/ | 无宿主密钥下 `pytest tests/unit/services/test_deepseek_client.py -q` 0 failed；无编码/structlog 警告 | git checkout tests/ |
| T1-3 | `configs/mock/`（新增目录与文件）、`scripts/validate_deployment.py`、`README.md`、`docs/评委速览.md` | src/、tests/、pyproject.toml | 干净环境 `validate_deployment.py --profile mock` 退出码 0；README/速览无矛盾表述 | git checkout；删 configs/mock/ |
| T1-4 | `src/prompts/assembler.py` | 其他 prompts 文件、runtime.py | 现有 prompt 单测全绿；缓存不再命中 | git checkout src/prompts/assembler.py |
| T1-5 | `src/prompts/assembler.py`、`src/prompts/runtime.py`、`configs/prompts.yaml`、`src/voice/terminology.py`、`tests/unit/prompts/`（新增） | T1-4 之外的 prompts 改动 | 不同 query/output_contract/会话 bundle key 不同；messages 含 query；mock 路径测试绿 | git checkout 上述文件；删新增测试 |
| T1-6 | `src/self_check/semantic_alignment.py`、`src/self_check/service.py`、`tests/unit/self_check/`（新增） | 其他 self_check 文件、contracts.py | 释义型 claim 不再判 UNSUPPORTED；既有子串测试绿；mock 降级不崩 | git checkout |
| T1-7 | `src/memory/retrieval.py`、`tests/unit/memory/`（新增） | 其他 memory 文件、controller.py | 新权重单测通过；`pytest tests/unit/memory -q` 0 failed | git checkout |
| T1-8 | `src/feedback/rewriter.py`、`tests/unit/feedback/`（新增） | checkpoint_store.py（T2-10 才动）、app_pipeline.py（T0-6 证实 validate_rewrite 已调用，本任务不动 app_pipeline） | 改写后通过自检；四分支不进回退循环；Simple:/表格子项测试通过 | git checkout |
| T1-9 | `src/generation/evidence_sketch.py`、`tests/integration/` 或 `tests/unit/generation/`（新增） | evidence_package.py、retrieval_controller.py | builder→sketch 集成测试通过；高分证据优先保留 | git checkout |
| T1-10 | `src/voice/edge_tts.py`、`tests/unit/voice/`（新增） | orchestrator.py、websocket_server.py（T2-11/T3-8 才动） | 模拟 hang 10s 内中断；barge-in 即时生效；voice 单测绿 | git checkout |

### 阶段二 · 高优修复

| 任务 | 允许修改文件 | 禁止触碰 | 验证检查 | 回滚策略 |
|---|---|---|---|---|
| T2-1 | `src/evaluation/runtime_configs.py`、`configs/evals.yaml`、`scripts/run_eval.py`、`docs/评测与验收/`（新增报告） | src/knowledge/、src/services/ | 断网 text_smoke 退出码 0 且 <5s；超 5s 非 0 | git checkout；删新增报告 |
| T2-2 | `src/agent/context_resolution.py`、`src/agent/langgraph_runtime.py`（仅路由函数）、`tests/integration/agent/`（新增） | langgraph_checkpointer.py、graph_contracts.py | 多候选进 CLARIFY；单候选绑定；集成测试绿 | git checkout |
| T2-3 | `src/agent/langgraph_runtime.py`（仅 prefetch 段）、`configs/app.yaml` 或 `configs/rag.yaml` | 其他 runtime 段 | 模拟 hang 超时回退成功；正常路径不受影响 | git checkout |
| T2-4 | `src/services/deepseek_client.py`（仅 complete 同步入口） | model_runtime.py（T2-6 才动）、stream_async（T2-5 才动） | 事件循环内调用不崩；同步 CLI 路径不变 | git checkout |
| T2-5 | `src/services/deepseek_client.py`（stream_async）、`tests/unit/services/test_deepseek_client.py` | T2-4 已改的 complete 入口（避免冲突，须先完成 T2-4） | 4xx/5xx 不污染流；SSE 解析正确 | git checkout |
| T2-6 | `src/services/deepseek_client.py`（重试段）、`src/services/retry_policy.py`（删除或合并）、`configs/providers.yaml`、`tests/unit/services/`、`src/services/model_runtime.py`（structured 重试） | T2-5 已改的 stream_async | 模拟 5xx/超时重试成功；429 按 Retry-After；受控 env 单测绿 | git checkout |
| T2-7 | `src/services/deepseek_client.py` 或 `src/services/model_runtime.py`（缺密钥分支）、`README.md`、`docs/评委速览.md` | T2-6 已改重试段 | 无宿主密钥 text_smoke 可跑 mock 闭环；README 可实测复现 | git checkout |
| T2-8 | `src/knowledge/retrieval_executor.py`、`configs/rag.yaml`、`src/agent/langgraph_runtime.py`（仅降级证据包处理段）、`tests/integration/rag/`（新增） | retrieval_controller.py、retrieval_planner.py | dense 失败/keyword 成功用例通过；降级证据包可审计 | git checkout |
| T2-9 | `src/knowledge/retrieval_planner.py`、`configs/rag.yaml`（fallback 配置） | retrieval_executor.py（T2-8 已改） | scene 关闭部件提问有语义召回；D15 改善 | git checkout |
| T2-10 | `src/app/cli.py`、`src/feedback/checkpoint_store.py`、`src/services/app_pipeline.py`（run_feedback 暴露）、`data/feedback.sqlite3`（运行期生成，不入库） | rewriter.py（T1-8 已改） | feedback CLI 可用；重启后 checkpoint 可恢复（TTL 内） | git checkout；删 feedback.sqlite3 |
| T2-11 | `src/voice/transport.py`、`src/voice/websocket_server.py`（仅音频上限段）、`src/voice/vad.py`、`configs/voice.yaml`、`tests/unit/voice/`（新增） | edge_tts.py（T1-10 已改）、orchestrator.py（T3-8 才动） | 超长/畸形帧/伪造 energy 三场景测试通过 | git checkout |
| T2-12 | `src/agent/langgraph_runtime.py`（resume 段）、`src/agent/graph_contracts.py`、`src/agent/runtime_settings.py` | T2-13 才动的终态清理段 | 反复重试损坏 checkpoint 熔断退出 | git checkout |
| T2-13 | `src/agent/langgraph_runtime.py`（终态清理段） | T2-12 已改的 resume 段 | 成功响应不被清理失败覆盖；错误路径仍清理 | git checkout |
| T2-14 | `src/knowledge/reranking.py`、`configs/rag.yaml`、`README.md`、`docs/评委速览.md` | retrieval_controller.py（T0-8 确认未传入） | 模型不可用纯特征排序；分数不被拉向 1.0；文档称"特征重排" | git checkout |
| T2-15 | `src/services/embedding_provider.py` | model_runtime.py、deepseek_client.py | 运行时降级 trace 标 mock；并发加载不重复 | git checkout |
| T2-16 | `src/safety/policy.py`、`src/input/query_understanding.py`、`tests/unit/safety/`（新增） | governance.py、temporal.py（T3-9 才动） | "操作原理"不误拒；legacy 无残留调用 | git checkout |
| T2-17 | `src/generation/pipeline.py`、`src/self_check/service.py`、`contracts/`（相关 proto/md）、`tests/unit/generation/`（新增） | decision_router.py、drafting.py | RETRIEVE_MORE 可构造指令；契约测试绿 | git checkout |
| T2-18 | `src/self_check/contracts.py`、`src/self_check/service.py` | T2-17 已改的 service 段 | medium 不阻塞；critical/high 仍阻塞 | git checkout |
| T2-19 | `README.md`、`docs/评委速览.md`、`docs/评测与验收/release_acceptance.md`、`docs/评测与验收/demo_audit_report.md`、`pyproject.toml`（仅 requires-python） | src/、tests/、configs/（除版本声明） | 换机可一键复验；阶段/版本/路径无冲突 | git checkout |
| T2-20 | `src/knowledge/retrieval_controller.py`（chunk 防御 + metadata 审计段） | retrieval_executor.py、retrieval_planner.py | parent 缺失不崩；trace 审计真实 | git checkout |

### 阶段三 · 中优修复

| 任务 | 允许修改文件 | 禁止触碰 | 验证检查 | 回滚策略 |
|---|---|---|---|---|
| T3-1 | `src/input/query_understanding.py`、`src/prompts/assembler.py`、`src/prompts/runtime.py`、`tests/unit/input/`（新增） | T1-5 已改的 query 注入段（避免冲突，先完成 T1-5） | 超长截断；canary 不触发指令 | git checkout |
| T3-2 | `src/knowledge/retrieval_controller.py`（ingest 段）、`tests/integration/rag/`（新增） | T2-20 已改的 chunk 防御段 | 入库后立即可检索；既有 chunk 不重复 embedding | git checkout |
| T3-3 | `src/agent/langgraph_checkpointer.py`、`src/agent/langgraph_runtime.py`（锁+审计+异常分支段） | T2-12/T2-13 已改段 | 锁表不膨胀；审计失败不拖垮；异常分支清 checkpoint | git checkout |
| T3-4 | `src/self_check/contracts.py`、`src/agent/langgraph_runtime.py`、`src/agent/actions.py` | T2-18 已改的 PASS 过滤段 | check_report_id 唯一；终态动作码规范；错误路径 0 分 | git checkout |
| T3-5 | `src/self_check/service.py`、`src/generation/decision_router.py`、`src/agent/actions.py`、`src/agent/context_resolution.py`、`src/knowledge/query_rewriter.py`、`src/knowledge/fusion.py`、`src/services/embedding_provider.py`（EmbeddingCache 删除）、`src/agent/artifact_rehydrator.py`、`src/observability/logging_config.py`、新增 `configs/voice_terminology.yaml`、`configs/no_fact_conversations.yaml` | 多处，须 grep 确认无引用后删 | 别名/白名单从配置加载；构造无文件 I/O；死代码无残留；全量绿 | git checkout；删新增配置 |
| T3-6 | `src/knowledge/indexes/vector_store.py`、`src/knowledge/ingestion/text_ingestor.py`、`src/knowledge/retrieval_controller.py`（top_k 段）、`configs/rag.yaml`、`src/knowledge/config.py` | T3-2 已改 ingest 段 | ANN 损坏回退不突变；恢复轮扩容有效；分块校验暴露错误配置 | git checkout |
| T3-7 | `src/generation/citation_binding.py`、`src/generation/claim_normalizer.py`、`src/self_check/claim_completeness.py`、`src/generation/evidence_sketch.py`、`src/self_check/service.py` | T1-6 已改的语义校验段 | 引用语义对应；claim 口径统一；缺失降级 PARTIALLY | git checkout |
| T3-8 | `src/voice/orchestrator.py`、`src/voice/whisper_asr.py`、`src/voice/websocket_server.py`、`src/voice/barge_in.py`、`src/voice/vad.py`、`src/voice/transport.py`、`src/voice/edge_tts.py`、`configs/voice.yaml` | T1-10/T2-11 已改段 | 长推理可收帧/响应取消；多会话不互阻；idle_timeout 不误触发 | git checkout |
| T3-9 | `src/memory/repository.py`、`src/memory/outbox.py`、`src/memory/controller.py`、`src/memory/cutover.py`、`src/safety/governance.py`、`src/safety/temporal.py`（或 temporal 模块） | T1-7 已改的 retrieval.py | 并发写不锁死；候选可回滚；远端故障可查；时间戳容错 | git checkout |
| T3-10 | `src/services/app_pipeline.py`、`src/voice/websocket_server.py`（健康段） | T2-11 已改音频上限段 | 健康检查反映真实；启停不延迟释放 | git checkout |

### 阶段四 · 低优/文档

| 任务 | 允许修改文件 | 禁止触碰 | 验证检查 | 回滚策略 |
|---|---|---|---|---|
| T4-1 | `.gitignore`、`tmp/`（清理）、`.env.example`、`pyproject.toml`（元数据+dev 分组）、`.github/workflows/ci.yml`（新增）、`README.md` | src/、tests/、configs/（除 mock 已有） | 干净环境 uv sync 成功；CI 模拟通过 | git checkout；删新增 workflow |
| T4-2 | `README.md`、`docs/评委速览.md`、`docs/评测与验收/`、`AGENTS.md`（仅对外表述段，若涉及） | src/、tests/ | 表述与实现一致，无过度宣称 | git checkout |
| T4-3 | `src/knowledge/retrieval_controller.py`（缓存段）、`src/knowledge/indexes/keyword_index.py`、`src/knowledge/indexes/scene_index.py`、`src/knowledge/ingestion/text_ingestor.py`（pack_sentences）、`src/knowledge/indexes/vector_store.py`（except 段）、`src/knowledge/reranking.py`（特征连续化，可选）、`src/score_card.py`（可选）、`src/agent/langgraph_runtime.py`（_restore_trace_for_resume 段）、`src/generation/drafting.py`、`src/feedback/rewriter.py`（_shorten）、`scripts/_rag_bench.py`、`docs/评测与验收/评测报告/rag_100q_bench_report.md` | T3-2/T3-6 已改段 | 各子项单测/集成测试绿；bench 口径准确 | git checkout |
| T4-4 | `src/feedback/evidence_lock.py`、`src/safety/response_builder.py`（或对应）、`src/safety/asset_models.py`（或 prompts asset）、`src/prompts/repository.py`、`src/knowledge/evidence_gate.py`、`src/knowledge/ingestion/pdf_ingestor.py`、`tests/unit/safety/`、`tests/unit/feedback/`（新增） | T2-16 已改 safety 段（协调避免重复） | 各杂项单测绿；safety 有最小回归 | git checkout |
| T4-5 | `src/knowledge/evidence_policy.py`、`src/knowledge/indexes/keyword_index.py`、`configs/rag.yaml` | T4-3 已改段 | bench 前后不退化才合入 | git checkout |

### 阶段五 · 放行门禁

| 任务 | 允许修改文件 | 禁止触碰 | 验证检查 | 回滚策略 |
|---|---|---|---|---|
| T5-1 | 无（仅执行验证）；验收文档 `docs/评测与验收/release_acceptance.md`、`demo_audit_report.md`（更新时间戳） | 任何 src/tests/configs 改动 | 6 条命令全绿；验收文档与命令结果一致 | 命令失败回炉对应任务，不动新文件 |

---

## 三、跨任务冲突预防（白名单重叠高发区）

以下文件被多个任务触碰，须严格按依赖顺序串行修改，禁止并行：

| 文件 | 涉及任务（按顺序） | 冲突预防 |
|---|---|---|
| `src/services/deepseek_client.py` | T2-4 → T2-5 → T2-6 → T2-7 | 同一文件多段改，前任务完成 commit 后再开下一任务 |
| `src/agent/langgraph_runtime.py` | T2-2（路由）→ T2-3（prefetch）→ T2-8（降级证据）→ T2-12（resume）→ T2-13（终态清理）→ T3-3（锁+审计）→ T3-4（终态动作码）→ T4-3（_restore_trace_for_resume） | 按段隔离，每任务只动对应段，commit 后再开 |
| `src/knowledge/retrieval_controller.py` | T2-20 → T3-2 → T3-6 → T4-3（缓存段） | 同上 |
| `src/prompts/assembler.py` | T1-4 → T1-5 → T3-1 | 同上 |
| `src/self_check/service.py` | T1-6 → T2-17 → T2-18 → T3-7 | 同上 |
| `configs/rag.yaml` | T2-1（embedding mock）→ T2-3 → T2-8 → T2-9 → T2-14 → T3-6 → T4-5 | 配置段隔离，避免覆盖 |
| `README.md` / `docs/评委速览.md` | T1-3 → T2-7 → T2-14 → T2-19 → T4-1 → T4-2 | 文档类集中在阶段二/四处理，避免频繁冲突 |
| `src/voice/websocket_server.py` | T2-11（音频上限）→ T3-8（并发）→ T3-10（健康） | 同上 |
| `tests/unit/safety/` | T2-16 → T4-4 | T2-16 先建最小用例，T4-4 补全 |

---

## 四、停止条件（AGENTS.md 自动开发停止条件映射）

遇到以下情况必须停止，不要继续：
1. 测试连续三次失败且无法定位原因。
2. 需要用户确认核心架构选择（如 T2-7 决定实现 vs 改文档、T2-14 决定接入 vs 不接入 cross-encoder）。
3. task.md / spec.md / harness.md 三者出现明显冲突。
4. 需要新增大型依赖（pyproject 主依赖），但文档没有明确允许（T0-7 cryptography 仅版本号修正不算）。
5. 需要修改安全边界、核心目录结构或阶段目标。
6. 需要访问密钥、真实生产数据库、真实外部服务。
7. 当前实现会破坏已有接口契约（如 T2-17 改 generate 返回结构，须同步更新所有调用方与契约测试）。

---

## 五、harness 自检清单（每任务收尾前过一遍）

- [ ] 本次提交的所有文件均在"任务文件白名单"内？
- [ ] 改动前已 `git stash` 或 `git commit` 保留可回滚点？
- [ ] 每步改动后跑了对应"验证检查项"？
- [ ] 未引入新依赖、未改架构模式、未绕过既定接口？
- [ ] 未硬编码核心业务规则（或在 T3-5 已配置化）？
- [ ] STATUS.md 已更新（阶段/任务 ID/文件清单/测试命令/结果/harness 合规/未完成/下一任务可否开始）？
- [ ] 与白名单重叠文件的并行任务已串行协调（commit 后再开）？
