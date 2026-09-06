# 翼览无余工业级升级约束与验收 Harness

本文件由工业级升级规划生成，用于指导 Codex 后续阶段化开发。

## 需要调用的 skill

后续让 Codex 依据本文执行开发时，提示词必须明确声明并按顺序调用以下 skill：

1. `using-superpowers`：阶段开始前必须调用。
2. `writing-plans`：阶段实施前必须调用。
3. `systematic-debugging`：遇到 bug、测试失败、乱码、模型/RAG/语音/API 异常时必须调用。
4. `verification-before-completion`：阶段完成前必须调用。
5. `browser:control-in-app-browser`：需要验证本地服务、报告页面或可视化演示时调用。

## 全局禁止事项

- 禁止推翻 `docs/项目总控/task.md`、`docs/项目总控/spec.md`、`docs/项目总控/harness.md` 的原设计边界。
- 禁止跳过阶段或提前实现后续阶段功能。
- 禁止把 `SimpleVectorIndex` 的 token Jaccard 描述为工业级 embedding 向量检索。
- 禁止把规则型 `GroundedAnswerGenerator` 描述为 LLM 智能生成。
- 禁止把 `VoiceTurnEvent` 契约描述为完整语音系统。
- 禁止业务模块直接调用 `deepseek-flash` 或任意模型 API。
- 禁止硬编码 API Key、Base URL、模型名、Windows 绝对路径、业务规则和安全阈值。
- 禁止删除测试来规避失败。
- 禁止访问真实生产数据库、真实密钥或真实外部服务。
- 禁止污染全局 Python 环境。
- 禁止为了“工业级”引入与航空科普和比赛目标无关的复杂系统。

## 全局必须事项

- 所有 Python 命令使用 `"D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe"`。
- 每个阶段结束后记录：阶段编号、完成内容、修改/新增/删除文件、测试命令、测试结果、是否违反 harness、风险与待确认、是否可进入下一阶段。
- 每个阶段结束后项目必须处于可测试状态。
- 新依赖必须先确认 Python 3.13 和 Windows 兼容性，并写入依赖文件。
- deepseek 模型调用必须经过统一 `ModelClient` / `LLMClient`。
- 密钥只允许来自环境变量或安全配置，不得写入代码和文档示例值。

## P0+：MVP 设计一致性审计与升级基线

- 约束目标：只做真实审计和基线记录，不改业务实现。
- 允许修改：`docs/升级规划/upgrade_audit.md`、`docs/项目总控/STATUS.md`、可选只读审计脚本。
- 禁止修改：`src/**`、`tests/**` 业务逻辑、旧设计文档正文。
- 禁止架构偏差：不得把审计写成新架构重设计；不得把未知能力写成已完成。
- 禁止依赖：不得新增运行依赖。
- 接口契约：沿用现有 `core.contracts` 名称。
- 模块边界：审计必须覆盖 core/input/prompts/memory/knowledge/generation/self_check/feedback/voice/API/eval。
- deepseek 边界：只记录模型命名冲突，不调用 API。
- 配置安全：不得要求真实密钥。
- 测试要求：运行 baseline `pytest`。
- 日志要求：审计报告记录扫描命令和时间。
- 异常要求：信息不足标 `待确认`。
- 性能要求：审计脚本只读仓库，不做大型扫描下载。
- 回滚要求：删除新增审计文档即可。
- 完成检查：模块分类完整；P7/P8/API/ModelClient 缺失明确；MVP 占位标注清楚。
- 停止条件：旧文档、用户要求和代码出现不可调和设计冲突。

## P1+：中文编码、配置体系与工程基础修复

- 约束目标：修复基础工程问题，不改变业务链路语义。
- 允许修改：`configs/**`、`README.md`、`pyproject.toml`、`.env.example`、编码/配置测试、`docs/项目总控/STATUS.md`。
- 禁止修改：不得重写 RAG、生成、自检、反馈业务逻辑。
- 禁止架构偏差：不得用硬编码默认值掩盖配置缺失。
- 禁止依赖：不得引入大型框架。
- 接口契约：`load_settings` 错误必须结构化。
- 模块边界：配置只提供参数，不承载业务规则实现。
- deepseek 边界：只增加配置项，不真实调用。
- 配置安全：`.env.example` 只写变量名，不写真实值。
- 测试要求：中文 UTF-8 回归、配置缺失测试、全量测试。
- 日志要求：配置校验日志不得输出密钥。
- 异常要求：乱码无法自动修复时停止并标 `待确认`。
- 性能要求：编码扫描应可在本地快速完成。
- 回滚要求：保留旧配置键兼容。
- 完成检查：中文样例可读；虚拟环境命令写入文档；全量测试通过。
- 停止条件：发现大量不可逆乱码或依赖需要全局安装。

## P2+：应用入口 / API 层与服务运行闭环

- 约束目标：建立入口，不把业务逻辑塞进 API。
- 允许修改：`src/app/**`、`src/services/**` 中 pipeline facade、`configs/app.yaml`、API 测试、`docs/接口与部署/api_contracts.md`。
- 禁止修改：不得在 API 层直接操作知识库底层索引、长期记忆或模型 API。
- 禁止架构偏差：不得为 API 复制一套独立问答逻辑。
- 禁止依赖：FastAPI/uvicorn 未确认兼容前不得作为 Must。
- 接口契约：请求/响应必须包含 `run_id`、错误响应必须包含 `error_code`。
- 模块边界：API/CLI 只做输入输出适配，调用 pipeline/service。
- deepseek 边界：P2+ 不直接接模型。
- 配置安全：host/port/log level 可配置。
- 测试要求：CLI smoke、health check、错误响应、边界测试。
- 日志要求：记录 request_id/run_id/action_decision/latency。
- 异常要求：用户响应不暴露堆栈。
- 性能要求：health check 不初始化重依赖。
- 回滚要求：HTTP 失败时 CLI 仍可用。
- 完成检查：一条命令可运行文本问答 smoke；旧测试不退化。
- 停止条件：需要新增 Web 框架但兼容性无法确认。

## P3+：`deepseek-flash` 统一模型调用层与结构化生成升级

- 约束目标：统一模型调用，业务模块不得直连 DeepSeek。
- 允许修改：`src/services/model_client.py`、`deepseek_client.py`、`structured_output.py`、`retry_policy.py`、`src/generation/**`、`configs/providers.yaml`、相关测试。
- 禁止修改：不得在 `input`、`knowledge`、`memory`、`feedback` 中散落模型 HTTP 调用。
- 禁止架构偏差：不得绕过 `answer_envelope` 结构化输出。
- 禁止依赖：不得要求真实密钥跑默认测试。
- 接口契约：`complete_structured()` 返回结构化 `ModelResult`，失败有 error code。
- 模块边界：规则 generator 只能作为 fallback/mock。
- deepseek 边界：模型名、API Key、Base URL、timeout、retry、temperature 全部来自配置或环境变量。
- 配置安全：日志不得输出 prompt 全文、密钥、Authorization header。
- 测试要求：mock 成功、超时、429、结构化失败、fallback。
- 日志要求：provider、model_alias、latency、retry_count、fallback_reason。
- 异常要求：超时/限流/结构化失败分类处理。
- 性能要求：默认 timeout 和 retry 有上限。
- 回滚要求：`fallback_enabled=true` 可恢复规则生成。
- 完成检查：`rg "deepseek|httpx|requests"` 不应在业务模块发现直连调用。
- 停止条件：无法通过 `DEEPSEEK_MODEL` 或 `configs/providers.yaml` 确认 `deepseek-flash` 对应的真实 API `model_id`，或需要真实密钥。

## P4+：RAG 从轻量检索升级为工程化知识库

- 约束目标：工程化 RAG，不伪装轻量检索。
- 允许修改：`src/knowledge/**`、`src/services/embedding_provider.py`、`configs/rag.yaml`、RAG 测试、`scripts/ingest_sources.py`。
- 禁止修改：不得把用户反馈写入事实库；不得删除 source/evidence gate。
- 禁止架构偏差：不得只接向量 top_k 而无来源、review_status、missing_evidence。
- 禁止依赖：Chroma/FAISS/sentence-transformers 未核验前不得设为 Must。
- 接口契约：`EvidencePackage` 必须保留 source、gate、missing_evidence、generation_boundary。
- 模块边界：EmbeddingProvider 和 VectorStore 可替换。
- deepseek 边界：embedding 如用 DeepSeek 也必须走 provider adapter。
- 配置安全：top_k、阈值、provider 不得硬编码。
- 测试要求：fallback 标注、reviewed filter、检索质量样例。
- 日志要求：channel_hits、fallback_reason、embedding_provider、gate_status。
- 异常要求：embedding 失败降级并标记，不静默装作向量成功。
- 性能要求：top_k 和 chunk size 有上限。
- 回滚要求：可切回 simple token similarity fallback。
- 完成检查：文档和日志明确 `SimpleVectorIndex` 是 fallback。
- 停止条件：向量库依赖不兼容或需要大型服务。

## P5+：多模态对象、页面区域与视觉检索链路升级

- 约束目标：补齐视觉契约，不夸大多模态能力。
- 允许修改：`src/knowledge/ingestion/**`、`src/knowledge/indexes/visual_page_index.py`、`src/knowledge/schemas.py`、多模态测试。
- 禁止修改：不得让视觉标签单独支撑核心航空事实。
- 禁止架构偏差：不得把字段预留描述为视觉理解完成。
- 禁止依赖：OCR/PDF layout 重依赖未确认前不得 Must。
- 接口契约：bbox、layout trace、page id、source id 必须可追踪。
- 模块边界：视觉检索输出证据候选，最终仍过 evidence gate。
- deepseek 边界：不得用 LLM 视觉猜测填补证据缺口。
- 配置安全：OCR 开关、layout 开关、visual_top_k 配置化。
- 测试要求：bbox schema、layout trace、text_cross_check。
- 日志要求：page_id、region_count、cross_check_status。
- 异常要求：缺 layout/OCR 标记 incomplete。
- 性能要求：页面图像处理默认限制文件大小和页数。
- 回滚要求：`visual_search_enabled=false` 可关闭。
- 完成检查：视觉 evidence 不越权为核心事实。
- 停止条件：必须安装系统级 OCR 或处理真实敏感图片。

## P6+：Prompt 资产库、教学策略与回滚机制升级

- 约束目标：Prompt 资产版本化、可评测、可回滚。
- 允许修改：`src/prompts/**`、`configs/prompts.yaml`、`assets/prompts/**`、Prompt 测试。
- 禁止修改：不得在业务代码硬编码新 prompt 长文本。
- 禁止架构偏差：不得让 Prompt 补充 RAG 没有的事实。
- 禁止依赖：不得引入重型 prompt 平台。
- 接口契约：Prompt 必须有 template_id、version、status、variables、constraints。
- 模块边界：PromptRouter 只选模板，不访问底层知识库。
- deepseek 边界：PromptAssembler 不调用模型。
- 配置安全：Prompt 文件不得含 API Key 或真实隐私样例。
- 测试要求：draft/candidate 不可路由、变量缺失、快照、回滚。
- 日志要求：template_id、version、snapshot_id、fallback_reason。
- 异常要求：未审核 prompt 进入 active 时失败。
- 性能要求：Prompt 加载可缓存。
- 回滚要求：parent_version 可恢复。
- 完成检查：Prompt 资产覆盖教学、知识讲解、语音、自检、兜底。
- 停止条件：Prompt 内容需要人工确认但未确认。

## P7+：语音交互模块补齐

- 约束目标：补齐语音入口/出口，同时保持证据链。
- 允许修改：`src/voice/**`、`src/input/voice_query_normalizer.py`、`configs/voice.yaml`、voice 测试。
- 禁止修改：不得让 voice 模块直接回答事实；不得默认保存原始音频。
- 禁止架构偏差：不得使用端到端 speech-to-speech 模型绕过 RAG 和自检。
- 禁止依赖：真实 ASR/TTS SDK 未确认前不得 Must。
- 接口契约：`voice_query_object` 必须含 transcript、confidence、normalized_query、scene_object_id、needs_clarification。
- 模块边界：ASR/TTS 是 adapter，voice 输出仍进入 P1-P6 主链路。
- deepseek 边界：术语纠错如需模型，走 ModelClient。
- 配置安全：Provider、阈值、音频保存策略配置化。
- 测试要求：低置信澄清、术语纠错、barge-in、spoken length、音频不落盘。
- 日志要求：ASR confidence、VAD state、TTS state、barge-in latency。
- 异常要求：TTS 失败不重跑 RAG；保留文本输出。
- 性能要求：spoken answer 有时长上限。
- 回滚要求：关闭 voice，文本入口仍可用。
- 完成检查：Mock voice E2E 通过；真实 Provider 待确认不阻塞 mock。
- 停止条件：需要真实麦克风、真实密钥或生产语音服务。

## P8+：评测体系、E2E、Trace 与部署治理

- 约束目标：证明系统可评测、可审计、可部署。
- 允许修改：`configs/evals.yaml`、`scripts/run_eval.py`、`scripts/export_trace_report.py`、`tests/e2e/**`、`src/observability/**`、评测文档。
- 禁止修改：不得为评测通过伪造证据包、自检报告或最终答案。
- 禁止架构偏差：不得只测 happy path；必须测证据不足、安全、场景错位、低置信语音。
- 禁止依赖：RAGAS/DeepEval/TruLens 未确认前不得 Must。
- 接口契约：评测必须通过正式 pipeline 或 API。
- 模块边界：EvalRunner 只调用系统，不修改业务行为。
- deepseek 边界：默认 smoke eval 使用 mock；真实模型 eval 单独 profile。
- 配置安全：评测报告脱敏，不输出密钥和原始音频。
- 测试要求：文本、反馈、语音 mock、安全、证据不足 E2E。
- 日志要求：run_trace 关联 query、scene、prompt、memory、retrieval、answer、check、rewrite、voice。
- 异常要求：失败报告定位阶段和原因。
- 性能要求：smoke suite 有总时长上限。
- 回滚要求：评测退化时回滚 prompt/model/config。
- 完成检查：`run_eval.py --suite smoke` 和 trace export 可运行。
- 停止条件：评测依赖无法安装或要求生产服务。

## P9+：全链路工业级验收与演示交付

- 约束目标：统一交付，不带失败进入完成声明。
- 允许修改：`docs/评测与验收/release_acceptance.md`、`docs/评测与验收/demo_audit_report.md`、`README.md`、`docs/项目总控/STATUS.md`。
- 禁止修改：不得临时修改业务逻辑粉饰演示。
- 禁止架构偏差：不得用“测试通过”替代工业级评测通过。
- 禁止依赖：不得新增依赖。
- 接口契约：验收只汇总已实现接口。
- 模块边界：交付文档不改变运行行为。
- deepseek 边界：真实模型演示必须确认配置和成本；无密钥则用 mock 报告说明。
- 配置安全：交付包不包含 `.env` 真实值。
- 测试要求：全量 pytest、compileall、smoke eval、deployment validate。
- 日志要求：记录命令、退出码、关键输出摘要。
- 异常要求：任何 Must 阶段失败则标记未达工业级。
- 性能要求：报告记录主要链路延迟。
- 回滚要求：回到上一通过阶段和配置。
- 完成检查：P0+ 到 P8+ 报告齐全；风险清单关闭或明确待确认；演示可复现。
- 停止条件：用户未确认关键待确认项或 Must 阶段未通过。

## 阶段完成统一检查清单

- [ ] 阶段编号与 `task_plus.md`、`spec_plus.md` 一致。
- [ ] 本阶段没有提前实现后续阶段功能。
- [ ] 所有新增依赖已做 Python 3.13 / Windows 兼容性判断。
- [ ] 所有 Python 命令使用指定虚拟环境解释器。
- [ ] 没有硬编码密钥、模型名、Base URL、业务规则和安全阈值。
- [ ] 没有删除测试规避失败。
- [ ] 规则实现、Mock、fallback 均已明确标注。
- [ ] 测试和验证命令已运行，并读取输出。
- [ ] `docs/项目总控/STATUS.md` 或阶段报告已记录结果。
