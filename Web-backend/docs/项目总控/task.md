# 翼览无余 AI 智能导师系统开发任务说明

## P7 实时语音专项授权（2026-07-13）

用户已明确批准按专项差异修复文档执行 P7 的 T0–T11。授权覆盖 `src/voice/**`、语音测试、配置与文档，并允许受控修改 `src/core/contracts.py`、`src/core/settings.py`、`src/services/app_pipeline.py`、`src/app/api/schemas.py`、`src/agent/runtime.py`、计划列明的 `src/feedback/**`、`src/memory/**` 公共接线位置、`src/observability/**`、`src/input/voice_query_normalizer.py`、`scripts/run_eval.py`、`scripts/validate_deployment.py`、`pyproject.toml`、`docs/接口与部署/**` 和项目总控文档。

T0–T11 已于 2026-07-13 全部完成并通过最终验收。当前交付为离线 Mock Provider 与本地 WebSocket 实时原型；真实 ASR、真实 TTS、真实模型、生产媒体服务和生产部署仍是后续范围。

本专项唯一允许新增的运行时依赖是 `websockets==15.0.1`。实施必须在 `codex/voice-realtime-refactor` 独立分支和隔离 worktree 中逐任务提交；禁止 push、自动合并、强制 reset、覆盖来源工作区未提交修改，禁止读取真实密钥或连接真实 ASR、TTS、模型、生产数据库及生产服务。文本 API、CLI、`AppPipeline.run_text_query()` 以及 P2/P3/P5/P6 公共契约必须保持向后兼容。

## 文档依据

本文以《翼览无余智能体模块设计文档V2_增加语音交互.docx》为主要依据，将概念设计拆解为可执行的分阶段开发任务。原设计文档明确指出，本系统面向航空科普场景，任务是围绕航空器、部件、原理、发展背景和应用价值进行有证据、可追溯、适合用户理解的讲解，而不是维修指导、课程训练或 MR 控制系统。

## 需要调用的 skill

后续让 Codex 按本文执行开发时，不要求每个阶段预先固定 skill，而是先调用 `using-superpowers`，由它根据当前阶段任务、文件类型、错误状态和可用工具动态判断还需要哪些 skill。为满足工作区 AGENTS.md 中“Codex 提示词必须明确声明 skill”的要求，本提示词只声明全局必需 skill：

1. `using-superpowers`：每次开始阶段任务前先调用，用于发现并调度当前阶段真正需要的 skill。
2. `writing-plans`：当阶段涉及多文件、多模块或多步骤实现时调用，用于生成可核查实施计划。
3. `systematic-debugging`：遇到 bug、测试失败、链路异常或输出偏离设计时调用。
4. `verification-before-completion`：每个阶段完成前必须调用，用于运行验证命令并确认结果。
5. `openai-docs`：仅当阶段涉及 OpenAI API、Realtime、ASR、TTS、Structured Outputs 或模型接口时调用，用于查询官方最新文档。

除上述全局规则外，本文不在每个阶段单独指定 skill。阶段执行者必须在进入具体实现前依据 `using-superpowers` 的判断动态补充调用相关 skill。

## 系统整体阶段划分表

| 阶段 | 阶段名称 | 核心目标 | 主要模块 |
| --- | --- | --- | --- |
| P0 | 工程骨架、统一状态机与数据契约 | 建立可扩展项目骨架、全局状态机、统一数据对象和配置体系 | core、config、schemas、trace |
| P1 | 输入理解、场景状态与 Prompt 路由 | 把文本/场景输入转成 query_object，并完成 Prompt 资产路由与注入边界 | input、scene、prompts |
| P2 | 记忆系统与时间有效性治理 | 建立事件日志、热状态、学习者画像、候选记忆、时间衰减和审计机制 | memory、event_log、policy |
| P3 | 本地航空知识库、RAG 与多模态证据包 | 建立资料入库、多索引检索、scene_object_registry 和 evidence_package | knowledge、rag、graph、multimodal |
| P4 | 回答生成、来源绑定与结构化输出 | 基于 evidence_package 生成 answer_envelope、source_binding 和 display_blocks | generation、citations、display |
| P5 | 自我检查、动作分流与回退闭环 | 对回答做事实、场景、多模态、教学和安全检查，并输出 action_decision | self_check、router |
| P6 | 反馈改写、多轮 checkpoint 与记忆候选 | 处理“太专业”“再短点”“你说错了”等反馈，锁定证据做受控改写 | feedback、checkpoint、rewrite |
| P7 | 语音交互与实时会话 | 增加 VAD、ASR、口语标准化、TTS、barge_in 和语音指标 | voice、realtime |
| P8 | 日志评测、演示审计与部署治理 | 汇总 run_trace、评测集、质量指标、部署脚本和答辩展示材料 | evals、ops、docs |

## 回答生成专项维护工作包

历史主线 P0 至 P8 已有验收记录。其后的回答生成差异修复使用 G0 至 G8 作为维护工作包编号，执行顺序为 `G0 → G1 → G2 → G3 → G4 → G5 → G6 → G7 → G8`。G 编号不创建新的 P 阶段，不覆盖历史验收结论；每个 G 工作包仍按实际触及模块遵守原 P0 至 P8 的目标和边界，并额外遵守 `harness.md` 的 G0–G8 专项门禁表。

| 工作包 | 维护目标 | 映射的原 P 边界 |
| --- | --- | --- |
| G0 | 治理基线、外部兼容契约与已知缺陷证据 | P0、P4、P5、P7、P8 |
| G1 | canonical 回答契约、兼容序列化与运行时配置 | P0 |
| G2 | 回答类型路由、证据草图、计划与大纲 | P1、P3、P4 |
| G3 | 模型运行时、Prompt 驱动生成与确定性 fallback | P1、P4 |
| G4 | claim 完整性、引用语义、自检与安全硬门控 | P4、P5 |
| G5 | 补检索、改写、终态与 trace 回退闭环 | P3、P4、P5 |
| G6 | 视觉引用、展示块和语音终态投影 | P4、P7 |
| G7 | Legacy bridge、死代码和伪配置清理 | 所有实际触及的原 P 边界 |
| G8 | 离线评测、发布验证与文档收口 | P8 |

专项的详细目标、非目标和完成标准以 `docs/4回答生成/设计文档/2026-07-11-回答生成系统差异修复任务.md` 为准；文件级实施顺序以已批准实施计划为准。

## 术语统一

- `query_object`：统一输入对象，承载用户问题、标准化问题、意图、场景绑定和语音补充字段。
- `scene_state`：前端或场景识别层产生的场景状态，包含 aircraft_id、component_id、hotspot_label、camera_view、visual_refs、scene_confidence。
- `prompt_asset`：提示词资产，包含 template_id、task_type、variables、constraints、version、review_status、activation_scope。
- `memory_context`：记忆系统输出的个性化上下文，包含 learner_profile、dialogue_context、session_preference、misconception_record、memory_boundary。
- `evidence_package`：知识检索模块输出的事实证据包，是事实性回答的唯一外部依据。
- `answer_envelope`：回答生成模块输出的结构化答案，包含 short_answer、main_answer、source_binding、visual_refs、display_blocks 等。
- `check_report`：自我检查模块输出的质量裁决结果。
- `rewrite_plan`：反馈改写模块输出的受控改写计划。
- `run_trace`：贯穿全链路的运行追踪记录。

## P0：工程骨架、统一状态机与数据契约

### 阶段目标

建立系统开发的共同地基，使后续 Prompt、记忆、RAG、生成、自检、反馈和语音模块都围绕同一套状态机、动作枚举、数据契约、配置和日志规范开发。

### 需要完成的核心任务

- 初始化项目结构、配置加载、错误码、日志和测试目录。
- 定义全局状态机：`INPUT_RECEIVED -> PROMPT_ROUTED -> MEMORY_CONTEXT_READY -> RETRIEVAL_PLANNED -> EVIDENCE_READY -> ANSWER_DRAFTED -> SELF_CHECKED -> FINAL_READY`。
- 定义动作枚举：`PASS`、`REWRITE_ONLY`、`RETRIEVE_MORE`、`ASK_CLARIFICATION`、`HUMAN_REVIEW`、`SAFE_RESPONSE`、`MEMORY_CANDIDATE`、`STOP`。
- 定义统一数据契约的基础 schema：`scene_state`、`prompt_asset`、`memory_context`、`evidence_package`、`answer_envelope`、`check_report`、`rewrite_plan`、`voice_turn_event`、`feedback_event`、`run_trace`。
- 建立配置文件边界，避免把模型名、阈值、检索 top_k、ASR/TTS Provider、记忆保留策略写死在代码中。

### 涉及的系统模块

`core`、`config`、`schemas`、`trace`、`tests`。

### 阶段输入

- Word 设计文档中的总体工作流、模块职责边界、统一数据契约、统一动作枚举。
- 设计假设：后端以 Python 服务为主，具体 Web 框架、数据库和模型 Provider 待确认。

### 阶段输出

- 可运行的项目基础骨架。
- 全局数据 schema 和状态机定义。
- 基础配置样例和测试骨架。
- 模块命名规范与错误处理基线。

### 完成标准

- 所有阶段共用同一套状态、动作和对象名称。
- 任一模块不得自行定义同名异义的数据对象。
- 基础单元测试能验证状态枚举、schema 校验、配置加载和 run_trace 最小字段。

### 与其他阶段的依赖关系

P0 是 P1 到 P8 的前置阶段。任何后续模块如果需要新增字段，必须回到 P0 的契约文件中扩展并补测试。

### 待确认 / 设计假设 / 潜在风险

- 待确认：技术栈、运行环境、数据库、消息队列、前端通信协议。
- 设计假设：先用模块化 Python 工程落地，后续可替换为 FastAPI、LangGraph 或其他框架。
- 潜在风险：如果 P0 契约过松，后续模块会出现同一对象多套字段、状态机不可回放、日志不可审计的问题。

## P1：输入理解、场景状态与 Prompt 路由

### 阶段目标

把用户文本问题、3D 场景状态和初步任务意图转成标准 `query_object`，并实现 Prompt 资产的路由、变量填充、注入顺序和风险边界。

### 需要完成的核心任务

- 实现用户输入理解：任务类型、目标飞机、目标部件、概念、反馈意图、是否需澄清。
- 实现场景对象绑定的最小闭环：读取 `scene_state`，对“这个”“旁边那个”“刚才的部件”等指代生成候选绑定。
- 建立 Prompt 资产模型和状态流转：`draft`、`candidate`、`active`、`experimental`、`deprecated`。
- 实现 Prompt 变量契约：`{{aircraft}}`、`{{component}}`、`{{concept}}`、`{{learner_level}}`、`{{explanation_preference}}`、`{{weak_points}}`、`{{scene_state}}`、`{{rag_evidence}}`、`{{output_contract}}`。
- 实现运行时注入顺序：系统角色与事实边界、任务类型、场景状态、RAG 证据、学习者记忆、薄弱点、active Prompt 资产、输出格式。

### 涉及的系统模块

`input`、`scene`、`prompts`、`core`、`trace`。

### 阶段输入

- P0 的数据契约和状态机。
- 前端或测试夹具提供的 `scene_state`。
- Word 设计文档中提示词工程、变量化 Prompt、运行时路由和注入顺序设计。

### 阶段输出

- `query_object` 和 `PromptRunInput`。
- Prompt 资产存储和路由器。
- 缺失变量降级策略。
- Prompt 注入摘要日志。

### 完成标准

- 缺少必填变量时，路由器降级到基础模板，不猜测航空事实或用户状态。
- RAG 证据在注入顺序上始终位于 Prompt 资产之前。
- `draft` 和 `candidate` Prompt 不可进入正式回答链路。

### 与其他阶段的依赖关系

依赖 P0。P1 输出是 P2 读取记忆、P3 检索规划、P4 生成和 P7 语音标准化的共同入口。

### 待确认 / 设计假设 / 潜在风险

- 待确认：Prompt 资产初始内容由人工维护、自动抽取，还是两者结合。
- 设计假设：MVP 先用文件或轻量数据库保存 Prompt 资产，后续再扩展评测治理。
- 潜在风险：Prompt 资产越权补事实，或用户记忆与 Prompt 模板覆盖 RAG 证据。

## P2：记忆系统与时间有效性治理

### 阶段目标

让系统具备“记住交流脉络和学习偏好，但不污染航空事实”的能力。建立事件日志、热状态、结构化记忆、向量语义记忆、时间图谱和治理审计的最小可用版本。

### 需要完成的核心任务

- 建立 L1 事件日志，保存语音、点击、手势、测验、反馈、MR/Web 状态、工具调用等原始事件。
- 建立 L2 热状态，保存当前平台、飞机、部件、模型状态、讲解进度。
- 建立 L3 结构化记忆：学习者画像、概念掌握、误解/薄弱点、隐私与授权。
- 建立 L4 语义记忆：会话摘要、反思记录、Prompt 资产候选。
- 建立时间有效性字段：`observed_at`、`valid_from`、`valid_until`、`expires_at`、`last_confirmed_at`、`confidence`、`stability_score`、`status`。
- 实现记忆状态机：`candidate`、`active`、`stale`、`superseded`、`expired`、`rejected`、`archived`。
- 实现 `memory_context` 构造，明确稳定画像、当前会话、表达偏好、相关误解、禁止注入记忆和时间说明。

### 涉及的系统模块

`memory`、`event_log`、`policy`、`trace`、`tests`。

### 阶段输入

- P0 的数据契约。
- P1 的 `query_object`、`scene_state` 和 Prompt 路由结果。
- Word 设计文档中的 L0 到 L8 分层、记忆类型、读写权限、RAG 与记忆边界、时间有效性机制。

### 阶段输出

- `event_log`、`current_state`、`learner_profile`、`misconception_memory`、`policy_memory` 等存储接口。
- `MemoryController`。
- `memory_context`。
- 记忆读写审计日志。

### 完成标准

- 用户偏好、误解和会话摘要可以进入记忆系统，但不能写入航空事实库。
- 低置信、过期、冲突或高隐私记忆默认不进入生成上下文。
- 记忆写入必须先进入事件日志，再抽取、校验、分层存储。

### 与其他阶段的依赖关系

依赖 P0 和 P1。P2 输出的 `memory_context` 供 P4 个性化表达、P5 边界检查、P6 记忆候选治理和 P7 语音偏好更新使用。

### 待确认 / 设计假设 / 潜在风险

- 待确认：用户授权、隐私等级、原始语音保留策略、数据删除需求。
- 设计假设：MVP 先实现结构化记忆和事件日志，时间图谱可以先用关系表或轻量图结构模拟。
- 潜在风险：记忆过度写入导致隐私风险，或把用户错误说法当作航空事实。

## P3：本地航空知识库、RAG 与多模态证据包

### 阶段目标

建立“事实由知识检索提供”的核心能力。将航空文本、PDF、图片、结构图、3D 场景元数据和轻量知识图谱统一入库，并输出可审计的 `evidence_package`。

### 需要完成的核心任务

- 建立 `source_registry`，登记资料来源、权威等级、审核状态、版本和适用机型。
- 建立文本切分和父子节点索引，按语义单元保留 source_id、parent_id、aircraft、component、concept、knowledge_type。
- 建立 PDF 与图文资料解析对象，保留 page_id、OCR 文本、版面块、图表区域、page_image 和 layout_trace。
- 建立 `visual_asset_registry` 和 `scene_object_registry`，把图片、结构图、3D 热点与知识实体连接。
- 建立关键词、稠密向量、稀疏/混合、父子节点、视觉页面和图谱索引。
- 实现检索控制器：问题复杂度分级、查询改写、场景绑定、多路召回、RRF 融合、重排序、evidence_gate。
- 输出 `evidence_package`：`query_understanding`、`scene_binding`、`retrieval_plan`、`evidence_items`、`claim_support_map`、`missing_evidence`、`generation_boundary`、`audit_trace`。

### 涉及的系统模块

`knowledge`、`rag`、`ingestion`、`indexes`、`graph`、`multimodal`、`scene_registry`。

### 阶段输入

- P1 的 `query_object` 和 `scene_state`。
- P2 的 `memory_context` 中可用于检索意图和偏好适配的非事实信息。
- 航空资料、PDF、图片、3D 场景对象清单。

### 阶段输出

- 可检索知识库。
- 场景对象注册表。
- 多索引检索服务。
- `evidence_package` 和证据质量门控结果。

### 完成标准

- 只有 `reviewed` 或人工审核资料可作为核心航空事实证据。
- 向量召回、视觉识别、用户反馈、模型生成内容不能单独作为核心事实。
- `missing_evidence` 必须明确告诉生成模块哪些内容不能说。

### 与其他阶段的依赖关系

依赖 P0 到 P2。P3 输出是 P4 回答生成、P5 自我检查、P6 事实纠错和 P7 Voice RAG 的事实基础。

### 待确认 / 设计假设 / 潜在风险

- 待确认：实际航空资料来源、审核流程、向量库/图数据库/文档解析工具选型。
- 设计假设：MVP 可以先实现文本、元数据和 scene_object_registry，视觉页面索引和图谱增强分步引入。
- 潜在风险：多模态证据没有 layout_trace，或视觉标签未经文本交叉验证就被当作事实。

## P4：回答生成、来源绑定与结构化输出

### 阶段目标

把 `evidence_package`、`scene_state`、`memory_context` 和 `prompt_asset` 组织成可检查、可展示、可追溯的 `answer_envelope`，实现从“能回答”到“有证据地回答”。

### 需要完成的核心任务

- 实现回答类型路由：概念解释、部件场景、对比归纳、参数事实、操作安全、澄清追问。
- 实现 `evidence_sketch`，把证据包整理成可用事实、关系、视觉线索、缺失点和冲突点。
- 实现生成流水线：`planning`、`outline`、`grounded_drafting`、`citation_binding`、`uncertainty_verbalization`、表达润色。
- 输出 `answer_envelope`：`answer_type`、`short_answer`、`main_answer`、`evidence_refs`、`visual_refs`、`uncertainty_notes`、`safety_notes`、`follow_up_questions`、`claim_candidates`、`display_blocks`、`generation_trace`。
- 实现 `source_binding`，在 claim 或句子粒度绑定 evidence_id。
- 实现书面回答与显示块分离，支持来源卡片、场景高亮、可追问建议。

### 涉及的系统模块

`generation`、`citations`、`display`、`prompts`、`trace`。

### 阶段输入

- P3 的 `evidence_package`。
- P1 的 Prompt 路由结果。
- P2 的 `memory_context`。
- P0 的结构化输出 schema。

### 阶段输出

- `answer_envelope`。
- `source_binding`。
- `generation_log`。
- 可供自我检查使用的 `claim_candidates`。

### 完成标准

- 参数、型号、结构功能等事实性 claim 必须能追溯到 evidence_id。
- 证据不足时输出不确定性说明或追问，不补无来源事实。
- 个性化只改变表达难度、长度、类比和顺序，不改变事实结论。

### 与其他阶段的依赖关系

依赖 P0 到 P3。P4 输出是 P5 自检和 P6 反馈改写的共同输入，也是 P7 语音播报的文本基础。

### 待确认 / 设计假设 / 潜在风险

- 待确认：模型 Provider、结构化输出约束方式、前端 display_blocks 渲染协议。
- 设计假设：MVP 先实现非流式结构化输出，流式输出在自检策略明确后再启用。
- 潜在风险：生成器将 evidence_sketch 外的模型常识混入答案，或在简化表达时丢失限定条件。

## P5：自我检查、动作分流与回退闭环

### 阶段目标

建立独立质量闸门，对回答做事实主张、证据对齐、检索充分性、场景一致性、多模态一致性、记忆/Prompt 边界、教学适配和安全边界检查，并通过 action_decision 驱动回退。

### 需要完成的核心任务

- 抽取最小事实主张 claim，标注 claim_type、risk_level、required_evidence_type。
- 建立 `claim_support_map`，判断 `supported`、`partially_supported`、`unsupported`、`contradicted`。
- 实现 `evidence_quality_gate`，判断检索证据是否足以进入生成或需要补检索。
- 检查 `scene_state` 与回答对象是否一致。
- 检查多模态回答的图文一致、bbox 对齐、场景对象绑定。
- 检查记忆和 Prompt 是否越界为事实来源。
- 输出 `check_report`、`score_card`、`issue_list`、`failed_checks`、`action_decision`、`revised_instruction`、`audit_log`。

### 涉及的系统模块

`self_check`、`core.state_machine`、`trace`、`tests`。

### 阶段输入

- P4 的 `answer_envelope`。
- P3 的 `evidence_package`。
- P1 的 `scene_state` 和 Prompt 资产。
- P2 的 `memory_context`。

### 阶段输出

- `check_report`。
- 动作分流：`PASS`、`REWRITE_ONLY`、`RETRIEVE_MORE`、`ASK_CLARIFICATION`、`HUMAN_REVIEW`、`SAFE_RESPONSE`、`STOP`。
- 回退原因，如 `missing_evidence`、`scene_unclear`、`unsupported_claim`、`unsafe_request`。

### 完成标准

- 高风险参数 claim 无证据时不得 PASS。
- 场景对象不一致时不得直接输出。
- 循环次数受控，超过上限输出保守结果或澄清，不无限重试。

### 与其他阶段的依赖关系

依赖 P0 到 P4。P5 的动作结果控制 P3 补检索、P4 改写、P6 反馈改写和 P8 评测。

### 待确认 / 设计假设 / 潜在风险

- 待确认：评分阈值、LLM Judge 与规则检查的比例、人工复核触发条件。
- 设计假设：MVP 先实现规则检查和轻量语义 judge，后续接入 Ragas、DeepEval、RAGChecker。
- 潜在风险：自检过松导致幻觉外放，过严导致答案过度保守或响应过慢。

## P6：反馈改写、多轮 checkpoint 与记忆候选

### 阶段目标

让系统在用户继续反馈时，不重新开始无上下文问答，而是从上一轮 `answer_envelope`、`evidence_package` 和 `check_report` 恢复，进行证据锁定的受控改写。

### 需要完成的核心任务

- 定义 `feedback_event` 和反馈意图：`SIMPLIFY`、`SHORTEN`、`EXPAND`、`FORMAT_TRANSFORM`、`FACT_CHALLENGE`、`SCENE_REBIND`、`PREFERENCE_SIGNAL`、`SAFETY_SENSITIVE`。
- 实现 checkpoint，保存 previous_answer_envelope、evidence_package、self_check_report、scene_state、source_binding。
- 实现 `evidence_locking`，默认不得新增事实 claim。
- 生成 `rewrite_plan`：删除无证据内容、降低术语密度、压缩段落、格式转换、场景重绑定、补不确定性、保留引用。
- 生成 `delta_map`、`preserved_source_binding` 和 `rewrite_log`。
- 对改写结果再次进入 P5 自我检查。
- 对稳定偏好生成 `memory_update_candidate`，交给 P2 治理，不直接写长期记忆。

### 涉及的系统模块

`feedback`、`checkpoint`、`memory`、`self_check`、`trace`。

### 阶段输入

- P4 的上一轮 `answer_envelope`。
- P5 的 `check_report`。
- 用户反馈文本或语音反馈。
- P2 的记忆治理规则。

### 阶段输出

- `revised_answer_envelope`。
- `rewrite_plan`、`delta_map`、`preserved_source_binding`。
- `memory_update_candidate`。
- `rewrite_log`。

### 完成标准

- “太专业”“再短一点”“用表格”类反馈只改表达，不新增事实。
- “你说错了”类反馈必须先检索或核查，不能直接相信用户。
- 安全敏感反馈进入 `SAFE_RESPONSE`，不能通过“换个说法”绕过边界。

### 与其他阶段的依赖关系

依赖 P0 到 P5。P6 的记忆候选返回 P2，事实挑战回到 P3，改写后再次进入 P5。

### 待确认 / 设计假设 / 潜在风险

- 待确认：最大改写轮数、用户反馈是否需要保存原文、前端如何展示修改差异。
- 设计假设：MVP 设置 `max_rewrite_rounds=2`，超过后透明说明限制。
- 潜在风险：改写器为了流畅新增幻觉，或长期偏好被一次性反馈污染。

## P7：语音交互与实时会话

### 阶段目标

在不改变可信 RAG 主链路的前提下，为系统增加自然、实时、可中断的语音入口和语音出口。语音只负责把声音变成可靠结构化问题、把可信答案变成可听表达。

### 需要完成的核心任务

- 实现音频采集与实时传输接口，支持 WebSocket 原型，后续可扩展 WebRTC。
- 实现 VAD、端点检测和 barge_in 控制，状态包括 `IDLE`、`LISTENING`、`TRANSCRIBING`、`UNDERSTANDING`、`RETRIEVING`、`GENERATING`、`SPEAKING`、`INTERRUPTED`、`REWRITE`、`CLARIFY`。
- 定义 `ASRProvider`、`TTSProvider` 可替换接口。
- 实现航空术语纠错和 `voice_query_normalizer`。
- 语音查询进入 P1/P3 前必须形成 `normalized_query`、`asr_confidence`、`needs_clarification`、`scene_object_id`。
- 实现 `spoken_answer`，采用一句话结论、两到三句解释、一个可选追问。
- 建立 `voice_turn_event`、`voice_query_object`、`voice_answer_object`、`voice_metric_record`。

### 涉及的系统模块

`voice`、`input`、`feedback`、`memory`、`generation`、`trace`。

### 阶段输入

- P0 的统一数据契约。
- P1 的 query 标准化和场景绑定。
- P3 到 P6 的可信回答闭环。
- 麦克风音频流、ASR 结果、TTS 播放状态。

### 阶段输出

- 语音输入事件和标准化查询。
- 可打断的 TTS 播报。
- 语音反馈改写事件。
- 语音质量指标。

### 完成标准

- 低置信 ASR 不直接进入正式检索，必须澄清或降级。
- 用户打断时旧 TTS 必须停止，并进入反馈改写或澄清流程。
- 语音回答仍然必须来自 `evidence_package` 和自我检查，不允许端到端语音模型绕过证据链。

### 与其他阶段的依赖关系

依赖 P0 到 P6。P7 不替代任何主链路模块，只是新增入口、出口和实时控制。

### 待确认 / 设计假设 / 潜在风险

- 待确认：ASR/TTS Provider、浏览器权限、前端音频协议、实时延迟目标。
- 设计假设：先用 WebSocket 级联式 Voice RAG 原型，再评估 WebRTC 和本地 Provider。
- 潜在风险：ASR 误识别污染检索，TTS 过长影响理解，barge_in 未处理导致交互失控。

## P8：日志评测、演示审计与部署治理

### 阶段目标

将系统从功能原型提升为可评测、可审计、可演示、可部署的工程系统，支撑后续联调、答辩展示和持续优化。

### 需要完成的核心任务

- 汇总统一 `run_trace`，包含 run_id、user_query、scene_state_id、prompt_template_id、memory_context_ids、retrieval_plan、evidence_ids、answer_id、check_report_id、action_decision、rewrite_log_id、final_answer_id、latency_ms。
- 建立分层评测：检索层 recall/precision/evidence_coverage，生成层 source_binding/answer_relevance/clarity，自检层 hallucination_detection/scene_alignment/risk_detection，反馈层 semantic_preservation/citation_retention，记忆层 memory_pollution_rate。
- 建立语音评测：ASR 字错误率、航空实体识别准确率、VAD 误切率、首响延迟、TTS 首包延迟、打断响应时间。
- 建立离线评测集，覆盖事实问答、场景部件、参数缺失、证据冲突、反馈改写、低置信 ASR、安全边界。
- 建立部署配置、数据迁移脚本、种子演示数据、回归测试命令。
- 输出答辩展示报告：证据链、记忆命中、回退链路、自检拦截、语音打断、run_trace 可视化。

### 涉及的系统模块

`evals`、`trace`、`ops`、`docs`、`scripts`、`tests`。

### 阶段输入

- P0 到 P7 的运行日志、测试夹具和业务场景。
- Word 设计文档中的全局日志与评测体系、语音质量指标、答辩展示价值。

### 阶段输出

- 评测集和评测脚本。
- 部署与运行文档。
- 演示审计数据。
- 回归测试报告。

### 完成标准

- 每次端到端回答都可通过 run_trace 追踪输入、证据、生成、自检、反馈和最终输出。
- 每个阶段有可重复运行的测试或验收脚本。
- 演示时能解释“为什么系统这样回答、用了哪些证据、哪些内容被拦截、记忆如何生效”。

### 与其他阶段的依赖关系

依赖 P0 到 P7。P8 反向约束所有阶段的日志、测试和可审计性。

### 待确认 / 设计假设 / 潜在风险

- 待确认：部署目标、本地/云端模型使用限制、演示数据脱敏标准、评测集规模。
- 设计假设：先支持本地开发部署，再扩展演示部署。
- 潜在风险：只有功能没有评测和审计，无法证明系统不是普通聊天机器人。

## LangGraph 文本主链迁移：用户授权

本节是 P0–P8 与回答生成 G0–G8 历史验收之后的新增迁移授权，不改写任何历史任务或验收结论。文本问答主链获准直接迁移为唯一的 LangGraph 监督编排器，固定依赖为 `langgraph==1.2.9` 与 `langgraph-checkpoint-sqlite==3.1.0`；依赖核验、同步和测试必须使用 `D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe`（Python 3.11.9 验证环境，项目声明仍为 Python `>=3.11`）。语音实时层仍只能通过既有 `AppPipeline` 公共入口消费文本主链，不迁移语音图编排。

迁移不得改变 `TextQueryRequest`、`TextQueryResponse`、`TextQueryResponse.to_dict()`、CLI 参数及公开 answer/action/trace 字段。图 checkpoint 必须使用与 `knowledge.sqlite3`、`memory.sqlite3` 分离的本地 SQLite 文件，仅保存可恢复的最小脱敏状态；不得持久化原始音频、完整 Prompt、私有记忆正文、来源全文、证据正文或密钥。直接回答仅限无事实对话，航空事实必须经 `RetrievalController` 的已审核证据；不安全输入必须在记忆查询、检索和生成之前拒答。

必经验证包括依赖契约、CLI 公共响应契约、四路路由与同会话指代消解、图恢复/清理/隐私扫描，以及文本、RAG、记忆、反馈、语音和全量 pytest 回归。若指定 Python 3.11.9 环境中的依赖不兼容、SQLite 集成必须持久化禁存内容、恢复需要改变公开契约、实现必须绕过既有 controller、语音边界不变量被破坏，或同类测试连续三次失败且无法定位，必须立即停止并在 `STATUS.md` 记录“待确认”。不得引入 LangChain、LangSmith、Agent Server、云服务、真实模型 provider、向量数据库或生产数据库连接。

## 记忆服务重构专项 M0–M5：用户授权

本专项位于 P0–P8 与 G0–G8 历史验收之后，只重构受治理的长期记忆能力，不覆盖或改写任何历史记录。执行顺序固定为 `M0 → M1 → M2 → M3 → M4 → M5`，不得跳过、倒序、跨阶段并行或在当前阶段失败时进入下一阶段：

1. M0 governance/contracts：批准治理边界，冻结现有公开回答，发布 Proto/OpenAPI 和无数据服务连接的 Java 骨架。
2. M1 Java authority：建立 PostgreSQL + pgvector 事件、治理、版本和 resolve 权威核心；Redis、Neo4j 与模型仍不进入本阶段。
3. M2 Python integration：通过稳定 port/adapter 接入 Java，保持 150 ms 等待上限、空长期记忆降级、session overlay、本地 durable outbox 和并行 RAG prefetch。
4. M3 async intelligence/projections：加入 Redis Streams、无状态 Python candidate/embedding worker、Java 二次治理、可重建 Neo4j 投影和有界缓存。
5. M4 privacy/control：实现默认关闭的同意、主体与角色边界、epoch、加密、查看/纠正/禁用/遗忘、retention、审计和本地控制中心。
6. M5 migration/acceptance：完成 SQLite 只读迁移、shadow 对比、单一权威切换、可观测性、160 用例、负载、故障与重放验收。

PostgreSQL + pgvector 是唯一长期记忆权威；Redis 仅为内部 Streams/L2，Neo4j 仅为可删除重建投影，Caffeine 仅为 Java L1；Python worker 无数据库凭据和晋级权限。记忆只提供个性化上下文，航空事实仍只能来自 `EvidencePackage`。Java 超时、不可用、schema/响应/身份异常时丢弃全部远端长期记忆并新建空长期上下文，让 RAG 继续产出有证据回答；REMOTE 激活后不得回退旧 SQLite 长期记忆。

M0 锁定 Python 3.11、`grpcio==1.82.1`、`grpcio-tools==1.82.1`、`protobuf==7.35.1`、JDK 21、Maven Wrapper 3.9.16、Spring Boot 4.0.6、Spring gRPC 1.0.3、JUnit 5 与 ArchUnit。M0 仅允许文档、契约、生成代码、依赖锁和无数据库 Java 骨架，不连接 PostgreSQL、Redis、Neo4j、真实模型或任何生产服务，也不改变 `AppPipeline`、`TextQueryRequest`、`TextQueryResponse.to_dict()`、CLI 或公开 answer/action/trace 契约。

缺少 JDK 21 或 Maven Wrapper 3.9.16 无法生成、需要未批准依赖/目录/接口/权限、触及任务 allowlist 外文件、公开契约将破坏、出现跨用户记忆/撤回后仍读取/memory-as-fact/forget 数据复活，或同一问题连续三次失败仍无法定位时，立即停止并在 `STATUS.md` 记录“待确认”。本专项不授权 Git、分支、worktree、提交、推送、合并、PR、生产部署、真实密钥或真实生产数据服务。

## 真实语音 Provider 专项：用户授权（2026-08-13）

本专项位于 P0–P8、G0–G8、LangGraph 迁移与 M0–M5 历史验收之后，不覆盖其历史记录。

### 授权范围
- 启用真实 ASR（faster-whisper 本地推理）与真实 TTS（edge-tts 公共端点）。
- 两个 provider 均无密钥；faster-whisper 本地推理不属外部服务；edge-tts 为无凭据公共 TTS 端点。

### 允许新增的运行时依赖
- faster-whisper（版本上限 <2）
- edge-tts（版本上限 <7）
- （onnxruntime、ctranslate2 等为 faster-whisper 传递依赖，不单独声明）

### 允许新增/修改的文件
- src/voice/whisper_asr.py（新增）
- src/voice/edge_tts.py（新增）
- src/voice/providers.py、src/voice/settings.py、src/voice/orchestrator.py、src/voice/asr.py（MockASRProvider 加 settings 参数）
- src/voice/websocket_server.py（仅 serve_voice 启动入口调整）
- configs/voice.yaml
- pyproject.toml、uv.lock
- scripts/run_voice.py（新增，启动 WS server）
- scripts/run_frontend.py（新增，独立 HTTP 静态服务）
- assets/voice/index.html（新增，前端页面）
- tests/unit/voice/test_whisper_asr.py、tests/unit/voice/test_edge_tts.py（新增）
- docs/项目总控/task.md、harness.md、STATUS.md、AUTO_DEV.md
- docs/接口与部署/**（若更新 WS 协议文档）

### 用户知情决策
1. edge-tts 为非官方逆向端点，非微软官方 API，稳定性无承诺。
2. edge-tts 本体 GPL-3.0 许可，若项目打包分发需注意传染性。
3. TTS 合成文本会发往第三方端点（文本出境），但 harness 既有“不记录完整 transcript/原始音频”约束不变。

### 停止条件
- faster-whisper/edge-tts 在指定 Python 3.11.9 核验环境无法安装或解析冲突；
- 需引入密钥、需连接生产数据库、需接入需付费云服务；
- 破坏 TextQueryRequest/TextQueryResponse.to_dict()/CLI/公开 answer/action/trace 契约；
- voice 模块绕过 AppPipeline 直接回答航空事实；
- 同一测试连续三次失败且无法定位。

### 回滚
- 配置级：configs/voice.yaml 改回 mock provider。
- 依赖级：移除 pyproject.toml 三行 + 重新 uv lock。
- 文件级：删除新增的 whisper_asr.py/edge_tts.py/run_voice.py/run_frontend.py/index.html。

## R0 审阅修复专项：用户授权（2026-08-16）

本专项位于 P0–P8、G0–G8、LangGraph 迁移、M0–M5 与真实语音 Provider 专项历史验收之后，不覆盖其历史记录。专项编号 **R0**（Review/Repair），与 G（回答生成）、M（记忆）并列，**不占用 G0–G8 编号**。R0 基于 deepseekpro、GLM5.3、gptsol 三份独立审阅（均为 2026-08-16）衍生的《最终修复计划.md》，对该计划去重合并后的 72 项问题分阶段修复。

### 授权范围

- 按 `docs/项目总控/R0审阅修复/task.md` 的阶段零→五任务清单逐项执行；任务 ID（T0-1 ~ T5-1）在该子文档与 `spec.md`/`harness.md` 间一一对应。
- 每个任务的允许修改文件以 `docs/项目总控/R0审阅修复/harness.md` 的逐任务白名单为唯一权威；白名单外文件一律不得修改。
- R0 任务触及的每个模块仍须遵守该模块原 P harness 的不变量（P1 Prompt 边界、P2 记忆边界、P3 证据边界、P4 生成边界、P5 自检边界、P6 反馈边界、P7 语音边界、P8 评测边界）。

### 阶段划分

| 阶段 | 任务区间 | 优先级 | 性质 |
| --- | --- | --- | --- |
| 阶段零 事实核查 | T0-1 ~ T0-8 | P0 | 只读验证，证实/证伪单源指控，不改产品代码 |
| 阶段一 阻断修复 | T1-1 ~ T1-10 | P0 | 提交/演示直接翻车项 |
| 阶段二 高优修复 | T2-1 ~ T2-20 | P1 | 稳定性与文档一致 |
| 阶段三 中优修复 | T3-1 ~ T3-10 | P2 | 答辩前完成 |
| 阶段四 低优/文档 | T4-1 ~ T4-5 | P3 | 整洁度与表述 |
| 阶段五 放行门禁 | T5-1 | P0 | 最终验收闸 |

### 用户预授权的架构决策（2026-08-16）

以下三处原属 harness 停止条件 #2“需用户确认核心架构选择”，现经用户预授权按推荐默认方案执行，到达时不再暂停：

1. **T2-7 缺密钥降级**：实现 `missing_key_strategy: mock`（缺 key 时返回 mock provider 响应并 trace 标注），不改为“只改文档”方案。
2. **T2-14 Cross-Encoder**：不接入真语义重排；仅消除“模型不可用时返回全 1.0 并 50% 混合”的隐患（不可用即置 None 跳过混合）+ 模型 ID/blend 系数入 `configs/rag.yaml` + 文档统一称“特征重排”。
3. **T2-17 `generate()` 返回结构变更**：改返回/传递 `generate_outcome`（含 envelope+plan+outline），同步更新所有调用方与契约测试；属既有接口契约变更，按 AGENTS.md 停止条件 #7 须同步全部调用方，预授权范围内。

### 不允许动的边界（风险大于收益，临近提交不重构）

- SQLite 全局锁架构重构（连接池/aiosqlite）。
- FTS5 tokenizer 更换、OCR 集成、GraphIndex 过滤改造。
- FastAPI/HTTP 层新增。
- Java memory-service 灰度切流（保持 LOCAL 口径）。

### 执行节奏

分阶段提交+汇报：每阶段完成验证后按 AGENTS.md“每阶段完成标准”更新 `STATUS.md`，提交一个回滚点，汇报后继续下一阶段；仅遇 harness 停止条件触发或新冲突才暂停。
