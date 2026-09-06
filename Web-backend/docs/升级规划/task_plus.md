# 翼览无余工业级升级任务说明

本文件由工业级升级规划生成，用于指导 Codex 后续阶段化开发。

## 需要调用的 skill

后续让 Codex 按本文执行开发时，提示词必须明确声明并按顺序调用以下 skill：

1. `using-superpowers`：每次开始阶段任务前调用，用于发现当前阶段还需要哪些 skill。
2. `writing-plans`：每个阶段进入多文件实现前调用，用于生成可核查实施计划。
3. `systematic-debugging`：遇到测试失败、链路异常、乱码、检索偏差、模型输出不合约时调用。
4. `verification-before-completion`：每个阶段完成前调用，用于运行验证命令并确认结果。
5. `browser:control-in-app-browser`：仅当阶段涉及本地 API、WebSocket、可视化报告或演示页面验证时调用。

## 全局约束

- 运行环境固定为 `"D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe"`，所有 Python 命令必须使用该解释器。
- Windows 路径包含空格，命令中必须加引号。
- 不允许污染全局 Python 环境。
- 目标业务模型统一采用 `deepseek-flash`。
- 真实 DeepSeek API `model_id` 不在业务代码中硬编码，必须通过 `DEEPSEEK_MODEL` 环境变量或 `configs/providers.yaml` 配置提供，并在 P3+ 阶段核验。
- 任何阶段都不得把 MVP 占位、接口预留、规则实现、Mock 实现描述为真实工业级能力。

## MVP 当前状态审计摘要

### 仓库扫描摘要

- 已发现目录：`src/`、`tests/`、`configs/`、`docs/`、`scripts/`。
- 已发现核心模块：`core`、`input`、`prompts`、`memory`、`knowledge`、`generation`、`self_check`、`feedback`。
- 已发现测试：`tests/unit`、`tests/integration/rag_pipeline`、`tests/integration/answer_pipeline`、`tests/integration/feedback_loop`。
- 已发现配置：`configs/app.yaml`、`configs/providers.yaml`、`configs/prompts.yaml`、`configs/memory.yaml`、`configs/rag.yaml`。
- 已发现脚本：`scripts/ingest_sources.py`。
- 已发现旧文档：`docs/项目总控/task.md`、`docs/项目总控/spec.md`、`docs/项目总控/harness.md`、`docs/项目总控/AUTO_DEV.md`、`docs/项目总控/STATUS.md`。
- 已发现缺失项：无 `src/app`、无 `src/voice`、无 `configs/voice.yaml`、无 `configs/evals.yaml`、无 `scripts/run_eval.py`、无 `scripts/export_trace_report.py`、无 `tests/e2e`、无统一 `ModelClient` / `LLMClient`。
- 与用户提供的 MVP 缺口描述基本一致。

### 设计一致性审计表

| 模块 | 审计分类 | 当前 MVP 现状 | 差距 | 升级任务 | 验收标准 |
| --- | --- | --- | --- | --- | --- |
| `core` 状态机与契约 | 已符合设计文档 | 已有动作枚举、状态机、基础 contract、`VoiceTurnEvent` 契约 | 状态仍偏模块测试级，缺少服务入口 trace 汇总 | P0+ 固化审计基线，P2+ 接入运行入口 | 契约变更有测试，run_trace 能贯穿 API 到输出 |
| `input` 场景绑定 | 部分符合但仍是 MVP | 已有 `QueryObject`、`SceneBinder` | 未接入真实 API/语音输入，复杂指代能力有限 | P2+、P7+ | 文本和语音入口都统一产出 query object |
| `prompts` | 部分符合但仍是 MVP | 只有默认解释和安全兜底模板 | 无完整教学策略、语音 prompt、评测快照、版本回滚 | P6+ | Prompt 有元数据、版本、快照、回滚和测试 |
| `memory` | 部分符合但仍是 MVP | 事件日志、内存 store、时间字段、候选治理已存在 | 仍是内存实现，缺少持久化策略和运行期治理报告 | P8+、P9+ | 记忆读写可审计，导出报告脱敏 |
| `knowledge/RAG` | 部分符合但仍是 MVP | `KeywordIndex`、`SimpleVectorIndex`、`HybridIndex`、source/scene registry | `SimpleVectorIndex` 是 token Jaccard，不是 embedding；无向量库、PDF layout、OCR、视觉页面索引 | P4+、P5+ | 不再把 token Jaccard 描述为向量检索；可切换 embedding adapter |
| 多模态 | 接口预留 / 待工业化升级 | 有 visual/page 相关占位和字段 | 无真实图片区域、bbox、layout trace、页面图像索引 | P5+ | 图像输入、bbox、layout trace、视觉检索链路有契约和测试 |
| `generation` | 规则实现 / 待工业化升级 | `GroundedAnswerGenerator` 规则化拼装答案 | 未接入 `deepseek-flash`，无结构化输出校验和失败重试 | P3+ | 生成只能通过统一 ModelClient，规则 generator 作为 fallback/mock |
| `self_check` | 部分符合但仍是 MVP | 规则型 claim、证据、场景、安全检查 | 无 LLM Judge、RAGAS/DeepEval/TruLens、评测数据集和 trace report | P8+ | 评测脚本覆盖检索、生成、自检、安全、中文问答 |
| `feedback` | 部分符合但仍是 MVP | checkpoint、证据锁、受控改写已存在 | 缺少服务入口和 E2E 覆盖 | P2+、P8+ | 反馈链路通过正式 API 或 pipeline E2E |
| 语音交互 P7 | 尚未实现 | 只有 `VoiceTurnEvent` 契约 | 无 `src/voice`、VAD、ASR、TTS、barge-in、voice metrics | P7+ | Mock ASR/TTS 和真实 adapter 边界清楚，语音不绕过 RAG |
| 应用入口 / API | 尚未实现 | 当前是模块库 + 测试 | 无 CLI/API/服务启动/健康检查/错误响应 | P2+ | 至少有 CLI 或 HTTP API 可运行入口和健康检查 |
| 评测与部署治理 P8 | 尚未实现 | 只有单元/集成测试 | 无 `run_eval.py`、E2E、trace export、部署配置校验 | P8+ | 一条命令能运行 smoke eval 并导出报告 |
| 中文编码 | 工程问题 / 升级风险 | 本次扫描未发现常见 mojibake 模式，但用户明确指出存在乱码风险 | 需要逐文件 UTF-8 审计和中文回归测试 | P1+ | 源码、配置、prompt、测试数据统一 UTF-8，中文样例回归通过 |
| 配置与模型 | 工程问题 | `providers.yaml` 仍是 `pending_confirmation` | 缺模型调用抽象、密钥边界、超时/重试/限流/降级 | P3+ | 模型参数全部来自配置或环境变量，业务模块不能直连模型 |

## 参考开源项目和论文映射

| 参考来源 | 核心思想 | 可借鉴点 | 落地模块 | 为什么适合 | 不采用部分 | 不采用原因 |
| --- | --- | --- | --- | --- | --- | --- |
| LangGraph 官方文档 | 用显式图状态管理 Agent 工作流、回退、人审和持久化 | 状态机、checkpoint、回退边、人工复核点 | `core.state_machine`、P2+ API、P5/P6/P8 链路 | 当前系统已有状态机和 action decision，适合渐进增强 | 不直接整体迁移为 LangGraph | 避免推翻现有设计，且新增依赖需 Python 3.13 兼容核验 |
| LlamaIndex RAG 文档 | 数据摄取、节点、索引、检索、评估分层 | 文档解析、chunk 元数据、检索评估、adapter 边界 | P4+ RAG、P8+ 评测 | 与 evidence_package 和 source_registry 方向一致 | 不直接引入完整框架为核心 | 现有 RAG 已有轻量边界，本轮先抽象接口 |
| Haystack 项目 | Pipeline 化 RAG、Retriever/Ranker/Generator 可替换 | 检索管线组件化、多路召回、重排器接口 | P4+ `RetrievalController`、P3+ ModelClient | 适合把关键词、embedding、rerank 分离 | 不采用完整服务栈 | 防止过度工程化和依赖膨胀 |
| RAG 论文 Lewis et al. 2020 | 生成前检索外部知识以降低幻觉 | 将事实来源限制在检索证据中 | P4+、P3+、P5+ | 当前系统核心就是证据包驱动回答 | 不采用端到端训练 | 本项目是工程应用，不做模型训练 |
| ReAct 论文 | 推理和行动交替，工具调用可追踪 | 工具调用日志、动作分流、失败回退 | P2+ API、P3+ 模型层、P8+ trace | 可解释 Agent 行为，适合答辩展示 | 不开放任意工具自治 | 安全边界要求不能访问生产服务和真实密钥 |
| Self-RAG 论文 | 检索、生成、批判自反结合 | 自检、补检索、证据不足时保守输出 | P5+、P8+ | 与现有 self_check 和 action_decision 强匹配 | 不训练 critic token | 使用规则/评测/可选 LLM Judge 替代 |
| RAGAS | RAG 质量评估指标 | faithfulness、context precision/recall、answer relevancy 思路 | P8+ 评测 | 能把“工业级”落实到可复现指标 | 不默认作为 Must 依赖 | Python 3.13 和依赖重量需核验 |
| DeepEval / TruLens | LLM 应用评测、trace 与反馈分析 | 评测报告、回归数据集、质量门禁 | P8+ | 适合后续增强评测治理 | 不默认引入 | 依赖和 API 兼容待确认 |
| OpenTelemetry | traces、metrics、logs 统一观测 | run_id、span、指标导出、错误定位 | P8+、P9+ | 系统要求可追踪可审计 | 不一开始接完整 collector | 本地比赛项目先用 JSON/Markdown trace export |
| DeepSeek API 文档 | 模型 API、计费、模型名与调用参数 | `ModelClient` 配置、超时、重试、限流、模型名核验 | P3+ | 用户指定使用 DeepSeek 系列模型 | 不在业务代码硬编码模型名 | 官方模型 id 需运行前确认 |

## 工业级升级总目标

将当前 P0-P6 MVP 从“模块边界和规则闭环可验证”升级为“设计一致、入口可运行、模型接入统一、RAG 可迁移、语音与评测补齐、全链路可审计、可部署演示”的工业级版本。升级不推翻现有设计，不跳阶段，不把轻量实现包装为生产级能力。

## 工业级升级阶段总览

| 阶段 | 阶段名称 | 优先级 | 核心目标 | 主要依赖 |
| --- | --- | --- | --- | --- |
| P0+ | MVP 设计一致性审计与升级基线 | Must | 固化真实现状、差距、验收基线 | 旧三件套、代码扫描 |
| P1+ | 中文编码、配置体系与工程基础修复 | Must | 修复 UTF-8、配置、依赖、路径和基础质量问题 | P0+ |
| P2+ | 应用入口 / API 层与服务运行闭环 | Must | 从模块库升级为可运行服务或 CLI/API | P1+ |
| P3+ | `deepseek-flash` 统一模型调用层与结构化生成升级 | Must | 统一 ModelClient，接入结构化输出与 fallback | P2+ |
| P4+ | RAG 从轻量检索升级为工程化知识库 | Must | 从 token Jaccard 迁移到 embedding adapter 和知识库治理 | P3+ |
| P5+ | 多模态对象、页面区域与视觉检索链路升级 | Should | 补齐 bbox、layout trace、页面图像索引边界 | P4+ |
| P6+ | Prompt 资产库、教学策略与回滚机制升级 | Must | Prompt 版本化、快照评测、回滚和语音 Prompt | P3+ |
| P7+ | 语音交互模块补齐 | Should | Mock/真实 ASR/TTS adapter、barge-in、voice metrics | P2+、P3+、P6+ |
| P8+ | 评测体系、E2E、Trace 与部署治理 | Must | run_eval、E2E、trace report、部署配置校验 | P2+ 到 P7+ |
| P9+ | 全链路工业级验收与演示交付 | Must | 统一验收、演示审计、风险收敛 | P0+ 到 P8+ |

## 阶段任务详情

### P0+：MVP 设计一致性审计与升级基线

- 当前 MVP 存在的问题：P0-P6 已完成但 P7/P8/API/模型层缺失；部分模块是规则或内存实现；旧文档没有明确 plus 升级基线。
- 工业级升级目标：形成真实审计清单、差距矩阵、阶段准入规则和 baseline 测试命令。
- 涉及模块：`docs`、`src/**`、`tests/**`、`configs/**`、`scripts/**`。
- 参考设计文档内容：P0-P8 阶段、证据包、记忆治理、语音交互、评测治理。
- 参考思想：LangGraph 的显式状态/回退，OpenTelemetry 的 trace 基线。
- 阶段输入：旧 `task.md/spec.md/harness.md`、`STATUS.md`、当前代码、用户提供的缺口事实。
- 阶段输出：`docs/升级规划/upgrade_audit.md` 或 P0+ 报告、阶段差距矩阵、基线测试记录。
- 完成标准：所有模块标注为已符合、部分符合、尚未实现、工程问题或设计冲突；不得把 MVP 占位描述为生产能力。
- 依赖关系：所有后续阶段依赖 P0+。
- 风险与待确认：DeepSeek 真实 model_id、中文乱码范围、是否采用 HTTP API 还是 CLI 优先。

### P1+：中文编码、配置体系与工程基础修复

- 当前 MVP 存在的问题：用户明确指出中文文本存在乱码风险；运行环境和依赖边界未在代码层强制；`providers.yaml` 仍是 `pending_confirmation`。
- 工业级升级目标：统一 UTF-8、固定 Python 3.13 虚拟环境命令、补充 `.env.example`、依赖决策和配置校验。
- 涉及模块：`configs/**`、`pyproject.toml`、`README.md`、`docs/**`、文本型 `src/**` 和 `tests/**`。
- 参考设计文档内容：配置集中管理、中文问答主链路、Prompt 和知识库文本治理。
- 参考思想：工程配置十二要素思想、OpenTelemetry 配置可观测性边界。
- 阶段输入：P0+ 审计报告、当前配置、所有源码和测试数据。
- 阶段输出：UTF-8 审计脚本或测试、配置校验、更新的 `.env.example` 和依赖说明。
- 完成标准：中文样例可正常读取、检索、生成和测试；全量测试仍通过。
- 依赖关系：依赖 P0+，阻塞 P2+ 及以后阶段。
- 风险与待确认：若发现历史文件已被错误编码破坏，需要人工确认原文含义。

### P2+：应用入口 / API 层与服务运行闭环

- 当前 MVP 存在的问题：项目是模块库 + 测试，没有 `src/app`、服务启动流程、健康检查、请求/响应对象和统一错误响应。
- 工业级升级目标：建立最小可运行入口，优先 CLI + 可选 FastAPI HTTP API；API 层只调 service/pipeline，不写业务逻辑。
- 涉及模块：新增 `src/app/**`、`src/services/**`，修改 `configs/app.yaml`，新增 `tests/integration/app_loop/**`。
- 参考设计文档内容：多入口输入、状态机、run_trace、Agent/Service 边界。
- 参考思想：ReAct 工具调用日志、LangGraph 状态入口边界。
- 阶段输入：P1+ 配置和现有 P0-P6 pipeline。
- 阶段输出：`main.py`、CLI 命令、健康检查、请求/响应 schema、错误响应、trace id。
- 完成标准：一条命令可发起文本问答 smoke flow；API/CLI 不绕过 RAG、生成、自检和反馈边界。
- 依赖关系：依赖 P1+，是 P3+/P7+/P8+ 的运行基础。
- 风险与待确认：FastAPI 是否作为 Must 依赖需确认 Python 3.13 和 Windows 兼容性；若不引入，先用 CLI 和纯函数 pipeline。

### P3+：`deepseek-flash` 统一模型调用层与结构化生成升级

- 当前 MVP 存在的问题：无统一模型调用层；`generation` 是规则实现；业务模型仍待确认；没有超时、重试、限流、降级和结构化输出校验。
- 工业级升级目标：新增 `ModelClient` / `LLMClient` 抽象，通过配置接入 DeepSeek；生成模块通过模型层产生结构化草稿，规则 generator 仅作为 fallback/mock。
- 涉及模块：新增 `src/services/model_client.py`、`src/services/deepseek_client.py`、`src/services/structured_output.py`，修改 `generation`、`configs/providers.yaml`、`configs/app.yaml`。
- 参考设计文档内容：模型调用封装、Prompt 注入、结构化输出、回答生成与自检边界。
- 参考思想：Haystack 的 generator adapter、DeepSeek API 配置实践、Self-RAG 的生成后自检。
- 阶段输入：P2+ 运行入口、P6 Prompt 资产现状、P4 `answer_envelope` 合约。
- 阶段输出：统一模型客户端、结构化输出 schema、失败重试、fallback 策略、模型调用日志。
- 完成标准：业务模块不得直接调用 DeepSeek；模型名、API Key、Base URL、timeout、retry、temperature 全部来自配置或环境变量。
- 依赖关系：依赖 P2+；P4+/P6+/P7+/P8+ 复用模型层。
- 风险与待确认：`deepseek-flash` 对应的真实 DeepSeek API `model_id` 待在 P3+ 通过 `DEEPSEEK_MODEL` 或 `configs/providers.yaml` 核验；真实密钥不可要求用户提供给测试。

### P4+：RAG 从轻量检索升级为工程化知识库

- 当前 MVP 存在的问题：`SimpleVectorIndex` 实际是 token Jaccard；没有真实 embedding、向量库、PDF layout、OCR、图数据库；视觉页面索引是占位。
- 工业级升级目标：明确轻量检索到 embedding 检索迁移路径，引入可替换 EmbeddingProvider 和 VectorStore 接口，保留 SimpleVectorIndex 为 fallback。
- 涉及模块：`src/knowledge/**`、`src/services/embedding_provider.py`、`configs/rag.yaml`、`scripts/ingest_sources.py`、RAG 测试。
- 参考设计文档内容：source_registry、scene_object_registry、evidence_package、多路召回和证据门控。
- 参考思想：LlamaIndex ingestion/retrieval，Haystack retriever/ranker pipeline，RAG 论文的证据增强生成。
- 阶段输入：P3+ 模型/embedding 配置、现有知识库模块、演示资料。
- 阶段输出：EmbeddingProvider、VectorStore 接口、迁移脚本、RAG 质量评测样例、fallback 声明。
- 完成标准：文档和日志不得再把 token Jaccard 叫作工业级向量检索；embedding 不可用时明确 fallback。
- 依赖关系：依赖 P3+，支撑 P5+/P8+。
- 风险与待确认：Chroma/FAISS/sentence-transformers/PyTorch 对 Python 3.13 和 Windows 支持需核验。

### P5+：多模态对象、页面区域与视觉检索链路升级

- 当前 MVP 存在的问题：只有字段和对象预留，无真实图片区域处理、bbox、layout trace、页面图像索引。
- 工业级升级目标：补齐多模态数据契约和页面区域对象，明确 PDF layout/OCR/视觉页面检索是否接入真实实现或 mock adapter。
- 涉及模块：`src/knowledge/ingestion/pdf_ingestor.py`、`visual_ingestor.py`、`visual_page_index.py`、`schemas.py`、`tests/integration/rag_pipeline/**`。
- 参考设计文档内容：多模态证据、scene_object_registry、视觉引用、display_blocks。
- 参考思想：LlamaIndex 多模态节点思想、RAG evidence trace。
- 阶段输入：P4+ RAG 接口、PDF/图片演示资料、场景对象清单。
- 阶段输出：`ImageInput`、`PageRegion`、`BoundingBox`、`LayoutTrace`、视觉索引 adapter、mock/真实 adapter 边界。
- 完成标准：视觉标签无文本交叉验证不得作为核心航空事实；bbox/layout trace 可进入 evidence_package。
- 依赖关系：依赖 P4+。
- 风险与待确认：OCR 和 PDF layout 依赖较重，本轮可先接口 + mock + 评测样例。

### P6+：Prompt 资产库、教学策略与回滚机制升级

- 当前 MVP 存在的问题：Prompt 资产库很小，缺少教学策略、语音交互、自检、兜底、评测快照和自动回滚机制。
- 工业级升级目标：建立 Prompt 目录、元数据、版本、激活范围、评测快照和回滚规则。
- 涉及模块：`src/prompts/**`、`configs/prompts.yaml`、新增 `prompts/` 或 `assets/prompts/`、Prompt 测试。
- 参考设计文档内容：Prompt 路由、变量契约、注入顺序、Prompt 状态机。
- 参考思想：软件配置版本治理、RAGAS/DeepEval 的评测样例快照思想。
- 阶段输入：P3+ 模型层、P2+ API、现有 Prompt 资产。
- 阶段输出：教学策略 Prompt、知识讲解 Prompt、语音 Prompt、自检 Prompt、兜底 Prompt、快照和回滚记录。
- 完成标准：Prompt 不散落硬编码在业务代码；任何 active Prompt 有版本、评审状态和测试快照。
- 依赖关系：依赖 P3+，支撑 P7+/P8+。
- 风险与待确认：Prompt 内容需人工审核，不能自动把未审核模板设为 active。

### P7+：语音交互模块补齐

- 当前 MVP 存在的问题：无 `src/voice`、无 `configs/voice.yaml`、无 VAD/ASR/TTS/barge-in/voice metrics，只有基础 `VoiceTurnEvent` 契约。
- 工业级升级目标：实现语音状态机、Mock ASR/TTS、barge-in、语音 query normalizer 和指标；真实 Provider 通过 adapter 接入。
- 涉及模块：新增 `src/voice/**`、`src/input/voice_query_normalizer.py`、`configs/voice.yaml`、`tests/unit/voice/**`、`tests/integration/voice_loop/**`。
- 参考设计文档内容：P7 语音交互、语音事件、ASR 纠错、spoken answer、barge-in。
- 参考思想：ReAct 的输入动作可追踪、OpenTelemetry latency metrics。
- 阶段输入：P2+ API、P3+ 模型层、P6+ 语音 Prompt、P5+ 自检。
- 阶段输出：Mock voice loop、真实 adapter 接口、语音状态机、语音指标。
- 完成标准：低置信 ASR 不进入正式检索；TTS 打断会进入反馈改写；语音不绕过 RAG 和自检。
- 依赖关系：依赖 P2+、P3+、P6+。
- 风险与待确认：真实 ASR/TTS Provider、浏览器协议、音频保存策略待确认。

### P8+：评测体系、E2E、Trace 与部署治理

- 当前 MVP 存在的问题：无 `scripts/run_eval.py`、无 `tests/e2e`、无 trace report、无部署配置校验和发布前检查。
- 工业级升级目标：建立可复现评测集、E2E、run_trace 导出、demo audit report、部署配置校验。
- 涉及模块：新增 `configs/evals.yaml`、`scripts/run_eval.py`、`scripts/export_trace_report.py`、`tests/e2e/**`、`docs/评测与验收/eval_plan.md`、`docs/接口与部署/deployment_checklist.md`。
- 参考设计文档内容：P8 日志评测、演示审计、部署治理。
- 参考思想：RAGAS 指标、OpenTelemetry trace、DeepEval/TruLens 报告模式。
- 阶段输入：P2+ 到 P7+ 的运行链路和 trace。
- 阶段输出：评测脚本、E2E 场景、trace report、demo audit report、部署检查。
- 完成标准：一条命令可运行 smoke eval；报告能解释输入、证据、生成、自检、反馈、语音和最终输出。
- 依赖关系：依赖 P2+ 到 P7+。
- 风险与待确认：RAGAS/DeepEval/TruLens 是否引入取决于 Python 3.13 兼容性；可先轻量自研指标。

### P9+：全链路工业级验收与演示交付

- 当前 MVP 存在的问题：P0-P6 通过测试不等于工业级；尚无统一验收报告和演示交付包。
- 工业级升级目标：汇总全链路验收，确认每个阶段可运行、可回滚、可评测、可解释。
- 涉及模块：`docs/**`、`scripts/**`、`tests/**`、`configs/**`。
- 参考设计文档内容：全系统闭环、答辩展示、部署治理。
- 参考思想：软件发布门禁、trace-based demo audit。
- 阶段输入：P0+ 到 P8+ 输出。
- 阶段输出：最终验收报告、演示脚本、风险清单、待确认关闭表、后续优化 backlog。
- 完成标准：全量测试、E2E、smoke eval、trace export 和部署配置检查通过；所有待确认有明确决策或阻塞记录。
- 依赖关系：依赖全部前置阶段。
- 风险与待确认：若任何 Must 阶段未通过，不得声称达到工业级。

## 待确认问题清单

1. `deepseek-flash` 对应的 DeepSeek API 真实 `model_id`、base_url 和结构化输出能力。
2. API 层首选 CLI、FastAPI HTTP API、WebSocket，还是先 CLI 后 HTTP。
3. 向量库优先 Chroma、FAISS、SQLite 向量扩展，还是先抽象接口不引入。
4. PDF layout、OCR、视觉页面检索是否本轮接真实依赖，还是 mock adapter + 契约测试。
5. 真实 ASR/TTS Provider、音频保存策略、浏览器协议。
6. Prompt 资产是否需要人工审核流程和初始教学策略内容。
7. 评测框架是否允许引入 RAGAS、DeepEval、TruLens，或先轻量自研。
8. 演示部署目标：本地 CLI、本地 HTTP、局域网服务或云端。

## 风险清单

- 依赖风险：Python 3.13 + Windows 对向量库、OCR、语音和评测框架支持不确定。
- 架构风险：为了工业级一次性引入过多框架，破坏 MVP 可运行性。
- 真实性风险：把 `SimpleVectorIndex`、规则 generator、`VoiceTurnEvent` 契约包装成真实能力。
- 安全风险：模型密钥、音频、用户记忆、评测报告泄露。
- 质量风险：没有 E2E 和 eval 就声称工业级。
- 交付风险：P7/P8 跳过会导致系统不能完整演示和验收。

## 后续给 Codex 使用的 `/goal` 提示词建议

```text
/goal 按 `docs/升级规划/task_plus.md`、`docs/升级规划/spec_plus.md`、`docs/升级规划/harness_plus.md` 将当前项目从 MVP 升级为工业级版本。

需要调用的 skill：
1. `using-superpowers`：每个阶段开始前必须调用。
2. `writing-plans`：每个阶段实施前必须调用，生成阶段级实施计划。
3. `systematic-debugging`：遇到测试失败、乱码、模型调用、检索、语音或 API 异常时必须调用。
4. `verification-before-completion`：每个阶段完成前必须调用。
5. `browser:control-in-app-browser`：仅在需要验证本地 API/演示页面/报告页面时调用。

执行规则：
- 从 P0+ 开始，不允许跳阶段。
- 每阶段只实现 `task_plus.md` 和 `spec_plus.md` 规定范围内的内容。
- 不允许绕过 `harness_plus.md` 的边界。
- 不允许提前实现后续阶段。
- 不允许污染全局 Python 环境。
- 所有 Python 命令必须使用 `"D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe"`。
- 遇到设计冲突、依赖风险、真实密钥、生产服务、架构不确定时必须停止并在阶段报告中记录阻塞原因。
- 每阶段结束后必须运行测试并写入 `docs/项目总控/STATUS.md` 或阶段报告，内容包括：完成内容、修改文件、新增文件、删除文件、测试命令、测试结果、是否违反 harness_plus.md、风险与待确认事项、是否可以进入下一阶段。
```
