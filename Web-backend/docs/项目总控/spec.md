# 翼览无余 AI 智能导师系统开发执行规格

## P7 实时语音专项执行说明（2026-07-13）

P7 已获用户正式授权，按 `docs/7语音交互` 下的差异修复任务文档与实施计划，以 T0–T11 顺序实施。目标权威链路为：`AudioTransport -> VAD/Endpoint -> ASRProvider -> VoiceQueryNormalizer -> VoiceSessionOrchestrator -> AppPipeline -> SpokenAnswerPlanner -> TTSProvider`。barge-in 必须先取消播放，再通过 P6 公共接口解析和分流；长期偏好候选必须通过 P2 公共治理接口，voice 不得直接写 memory repository。

Provider 创建、阈值、音频参数、词典与隐私开关均由配置驱动。partial transcript 只能进入独立预检索结果，final transcript 且通过置信度、场景和 session/turn 校验后才能构造正式 `TextQueryRequest`。spoken answer 只能由完成检索、生成和 P5 自检的最终 `AnswerEnvelope` 派生。WebSocket 仅作为轻量协议适配层，唯一新增运行时依赖为 `websockets==15.0.1`；真实语音 Provider 与生产部署不在本专项范围。

## 文档依据

本文与 `task.md` 使用同一套 P0 到 P8 阶段编号，说明每个阶段如何工程化实现。所有实现动作均追溯到《翼览无余智能体模块设计文档V2_增加语音交互.docx》中的模块边界、统一状态机、数据契约、RAG 证据核心、记忆治理和语音交互补充。

## 需要调用的 skill

后续让 Codex 执行本文时，不要求每个阶段预先固定 skill，而是先调用 `using-superpowers`，由它根据当前阶段任务、实现语言、文件类型、错误状态和可用工具动态判断还需要哪些 skill。为满足工作区 AGENTS.md 中“Codex 提示词必须明确声明 skill”的要求，本提示词只声明全局必需 skill：

1. `using-superpowers`：每次开始阶段实现前先调用，用于发现并调度当前阶段真正需要的 skill。
2. `writing-plans`：当阶段涉及多文件、多模块或多步骤实现时调用，用于生成文件级实施计划。
3. `systematic-debugging`：遇到 bug、测试失败、检索/生成/自检/语音状态机异常时调用。
4. `verification-before-completion`：阶段完成前必须调用，用于运行测试、schema 校验和文档一致性检查。
5. `openai-docs`：仅当阶段涉及 OpenAI API、Realtime、Speech to Text、Text to Speech、Structured Outputs 或模型配置时调用，用于查询官方最新文档。

除上述全局规则外，本文不在每个阶段单独指定 skill。阶段执行者必须在进入具体实现前依据 `using-superpowers` 的判断动态补充调用相关 skill，例如向量检索、语音转写、LangGraph 编排或数据处理类 skill。

## 推荐工程目录结构

```text
project-root/
  README.md
  pyproject.toml
  .env.example
  configs/
    app.yaml
    providers.yaml
    prompts.yaml
    memory.yaml
    rag.yaml
    voice.yaml
    evals.yaml
  src/
    app/
      main.py
      api/
        http_routes.py
        ws_routes.py
        schemas.py
    core/
      actions.py
      contracts.py
      errors.py
      state_machine.py
      tracing.py
      settings.py
    input/
      query_object.py
      query_understanding.py
      scene_binding.py
      voice_query_normalizer.py
    prompts/
      asset_models.py
      repository.py
      router.py
      assembler.py
      evaluation.py
    memory/
      schemas.py
      controller.py
      repository.py
      extractor.py
      governance.py
      conflict.py
      consolidation.py
      semantic_index.py
      retrieval.py
      context.py
      temporal.py
      audit.py
    knowledge/
      schemas.py
      source_registry.py
      scene_object_registry.py
      ingestion/
        text_ingestor.py
        pdf_ingestor.py
        visual_ingestor.py
        scene_ingestor.py
      indexes/
        keyword_index.py
        vector_index.py
        hybrid_index.py
        visual_page_index.py
        graph_index.py
      retrieval_controller.py
      evidence_package.py
      evidence_gate.py
    generation/
      answer_types.py
      evidence_sketch.py
      planner.py
      generator.py
      citation_binding.py
      display_blocks.py
    self_check/
      claim_extractor.py
      evidence_alignment.py
      scene_checker.py
      multimodal_checker.py
      boundary_checker.py
      score_card.py
      decision_router.py
    feedback/
      feedback_event.py
      parser.py
      checkpoint_store.py
      evidence_lock.py
      rewrite_planner.py
      rewriter.py
      delta_map.py
    voice/
      transport.py
      session_state.py
      vad.py
      asr.py
      terminology.py
      tts.py
      barge_in.py
      metrics.py
    services/
      llm_provider.py
      embedding_provider.py
      storage.py
      clock.py
    utils/
      ids.py
      json_schema.py
      text_normalize.py
  tests/
    unit/
      core/
      prompts/
      memory/
      knowledge/
      generation/
      self_check/
      feedback/
      voice/
    integration/
      rag_pipeline/
      answer_pipeline/
      feedback_loop/
      voice_loop/
    e2e/
      fixtures/
      scenarios/
  data/
    raw_sources/
      .gitkeep
    processed/
      .gitkeep
    demo/
      .gitkeep
  scripts/
    ingest_sources.py
    seed_demo_data.py
    run_eval.py
    export_trace_report.py
  docs/
    architecture.md
    api_contracts.md
    eval_plan.md
```

## 全局执行原则

- 事实只来自 `evidence_package`，记忆只影响个性化表达和指代解析，Prompt 只影响讲法和输出格式。
- 所有模块通过 schema 对象传递数据，不传递未结构化大字典。
- 所有阶段必须记录 `run_trace`，至少保留阶段输入摘要、输出对象 id、决策、错误和耗时。
- 所有可变 Provider 都通过接口和配置注入，避免在业务模块中硬编码模型、数据库或服务商。
- `待确认` 字段不得被代码用默认事实偷偷填满，只能通过配置、测试夹具或显式降级处理。

## 回答生成专项执行说明

P0 至 P8 是已完成并保留验收日志的历史主线。回答生成专项在其后按 `G0 → G1 → G2 → G3 → G4 → G5 → G6 → G7 → G8` 执行；G 是维护工作包，不是新的 P 阶段。跨模块修改必须同时满足对应原 P 规格、原 P harness 和 G 专项门禁，且只能修改已批准实施计划在该 G 的 Task Files 中明确列出的文件。

- G0 冻结公开 request/response/answer/action/trace/CLI 契约并登记缺陷，不改变业务行为。
- G1–G4 按“基础契约和配置 → 路由与生成准备 → 模型生成主链 → 自检硬门控”的依赖顺序推进。
- G5 原子替换运行时 recovery loop；G6 只从 `FINAL_READY` 的已核验内容物化展示和语音投影。
- G7 只有在替代路径、引用清零和前序回归均通过后才能删除 legacy；G8 只做评测、发布门和文档收口。
- 任一中间提交都必须保持公开 golden contract 与已完成前序回归绿色；需要实质架构扩展、未列核心目录或大型依赖时停止并记录待确认。

## P1 Prompt Refactor Update

本节用于覆盖旧版 P1 Prompt 文档中与当前实现冲突的描述，只说明 Prompt 审计相关的现行契约。

- `src/prompts/repository.py` 已替代删除的 `src/prompts/asset_store.py`。
- Prompt 资产目录固定为 `assets/prompts/<template_id>/asset.json`、`assets/prompts/<template_id>/versions/*.json`、`assets/prompts/<template_id>/evaluations/*.json`。
- `PromptAssetRepository` 是唯一的资产、版本和评估快照加载器；运行态 `active` / `experimental` 版本必须存在已批准快照，且版本 `snapshot_id` 必须与对应评估快照 `snapshot_id` 完全一致，否则拒绝加载。
- `PromptAssembler` 返回 canonical `PromptMessageBundle`，不存在独立的 `MessageBundle` 运行时契约。
- `PromptMessageBundle.messages` 只能包含 `services.model_client.ModelMessage`，角色只允许 `system` 和 `user`。
- 常规 trace 只记录 template id、version、snapshot id、missing variables、route reason 和 injection summary，不记录完整 prompt 正文。
- Prompt 资产只定义说明风格、教学风格和输出行为，不提供航空事实；事实仍必须来自 `evidence_package`。
- 旧平铺 YAML Prompt 资产、`PromptAssetStore`、`default_prompt_assets`、以及 `role="context"` / `role: context` 消息角色都是禁止项。

## P0：工程骨架、统一状态机与数据契约

### 对应 task.md 中的阶段目标

建立可扩展项目骨架、全局状态机、统一数据对象、动作枚举、配置体系和日志追踪，使后续模块不会各自发明接口。

### 推荐工程目录结构

重点创建 `src/core/`、`configs/`、`tests/unit/core/` 和顶层工程文件。

### 需要新增或修改的文件

- `pyproject.toml`
- `.env.example`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/core/actions.py`
- `src/core/contracts.py`
- `src/core/state_machine.py`
- `src/core/errors.py`
- `src/core/tracing.py`
- `src/core/settings.py`
- `tests/unit/core/test_contracts.py`
- `tests/unit/core/test_state_machine.py`

### 核心类、函数、模块或服务设计

- `ActionDecision`：统一动作枚举。
- `AgentState`：全局状态枚举。
- `StateTransition`：记录 from_state、to_state、action、reason。
- `RunTrace`：统一运行追踪对象。
- `BaseContract`：所有 schema 的基础校验类。
- `Settings`：加载 YAML、环境变量和默认值。

### 数据流 / 调用链路

1. API 或测试入口创建 `run_id`。
2. 输入被包装为基础 `RunTrace`。
3. 状态机从 `INPUT_RECEIVED` 开始。
4. 后续阶段只允许通过 `transition(next_state, reason)` 改变状态。

### 接口设计

```text
transition(run_trace, next_state, action_decision=None, reason=None) -> RunTrace
validate_contract(payload, contract_type) -> Contract
load_settings(config_path, env) -> Settings
```

### 配置项设计

- `app.environment`
- `app.log_level`
- `app.max_state_loop`
- `providers.llm.default`
- `providers.embedding.default`
- `trace.persist_enabled`

### 错误处理方式

- schema 校验失败抛出 `ContractValidationError`。
- 非法状态跳转抛出 `InvalidStateTransitionError`。
- 配置缺失抛出 `ConfigError`，错误中必须包含缺失键名。

### 日志与监控要求

- 每次状态跳转记录 run_id、from_state、to_state、action_decision、reason、latency_ms。
- 日志不得输出敏感原始语音或用户隐私字段。

### 测试方案

- 单测覆盖所有动作枚举和状态枚举。
- 单测验证非法跳转会失败。
- 单测验证 `RunTrace` 最小字段完整。

### 阶段验收方式

运行 core 单测，确认 P0 schema、状态机、配置加载全部通过。

### 待确认 / 设计假设 / 潜在风险

- 待确认：是否使用 Pydantic、dataclass 或其他 schema 框架。
- 设计假设：先用 Python schema 实现，后续可导出 JSON Schema 给前端。
- 潜在风险：后续阶段绕过 `core.contracts` 直接传 dict。

## P1：输入理解、场景状态与 Prompt 路由

### 对应 task.md 中的阶段目标

将文本输入和 3D 场景状态标准化为 `query_object`，并完成 Prompt 资产路由、变量填充和注入边界。

### 推荐工程目录结构

重点创建 `src/input/` 和 `src/prompts/`，补充 `configs/prompts.yaml`。

### 需要新增或修改的文件

- `src/input/query_object.py`
- `src/input/query_understanding.py`
- `src/input/scene_binding.py`
- `src/prompts/asset_models.py`
- `src/prompts/repository.py`
- `src/prompts/router.py`
- `src/prompts/assembler.py`
- `configs/prompts.yaml`
- `tests/unit/prompts/test_prompt_router.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `tests/unit/core/test_contracts.py`

### 核心类、函数、模块或服务设计

- `QueryObject`：保存 raw_query、normalized_query、intent_type、target_aircraft、target_component、scene_object_id、needs_clarification。
- `SceneBinder`：根据 scene_state 和指代词绑定场景对象。
- `TeachingPromptAsset`：Prompt 资产总档案。
- `PromptContentVersion`：Prompt 文本版本。
- `PromptRouter`：根据任务、场景、记忆和证据选择 active Prompt。
- `PromptAssembler`：按固定注入顺序构建 messages。

### 数据流 / 调用链路

1. raw user input 与 scene_state 进入 `QueryUnderstandingService`。
2. `SceneBinder` 尝试绑定飞机、部件、热点对象。
3. 生成 `QueryObject`。
4. `PromptRouter` 根据 intent_type 和 scene_scope 查找 active Prompt。
5. `PromptAssembler` 等待 P2/P3 输出后完成最终注入。

### 接口设计

```text
understand_query(raw_query, scene_state, dialogue_context) -> QueryObject
bind_scene_reference(query_object, scene_state) -> SceneBindingResult
select_prompt(query_object, scene_state, memory_context, evidence_package) -> PromptSelection
assemble_messages(prompt_selection, evidence_package, memory_context, output_contract) -> PromptMessageBundle
```

### 配置项设计

- `prompts.default_template_id`
- `prompts.allowed_runtime_statuses: [active, experimental]`
- `prompts.injection_order`
- `prompts.required_variables`
- `prompts.fallback_template_id`

### 错误处理方式

- 缺少必填 Prompt 变量时返回降级原因，不抛业务致命错误。
- 场景指代多候选时设置 `needs_clarification=true`。
- 非 active Prompt 被路由选中时抛出 `PromptStatusError`。

### 日志与监控要求

- 记录 selected_template_id、missing_variables、fallback_reason、scene_binding_confidence。
- 不记录完整 Prompt 密文或未脱敏个人记忆。

### 测试方案

- 场景指代测试：“这个部件有什么用”在有 selected_object_id 时正确绑定。
- 缺失变量测试：缺少 `rag_evidence` 时不生成事实型最终 Prompt。
- 状态测试：draft/candidate/deprecated 不能被正式路由。

### 阶段验收方式

用模拟 scene_state 和 query 输入，确认可以生成稳定 `QueryObject`、Prompt 选择结果和注入摘要。

### 待确认 / 设计假设 / 潜在风险

- 待确认：首批 Prompt 模板内容和人工审核流程。
- 设计假设：Prompt 资产先用本地 JSON 目录存储，目录结构遵循 `asset.json + versions/*.json + evaluations/*.json`。
- 潜在风险：路由器为了补齐变量猜测航空事实。

## P2：记忆系统与时间有效性治理

### 对应 task.md 中的阶段目标

实现事件先行、候选抽取、时间有效、权限治理、审计可追溯的记忆系统，保证记忆只用于个性化而不充当航空事实。

### 推荐工程目录结构

重点创建 `src/memory/`、`configs/memory.yaml` 和 `tests/unit/memory/`。

### 需要新增或修改的文件

- `src/memory/schemas.py`
- `src/memory/controller.py`
- `src/memory/repository.py`
- `src/memory/extractor.py`
- `src/memory/governance.py`
- `src/memory/conflict.py`
- `src/memory/consolidation.py`
- `src/memory/semantic_index.py`
- `src/memory/retrieval.py`
- `src/memory/context.py`
- `src/memory/temporal.py`
- `src/memory/audit.py`
- `configs/memory.yaml`
- `tests/unit/memory/test_memory_write_flow.py`
- `tests/unit/memory/test_temporal_status.py`
- `tests/unit/memory/test_memory_context.py`

### 核心类、函数、模块或服务设计

- `InteractionEvent`：统一事件入口。
- `MemoryCandidate`：候选记忆。
- `StructuredMemory`：学习者画像、掌握度、误解、授权。
- `MemoryController`：读写权限、抽取、校验、检索和注入控制。
- `SQLiteMemoryRepository`：持久化事件、候选、结构化记忆、关系、语义条目和审计日志。
- `MemoryExtractor`：从事件抽取候选记忆。
- `TemporalNormalizer`：解析 observed_at、valid_from、expires_at。
- `MemoryGovernanceChecker`：执行隐私、置信度、长期记忆类型和事实污染检查。
- `ConflictDetector`：检测偏好或误解冲突。
- `MemoryConsolidator`：执行重复合并、冲突替代和状态迁移。
- `SemanticMemoryIndex`：维护可替换的语义检索条目。
- `MemoryRetriever`：按 scope、时间、置信度、稳定度和语义相关性召回记忆。
- `MemoryContextBuilder`：生成 `memory_context`。

### 数据流 / 调用链路

1. P1/P4/P6/P7 产生 `InteractionEvent`，统一通过 `MemoryController.record_event` 写入 `SQLiteMemoryRepository`。
2. `MemoryExtractor` 抽取候选记忆。
3. `TemporalNormalizer` 补时间字段。
4. `ConflictDetector` 判断替代、冲突或重复。
5. `MemoryGovernanceChecker` 检查隐私、安全、置信度、类型白名单和事实污染。
6. `MemoryConsolidator` 生成 canonical memory，并通过 `SQLiteMemoryRepository` 写入结构化存储、关系表、语义条目和审计日志。
7. 回答前由 `MemoryController` 生成 `memory_context`。

### 接口设计

```text
MemoryController.from_config(path="configs/memory.yaml") -> MemoryController
MemoryController.record_event(event: InteractionEvent) -> event_id
MemoryController.process_event(event_id: str) -> MemoryProcessingReport
MemoryController.submit_candidate(candidate: MemoryCandidate) -> MemoryProcessingReport
MemoryController.build_memory_context(request: MemoryRetrievalRequest) -> MemoryContext
SQLiteMemoryRepository.save_memory_record(memory: StructuredMemory) -> memory_id
```

时间状态刷新属于 `MemoryController` 的内部治理动作，用于扫描并更新 stale/expired 状态；不得恢复旧的独立维护任务模块或泛化维护任务接口。

### 配置项设计

- `memory.max_context_items`
- `memory.store.provider: sqlite`
- `memory.store.path`
- `memory.default_expiration_days`
- `memory.stale_after_days`
- `memory.low_confidence_threshold`
- `memory.privacy_levels`
- `memory.decay_policy`
- `memory.allowed_long_term_types`
- `memory.retrieval_weights`
- `memory.semantic_index.enabled`

### 错误处理方式

- 未授权写入返回 `MemoryRejected`，并记录审计原因。
- 冲突记忆不覆盖删除旧记录，而是建立 supersedes/superseded_by。
- 低置信 ASR 事件只能进入 event_log，不进入长期记忆。

### 日志与监控要求

- 记录 memory_id、source_event_id、write_agent、confidence、status、privacy_level、decision_reason。
- 监控 memory_pollution_rate、expired_memory_hit_count、conflict_count。

### 测试方案

- 写入流程测试：事件到候选再到 active。
- 冲突测试：旧偏好被新偏好 superseded。
- 边界测试：用户说的航空事实不能进入知识库事实表。
- 时间测试：expires_at 后不进入 `memory_context`。

### 阶段验收方式

用三轮模拟对话验证系统能记住表达偏好、当前主题和误解候选，并能在过期或冲突时降权。

### 待确认 / 设计假设 / 潜在风险

- 待确认：真实用户授权 UI 和删除流程。
- 设计假设：事件日志和结构化记忆先用 SQLite 或轻量存储抽象。
- 潜在风险：事件原文含隐私，日志导出时未脱敏。

## P3：本地航空知识库、RAG 与多模态证据包

### 对应 task.md 中的阶段目标

构建航空事实证据核心，支持文本、PDF、图片、结构图、3D 场景对象和轻量图谱的入库、检索、融合、重排序和 evidence_package 输出。

### 推荐工程目录结构

重点创建 `src/knowledge/`、`src/knowledge/ingestion/`、`src/knowledge/indexes/`、`configs/rag.yaml`。

### 需要新增或修改的文件

- `src/knowledge/schemas.py`
- `src/knowledge/source_registry.py`
- `src/knowledge/scene_object_registry.py`
- `src/knowledge/ingestion/text_ingestor.py`
- `src/knowledge/ingestion/pdf_ingestor.py`
- `src/knowledge/ingestion/visual_ingestor.py`
- `src/knowledge/ingestion/scene_ingestor.py`
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_index.py`
- `src/knowledge/indexes/hybrid_index.py`
- `src/knowledge/indexes/visual_page_index.py`
- `src/knowledge/indexes/graph_index.py`
- `src/knowledge/retrieval_controller.py`
- `src/knowledge/evidence_package.py`
- `src/knowledge/evidence_gate.py`
- `scripts/ingest_sources.py`
- `tests/integration/rag_pipeline/test_basic_retrieval.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`

### 核心类、函数、模块或服务设计

- `SourceRecord`：source_id、source_type、authority_level、review_status、version。
- `TextChunk`：父子节点文本片段。
- `VisualAsset`：图片、结构图和示意图对象。
- `SceneObject`：3D 热点与知识实体绑定。
- `RetrievalController`：查询理解、计划选择、多路召回、融合。
- `EvidenceGate`：confident、weak、conflict、unclear。
- `EvidencePackageBuilder`：构造统一证据包。

### 数据流 / 调用链路

1. `ingest_sources.py` 登记 source_registry。
2. 文本/PDF/图片/3D 元数据分别进入解析器。
3. 解析结果写入关键词、向量、视觉页面和图谱索引。
4. 运行时 `RetrievalController` 接收 query_object、scene_state、memory_context。
5. 生成多查询，执行多路召回。
6. RRF 融合与重排序。
7. `EvidenceGate` 判定证据质量。
8. 输出 `evidence_package`。

### 接口设计

```text
register_source(source_record) -> source_id
ingest_text(source_id, document) -> list[TextChunk]
register_scene_object(scene_object) -> scene_object_id
plan_retrieval(query_object, scene_state, memory_context, budget) -> RetrievalPlan
retrieve_evidence(retrieval_plan) -> EvidencePackage
evaluate_evidence_package(evidence_package) -> EvidenceGateResult
```

### 配置项设计

- `rag.chunk_size_chars: 300-600`
- `rag.chunk_overlap_chars: 60-100`
- `rag.keyword_top_k`
- `rag.vector_top_k`
- `rag.visual_top_k`
- `rag.parent_top_k`
- `rag.allowed_core_review_status: [reviewed]`
- `rag.evidence_gate_thresholds`

### 错误处理方式

- 无 reviewed 证据时，`EvidenceGate` 返回 weak 或 unclear。
- 视觉证据缺少 text_cross_check 时，`usable_as_core_evidence=false`。
- scene_object_id 找不到时返回澄清建议，不默认绑定。

### 日志与监控要求

- 记录 retrieval_plan、query_variants、channel_hits、filtered_count、evidence_ids、gate_status、missing_evidence。
- 监控 evidence_coverage、scene_match_rate、retrieval_latency_ms。

### 测试方案

- 入库测试：source_registry、text_chunk、scene_object_registry 字段完整。
- 检索测试：术语查询走关键词，口语查询走向量，场景指代走 scene registry。
- 门控测试：draft 资料不能作为核心证据。
- 多模态测试：视觉标签无文本校验不能支撑事实 claim。

### 阶段验收方式

用 C919 发动机、机翼升力、AG600 船型机身等演示数据验证 `evidence_package` 能提供来源、缺失证据和生成边界。

### 待确认 / 设计假设 / 潜在风险

- 待确认：最终向量库、图数据库、PDF 解析工具和 embedding 模型。
- 设计假设：MVP 可先用内存或文件索引模拟，接口保持可替换。
- 潜在风险：工具选型过早绑定，后续难以替换。

## P4：回答生成、来源绑定与结构化输出

### 对应 task.md 中的阶段目标

基于事实证据包生成可检查的 `answer_envelope`，包括短答、主体、来源绑定、视觉引用、不确定性、安全说明和前端展示块。

### 推荐工程目录结构

重点创建 `src/generation/` 和 `tests/unit/generation/`。

### 需要新增或修改的文件

- `src/generation/answer_types.py`
- `src/generation/evidence_sketch.py`
- `src/generation/planner.py`
- `src/generation/generator.py`
- `src/generation/citation_binding.py`
- `src/generation/display_blocks.py`
- `tests/unit/generation/test_answer_type_router.py`
- `tests/unit/generation/test_citation_binding.py`
- `tests/integration/answer_pipeline/test_grounded_answer.py`

### 核心类、函数、模块或服务设计

- `AnswerTypeRouter`：选择概念解释、部件场景、对比归纳、参数事实、操作安全、澄清追问。
- `EvidenceSketchBuilder`：提炼 facts、relations、visual_clues、missing_points、conflicts。
- `GenerationPlanner`：生成 answer_plan。
- `GroundedAnswerGenerator`：生成结构化初稿。
- `CitationBinder`：维护 claim 到 evidence_id 的映射。
- `DisplayBlockBuilder`：生成 conclusion_block、source_block、scene_block、quiz_block。

### 数据流 / 调用链路

1. 接收 query_object、scene_state、memory_context、prompt_selection、evidence_package。
2. `AnswerTypeRouter` 判定回答类型。
3. `EvidenceSketchBuilder` 压缩证据。
4. `GenerationPlanner` 产出回答计划。
5. `GroundedAnswerGenerator` 生成草稿。
6. `CitationBinder` 生成 source_binding。
7. 输出 `answer_envelope` 和 `generation_trace`。

### 接口设计

```text
route_answer_type(query_object, evidence_package, scene_state) -> AnswerType
build_evidence_sketch(evidence_package, answer_type) -> EvidenceSketch
generate_answer(plan, evidence_sketch, memory_context, prompt_selection) -> AnswerEnvelope
bind_citations(answer_envelope, evidence_package) -> SourceBinding
build_display_blocks(answer_envelope, scene_state) -> list[DisplayBlock]
```

### 配置项设计

- `generation.max_main_answer_chars`
- `generation.require_source_binding_for_claims`
- `generation.answer_type_policies`
- `generation.stream_low_risk_only`
- `generation.safety_template_id`

### 错误处理方式

- 证据包 gate 为 weak/conflict/unclear 时不生成确定回答，转成追问或不确定说明。
- 参数问题无权威证据时禁止输出具体数值。
- source_binding 缺失时将该 claim 标记为 `claim_candidates.unsupported_pending_check`。

### 日志与监控要求

- 记录 answer_type、template_id、evidence_ids、source_binding_count、uncertainty_count、output_length、latency_ms。
- 监控 unsupported_generation_rate、citation_coverage、scene_reference_rate。

### 测试方案

- 参数事实测试：缺少参数证据时不输出数值。
- 部件场景测试：scene_state.component_id 必须进入答案对象。
- 个性化测试：learner_profile 改变表达，不改变事实。
- source_binding 测试：每个关键 claim 有 evidence_id 或不确定性说明。

### 阶段验收方式

用固定 evidence_package 生成 C919、机翼升力、AG600 三类答案，检查结构化字段、来源绑定和缺失证据表达。

### 待确认 / 设计假设 / 潜在风险

- 待确认：结构化输出工具、模型名称、是否启用流式。
- 设计假设：先使用非流式输出以确保自检可控。
- 潜在风险：流式回答在自检前输出高风险事实。

## P5：自我检查、动作分流与回退闭环

### 对应 task.md 中的阶段目标

建立独立质量闸门，生成 `check_report` 并驱动 PASS、REWRITE_ONLY、RETRIEVE_MORE、ASK_CLARIFICATION、HUMAN_REVIEW、SAFE_RESPONSE、STOP 等回退动作。

### 推荐工程目录结构

重点创建 `src/self_check/` 和自检相关集成测试。

### 需要新增或修改的文件

- `src/self_check/claim_extractor.py`
- `src/self_check/evidence_alignment.py`
- `src/self_check/scene_checker.py`
- `src/self_check/multimodal_checker.py`
- `src/self_check/boundary_checker.py`
- `src/self_check/score_card.py`
- `src/self_check/decision_router.py`
- `tests/unit/self_check/test_claim_support.py`
- `tests/unit/self_check/test_decision_router.py`
- `tests/integration/answer_pipeline/test_self_check_loop.py`

### 核心类、函数、模块或服务设计

- `ClaimExtractor`：拆解事实主张。
- `EvidenceAligner`：映射 claim 到 evidence_items。
- `SceneAlignmentChecker`：检查 aircraft/component/hotspot 是否一致。
- `MultimodalConsistencyChecker`：检查 visual_refs、bbox、text_cross_check。
- `BoundaryChecker`：检查记忆和 Prompt 是否越权。
- `ScoreCardBuilder`：生成多维评分。
- `DecisionRouter`：将评分和问题映射为 action_decision。

### 数据流 / 调用链路

1. 接收 answer_envelope、evidence_package、scene_state、memory_context、prompt_asset。
2. 抽取 claims。
3. 执行证据对齐、场景检查、多模态检查、边界检查、安全检查。
4. 生成 score_card。
5. `DecisionRouter` 输出 action_decision 和 revised_instruction。
6. 状态机按动作回到 P3、P4、P6 或 final。

### 接口设计

```text
extract_claims(answer_envelope) -> list[Claim]
align_claims_to_evidence(claims, evidence_package) -> ClaimSupportMap
check_scene_alignment(answer_envelope, scene_state) -> SceneCheckResult
build_score_card(check_results) -> ScoreCard
route_decision(score_card, issue_list, loop_count) -> CheckReport
```

### 配置项设计

- `self_check.thresholds.factual_grounding`
- `self_check.thresholds.scene_alignment`
- `self_check.thresholds.risk_score`
- `self_check.max_retrieve_loops`
- `self_check.max_rewrite_loops`
- `self_check.high_risk_claim_types`

### 错误处理方式

- check_report 缺字段时重试或降级为 HUMAN_REVIEW。
- 循环超过上限时输出 STOP 和保守回答。
- 高风险安全请求直接 SAFE_RESPONSE。

### 日志与监控要求

- 记录 failed_checks、unsupported_claims、action_decision、revised_instruction、loop_count。
- 监控 hallucination_detection、scene_alignment_fail_count、risk_detection_count。

### 测试方案

- 无证据参数触发 RETRIEVE_MORE 或 REWRITE_ONLY。
- 场景对象错位触发 REWRITE_ONLY/ASK_CLARIFICATION。
- 记忆越界触发 REWRITE_ONLY。
- 危险操作触发 SAFE_RESPONSE。

### 阶段验收方式

构建四类失败样例：无证据参数、部件错位、过难表达、危险操作，确认动作分流正确。

### 待确认 / 设计假设 / 潜在风险

- 待确认：人工复核入口和评分阈值。
- 设计假设：先以规则为主，LLM Judge 只处理语义支持度。
- 潜在风险：LLM Judge 误判，需要保留可解释 issue_list。

## P6：反馈改写、多轮 checkpoint 与记忆候选

### 对应 task.md 中的阶段目标

处理用户对上一轮回答的反馈，在证据锁定、来源保持和自检复核下做局部改写，并生成候选记忆。

### 推荐工程目录结构

重点创建 `src/feedback/` 和 `tests/integration/feedback_loop/`。

### 需要新增或修改的文件

- `src/feedback/feedback_event.py`
- `src/feedback/parser.py`
- `src/feedback/checkpoint_store.py`
- `src/feedback/evidence_lock.py`
- `src/feedback/rewrite_planner.py`
- `src/feedback/rewriter.py`
- `src/feedback/delta_map.py`
- `tests/unit/feedback/test_feedback_parser.py`
- `tests/unit/feedback/test_evidence_lock.py`
- `tests/integration/feedback_loop/test_rewrite_preserves_sources.py`

### 核心类、函数、模块或服务设计

- `FeedbackParser`：识别 SIMPLIFY、SHORTEN、FORMAT_TRANSFORM、FACT_CHALLENGE、SCENE_REBIND、PREFERENCE_SIGNAL、SAFETY_SENSITIVE。
- `CheckpointStore`：保存上一轮回答、证据、自检和状态。
- `EvidenceLock`：锁定可用证据和 claim。
- `RewritePlanner`：生成可执行改写操作。
- `ControlledRewriter`：生成 revised_answer_envelope。
- `DeltaMapper`：记录删改增差异。

### 数据流 / 调用链路

1. FINAL_READY 后保存 checkpoint。
2. 收到 feedback_event。
3. 解析 feedback_intent。
4. 对表达类反馈执行 evidence_locking 后改写。
5. 对事实挑战或场景重绑定回到 P3 补检索。
6. 生成 revised_answer_envelope、delta_map、memory_update_candidate。
7. 改写后送 P5 自检。

### 接口设计

```text
save_checkpoint(run_id, answer_envelope, evidence_package, check_report) -> checkpoint_id
parse_feedback(feedback_event, checkpoint) -> FeedbackParseResult
build_evidence_lock(checkpoint) -> EvidenceLock
plan_rewrite(parse_result, evidence_lock) -> RewritePlan
rewrite_answer(rewrite_plan, checkpoint) -> RevisedAnswerEnvelope
build_delta_map(previous_answer, revised_answer) -> DeltaMap
```

### 配置项设计

- `feedback.max_rewrite_rounds`
- `feedback.allowed_format_transforms`
- `feedback.preference_stability_threshold`
- `feedback.safety_sensitive_patterns`

### 错误处理方式

- checkpoint 缺失时要求用户重新说明问题或降级普通问答。
- FACT_CHALLENGE 不允许直接改事实，必须设置 verification_queries。
- 安全敏感反馈直接 SAFE_RESPONSE。

### 日志与监控要求

- 记录 feedback_intent、rewrite_action、changed_claims、removed_claims、added_claims、preserved_source_binding、self_check_result。
- 监控 semantic_preservation、citation_retention、user_acceptance_signal。

### 测试方案

- “太专业了”只降低术语密度，source_binding 保留。
- “你说错了”触发补检索或不确定性说明。
- “用表格”保留无证据维度为“资料未覆盖”。
- “具体维修步骤”触发 SAFE_RESPONSE。

### 阶段验收方式

运行多轮对话场景，确认反馈改写不会丢证据、不会新增事实、不会直接写长期记忆。

### 待确认 / 设计假设 / 潜在风险

- 待确认：前端是否展示 delta_map。
- 设计假设：checkpoint 先用内存或轻量存储，生产再持久化。
- 潜在风险：用户连续反馈导致循环延迟，必须执行 max_rewrite_rounds。

## P7：语音交互与实时会话

> 实施状态：2026-07-13 已完成 T0–T11。权威主链为 `AudioTransport -> VAD/Endpoint -> ASRProvider -> VoiceQueryNormalizer -> VoiceSessionOrchestrator -> AppPipeline -> SpokenAnswerPlanner -> TTSProvider`，不再保留 Mock-only 平行业务链。

### P7 最终实施约束

- `configs/voice_terminology.yaml` 严格分为 `terms` 和 `simplifications`：ASR 只消费前者，P6 `SIMPLIFY` 只消费后者，两个分区均进入脱敏配置 snapshot digest。
- `feedback.checkpoint.max_items` 是 checkpoint 容量的唯一配置来源；存储按 FIFO 显式淘汰，淘汰审计同样有界且不包含 run/session 自由文本。
- 播放 task 在运行时生命周期内完成业务收敛，done callback 只消费已记录的异常，不建立第二个 finalizer 业务路径。
- 本轮只验证 Mock VAD/ASR/TTS 与本地 WebSocket adapter，不代表真实 Provider 或生产媒体能力已上线。

### 对应 task.md 中的阶段目标

实现级联式 Voice RAG：VAD -> ASR -> 查询标准化 -> RAG -> 回答生成 -> 自我检查 -> TTS，并支持实时打断和语音反馈。

### 推荐工程目录结构

重点创建 `src/voice/`、`configs/voice.yaml` 和 `tests/integration/voice_loop/`。

### 需要新增或修改的文件

- `src/voice/transport.py`
- `src/voice/session_state.py`
- `src/voice/vad.py`
- `src/voice/asr.py`
- `src/voice/terminology.py`
- `src/voice/tts.py`
- `src/voice/barge_in.py`
- `src/voice/metrics.py`
- `src/input/voice_query_normalizer.py`
- `configs/voice.yaml`
- `tests/unit/voice/test_voice_state_machine.py`
- `tests/unit/voice/test_query_normalizer.py`
- `tests/integration/voice_loop/test_barge_in_feedback.py`

### 核心类、函数、模块或服务设计

- `VoiceSessionStateMachine`：语音状态机。
- `AudioTransport`：WebSocket/WebRTC 抽象。
- `VADService`：voice_start、voice_active、voice_pause、voice_end。
- `ASRProvider`：转写接口。
- `AviationTermCorrector`：航空术语纠错。
- `VoiceQueryNormalizer`：输出 voice_query_object。
- `TTSProvider`：语音合成接口。
- `BargeInController`：打断控制。
- `VoiceMetricsRecorder`：语音指标记录。

### 数据流 / 调用链路

1. 前端发送音频帧和 scene_state。
2. VAD 判断开始、停顿、结束。
3. ASR 输出 raw_transcript、confidence、partial/final。
4. 术语纠错与口语标准化生成 voice_query_object。
5. 低置信或缺场景对象时 ASK_CLARIFICATION。
6. 正常查询进入 P1/P3/P4/P5 主链路。
7. answer_envelope 转 spoken_answer。
8. TTS 播报时监听 barge_in。
9. 打断事件进入 P6 反馈改写。

### 接口设计

```text
handle_audio_frame(session_id, frame, scene_state) -> VoiceEvent
transcribe(audio_segment) -> ASRResult
correct_terms(asr_result, scene_state, term_lexicon) -> CorrectedTranscript
normalize_voice_query(corrected_transcript, scene_state, dialogue_context) -> VoiceQueryObject
synthesize_spoken_answer(answer_envelope, tts_config) -> AudioStream
handle_barge_in(session_id, partial_feedback) -> FeedbackEvent
```

### 配置项设计

- `voice.transport: websocket|webrtc`
- `voice.sample_rate`
- `voice.vad_provider`
- `voice.asr_provider`
- `voice.tts_provider`
- `voice.asr_low_confidence_threshold`
- `voice.max_spoken_answer_seconds`
- `voice.barge_in_priority`
- `voice.pronunciation_lexicon_path`

### 错误处理方式

- ASR 低置信时，不进入 RAG，返回澄清。
- TTS 播报失败时保留文字回答，不重跑生成。
- barge_in 必须先停止旧音频，再解析反馈。

### 日志与监控要求

- 记录 ASR confidence、VAD 误切、首响延迟、TTS 首包延迟、barge_in 响应时间、spoken_answer 长度。
- 不在普通日志保存原始音频；如需保存，必须受 policy_memory 控制。

### 测试方案

- “涵道比”误识别为“航道比”时进入术语纠错或澄清。
- 用户说“这个是什么”但无 scene_object_id 时 ASK_CLARIFICATION。
- TTS 播报中用户说“停一下，讲简单点”时触发 barge_in 和 P6 改写。
- spoken_answer 不超过配置长度。

### 阶段验收方式

完成一个 WebSocket 语音原型，能将模拟语音转写输入标准化为 query_object，并能在 TTS 期间处理中断。

### 待确认 / 设计假设 / 潜在风险

- 待确认：语音 Provider、WebRTC 是否必须、部署网络条件。
- 设计假设：先用 WebSocket 和模拟 ASR/TTS 通过状态机，再接真实 Provider。
- 潜在风险：端到端语音模型绕过证据链，必须禁止其直接生成事实回答。

## P8：日志评测、演示审计与部署治理

### 对应 task.md 中的阶段目标

建立评测、日志、审计、部署和演示体系，使系统能证明“有证据回答、记忆受控、自检有效、反馈可追踪、语音可打断”。

### 推荐工程目录结构

重点创建 `scripts/`、`docs/`、`tests/e2e/` 和评测配置。

### 需要新增或修改的文件

- `configs/evals.yaml`
- `scripts/run_eval.py`
- `scripts/seed_demo_data.py`
- `scripts/export_trace_report.py`
- `docs/architecture.md`
- `docs/api_contracts.md`
- `docs/eval_plan.md`
- `tests/e2e/scenarios/test_text_qa_flow.py`
- `tests/e2e/scenarios/test_feedback_flow.py`
- `tests/e2e/scenarios/test_voice_flow.py`

### 核心类、函数、模块或服务设计

- `EvaluationDataset`：固定评测样例。
- `RunTraceExporter`：导出每轮运行证据链。
- `MetricsAggregator`：汇总检索、生成、自检、反馈、记忆、语音指标。
- `DemoReportBuilder`：生成答辩展示报告。
- `DeploymentConfigValidator`：检查部署配置完整性。

### 数据流 / 调用链路

1. `seed_demo_data.py` 写入演示资料、场景对象和测试用户。
2. `run_eval.py` 执行固定场景。
3. 每轮收集 run_trace、check_report、rewrite_log、voice_metric_record。
4. `MetricsAggregator` 输出指标。
5. `export_trace_report.py` 导出可审计报告。

### 接口设计

```text
run_eval_suite(suite_name, config) -> EvaluationReport
aggregate_metrics(run_traces) -> MetricsSummary
export_trace_report(run_id, format="markdown") -> ReportPath
validate_deployment_config(configs) -> ValidationReport
```

### 配置项设计

- `evals.suites`
- `evals.required_pass_rate`
- `evals.latency_budget_ms`
- `evals.trace_export_fields`
- `deployment.required_env`

### 错误处理方式

- 评测样例缺证据时标记为数据问题，不归咎模型。
- 指标低于阈值时阻止阶段验收。
- 部署配置缺失时输出明确缺失项。

### 日志与监控要求

- 全链路日志统一 run_id。
- 评测报告必须能定位到失败阶段、失败对象和失败原因。
- 演示导出需要脱敏用户隐私和原始音频。

### 测试方案

- E2E 文本问答：输入 -> RAG -> 生成 -> 自检 -> 输出。
- E2E 反馈改写：上一轮回答 -> 用户反馈 -> 改写 -> 自检。
- E2E 语音：ASR 模拟 -> 标准化 -> 主链路 -> TTS -> barge_in。
- 回归测试：参数无证据、场景错位、安全敏感、ASR 低置信。

### 阶段验收方式

一条命令可运行评测套件并导出报告。报告显示每个场景的证据命中、自检动作、最终输出和失败项。

### 待确认 / 设计假设 / 潜在风险

- 待确认：正式部署方式、演示环境、评测集规模、隐私脱敏标准。
- 设计假设：先本地部署和演示报告，后续再 CI/CD。
- 潜在风险：功能已完成但没有可重复评测，导致后续改动无法防回归。

## P2 Review Fix Update (2026-07-09)

- `MemoryController.submit_candidate(...)` now loads every referenced source event before acceptance and enforces event-derived provenance for `user_id`, `session_id`, `privacy_level`, and source timing. A direct caller cannot reattach a candidate to another user's/session's event or downgrade privacy below the strictest source event.
- `memory.retrieval_weights` is now part of the typed controller/runtime config and is passed into `MemoryRetriever`; the configured weights drive confidence, stability, scope, scene, recency, semantic relevance, explicit confirmation, expiry, conflict, and privacy scoring.
- `memory.privacy.default_long_term_allowed` and `memory.privacy.high_privacy_requires_explicit_authorization` now affect governance decisions. High-privacy and other protected long-term candidates require authorization according to config instead of being controlled only by hard-coded behavior.
- `memory.semantic_index.embedding_provider` is now a real runtime config input for the memory semantic index and retriever embedding path.
- Prompt-facing memory injection is sanitized before serialization into `PromptMessageBundle.messages`: safe preference summaries and misconception labels may appear, but raw private values, raw recent feedback text, and other free-text private memory payloads must not be injected verbatim.

## RAG 知识检索工程化重构验收规格（2026-07-10）

- 生产检索统一由 `RetrievalController.from_config()` 进入，旧 `EvidenceRanker`、`SimpleVectorIndex` 与 controller 手工 `add_chunks` 路径已删除。
- 当前真实通道为 SQLite FTS5/BM25 关键词检索、持久化 dense adapter、场景对象关联、受审核来源约束的有界图遍历，以及已审核 PDF 页面文本/标签检索；融合使用 RRF，之后执行七特征重排与四态 Evidence Gate。
- 默认 `configs/rag.yaml` 是离线 Mock embedding profile，只用于本地验证；生产 profile 禁止 Mock embedding。当前未接入外部生产 embedding Provider 或生产级向量数据库。
- PDF 使用 `pypdf 6.14.2` 提取可提取文本并保留页码/来源/layout trace。未实现 OCR、真实版面模型、视觉 embedding、ColPali/VisRAG；图通道是有界邻接遍历，不宣称 GraphRAG。
- 知识库与记忆库继续使用独立 SQLite 文件；评测仅使用 `TemporaryDirectory` 中的显式临时 RAG/Memory 配置。
- `rag_refactor` 固定 8 个案例，逐案记录预期通道、Gate、所需证据类型和禁止声明；追踪导出只包含复杂度、通道命中、RRF 排名、重排特征汇总、来源权威标签、Gate、缺失/冲突代码、补检索差异与延迟，不导出受保护来源全文或私有记忆正文。

## LangGraph 文本主链迁移：用户授权

在不改写 P0–P8 或 G0–G8 历史验收的前提下，授权将文本问答主链直接迁移到 LangGraph，并锁定 `langgraph==1.2.9` 和 `langgraph-checkpoint-sqlite==3.1.0`。安装、锁定、依赖检查和测试统一以 `D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe` 的 Python 3.11.9 进行验证，项目最低 Python 声明继续为 `>=3.11`。`AppPipeline` 继续是文本和语音的唯一公共边界；语音实时层不改为图编排。

LangGraph 只负责顺序、条件分支、恢复和可观测性，不产生航空事实，也不改变 `TextQueryRequest`、`TextQueryResponse`、`TextQueryResponse.to_dict()`、CLI 参数或公开 answer/action/trace 字段。checkpoint 使用独立的本地 SQLite 存储，禁止与 `knowledge.sqlite3` 或 `memory.sqlite3` 共用；只保留恢复必需的最小脱敏状态，排除原始音频、完整 Prompt、私有记忆正文、来源全文、证据正文和密钥。无事实对话才可直接回答；航空事实必须通过 `RetrievalController` 和已审核证据；不安全输入必须先于记忆读取、检索和生成进入拒答终态。

验收至少覆盖精确依赖契约、CLI 公共响应契约、DIRECT/RETRIEVE/CLARIFY/REFUSE、同会话指代消解、有限恢复、SQLite 删除和隐私扫描，以及文本、RAG、记忆、反馈、语音和全量 pytest 回归。若 Python 3.11.9 依赖不兼容、SQLite 必须保存禁存内容、恢复需要修改公开契约、节点需要绕过 controller、语音边界被破坏，或同类测试连续三次无法定位失败，立即停止并在 `STATUS.md` 记录“待确认”。禁止增加 LangChain、LangSmith、Agent Server、云服务、真实模型 provider、向量数据库或生产数据库连接。

## 记忆服务重构专项 M0–M5：执行规格

本专项不得覆盖 P0 至 P8 或 G0 至 G8 的任务和验收。阶段严格按 `M0 → M1 → M2 → M3 → M4 → M5` 串行关闭：M0 治理/契约，M1 Java PostgreSQL/pgvector 权威核心，M2 Python 在线集成与 150 ms 空记忆降级，M3 Redis/worker/Neo4j 异步投影，M4 隐私与用户控制，M5 迁移、单一权威切换和定量验收。每项均执行 RED、最小实现、任务测试、上游公开回答回归、只读审查、清洁检查和 `STATUS.md` 记录。

### M0 规格与精确文件范围

M0 只创建稳定契约、确定性生成代码和无数据服务连接的 Java skeleton。契约源位于 `contracts/memory/v1`，Python 生成代码位于 `src/memory/transport/generated`，Java 服务只位于 `services/memory-service`。`ResolveMemoryContext` 状态固定为 `APPLIED`、`EMPTY`、`DEGRADED`，技术降级不得携带可用记忆；身份只来自签名 gRPC metadata，request body 不得出现 `learner_id` 或 `user_id`；Proto 字段号不复用，破坏性变更新建 package version。

M0 允许的项目文件集合仅为批准计划声明的以下路径：

- 总控：`docs/项目总控/task.md`、`spec.md`、`harness.md`、`AUTO_DEV.md`、`STATUS.md`。
- Task 1：`tests/contracts/test_memory_refactor_governance.py`、`tests/contracts/test_memory_workspace_layout.py`、`tests/fixtures/memory_contract/v1/text_response.json`、`scripts/check_memory_workspace_cleanliness.py`。
- Task 2：`contracts/memory/v1/memory_context.proto`、`memory_event.proto`、`memory_worker.proto`、`memory_management.openapi.yaml`、`COMPATIBILITY.md`、`tests/contracts/test_memory_proto_contract.py`。
- Task 3：`scripts/generate_memory_contracts.py`、`src/memory/transport/__init__.py`、`src/memory/transport/generated/__init__.py`、`src/memory/transport/generated/*_pb2.py`、`src/memory/transport/generated/*_pb2_grpc.py`、`pyproject.toml`、`uv.lock` 及 Proto 契约测试。
- Task 4：`services/memory-service/pom.xml`、`mvnw`、`mvnw.cmd`、`.mvn/wrapper/**`、`src/main/java/com/yilan/memory/MemoryServiceApplication.java`、`src/main/resources/application.yaml`、`src/test/java/com/yilan/memory/ArchitectureTest.java`。
- Task 5：`tests/fixtures/memory_contract/v1/resolve_applied.bin`、`services/memory-service/src/test/java/com/yilan/memory/contract/GoldenContractTest.java`、Proto 契约测试和 `STATUS.md`。

`.venv` 只作为 Python 3.11 本地锁定测试环境；任务临时产物只允许在 `tmp/memory-system/m0`，并须在 M0 验收前移除。M0 依赖固定为 Python 3.11、`grpcio==1.82.1`、`grpcio-tools==1.82.1`、`protobuf==7.35.1`、JDK 21、Maven Wrapper 3.9.16、Spring Boot 4.0.6、Spring gRPC 1.0.3、JUnit 5 与 ArchUnit。M0 不连接任何数据服务、真实模型或外部生产服务。

### M1–M5 接口和验收边界

- M1 的 PostgreSQL + pgvector 路径不依赖 Redis/Neo4j 即可完成事件幂等、治理、追加版本、source closure 和 gRPC resolve；M0–M4 的 Java 只能处于非权威、测试或 shadow 状态。
- M2 引入 `MemoryPort` 与 local/shadow/remote adapter；session overlay 不长期持久化；remote 任一异常返回全新空长期上下文，单次等待不超过 150 ms、无 inline retry，RAG 继续运行。
- M3 的 PostgreSQL transactional outbox 经 Redis Streams 驱动无状态 Python worker，再回 Java 验证治理；Neo4j 与缓存均可旁路/重建，移除 Redis/Neo4j 不得破坏 M1 PostgreSQL-only 测试。
- M4 所有 `/v1/me` 自助接口不能由 body/path/query 选择主体；默认 opt-out，同意撤回和 forget 先提升 epoch 并同步阻断读取，历史 append-only，敏感 payload 使用每学习者密钥保护。
- M5 只读冻结旧 SQLite，shadow import/compare 后以 `LOCAL → SHADOW → CUTOVER_PREPARED → REMOTE` 原子切换；先停 Python 长期写，再提升 Java authority epoch，任何崩溃点最多一个长期写权威；REMOTE 后 Java down 不回退 SQLite。

最终验收必须证明 Java resolve p50 ≤ 50 ms、p95 ≤ 120 ms，Python memory wait ≤ 150 ms；160 用例 critical counters 为 0、source closure=1、temporal ≥0.95、abstention ≥0.98、F1 ≥0.90；负载为 50 sessions、20 resolve/s、50 event/s、600 秒且 ACK loss=0、duplicate active=0。任何 memory-as-fact、跨用户读取、撤回后读取或 forget 复活均直接失败，不得放宽断言或延长 deadline。

### M5-7 Task 4 负载验收关闭判定决策树

canonical outer CLI 执行（一次性，`run_memory_load.py --sessions 50 --read-rate 20 --event-rate 50 --duration-seconds 600 --profile full --output docs/评测与验收/评测报告/memory_m5_load.json`）按以下决策树判定关闭：

1. **通过（exit 0，gates 全绿）**：关闭 Task 4，生成 canonical report（`profile_gates_passed=true`、`canonical_600_second_run=true`），进入 M5-8。
2. **失败：typed failure（exit 45/48/54）**：
   - R30-R33 产生配对状态（R32 `ACTIVE`+R33 `EXPIRED` 或 R33 `ACTIVE`+R31 `EXPIRED`）-> 分类结论：
     - commit envelope 内 expiry -> 记录为架构约束，提请 harness 决策（不自动修改冻结参数）。
     - 其他区间 expiry -> 定位到具体回调，提请针对性修复。
   - R30-R33 无配对状态（`NOT_RECORDED`/`UNAVAILABLE`/缺失配对）-> 记录为非确定性，提请架构决策。
3. **失败：unclassified** -> 记录为环境问题，允许重试一次；第二次仍失败则提请架构决策。

决策树不授权自动修改 deadline、50/20/50/600、Hikari、executor、transaction semantics 或 authority。任何超出当前冻结测量架构的修复需独立审查和 harness 决策。
