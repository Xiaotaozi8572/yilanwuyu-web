# 翼览无余工业级升级执行规格

本文件由工业级升级规划生成，用于指导 Codex 后续阶段化开发。

## 需要调用的 skill

后续让 Codex 按本文执行开发时，提示词必须明确声明并按顺序调用以下 skill：

1. `using-superpowers`：阶段开始前调用，确认还需要哪些 skill。
2. `writing-plans`：阶段实施前调用，将本规格拆成文件级、测试级计划。
3. `systematic-debugging`：任何测试失败、模型调用失败、RAG 偏差、语音状态机异常、API 失败时调用。
4. `verification-before-completion`：每阶段完成前调用并读取验证输出。
5. `browser:control-in-app-browser`：需要验证本地服务、API 文档或报告页面时调用。

## 全局执行约束

- Python 命令统一使用：

```powershell
"D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

- 安装依赖统一使用：

```powershell
"D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pip install 包名
```

- 新增依赖必须写入 `pyproject.toml` 或项目后续确认的依赖文件。
- 模型目标统一为 `deepseek-flash`；真实 DeepSeek API `model_id` 必须在 P3+ 通过 `DEEPSEEK_MODEL` 环境变量或 `configs/providers.yaml` 配置核验，不得硬编码到业务模块。
- 每阶段必须保留当前 MVP 可运行性，规则 generator、`SimpleVectorIndex`、Mock adapter 可作为 fallback，但必须标注为 fallback/mock。

## 推荐升级后的工程目录结构

```text
挑战杯/
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
      cli.py
      main.py
      api/
        http_routes.py
        schemas.py
        errors.py
    core/
    input/
      voice_query_normalizer.py
    prompts/
    memory/
    knowledge/
      indexes/
      ingestion/
    generation/
    self_check/
    feedback/
    services/
      model_client.py
      deepseek_client.py
      structured_output.py
      embedding_provider.py
      retry_policy.py
    voice/
      session_state.py
      transport.py
      vad.py
      asr.py
      terminology.py
      tts.py
      barge_in.py
      metrics.py
    observability/
      trace_exporter.py
      metrics.py
      log_schema.py
  tests/
    unit/
    integration/
      app_loop/
      rag_pipeline/
      answer_pipeline/
      feedback_loop/
      voice_loop/
    e2e/
      scenarios/
      fixtures/
  scripts/
    ingest_sources.py
    run_eval.py
    export_trace_report.py
    validate_deployment.py
  docs/
    task_plus.md
    spec_plus.md
    harness_plus.md
    upgrade_audit.md
    api_contracts.md
    eval_plan.md
    deployment_checklist.md
```

## 依赖引入决策表

| 依赖 | 用途 | 模块 | Python 3.13 / Windows | 是否必须 | 轻量替代 | 安装命令 | 写入文件 | fallback |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `httpx` | DeepSeek HTTP 调用 | P3+ `services` | 待确认，通常支持 | Should | `urllib.request` | `"D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pip install httpx` | `pyproject.toml` | 标准库 HTTP client |
| `tenacity` | 重试、退避、限流恢复 | P3+ | 待确认 | Could | 自研 retry policy | 同上替换包名 | `pyproject.toml` | `services/retry_policy.py` |
| `fastapi` + `uvicorn` | HTTP API | P2+ | 待确认 | Should | CLI + 标准库 `http.server` smoke | 同上 | `pyproject.toml` | 先实现 CLI pipeline |
| `pydantic` | API schema / 配置校验 | P2+/P3+ | 待确认 | Could | dataclass + 手写校验 | 同上 | `pyproject.toml` | 继续使用 `core.contracts` |
| `chromadb` | 向量库 | P4+ | 待确认 | Could | 文件/SQLite + adapter | 同上 | `pyproject.toml` | `SimpleVectorIndex` fallback |
| `faiss-cpu` | 本地向量检索 | P4+ | Windows/Python 3.13 风险较高 | Could | Chroma 或纯 Python adapter | 同上 | `pyproject.toml` | 不作为 Must |
| `sentence-transformers` | 本地 embedding | P4+ | PyTorch/Python 3.13 风险 | Could | DeepSeek/远程 embedding adapter 或 mock | 同上 | `pyproject.toml` | mock embedding |
| `pypdf` / `pymupdf` | PDF 文本/layout | P5+ | 待确认 | Could | 先文本样例 + mock layout | 同上 | `pyproject.toml` | `pdf_ingestor` mock |
| `pytesseract` | OCR | P5+ | 需系统依赖，风险高 | Won't for first pass | 人工 OCR 样例 | 不默认安装 | 不写入 | OCR adapter 接口 |
| `opentelemetry-sdk` | Trace/metrics 标准化 | P8+ | 待确认 | Could | JSON trace export | 同上 | `pyproject.toml` | `trace_exporter.py` |
| `ragas` / `deepeval` / `trulens` | RAG/生成质量评测 | P8+ | 待确认，依赖可能较重 | Could | 轻量自研指标 | 不默认安装 | 待确认 | 自研 eval runner |

## P0+：MVP 设计一致性审计与升级基线

1. 对应目标：固化真实现状、差距、风险和基线测试，作为后续阶段准入依据。
2. 当前代码现状：P0-P6 模块存在；P7/P8/API/ModelClient 缺失；`SimpleVectorIndex` 为 token Jaccard；生成器为规则实现。
3. 目标目录：新增 `docs/升级规划/upgrade_audit.md`，必要时新增 `scripts/audit_project_state.py`。
4. 新增文件：`docs/升级规划/upgrade_audit.md`、可选 `scripts/audit_project_state.py`。
5. 修改文件：`docs/项目总控/STATUS.md` 追加 P0+ 记录。
6. 禁止修改：`src/**` 业务代码不得在 P0+ 重构。
7. 核心设计：建立“设计要求 -> MVP 状态 -> 差距 -> 任务 -> 验收”矩阵。
8. 核心接口：无新增运行接口；审计脚本只读文件系统。
9. 数据流：扫描文件 -> 读取旧文档 -> 对照用户缺口 -> 输出审计报告。
10. deepseek 接入：不得接入，只记录模型命名冲突。
11. 配置：记录虚拟环境路径和配置文件现状。
12. 日志：审计输出包含扫描时间、命令、文件清单。
13. 异常处理：无法读取文件时标记 `待确认`，不得编造。
14. 测试方案：运行现有全量测试作为 baseline。
15. 评估方案：检查 P0-P6 状态是否与 `STATUS.md` 一致。
16. 兼容要求：只读操作不改变 MVP。
17. 迁移步骤：无迁移。
18. 回滚方案：删除新增审计文档即可回滚。
19. 验收方式：`pytest` 通过，审计报告覆盖所有模块。
20. 停止条件：旧文档与代码出现核心架构冲突且无法判断。

## P1+：中文编码、配置体系与工程基础修复

1. 对应目标：修复中文编码风险、配置和依赖边界。
2. 当前代码现状：配置存在但模型/embedding 为 `pending_confirmation`；本次扫描未发现典型乱码，但用户明确要求作为最高优先级工程任务。
3. 目标目录：保留现有结构，新增编码/配置测试。
4. 新增文件：`tests/unit/core/test_encoding_and_config.py`、`.env.example`、可选 `scripts/audit_text_encoding.py`。
5. 修改文件：`README.md`、`pyproject.toml`、`configs/*.yaml`、`docs/项目总控/STATUS.md`。
6. 禁止修改：不得修改业务规则绕过测试。
7. 核心设计：所有文本文件按 UTF-8 读取；配置缺失必须显式失败或标记 `待确认`。
8. 核心接口：`load_settings()` 增加 providers/model/env 校验；`parse_simple_yaml()` 保持兼容。
9. 数据流：配置文件 + 环境变量 -> Settings -> 模块读取。
10. deepseek 接入：只增加配置键，不发起真实调用。
11. 配置项：`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`、`DEEPSEEK_TIMEOUT_SECONDS`、`DEEPSEEK_MAX_RETRIES`、`DEEPSEEK_TEMPERATURE`。
12. 日志：配置校验日志不得输出密钥值。
13. 异常处理：缺密钥时测试使用 mock，不访问真实外部服务。
14. 测试方案：中文样例读取、Prompt 模板读取、配置缺失错误、Windows 路径命令文档检查。
15. 评估方案：中文 query 能通过 input/RAG/generation smoke。
16. 兼容要求：现有 57 个测试不得回退。
17. 迁移步骤：先测试暴露问题，再修复编码，再更新配置。
18. 回滚方案：保留旧配置默认，新增校验可通过开关临时降级。
19. 验收方式：全量测试 + 中文回归测试通过。
20. 停止条件：发现大量乱码需要人工确认原文含义。

## P2+：应用入口 / API 层与服务运行闭环

1. 对应目标：建立可运行入口和 API/CLI 边界。
2. 当前代码现状：无 `src/app`；测试直接调用模块。
3. 目标目录：新增 `src/app`、`src/app/api`、`tests/integration/app_loop`。
4. 新增文件：`src/app/cli.py`、`src/app/main.py`、`src/app/api/schemas.py`、`src/app/api/errors.py`、可选 `src/app/api/http_routes.py`。
5. 修改文件：`configs/app.yaml`、`README.md`、`docs/接口与部署/api_contracts.md`。
6. 禁止修改：API 层不得直接写 RAG、生成、记忆、自检逻辑。
7. 核心设计：`AppPipeline` 串联 P1-P6 模块，API/CLI 只做输入输出转换。
8. 核心接口：`run_text_query(request: TextQueryRequest) -> TextQueryResponse`；`health_check() -> HealthStatus`。
9. 数据流：request -> QueryObject -> retrieval -> generation -> self_check -> response + run_trace。
10. deepseek 接入：P2+ 不直接调用模型；预留 request trace 字段。
11. 配置项：`app.entry_mode`、`app.host`、`app.port`、`app.log_level`、`app.trace_enabled`。
12. 日志：每个请求记录 run_id、latency、action_decision、error_code。
13. 异常处理：统一 `error_code`、`message`、`run_id`；不泄露堆栈给用户。
14. 测试方案：CLI smoke、health check、错误响应、模块边界测试。
15. 评估方案：P8+ 之前只做 smoke，不做完整评测。
16. 兼容要求：旧模块导入路径保持。
17. 迁移步骤：先 CLI，再可选 HTTP API。
18. 回滚方案：API 失败时保留 CLI 和模块测试。
19. 验收方式：一条命令可完成文本问答 smoke。
20. 停止条件：需要选择 FastAPI 且依赖兼容性无法确认。

## P3+：`deepseek-flash` 统一模型调用层与结构化生成升级

1. 对应目标：统一模型调用并升级生成链路。
2. 当前代码现状：`GroundedAnswerGenerator` 规则化生成，无外部 LLM 调用。
3. 目标目录：新增 `src/services`。
4. 新增文件：`src/services/model_client.py`、`src/services/deepseek_client.py`、`src/services/structured_output.py`、`src/services/retry_policy.py`、`tests/unit/services/test_model_client.py`。
5. 修改文件：`src/generation/generator.py`、`configs/providers.yaml`、`configs/app.yaml`、`tests/unit/generation/**`。
6. 禁止修改：业务模块不得直接 import `httpx` 或 DeepSeek client。
7. 核心设计：`ModelClient` 抽象 + `DeepSeekModelClient` + `MockModelClient`；生成器依赖接口注入。
8. 核心接口：`complete_structured(messages, schema, options) -> ModelResult`。
9. 数据流：PromptAssembler -> ModelClient -> StructuredOutputValidator -> AnswerEnvelope -> SelfCheck。
10. deepseek 接入：业务目标模型统一记录为 `deepseek-flash`；真实 API `model_id` 来自 `DEEPSEEK_MODEL` 或 `configs/providers.yaml`；缺密钥时用 mock。
11. 配置项：base_url、api_key_env、model、timeout、max_retries、temperature、rate_limit、fallback_enabled。
12. 日志：记录 provider、model_alias、request_id、latency、retry_count，不记录 prompt 全文和密钥。
13. 异常处理：超时、429、5xx、结构化校验失败分别映射错误码。
14. 测试方案：mock client 成功、超时重试、结构化字段缺失、fallback 到规则 generator。
15. 评估方案：P8+ 记录生成质量；P3+ 只验证 contract。
16. 兼容要求：无密钥环境全量测试仍可跑。
17. 迁移步骤：先引入接口和 mock，再改 generator 注入，最后接 DeepSeek adapter。
18. 回滚方案：配置 `fallback_enabled=true` 使用规则 generator。
19. 验收方式：生成模块测试证明业务代码不直连模型。
20. 停止条件：需要真实 API Key 或真实外部服务才能继续。

## P4+：RAG 从轻量检索升级为工程化知识库

1. 对应目标：从 token Jaccard 过渡到 embedding 检索和知识库治理。
2. 当前代码现状：`SimpleVectorIndex.search()` 用 token set Jaccard；`HybridIndex` 依赖它。
3. 目标目录：扩展 `src/knowledge/indexes` 和 `src/services/embedding_provider.py`。
4. 新增文件：`src/services/embedding_provider.py`、`src/knowledge/indexes/vector_store.py`、`tests/unit/knowledge/test_vector_store_contract.py`。
5. 修改文件：`src/knowledge/indexes/vector_index.py`、`hybrid_index.py`、`retrieval_controller.py`、`configs/rag.yaml`。
6. 禁止修改：不得删除 `SimpleVectorIndex`，除非替代实现和回滚已验证。
7. 核心设计：`VectorStore` 接口、`EmbeddingProvider` 接口、`SimpleTokenSimilarityIndex` fallback 重命名或标注。
8. 核心接口：`embed(texts) -> list[list[float]]`；`upsert(chunks, embeddings)`；`search(query_embedding, top_k, filters)`。
9. 数据流：ingest -> chunk -> embedding -> vector store -> hybrid retrieval -> evidence gate。
10. deepseek 接入：如 DeepSeek 提供 embedding，必须通过 `EmbeddingProvider`，否则 mock/local provider。
11. 配置项：embedding provider、dimension、top_k、rrf_k、review_status_filter、fallback_index。
12. 日志：记录通道命中、embedding provider、fallback_reason。
13. 异常处理：embedding 失败时降级关键词和 token fallback，并标记 gate。
14. 测试方案：接口契约、fallback 标记、reviewed source filter、RAG regression。
15. 评估方案：新增检索 recall/precision 样例。
16. 兼容要求：无向量库依赖时项目仍可运行。
17. 迁移步骤：接口 -> fallback 标注 -> mock embedding -> 可选真实向量库。
18. 回滚方案：切回 `fallback_index=simple_token_similarity`。
19. 验收方式：日志和文档不再误称 SimpleVectorIndex 为工业级向量库。
20. 停止条件：新增向量库不支持 Python 3.13/Windows。

## P5+：多模态对象、页面区域与视觉检索链路升级

1. 对应目标：补齐真实多模态契约和视觉检索边界。
2. 当前代码现状：存在 `visual_ingestor.py` 和 `visual_page_index.py` 占位，缺 bbox/layout trace。
3. 目标目录：扩展 `src/knowledge/schemas.py` 和 ingestion/indexes。
4. 新增文件：`tests/unit/knowledge/test_multimodal_contracts.py`、可选 `data/demo/visual_samples/.gitkeep`。
5. 修改文件：`pdf_ingestor.py`、`visual_ingestor.py`、`visual_page_index.py`、`evidence_package.py`。
6. 禁止修改：视觉标签不得直接成为核心事实。
7. 核心设计：`ImageInput`、`PageRegion`、`BoundingBox`、`LayoutTrace`、`VisualEvidenceItem`。
8. 核心接口：`ingest_page_image(source_id, page_image) -> list[PageRegion]`；`search_visual_page(query, scene_state) -> list[VisualEvidence]`。
9. 数据流：PDF/image -> regions -> OCR/layout/mock -> visual index -> evidence_package visual refs。
10. deepseek 接入：不得用 LLM 视觉猜测事实；若用多模态模型，只能产出待验证标签。
11. 配置项：ocr_enabled、layout_enabled、visual_top_k、require_text_cross_check。
12. 日志：记录 page_id、region_count、bbox、cross_check_status。
13. 异常处理：无 OCR/layout 时标记 `visual_evidence_incomplete`。
14. 测试方案：bbox schema、layout trace、无文本交叉验证不可 core evidence。
15. 评估方案：P8+ 覆盖视觉证据命中率。
16. 兼容要求：不引入 OCR 重依赖也能通过 mock。
17. 迁移步骤：schema -> mock adapter -> evidence integration -> 可选真实 parser。
18. 回滚方案：关闭 `visual_search_enabled`。
19. 验收方式：多模态证据可追踪，但不夸大能力。
20. 停止条件：真实 OCR/PDF 依赖需大型系统安装。

## P6+：Prompt 资产库、教学策略与回滚机制升级

1. 对应目标：从小型 prompt store 升级为可版本化资产库。
2. 当前代码现状：`default_prompt_assets()` 只有两个模板。
3. 目标目录：新增 `assets/prompts` 或 `prompts/`，保留 `src/prompts` 代码。
4. 新增文件：`assets/prompts/*.yaml`、`tests/unit/prompts/test_prompt_versioning.py`、`tests/fixtures/prompt_snapshots/*.json`。
5. 修改文件：`src/prompts/asset_store.py`、`asset_models.py`、`router.py`、`configs/prompts.yaml`。
6. 禁止修改：不得把未审核 prompt 设为 active。
7. 核心设计：Prompt 元数据、版本、状态、适用任务、变量、风险边界、评测快照。
8. 核心接口：`load_prompt_assets(path) -> PromptAssetStore`；`activate_version(template_id, version)`；`rollback_prompt(template_id, version)`。
9. 数据流：asset files -> store -> router -> assembler -> model client。
10. deepseek 接入：Prompt 只组装 messages，不直接调用 DeepSeek。
11. 配置项：asset_dir、allowed_statuses、snapshot_dir、rollback_policy。
12. 日志：记录 template_id、version、status、snapshot_id。
13. 异常处理：变量缺失、状态非法、快照缺失均返回结构化错误。
14. 测试方案：版本加载、draft 不可路由、快照对比、回滚。
15. 评估方案：P8+ 使用 prompt snapshot 跑回归。
16. 兼容要求：旧默认模板作为 fallback。
17. 迁移步骤：外置现有模板 -> 增加教学/语音/自检模板 -> 加快照。
18. 回滚方案：切回 parent_version。
19. 验收方式：业务代码中无散落 prompt 长文本。
20. 停止条件：Prompt 内容需要人工审核但无人确认。

## P7+：语音交互模块补齐

1. 对应目标：实现语音状态机、ASR/TTS adapter、barge-in 和指标。
2. 当前代码现状：无 `src/voice`，只有 `VoiceTurnEvent` 契约。
3. 目标目录：新增 `src/voice` 和 voice 测试。
4. 新增文件：`src/voice/session_state.py`、`transport.py`、`vad.py`、`asr.py`、`terminology.py`、`tts.py`、`barge_in.py`、`metrics.py`、`configs/voice.yaml`。
5. 修改文件：`src/input/voice_query_normalizer.py`、`src/core/contracts.py` 可扩展 voice contracts。
6. 禁止修改：voice 不得直接生成事实答案。
7. 核心设计：Mock ASR/TTS + Provider interface + voice state machine。
8. 核心接口：`transcribe(audio) -> ASRResult`；`normalize_voice_query(...) -> VoiceQueryObject`；`synthesize(answer) -> TTSResult`。
9. 数据流：audio/mock transcript -> ASR -> term correction -> query object -> main pipeline -> spoken answer -> TTS -> barge-in -> feedback。
10. deepseek 接入：ASR 纠错或 spoken style 如需模型，必须通过 ModelClient。
11. 配置项：asr_provider、tts_provider、vad_threshold、low_confidence_threshold、max_spoken_seconds。
12. 日志：ASR confidence、VAD 状态、barge-in latency、TTS duration。
13. 异常处理：低置信 ASR 进入澄清；TTS 失败保留文本回答。
14. 测试方案：状态机、低置信、术语纠错、barge-in、原始音频不落盘。
15. 评估方案：P8+ 增加 ASR entity accuracy 和 latency 指标。
16. 兼容要求：无真实 Provider 时 mock voice loop 可跑。
17. 迁移步骤：契约 -> mock -> pipeline -> 可选真实 Provider。
18. 回滚方案：关闭 voice entry，保留文本 API。
19. 验收方式：模拟语音 E2E 通过。
20. 停止条件：需要真实音频设备、真实 Provider 或密钥。

## P8+：评测体系、E2E、Trace 与部署治理

1. 对应目标：建立可复现评测、trace 导出和部署治理。
2. 当前代码现状：无 `tests/e2e`、无 eval script、无 trace report。
3. 目标目录：新增 `tests/e2e`、`scripts/run_eval.py`、`observability`。
4. 新增文件：`configs/evals.yaml`、`scripts/run_eval.py`、`scripts/export_trace_report.py`、`scripts/validate_deployment.py`、`docs/评测与验收/eval_plan.md`。
5. 修改文件：`src/core/tracing.py`、可新增 `src/observability/**`。
6. 禁止修改：不得伪造 evidence_package/check_report 让评测通过。
7. 核心设计：EvalDataset、EvalRunner、MetricsAggregator、TraceExporter、DeploymentConfigValidator。
8. 核心接口：`run_eval_suite(name) -> EvaluationReport`；`export_trace_report(run_id) -> path`。
9. 数据流：eval case -> app pipeline -> run_trace -> metrics -> report。
10. deepseek 接入：评测默认 mock，可选真实模型套件单独标记成本和密钥需求。
11. 配置项：suite、required_pass_rate、latency_budget_ms、trace_fields、deployment_required_env。
12. 日志：每个 eval case 有 run_id、失败阶段、失败原因。
13. 异常处理：数据问题和系统问题分开标记。
14. 测试方案：文本 E2E、反馈 E2E、语音 mock E2E、安全边界、证据不足。
15. 评估方案：检索、生成、自检、反馈、记忆、语音指标。
16. 兼容要求：本地无密钥可跑 smoke eval。
17. 迁移步骤：trace export -> eval fixtures -> E2E -> deployment validation。
18. 回滚方案：评测退化时回滚相关阶段配置或 prompt/model。
19. 验收方式：`run_eval.py --suite smoke` 可运行并导出报告。
20. 停止条件：评测依赖不兼容或需要真实生产服务。

## P9+：全链路工业级验收与演示交付

1. 对应目标：完成全链路验收和演示交付。
2. 当前代码现状：无统一 plus 验收报告。
3. 目标目录：`docs/评测与验收/release_acceptance.md`、`docs/评测与验收/demo_audit_report.md`。
4. 新增文件：最终验收报告、演示脚本、风险关闭表。
5. 修改文件：`README.md`、`docs/项目总控/STATUS.md`。
6. 禁止修改：不得为交付临时绕过 harness。
7. 核心设计：以阶段报告 + 测试 + eval + trace 证明工业级。
8. 核心接口：无新增接口，汇总已有入口。
9. 数据流：全量测试 -> eval -> trace export -> demo audit -> acceptance。
10. deepseek 接入：真实模型演示必须确认 model id、密钥来源和成本边界。
11. 配置项：release profile、demo profile、mock/real provider profile。
12. 日志：保留最终验收命令和输出摘要。
13. 异常处理：任何 Must 阶段失败则不发布。
14. 测试方案：全量 `pytest`、compileall、smoke eval、deployment validate。
15. 评估方案：工业级维度逐项打勾。
16. 兼容要求：保留 MVP fallback。
17. 迁移步骤：冻结配置 -> 跑验收 -> 生成报告。
18. 回滚方案：回到上一通过阶段。
19. 验收方式：报告明确是否达到工业级，不能模糊表述。
20. 停止条件：关键风险未关闭或用户未确认。
