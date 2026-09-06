# harness.md — 翼览无余系统优化修复约束规范

> 本文档是 `task.md`（任务清单）、`spec.md`（执行步骤）的约束边界。每个 `§T##` 与任务/执行步骤一一对应。
> 三件套引用关系：`task.md §T##`（目标/验收）↔ `spec.md §T##`（步骤）↔ `harness.md §T##`（文件约束）。
> 本计划独立于 `docs/项目总控/harness.md` 原有 P0–P8 / G0–G8 约束，不覆盖原 harness；本计划任务同时受原 harness 与本文件约束，冲突时以原 harness 为准并停止待确认。

---

## 总则

### 1. 公共契约不可破坏（硬约束，沿用原 harness）
以下契约必须保持向后兼容，任何任务不得破坏：
- `TextQueryRequest` / `TextQueryResponse.to_dict()` / `AppPipeline.run_text_query()` 签名与返回
- `scene_state` / `memory_context` / `evidence_package` / `answer_envelope` / `check_report` / `run_trace` 统一契约
- `evidence_package` 作为航空事实唯一来源（记忆/反馈/Prompt/模型常识均不可作为事实来源）
- LangGraph checkpoint 与 `knowledge.sqlite3` / `memory.sqlite3` 隔离，只存最小脱敏状态

### 2. 禁止项（硬约束，沿用原 harness）
- 禁止引入 LangChain / LangSmith / Agent Server
- 禁止引入云服务、真实模型 provider 连接（本地模型除外）
- 禁止引入独立向量数据库服务（sqlite-vec 是 SQLite 扩展，非独立服务，允许）
- 禁止绕过 `evidence_package` 生成航空事实
- 禁止硬编码核心业务规则（阈值/Provider/模型名须配置化）
- 禁止把 Agent 调度、RAG、记忆系统、业务模块混写在同一文件（T09 拆分正是为此）
- 禁止真实密钥 / 生产数据库 / 真实外部服务
- 禁止为通过测试而删除测试

### 3. 受控依赖白名单（本计划设立，默认禁止其余新依赖）
默认禁止引入任何新依赖。以下依赖经评审列入白名单，仅限指定任务使用，且必须装入受控 venv（不入全局）：

| 依赖 | 用途 | 允许任务 | 是否本地 | 是否违反原 harness |
|------|------|----------|----------|---------------------|
| `httpx` | 异步 HTTP 客户端，替代 urllib | T01 | 是（本地库） | 否（非云服务） |
| `structlog` | 结构化日志 | T05 | 是（本地库） | 否 |
| `jieba` | 中文分词 | T11 | 是（本地库） | 否 |
| `sqlite-vec` | SQLite ANN 扩展 | T03 | 是（SQLite 扩展，非独立向量库） | 否（非向量数据库服务） |
| `bge-reranker-base`（经 sentence-transformers） | 本地 cross-encoder 重排 | T07 | 是（本地模型） | 否（非云 reranker） |

**白名单外的新依赖**：一律禁止。若任务执行中发现必需新依赖 → 触发停止条件，记入 STATUS 待确认，不得擅自引入。

### 4. 备份与回滚（每个任务强制）
- 改动前：对将被修改的文件用 `cp` 创建备份至 `.workbuddy/backup/<task_id>/<original_path>`，保留原相对路径。
- 改动后：若验证失败，优先从备份回滚；回滚后须在 STATUS 记录回滚原因。
- 数据库改动（T03/T13/T14）：改动 schema 前对 `data/processed/*.sqlite3` 做整库备份（`cp` 或 `.backup`）。
- 配置改动：原 yaml 保留注释标记变更点。

### 5. 单步验证（每个任务每步后须通过）
- 代码改动后：`pytest tests/` 相关子集全绿（不强制全量，但任务完成前须全量绿）。
- 行为等价类任务（T09/T19）：`run_trace` diff 为空。
- 性能类任务（T03/T06）：附 benchmark 数据。
- 任务完成前：全量 `pytest tests/` 绿 + 无 harness 违反自检。

### 6. 单次提交边界
- 单次提交（commit）不得超出当前任务 `§T##` 定义的允许文件清单。
- 跨文件清单的改动 → 拒绝提交，拆分。
- 一个任务可多次提交，但每次提交须在边界内且测试绿。

### 7. STATUS 记录
每个任务完成后写入 `docs/优化修复计划/STATUS.md`：任务编号、已完成项、修改文件、新增文件、删除文件、测试命令、测试结果、是否违反 harness、未完成事项、下一任务是否可开始。

### 8. 记忆系统切换冻结约束（2026-07-26 新增）
> 背景：长期记忆目标权威后端是 Java（`services/memory-service/`，PostgreSQL+pgvector），当前 `backend: local` 仍跑 Python+SQLite，处于 LOCAL→REMOTE 灰度切换中（记忆服务重构 M5 进行中）。

- 记忆服务 M5 切换完成前，**禁止对 `src/memory/` 做深度重构**：不得拆分/重写 `controller.py` 及其子系统（extractor/retriever/consolidator/conflict/governance/context/semantic_index/temporal/audit 等）。
- 仅允许不改变业务逻辑的浅层改动（如 T05 日志接入、异常处理收敛），且须保持 `MemoryPort` 协议、`AuthorityMode` 状态机、`MemoryContext`/`InteractionEvent` 契约不变。
- 任何任务触及 `src/memory/` 须在 §T## 文件清单中显式声明，并限于浅层改动。
- **注意区分**：`src/memory_worker/`（embedding/LLM worker：bge_m3/qwen_llamacpp/fake）与 `src/memory/`（记忆系统）是两个独立模块。T04/T08 改的是前者，与本冻结约束无关。
- 切换完成后，先评估 Python 侧 `memory` 模块最终职责边界，再决定是否解冻重构。

---

## 每任务允许修改文件清单

> 仅以下文件可被对应任务修改。清单外文件不得改动；若发现需改动 → 停止，记 STATUS 待确认。
> "新增"标注的文件为该任务创建，不计入"修改"。

### §T01 — LLM 客户端异步化
**允许修改**：
- `src/services/deepseek_client.py`
- `src/services/model_factory.py`
- `configs/providers.yaml`

**允许新增**：
- `tests/unit/services/test_deepseek_client.py`（骨架，T15 完善）

**禁止改动**：`src/agent/*`、`src/generation/*`（调用方须零改动）

### §T02 — 拆分全局锁
**允许修改**：
- `src/services/app_pipeline.py`
- `src/agent/langgraph_checkpointer.py`

**禁止改动**：`src/knowledge/*`（检索路径只读，不涉及锁）、公共契约

### §T03 — 引入 ANN 向量索引
**允许修改**：
- `src/knowledge/indexes/vector_store.py`
- `src/knowledge/ingestion/text_ingestor.py`
- `configs/rag.yaml`

**允许新增**：
- `scripts/bench_vector_search.py`

**数据备份**：`data/processed/knowledge.sqlite3` 整库备份（schema 变更）

**禁止改动**：`src/agent/*`、`src/services/app_pipeline.py`

### §T04 — 激活 BGE-M3 真实 embedding
**允许修改**：
- `configs/rag.yaml`
- `src/services/embedding_provider.py`
- `src/knowledge/ingestion/text_ingestor.py`
- `src/knowledge/indexes/vector_store.py`

**允许新增**：
- `scripts/calibrate_threshold.py`
- `scripts/eval_recall.py`
- `docs/优化修复计划/threshold_calibration.md`

**禁止改动**：`src/agent/*`、`src/memory/*`（记忆系统独立）

### §T05 — 接入结构化日志体系
**允许修改**：
- `src/agent/langgraph_runtime.py`（仅日志/异常相关行，不改业务逻辑）
- `src/services/app_pipeline.py`（仅日志/异常相关行）
- `src/knowledge/retrieval_controller.py`（仅日志）
- `src/generation/pipeline.py`（仅日志）
- `src/self_check/*`（仅日志）
- `src/voice/orchestrator.py`（仅日志/异常）

**允许新增**：
- `src/observability/logging_config.py`

**约束**：日志改动不得改变业务控制流（仅加日志/改异常处理），run_trace 等价。

### §T06 — 引入多级缓存层
**允许修改**：
- `src/services/embedding_provider.py`
- `src/knowledge/retrieval_controller.py`
- `src/prompts/assembler.py`

**禁止改动**：`src/agent/*`、`configs/`（除非新增缓存配置项到 rag.yaml，允许）

### §T07 — 引入 cross-encoder 二阶段重排
**允许修改**：
- `src/knowledge/reranking.py`
- `src/knowledge/fusion.py`
- `configs/rag.yaml`

**允许新增**：
- `scripts/eval_rerank.py`
- `docs/优化修复计划/rerank_eval.md`

**禁止改动**：`src/agent/*`、`src/generation/*`

### §T08 — 启用 BGE-M3 稀疏/多向量
**允许修改**：
- `src/memory_worker/providers/bge_m3.py`
- `src/knowledge/indexes/keyword_index.py`
- `configs/rag.yaml`

**允许新增**：
- `scripts/eval_sparse.py`

**禁止改动**：`src/agent/*`、`src/services/app_pipeline.py`

### §T09 — 拆分 LangGraphAgentRuntime God Object
**允许修改**：
- `src/agent/langgraph_runtime.py`

**允许新增**：
- `src/agent/nodes/base.py`
- `src/agent/nodes/context_resolution.py`
- `src/agent/nodes/retrieve_evidence.py`
- `src/agent/nodes/generate_draft.py`
- `src/agent/nodes/self_check.py`
- `src/agent/nodes/finalize.py`
- `src/agent/nodes/rewrite.py`
- `src/agent/nodes/retrieve_more.py`
- `src/agent/nodes/supervisor_route.py`
- `src/agent/artifact_rehydrator.py`

**硬约束**：行为等价，`run_trace` diff 必须为空。不得借机改业务逻辑。

### §T10 — 建立工具抽象与节点注册表
**允许修改**：
- `src/agent/langgraph_runtime.py`

**允许新增**：
- `src/agent/tool_protocol.py`
- `src/agent/node_registry.py`

**约束**：默认 DAG 行为不变。

### §T11 — 分块/分词/术语外置化
**允许修改**：
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/ingestion/text_ingestor.py`

**允许新增**：
- `configs/terminology.yaml`

**禁止改动**：`src/agent/*`、`src/services/*`

### §T12 — 上下文压缩/去重/token 预算
**允许修改**：
- `src/prompts/assembler.py`
- `configs/rag.yaml`

**禁止改动**：`src/knowledge/*`（检索结果不变，仅装配侧处理）

### §T13 — SQLite 连接池 + WAL
**允许修改**：
- `src/knowledge/repository.py`
- `src/agent/langgraph_checkpointer.py`

**数据备份**：`data/processed/knowledge.sqlite3`、`data/processed/langgraph_checkpoints.sqlite3` 备份

**禁止改动**：业务逻辑、公共契约

### §T14 — 图边批量写入消除 N+1
**允许修改**：
- `src/knowledge/repository.py`

**约束**：仅改 `write_graph_edge`/`write_graph_edges_batch` 相关方法，不动其他方法。

### §T15 — 补充生产路径测试覆盖
**允许修改**：无生产代码改动（仅测试）

**允许新增**：
- `tests/unit/services/test_deepseek_client.py`
- `tests/integration/test_app_pipeline_concurrency.py`
- `tests/e2e/scenarios/*`（≥ 3 个新场景）

**约束**：不得为通过测试修改生产代码（若发现生产 bug → 单独立任务）。

### §T16 — LLM 驱动查询改写
**允许修改**：
- `src/knowledge/query_rewriter.py`
- `configs/rag.yaml`

**约束**：默认关闭，不影响确定性改写稳定路径。

### §T17 — 统一 KeywordIndex
**允许修改**：
- `src/knowledge/indexes/keyword_index.py`

**约束**：行为等价或更优，对比测试通过。

### §T18 — 多 Agent 协作可行性评估
**允许新增**：
- `docs/优化修复计划/multi_agent_assessment.md`

**禁止改动**：任何代码（纯文档任务）。

### §T19 — 简化双轨制恢复
**允许修改**：
- `src/agent/artifact_rehydrator.py`
- `src/agent/langgraph_runtime.py`

**允许新增**：
- `docs/优化修复计划/rehydrate_simplification.md`

**硬约束**：若实施，`run_trace` diff 必须为空。

---

## 通用验证检查项（每任务完成前自检）

- [ ] 改动文件均在 `§T##` 允许清单内
- [ ] 改动前已备份（代码 + 数据库 schema）
- [ ] 未引入白名单外依赖
- [ ] 未破坏公共契约（签名/返回/契约字段）
- [ ] 未硬编码核心业务规则
- [ ] `pytest tests/` 全量绿
- [ ] 行为等价类任务：`run_trace` diff 为空
- [ ] 性能类任务：附 benchmark 数据
- [ ] 日志脱敏（无隐私泄露）
- [ ] STATUS.md 已记录

---

## 停止条件（沿用原 AUTO_DEV + 本计划补充）

1. 测试连续三次失败且无法定位原因 → 停止
2. 需要白名单外新依赖 → 停止，提交评审
3. 公共契约将破坏 → 停止
4. 需要真实密钥 / 生产服务 → 停止
5. 需要改动 `§T##` 清单外文件 → 停止，记 STATUS 待确认
6. 行为等价类任务 `run_trace` diff 非空且无法收敛 → 停止
7. 与原 `docs/项目总控/harness.md` 冲突 → 停止，以原 harness 为准

---

文档完。任务清单见 `task.md`，执行步骤见 `spec.md`。
