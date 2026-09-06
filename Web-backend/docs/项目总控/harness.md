# 翼览无余 AI 智能导师系统开发约束与验收 Harness

## P7 实时语音专项授权边界（2026-07-13）

本轮 P7 T0–T11 已取得用户明确授权。除原 P7 范围外，可按实施计划受控修改 `src/core/contracts.py`、`src/core/settings.py`、`src/services/app_pipeline.py`、`src/app/api/schemas.py`、`src/agent/runtime.py`、计划列明的 `src/feedback/**`、`src/memory/**` 公共接线、`src/observability/**`、`src/input/voice_query_normalizer.py`、`configs/voice.yaml`、`configs/voice_terminology.yaml`、`configs/voice_pronunciation.yaml`、`scripts/run_eval.py`、`scripts/validate_deployment.py`、`pyproject.toml`、`docs/接口与部署/**` 和项目总控文档。该授权仅用于统一契约、公共接线、测试、部署验证与文档验收，不得借机重做其他阶段或破坏现有外部契约。

2026-07-13 最终 harness 核验结论：T0–T11 的修改均在上述授权内，唯一新增运行时依赖为 `websockets==15.0.1`；未读取真实密钥，未连接生产数据库或真实外部语音/模型服务，未破坏文本 API/CLI、P2、P3、P5 或 P6 公共契约。

唯一允许新增的运行时依赖是 `websockets==15.0.1`；P7 真实 provider 推进阶段（2026-08-13 用户授权）另允许 `faster-whisper<2`、`edge-tts<7`（2026-08-15 用户授权 T37：因 6.1.19 旧端点被服务端拒绝返回 403，上限调整为 `edge-tts<8`，与 `pyproject.toml` `>=7.2,<8` 一致）。严禁 voice 直接访问 retrieval index、generator、MemoryRepository 或 EvidenceStore；只能通过既有公共接口。严禁真实密钥、生产数据库、需密钥的云 ASR/TTS 与真实模型服务；允许 Mock Provider、本地随机端口、内存 transport 与离线 fixture，并允许 faster-whisper（本地推理，无密钥）与 edge-tts（无密钥公共 TTS 端点）。不得 push、自动合并、强制 reset 或覆盖来源工作区用户修改。

强制不变量：final transcript 才可进入正式 RAG；session 状态由独立 `asyncio.Lock` 串行化；TTS 取消必须原子、幂等并停止后续 chunk；barge-in 顺序必须为 `cancel -> parse`；默认 `raw_audio_persist_enabled: false`；日志、trace 和异常不得包含原始音频、完整私人 transcript、完整 Prompt、原始私人记忆或密钥。2026-08-13 真实语音 Provider 专项法典化不变量（法典化现状，非新增实现）：edge-tts 失败降级文本回答已由现有 orchestrator/websocket_server 代码成立。

## 文档依据

本文与 `task.md`、`spec.md` 使用同一套 P0 到 P8 阶段编号，用于约束 Codex 或开发者在实现系统时不能偏离《翼览无余智能体模块设计文档V2_增加语音交互.docx》的核心设计。本文重点回答：哪些文件可以改、哪些边界不能破、哪些测试必须过、如何防止 Agent 调度、知识库、记忆系统、业务模块混写。

## 需要调用的 skill

让 Codex 依据本文执行开发时，不要求每个阶段预先固定 skill，而是先调用 `using-superpowers`，由它根据当前阶段任务、风险、失败状态和可用工具动态判断还需要哪些 skill。为满足工作区 AGENTS.md 中“Codex 提示词必须明确声明 skill”的要求，本提示词只声明全局必需 skill：

1. `using-superpowers`：每次开始阶段实现或修复前先调用，用于发现并调度当前阶段真正需要的 skill。
2. `writing-plans`：当阶段涉及多文件、多模块或多步骤实现时调用，用于列出可核查计划和文件范围。
3. `systematic-debugging`：任何测试失败、链路异常、输出偏离或线上症状复现时调用。
4. `verification-before-completion`：阶段完成前必须调用，用于运行测试、契约检查和文档一致性检查。
5. `openai-docs`：仅当阶段涉及 OpenAI 语音、Realtime、结构化输出、模型或 API 变化时调用，用于查询官方最新文档。

除上述全局规则外，本文不在每个阶段单独指定 skill。阶段执行者必须在进入具体实现前依据 `using-superpowers` 的判断动态补充调用相关 skill，并在阶段验收中记录实际调用过的关键 skill。

## P1 Prompt Refactor Update

由于旧文档顶部存在乱码，本节单独重述当前实现下 P1 Prompt 相关的可审计边界；本节只做澄清，不新增安全范围。

- `src/prompts/repository.py` 替代已删除的 `src/prompts/asset_store.py`。
- Prompt 资产只能从 `assets/prompts/<template_id>/asset.json`、`versions/*.json`、`evaluations/*.json` 加载。
- `PromptAssetRepository` 是唯一允许的 Prompt 资产/版本/快照加载入口；运行态 `active` 和 `experimental` 版本必须有 approved evaluation snapshot，且快照 `snapshot_id` 必须与版本 `snapshot_id` 完全匹配。
- `PromptAssembler` 必须输出 canonical `PromptMessageBundle`，不得再定义或返回独立 `MessageBundle`。
- bundle 中的消息必须是 `services.model_client.ModelMessage`，角色只能是 `system` 与 `user`。
- 普通 trace 可记录 template id、version、snapshot id、missing variables、route reason 和 injection summary，但不得记录完整 prompt 正文。
- Prompt 资产只负责 instruction style、teaching style 和 output behavior；航空事实必须继续来自 `evidence_package`。
- 旧平铺 YAML 资产、`PromptAssetStore`、`default_prompt_assets` 和任何 `role="context"` / `role: context` 组装方式均为禁止项。

## 全局安全边界

- 禁止绕过 `evidence_package` 直接生成航空事实。
- 禁止把用户记忆、用户反馈、Prompt 资产或模型常识当作航空事实来源。
- 禁止将多模块逻辑混写到单一巨型文件中。
- 禁止硬编码核心业务规则、阈值、Provider、模型名、top_k、Prompt 内容和安全策略。
- 禁止破坏 `scene_state`、`memory_context`、`evidence_package`、`answer_envelope`、`check_report`、`rewrite_plan`、`run_trace` 等统一契约。
- 禁止引入 Word 设计文档没有依据的大型依赖；确需引入时必须标记 `待确认` 并说明用途、替代方案和回滚方式。
- 禁止让语音端到端模型绕过 RAG、自检和日志审计直接回答事实问题。
- 禁止跳过测试、日志、异常处理和配置管理。

## 全局验收命令建议

待项目脚手架建立后，每阶段至少提供以下等价命令：

```text
pytest tests/unit
pytest tests/integration
pytest tests/e2e
python scripts/run_eval.py --suite smoke
python scripts/export_trace_report.py --run-id <run_id>
```

如果某阶段尚未具备完整测试命令，必须在阶段验收中标记 `待确认`，并至少提供 schema 校验、单元测试或手工验收记录。

## 回答生成专项 G0–G8 门禁

历史 P0 至 P8 已完成；G0 至 G8 是其后的回答生成维护工作包，不是新的 P 阶段。每个 G 除遵守下表外，还必须遵守所触及模块对应的原 P harness。跨 P 文件只有在该 G 的“允许目录/文件”中明确列出时才可修改。

所有 G 的公共禁项为：真实密钥、真实生产数据库或外部生产服务；破坏 `TextQueryRequest`、`TextQueryResponse.to_dict()`、现有 answer/action/trace/CLI 外部契约；新增大型依赖；修改未列出的核心目录；覆盖用户无关改动；把 Prompt、记忆、反馈或模型常识作为航空事实。出现总控文档冲突、需要核心架构选择、需要扩大目录/依赖/接口范围、既有接口将被破坏，或同一测试连续三次失败且无法定位时，立即停止并在 `STATUS.md` 记录待确认。

| G 工作包 | 允许目录/文件（实施计划 Task Files 汇总） | 禁止触碰项 | 目标测试 | 前序回归 | 本包停止条件 | 回滚提交 |
| --- | --- | --- | --- | --- | --- | --- |
| G0 | 仅下方“G0 精确允许清单” | 公共禁项；任何 `src/**`、配置或业务行为 | golden+CLI；9 strict xfail；全量 pytest、compile、smoke、trace、deploy | 当前已发布 suite 与 `test_cli_pipeline.py` | golden 需要改业务才能通过；九项中出现意外 XPASS 未核实；基线命令失败无法定位 | `test: freeze answer generation baseline` |
| G1 | 仅下方“G1 精确允许清单” | 公共禁项；generation/self-check/runtime 主链和未列配置 | answer contracts、runtime settings、structured parser 单测 | G0 golden/CLI、9 strict xfail、既有 core/services 测试 | canonical 与 legacy 不能单向兼容；配置存在第二事实源；外部 golden 变化 | `refactor: add canonical answer subcontracts`；`refactor: add typed generation settings and answer parser` |
| G2 | 仅下方“G2 精确允许清单” | 公共禁项；模型调用、自检判定、recovery loop、voice | 路由、evidence sketch、plan/outline 与 generation preparation | G0、G1 全部门；现有 rag/answer pipeline | 路由无法保持六类字符串；证据边界需改变外部契约；plan/outline 未被真实消费 | `refactor: centralize answer type routing`；`refactor: build complete evidence sketches`；`feat: add grounded answer plans and outlines` |
| G3 | 仅下方“G3 精确允许清单” | 公共禁项；P5 recovery loop、自检语义、DisplayBlock 终态物化、真实网络调用 | model runtime/factory、generation pipeline、Prompt 注入、deterministic fallback、AppPipeline 模型路径 | G0–G2；现有 prompt/generation/app tests | G3 不能原子接通主路径；fallback 双轨；需真实 Provider/密钥；Prompt 可补事实 | `refactor: centralize model runtime and factory`；`feat: add model-backed answer generation pipeline` |
| G4 | 仅下方“G4 精确允许清单” | 公共禁项；检索 recovery loop、display/voice 投影、未列知识索引 | claim completeness、semantic/parameter/multimodal support、decision priority、canonical self-check、安全门 | G0–G3；既有 self-check/app regression | 空 claim 或假引用仍可 PASS；安全门可被模型/后序动作覆盖；canonical 切换不能保持绿色 | `feat: enforce answer claim completeness`；`feat: verify claims against evidence semantics`；`feat: enforce canonical grounded and safe self-check` |
| G5 | 仅下方“G5 精确允许清单” | 公共禁项；G6 display/voice、G7 删除项、未列索引/配置 | retrieval directive/refinement/merge/fingerprint、rewrite/retrieve actions、AnswerLoop、终态与 trace | G0–G4；CLI、self-check loop、answer pipeline | RETRIEVE_MORE 空转；REWRITE_ONLY 不删正文或不重检；终态绕过 FINAL_READY；trace 泄露隐私 | `feat: add retrieval refinement directives`；`feat: implement answer recovery actions`；`refactor: run canonical answer recovery loop` |
| G6 | 仅下方“G6 精确允许清单” | 公共禁项；从 draft/HUMAN_REVIEW/STOP 创造展示或语音事实；原始音频默认落盘 | visual integrity、FINAL_READY display projection、spoken claim preservation、voice final-only | G0–G5；现有 voice/e2e 核心流 | 展示/语音需生成 envelope 外事实；无法证明来源和 claim 交集；非 FINAL_READY 仍送 TTS | `feat: project verified answers into display blocks`；`feat: restrict voice output to verified final answers` |
| G7 | 仅下方“G7 精确允许清单” | 公共禁项；无替代路径或仍有引用的删除；改变 Prompt 事实边界；修改未列资产/目录 | legacy/旧 facade 零引用搜索、配置 consumer map、compile、目标测试、全量测试 | G0–G6 全部门 | 任一删除目标仍有生产/测试引用；兼容 bridge 仍承担外部读取；配置消费者不唯一 | `refactor: remove legacy answer generation paths` |
| G8 | 仅下方“G8 精确允许清单” | 公共禁项；为评测通过修改业务逻辑；伪造 evidence/check/trace；未脱敏报告 | evaluation settings、unit/integration/E2E/full pytest、compile、smoke/eval、trace、deploy、零引用审计 | G0–G7 全部门和 golden contract | 任一发布门失败；指标低于批准阈值；trace/报告泄露；外部 contract 变化 | `test: validate answer generation refactor` |

### G0–G8 精确允许清单

以下清单逐项抄录已批准实施计划的 Task Files 汇总，是专项修改范围的唯一白名单。除清单中显式保留的 `/**` 项外，目录名、概括性模块名、测试类别或上表文字均不得解释为允许修改同目录其他文件；测试命令可读取未列文件，但不能修改或暂存它们。

- **G0：** `tests/fixtures/answer_contract_v1.json`；`tests/integration/app_loop/test_answer_contract_compatibility.py`；`tests/integration/answer_pipeline/test_answer_generation_regressions.py`；`AGENTS.md`；`docs/项目总控/task.md`；`docs/项目总控/spec.md`；`docs/项目总控/harness.md`；`docs/项目总控/AUTO_DEV.md`；`docs/项目总控/STATUS.md`；`docs/4回答生成/设计文档/2026-07-11-回答生成系统差异修复任务.md`；`docs/4回答生成/实施计划/2026-07-11-回答生成系统差异修复实施计划.md`；`docs/评测与验收/评测报告/smoke_eval.json`；`docs/评测与验收/追踪报告/answer_generation_g0_baseline.md`。

- **G1：** `src/core/base_contracts.py`；`src/core/answer_contracts.py`；`src/core/answer_compat.py`；`src/core/legacy_answer_payload.py`；`src/core/errors.py`；`src/core/contracts.py`；`src/core/runtime_settings.py`；`src/core/settings.py`；`src/services/structured_output.py`；`configs/app.yaml`；`configs/providers.yaml`；`configs/voice.yaml`；`configs/rag.yaml`；`tests/unit/core/test_answer_contracts.py`；`tests/unit/core/test_runtime_settings.py`；`tests/unit/services/test_model_client.py`；`docs/项目总控/STATUS.md`。

- **G2：** `src/input/query_object.py`；`src/input/query_understanding.py`；`src/generation/policies.py`；`src/generation/answer_types.py`；`src/generation/evidence_sketch.py`；`src/generation/planner.py`；`src/generation/outline.py`；`src/knowledge/evidence_package.py`；`src/knowledge/evidence_gate.py`；`src/knowledge/schemas.py`；`src/knowledge/source_registry.py`；`src/knowledge/retrieval_controller.py`；`src/agent/runtime.py`；`src/services/app_pipeline.py`；`configs/prompts.yaml`；`tests/fixtures/answer_generation.py`；`tests/unit/generation/test_answer_type_router.py`；`tests/unit/input/test_safety_intent_priority.py`；`tests/unit/generation/test_evidence_sketch.py`；`tests/unit/knowledge/test_evidence_package.py`；`tests/unit/generation/test_generation_planner.py`；`tests/unit/generation/test_answer_outline.py`；`tests/integration/rag_pipeline/test_evidence_relevance_gate.py`；`tests/integration/answer_pipeline/test_generation_preparation.py`；`tests/integration/answer_pipeline/test_answer_generation_regressions.py`；`docs/项目总控/STATUS.md`。

- **G3：** `src/services/model_factory.py`；`src/services/model_runtime.py`；`src/services/model_client.py`；`src/services/deepseek_client.py`；`src/services/structured_output.py`；`src/generation/drafting.py`；`src/generation/claim_normalizer.py`；`src/generation/trace_builder.py`；`src/generation/pipeline.py`；`src/generation/citation_binding.py`；`src/generation/generator.py`；`src/core/errors.py`；`src/core/legacy_answer_payload.py`；`src/prompts/runtime.py`；`src/prompts/assembler.py`；`src/prompts/asset_models.py`；`src/prompts/router.py`；`src/safety/legacy_response_bridge.py`；`src/agent/actions.py`；`src/agent/runtime.py`；`src/services/app_pipeline.py`；`configs/prompts.yaml`；`tests/fixtures/answer_generation.py`；`tests/unit/services/test_model_client.py`；`tests/unit/services/test_model_runtime.py`；`tests/unit/generation/test_model_generation.py`；`tests/unit/generation/test_claim_normalizer.py`；`tests/unit/prompts/test_prompt_assembler.py`；`tests/unit/prompts/test_prompt_router.py`；`tests/integration/answer_pipeline/test_generation_pipeline.py`；`tests/integration/app_loop/test_app_pipeline_model_generation.py`；`tests/integration/answer_pipeline/test_answer_generation_regressions.py`；`docs/项目总控/STATUS.md`。

- **G4：** `src/generation/citation_binding.py`；`src/generation/generator.py`；`src/generation/drafting.py`；`src/self_check/contracts.py`；`src/self_check/claim_completeness.py`；`src/self_check/claim_extractor.py`；`src/self_check/semantic_alignment.py`；`src/self_check/parameter_checker.py`；`src/self_check/parameter_index.py`；`src/self_check/multimodal_checker.py`；`src/self_check/safety_checker.py`；`src/self_check/service.py`；`src/self_check/decision_router.py`；`src/self_check/score_card.py`；`src/self_check/boundary_checker.py`；`src/self_check/legacy_adapter.py`；`src/safety/response_builder.py`；`src/safety/legacy_response_bridge.py`；`src/knowledge/evidence_package.py`；`src/agent/actions.py`；`src/agent/runtime.py`；`src/services/app_pipeline.py`；`tests/fixtures/answer_generation.py`；`tests/unit/self_check/test_claim_completeness.py`；`tests/unit/generation/test_citation_binding.py`；`tests/unit/self_check/test_evidence_alignment.py`；`tests/unit/self_check/test_parameter_checker.py`；`tests/unit/self_check/test_claim_support.py`；`tests/unit/self_check/test_decision_router.py`；`tests/unit/self_check/test_safety_policy.py`；`tests/unit/self_check/test_decision_priority.py`；`tests/integration/app_loop/test_agent_decision_execution.py`；`tests/integration/answer_pipeline/test_canonical_self_check.py`；`tests/integration/answer_pipeline/test_answer_generation_regressions.py`；`docs/项目总控/STATUS.md`。

- **G5：** `src/core/action_contracts.py`；`src/core/errors.py`；`src/core/runtime_settings.py`；`src/core/state_machine.py`；`src/core/contracts.py`；`src/core/answer_contracts.py`；`src/core/tracing.py`；`src/self_check/retrieval_directive.py`；`src/self_check/contracts.py`；`src/self_check/service.py`；`src/knowledge/schemas.py`；`src/knowledge/retrieval_controller.py`；`src/knowledge/evidence_package.py`；`src/agent/runtime.py`；`src/agent/actions.py`；`src/agent/rewrite_engine.py`；`src/agent/retrieval_action.py`；`src/agent/answer_loop.py`；`src/services/app_pipeline.py`；`src/services/streaming_policy.py`；`configs/rag.yaml`；`tests/unit/knowledge/test_retrieval_directive.py`；`tests/unit/self_check/test_retrieval_directive.py`；`tests/unit/agent/test_rewrite_engine.py`；`tests/unit/agent/test_retrieval_action.py`；`tests/unit/agent/test_answer_loop.py`；`tests/unit/services/test_streaming_policy.py`；`tests/integration/rag_pipeline/test_retrieval_refinement.py`；`tests/integration/answer_pipeline/test_self_check_retrieval_directive.py`；`tests/integration/answer_pipeline/test_action_recovery.py`；`tests/integration/app_loop/test_generation_runtime.py`；`tests/integration/app_loop/test_cli_pipeline.py`；`tests/integration/answer_pipeline/test_self_check_loop.py`；`tests/integration/answer_pipeline/test_answer_generation_regressions.py`；`docs/项目总控/STATUS.md`。

- **G6：** `src/generation/visual_references.py`；`src/generation/display_blocks.py`；`src/core/answer_contracts.py`；`src/agent/answer_loop.py`；`src/agent/runtime.py`；`src/knowledge/evidence_package.py`；`src/voice/spoken_answer.py`；`src/voice/voice_loop.py`；`src/voice/tts.py`；`src/voice/terminology.py`；`src/input/voice_query_normalizer.py`；`src/app/api/schemas.py`；`src/services/app_pipeline.py`；`configs/voice.yaml`；`tests/unit/generation/test_display_blocks.py`；`tests/unit/generation/test_visual_references.py`；`tests/unit/voice/test_spoken_answer.py`；`tests/unit/voice/test_query_normalizer.py`；`tests/unit/voice/test_voice_components.py`；`tests/integration/answer_pipeline/test_final_display_projection.py`；`tests/integration/voice_loop/test_mock_voice_loop.py`；`tests/integration/answer_pipeline/test_answer_generation_regressions.py`；`docs/项目总控/STATUS.md`。

- **G7：** `src/generation/generator.py`；`src/core/legacy_answer_payload.py`；`src/safety/legacy_response_bridge.py`；`src/self_check/legacy_adapter.py`；`src/self_check/evidence_alignment.py`；`src/core/contracts.py`；`src/core/answer_compat.py`；`src/generation/answer_types.py`；`src/generation/evidence_sketch.py`；`src/generation/planner.py`；`src/generation/citation_binding.py`；`src/generation/display_blocks.py`；`src/self_check/claim_extractor.py`；`src/self_check/boundary_checker.py`；`src/self_check/multimodal_checker.py`；`src/self_check/decision_router.py`；`src/agent/actions.py`；`src/feedback/**`；`src/services/app_pipeline.py`；`src/services/model_client.py`；`src/services/deepseek_client.py`；`src/prompts/repository.py`；`src/prompts/router.py`；`src/prompts/runtime.py`；`src/prompts/asset_models.py`；`assets/prompts/aviation_concept_correction/asset.json`；`assets/prompts/aviation_quiz_reinforcement/asset.json`；`assets/prompts/aviation_self_check/asset.json`；`assets/prompts/spoken_answer_style_prompt/asset.json`；`assets/prompts/experimental_teaching_strategy/asset.json`；`tests/unit/prompts/**`；`tests/unit/generation/**`；`tests/unit/generation/test_memory_expression_only.py`；`tests/unit/core/test_encoding_and_config.py`；`tests/unit/feedback/**`；`tests/integration/answer_pipeline/**`；`tests/integration/feedback_loop/**`；`tests/e2e/scenarios/test_voice_flow.py`；`docs/项目总控/spec.md`；`docs/项目总控/harness.md`；`docs/项目总控/STATUS.md`。

- **G8：** `tests/evals/answer_generation_cases.json`；`configs/evals.yaml`；`src/evaluation/__init__.py`；`src/evaluation/settings.py`；`scripts/run_eval.py`；`scripts/export_trace_report.py`；`scripts/validate_deployment.py`；`tests/e2e/scenarios/test_eval_and_deployment_scripts.py`；`tests/unit/evaluation/test_eval_settings.py`；`tests/e2e/scenarios/test_text_qa_flow.py`；`tests/e2e/scenarios/test_voice_flow.py`；`tests/integration/feedback_loop/test_canonical_answer_compatibility.py`；`docs/评测与验收/eval_plan.md`；`docs/评测与验收/release_acceptance.md`；`docs/评测与验收/评测报告/smoke_eval.json`；`docs/评测与验收/评测报告/answer_generation_eval.json`；`docs/评测与验收/追踪报告/answer_generation_refactor_acceptance.md`；`docs/接口与部署/api_contracts.md`；`docs/接口与部署/deployment_checklist.md`；`docs/项目总控/task.md`；`docs/项目总控/spec.md`；`docs/项目总控/harness.md`；`docs/项目总控/STATUS.md`。

“回滚提交”是该 G 的独立提交边界：回滚失败工作包只能回滚表中对应提交，不得重写或合并历史 P0 至 P8 验收提交，也不得把后续 G 的修复偷带回前序提交。

## P0：工程骨架、统一状态机与数据契约

### 约束对象

约束 `spec.md` 中 P0 的工程骨架、数据契约、状态机、动作枚举、配置和基础日志实现。

### 允许修改的文件范围

- `pyproject.toml`
- `.env.example`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/core/**`
- `tests/unit/core/**`
- `README.md`

### 禁止修改的文件范围

- P1 到 P8 尚未创建的业务模块不得塞入 P0。
- 不得修改原始 Word 文档和用户提供资料。
- 不得将演示数据、Prompt 模板、航空知识库内容写入 `src/core/`。

### 禁止引入的设计偏差

- 禁止每个模块自定义状态枚举或动作枚举。
- 禁止把状态机写成不可回放的 if/else 散逻辑。
- 禁止把配置默认值散落在业务代码中。

### 必须遵守的接口边界

- 所有阶段必须引用 `core.actions.ActionDecision`。
- 所有阶段必须通过 `core.state_machine` 记录状态跳转。
- 所有跨模块对象必须继承或遵守 `core.contracts`。

### 必须通过的测试

- 状态枚举完整性测试。
- 非法状态跳转失败测试。
- `RunTrace` 最小字段校验测试。
- 配置加载缺失项测试。

### 必须满足的日志、异常、配置要求

- 状态跳转必须记录 run_id、from_state、to_state、reason。
- schema 错误必须包含字段路径。
- 配置缺失不得静默使用业务默认值。

### 安全边界

P0 不接触用户真实数据、语音、知识库和 Prompt 内容，只定义框架。

### 回滚策略

如果 P0 契约设计错误，应先新增兼容字段和迁移测试，再逐步替换；不得直接删除已被后续阶段引用的字段。

### 阶段完成前检查清单

- [ ] `ActionDecision` 包含 Word 文档定义的八类动作。
- [ ] 状态机覆盖主链路和回退链路。
- [ ] `RunTrace` 可关联输入、检索、生成、自检、反馈和最终输出。
- [ ] 配置不含硬编码密钥。
- [ ] core 单测通过。

## P1：输入理解、场景状态与 Prompt 路由

### 约束对象

约束 `spec.md` 中 P1 的 `query_object`、场景绑定、Prompt 资产模型、Prompt 路由和注入顺序。

### 允许修改的文件范围

- `src/input/**`
- `src/prompts/**`
- `configs/prompts.yaml`
- `tests/unit/prompts/**`
- `tests/unit/core/test_contracts.py`

### 禁止修改的文件范围

- 不得在 P1 修改 `src/knowledge/**` 以绕过检索。
- 不得在 P1 写入长期记忆。
- 不得在 Prompt 模块中写航空事实库内容。

### 禁止引入的设计偏差

- 禁止用一个万能 Prompt 处理所有任务类型。
- 禁止把某次运行的具体飞机、部件、用户偏好固化到 Prompt 模板。
- 禁止让 Prompt 资产补充 RAG 没有的事实。
- 禁止 candidate/draft Prompt 进入正式回答链路。

### 必须遵守的接口边界

- `QueryObject` 只表达用户意图和场景绑定，不生成事实答案。
- `PromptRouter` 只能选择和组装模板，不访问知识库底层索引。
- `PromptAssembler` 必须保证 RAG 证据在 Prompt 资产之前注入。

### 必须通过的测试

- 缺失必填变量时降级基础模板。
- 非 active Prompt 不能被正式路由。
- 指代词在 scene_object_id 明确时正确绑定。
- 多候选指代时返回 `needs_clarification`。

### 必须满足的日志、异常、配置要求

- 记录 selected_template_id、fallback_reason、missing_variables。
- Prompt 状态非法时抛出明确错误。
- 模板变量契约必须可配置和可测试。

### 安全边界

Prompt 模块只能管理“怎么讲”，不能决定“事实是什么”。用户问题中的事实猜测不得进入 Prompt 资产正文。

### 回滚策略

Prompt 新版本出现质量下降时，将资产状态改为 `deprecated` 或回滚到 parent_version，不删除历史版本。

### 阶段完成前检查清单

- [ ] 注入顺序符合 Word 文档：事实边界、任务、场景、RAG、记忆、薄弱点、Prompt、输出契约。
- [ ] `{{rag_evidence}}` 缺失时不会生成事实型最终 Prompt。
- [ ] Prompt 资产包含状态、版本、变量、风险边界。
- [ ] prompt 单测通过。

## P2：记忆系统与时间有效性治理

### 约束对象

约束 `spec.md` 中 P2 的事件日志、结构化记忆、时间有效性、记忆读写权限、记忆上下文和审计。

### 允许修改的文件范围

- `src/memory/**`
- `configs/memory.yaml`
- `tests/unit/memory/**`
- `tests/integration/answer_pipeline/**` 中与 memory_context 相关的测试

### 禁止修改的文件范围

- 不得修改 `src/knowledge/source_registry.py` 或知识库事实表来写入用户说法。
- 不得在记忆模块中生成最终回答。
- 不得让任意 Agent 直接写长期记忆而不走 `MemoryController`。

### 禁止引入的设计偏差

- 禁止把完整聊天记录无差别塞进长期记忆。
- 禁止用户误解污染航空事实库。
- 禁止过期、低置信、高隐私记忆默认进入生成上下文。
- 禁止自我反思模块承担运行时最终质量裁决。

### 必须遵守的接口边界

- 记忆写入必须遵循：事件日志 -> 候选抽取 -> 时间归一 -> 冲突检测 -> 治理检查 -> 分层存储。
- `memory_context` 只能影响表达、指代和教学路径。
- `policy_memory` 优先约束所有记忆读写。

### 必须通过的测试

- 事件写入后才能生成候选记忆。
- 冲突偏好使用 supersedes/superseded_by 表达。
- 过期记忆不进入 `memory_context`。
- 用户反馈中的航空事实不能进入知识事实库。

### 必须满足的日志、异常、配置要求

- 每次写入记录 source_event_id、confidence、privacy_level、status、reason。
- 拒绝写入必须可审计。
- 衰减、过期、冲突扫描阈值必须配置化。

### 安全边界

原始语音、学习轨迹、隐私偏好和导出报告必须遵守用户授权。未确认的 ASR 结果只能保留在事件日志。

### 回滚策略

记忆写错时不得物理删除第一选择，应将状态改为 `rejected`、`superseded` 或 `archived`，保留审计记录。用户要求删除时按隐私策略执行硬删除或脱敏。

### 阶段完成前检查清单

- [ ] `MemoryController` 是唯一长期写入入口。
- [ ] 记忆状态机包含 candidate、active、stale、superseded、expired、rejected、archived。
- [ ] `memory_context` 明确列出 prohibited_memories。
- [ ] memory 单测通过。

## P3：本地航空知识库、RAG 与多模态证据包

### 约束对象

约束 `spec.md` 中 P3 的资料入库、source_registry、多索引检索、scene_object_registry、多模态证据和 evidence_package。

### 允许修改的文件范围

- `src/knowledge/**`
- `configs/rag.yaml`
- `scripts/ingest_sources.py`
- `data/raw_sources/**`
- `data/processed/**`
- `tests/integration/rag_pipeline/**`

### 禁止修改的文件范围

- 不得在 P3 写回答生成逻辑。
- 不得在知识库中写入用户记忆、用户反馈或模型自由生成事实。
- 不得在检索模块修改 Prompt 资产状态。

### 禁止引入的设计偏差

- 禁止只做向量 top_k 检索而没有关键词、元数据、来源和质量门控。
- 禁止未审核资料作为核心航空事实。
- 禁止视觉识别标签无文本交叉验证就作为事实依据。
- 禁止 missing_evidence 为空但实际证据不足。

### 必须遵守的接口边界

- `source_registry.review_status=reviewed` 才能成为核心证据。
- `scene_object_registry` 是 3D 场景与知识实体的绑定入口。
- `EvidenceGate` 只判断证据包能否进入生成，不评价最终答案是否忠实使用证据。

### 必须通过的测试

- 资料入库字段完整。
- 精确术语能走关键词通道。
- 口语问题能走向量或混合通道。
- 当前 selected_object_id 能绑定场景对象。
- draft/deprecated 来源被排除为核心证据。

### 必须满足的日志、异常、配置要求

- 记录 retrieval_plan、query_variants、channel_hits、gate_status、missing_evidence。
- top_k、阈值、权威等级、review_status 过滤必须配置化。
- 检索失败要返回结构化原因，不返回空字符串。

### 安全边界

知识库只接收人工审核资料、项目资料、PDF/图文解析和 3D 元数据。用户反馈只能作为误解或待核查信号，不能成为事实库来源。

### 回滚策略

入库资料质量有问题时，将 source 或 chunk 标记为 `deprecated`，重建索引；不得直接静默删除造成证据链断裂。

### 阶段完成前检查清单

- [ ] `evidence_package` 包含 query_understanding、scene_binding、retrieval_plan、evidence_items、claim_support_map、missing_evidence、generation_boundary、audit_trace。
- [ ] 视觉证据有 source_id、bbox 或 layout_trace。
- [ ] `generation_boundary` 明确禁止无证据内容。
- [ ] rag 集成测试通过。

## P4：回答生成、来源绑定与结构化输出

### 约束对象

约束 `spec.md` 中 P4 的 answer type 路由、evidence_sketch、grounded_drafting、source_binding、answer_envelope 和 display_blocks。

### 允许修改的文件范围

- `src/generation/**`
- `tests/unit/generation/**`
- `tests/integration/answer_pipeline/**`
- `configs/app.yaml` 中 generation 相关配置

### 禁止修改的文件范围

- 不得在 P4 修改知识库证据。
- 不得在 P4 直接写长期记忆。
- 不得绕过 P5 直接把 draft 标记为最终安全输出。

### 禁止引入的设计偏差

- 禁止生成器凭模型常识补航空参数。
- 禁止为了表达生动改变事实含义。
- 禁止 source_binding 事后随意贴来源。
- 禁止把用户偏好作为事实依据。

### 必须遵守的接口边界

- `evidence_package` 是事实唯一来源。
- `memory_context` 只控制表达方式和纠偏提醒。
- `answer_envelope` 必须包含 claim_candidates，供 P5 检查。
- 参数事实型回答必须有权威来源或输出不确定说明。

### 必须通过的测试

- 缺少具体参数证据时不输出数值。
- 每个关键 claim 有 evidence_id 或 uncertainty_notes。
- 部件场景回答使用 scene_state.component_id。
- display_blocks 不改变事实内容。

### 必须满足的日志、异常、配置要求

- generation_log 记录 template_id、evidence_ids、source_binding_count、uncertainty_count、latency_ms。
- 输出长度、流式策略、安全模板必须配置化。
- 结构化输出缺字段时失败重试或降级。

### 安全边界

涉及维修、改装、飞行操作、故障处置或危险实验时，生成器只能输出科普解释和安全建议，不能输出可执行步骤或参数。

### 回滚策略

生成模板导致幻觉率升高时，回滚 Prompt 资产或 generation policy，并保留失败样例进入评测集。

### 阶段完成前检查清单

- [ ] `answer_envelope` 字段完整。
- [ ] `source_binding` 与 evidence_id 对齐。
- [ ] unsupported claim 不会被当作最终事实。
- [ ] generation 单测和主链路集成测试通过。

## P5：自我检查、动作分流与回退闭环

### 约束对象

约束 `spec.md` 中 P5 的 claim 抽取、证据对齐、场景/多模态/边界/安全检查、score_card 和 action_decision。

### 允许修改的文件范围

- `src/self_check/**`
- `src/core/state_machine.py` 中必要的回退边扩展
- `tests/unit/self_check/**`
- `tests/integration/answer_pipeline/**`

### 禁止修改的文件范围

- 不得在 self_check 中直接写长期记忆。
- 不得在 self_check 中直接修改知识库资料。
- 不得让自检模块生成全新事实答案。

### 禁止引入的设计偏差

- 禁止自检只返回“通过/不通过”而没有 issue_list。
- 禁止 evidence_gate 与自检重复召回逻辑混在一起。
- 禁止无限 RETRIEVE_MORE 或 REWRITE_ONLY 循环。
- 禁止 LLM Judge 单独决定所有高风险安全问题。

### 必须遵守的接口边界

- 自检只裁决质量和动作，不承担初稿生成职责。
- `RETRIEVE_MORE` 必须携带缺失证据原因。
- `REWRITE_ONLY` 必须携带 revised_instruction。
- `SAFE_RESPONSE` 由安全检查硬触发。

### 必须通过的测试

- 无证据参数 claim 不 PASS。
- 机翼场景回答发动机内容被拦截。
- Prompt/记忆越权作为事实来源被拦截。
- 危险操作请求触发 SAFE_RESPONSE。
- 超过循环上限触发 STOP。

### 必须满足的日志、异常、配置要求

- 记录 score_card、failed_checks、unsupported_claims、action_decision、loop_count。
- 阈值必须配置化。
- check_report 缺字段时不得静默通过。

### 安全边界

自检日志不得泄露敏感个人信息。对语音来源文本，应记录必要摘要和置信度，不保存原始音频。

### 回滚策略

自检规则误杀正常答案时，先增加测试样例和阈值配置，再调整规则；不得删除安全检查。

### 阶段完成前检查清单

- [ ] check_report 包含 score_card、issue_list、failed_checks、action_decision、revised_instruction、audit_log。
- [ ] 每个回退动作都有 reason。
- [ ] 最大循环次数生效。
- [ ] self_check 测试通过。

## P6：反馈改写、多轮 checkpoint 与记忆候选

### 约束对象

约束 `spec.md` 中 P6 的 checkpoint、反馈分类、证据锁定、受控改写、delta_map、记忆候选和改写后自检。

### 允许修改的文件范围

- `src/feedback/**`
- `src/memory/**` 中 memory_update_candidate 接口
- `tests/unit/feedback/**`
- `tests/integration/feedback_loop/**`

### 禁止修改的文件范围

- 不得在 feedback 模块直接修改知识库事实。
- 不得在 feedback 模块直接把偏好写成 active 长期记忆。
- 不得跳过 P5 自检输出改写结果。

### 禁止引入的设计偏差

- 禁止把反馈改写当作重新问答，从而丢失上一轮证据。
- 禁止用户说“你错了”就直接改口。
- 禁止格式转换时为了表格完整编造资料。
- 禁止删除必要安全提示。

### 必须遵守的接口边界

- 所有改写基于 checkpoint 恢复的 previous_answer_envelope 和 evidence_package。
- `FACT_CHALLENGE` 必须生成 verification_queries 或回到 P3。
- `memory_update_candidate` 必须交给 P2 治理。
- `delta_map` 必须记录新增、删除、改写 claim。

### 必须通过的测试

- SIMPLIFY 保留 source_binding。
- SHORTEN 不删除安全提醒。
- FORMAT_TRANSFORM 对无证据维度输出“资料未覆盖”。
- FACT_CHALLENGE 不直接覆盖事实。
- SAFETY_SENSITIVE 强制 SAFE_RESPONSE。

### 必须满足的日志、异常、配置要求

- rewrite_log 记录 feedback_intent、rewrite_action、changed_claims、preserved_source_binding。
- max_rewrite_rounds 必须配置化并生效。
- checkpoint 缺失要明确降级。

### 安全边界

用户反馈可能包含错误事实或越界请求。反馈只能作为待处理信号，不能直接成为事实、Prompt 资产或长期记忆。

### 回滚策略

改写模板造成新增幻觉时，回滚模板版本，将失败反馈加入评测集，并降低对应 Prompt 资产状态。

### 阶段完成前检查清单

- [ ] checkpoint 保存完整上一轮证据和自检结果。
- [ ] evidence_lock 防止新增无证据 claim。
- [ ] 改写后重新进入 P5。
- [ ] feedback 测试通过。

## P7：语音交互与实时会话

### 约束对象

约束 `spec.md` 中 P7 的音频传输、VAD、ASR、术语纠错、voice_query_normalizer、TTS、barge_in 和语音指标。

### 允许修改的文件范围

- `src/voice/**`
- `src/input/voice_query_normalizer.py`
- `configs/voice.yaml`
- `tests/unit/voice/**`
- `tests/integration/voice_loop/**`

### 禁止修改的文件范围

- 不得让 voice 模块绕过 P1/P3/P4/P5 主链路直接回答航空事实。
- 不得在 voice 模块直接写长期记忆。
- 不得把低置信 ASR 文本送入 RAG 正式检索。

### 禁止引入的设计偏差

- 禁止端到端 speech-to-speech 模型作为事实问答主链路。
- 禁止 TTS 播报长篇书面答案。
- 禁止 barge_in 只记录不停止旧音频。
- 禁止原始音频默认持久化。

### 必须遵守的接口边界

- `ASRProvider`、`TTSProvider` 必须可替换。
- `voice_query_object` 必须包含 raw_transcript、corrected_transcript、normalized_query、asr_confidence、intent_type、target_entity、scene_object_id、needs_clarification。
- spoken_answer 来自已通过主链路的 answer_envelope。
- barge_in 事件进入 P6 反馈改写。

### 必须通过的测试

- 低置信 ASR 触发澄清。
- 航空术语误识别触发纠错或确认。
- TTS 播报中打断进入 INTERRUPTED/REWRITE。
- spoken_answer 长度受控。
- 原始音频不默认落盘。

### 必须满足的日志、异常、配置要求

- 记录 ASR 置信度、VAD 状态、TTS 状态、barge_in 时间、语音指标。
- Provider、阈值、术语词典路径、最大语音长度必须配置化。
- 音频错误不应重跑 RAG，保留文本回答降级。

### 安全边界

语音输入更容易误识别，因此事实链路必须更保守。低置信、无场景对象、指代不明时先澄清，不强答。

### 回滚策略

语音 Provider 质量不稳定时切换到备用 Provider 或文本模式，保留主链路可用性。

### 阶段完成前检查清单

- [ ] 语音状态机包含 IDLE、LISTENING、TRANSCRIBING、UNDERSTANDING、RETRIEVING、GENERATING、SPEAKING、INTERRUPTED、REWRITE、CLARIFY。
- [ ] 低置信 ASR 不进入正式检索。
- [ ] barge_in 优先级最高。
- [ ] voice 测试通过。

## P8：日志评测、演示审计与部署治理

### 约束对象

约束 `spec.md` 中 P8 的 run_trace 汇总、评测集、指标、演示报告、部署配置和回归测试。

### 允许修改的文件范围

- `configs/evals.yaml`
- `scripts/**`
- `docs/**`
- `tests/e2e/**`
- `tests/integration/**` 中评测相关文件

### 禁止修改的文件范围

- 不得为了评测通过修改业务逻辑绕过真实流程。
- 不得在演示脚本中伪造 evidence_package 或 check_report。
- 不得导出未脱敏隐私、原始语音或未授权学习轨迹。

### 禁止引入的设计偏差

- 禁止只测最终回答文本，不测证据链。
- 禁止只测正常路径，不测证据不足、场景错位、安全和语音低置信。
- 禁止部署配置依赖开发机绝对路径。

### 必须遵守的接口边界

- 评测脚本必须通过正式 API 或正式 pipeline 调用系统。
- `run_trace` 必须能关联 query、scene、prompt、memory、retrieval、answer、check、rewrite、voice。
- 演示报告只能展示脱敏数据。

### 必须通过的测试

- 文本问答 E2E。
- 反馈改写 E2E。
- 语音状态机 E2E 或模拟 E2E。
- 证据不足和安全边界回归测试。
- trace 导出测试。

### 必须满足的日志、异常、配置要求

- run_trace 缺关键字段则评测失败。
- 评测阈值、套件名称、部署变量必须配置化。
- 评测失败报告必须定位模块和原因。

### 安全边界

演示与评测数据必须可脱敏、可复现、可清理。不得在报告中暴露真实用户隐私或密钥。

### 回滚策略

部署失败时回滚到上一套通过评测的配置和模型 Provider。评测退化时保留失败 run_trace，回滚相关阶段改动。

### 阶段完成前检查清单

- [ ] `run_eval.py --suite smoke` 可运行。
- [ ] E2E 覆盖文本、反馈、语音核心路径。
- [ ] trace 报告能解释证据命中、自检动作和最终输出。
- [ ] 部署配置不含本机绝对路径和密钥。
- [ ] P0 到 P8 全部测试或替代验收记录已通过。

## RAG 知识检索工程化重构验收边界（2026-07-10）

- 唯一生产入口为配置驱动的持久化 repository/controller；生产配置不得选择测试种子适配器、Mock 语义回退或已删除的 token 相似度路径。
- reviewed 来源、来源类型、版本/权威、Evidence Gate 与知识/记忆分库边界不得为评测通过而绕过；Prompt、记忆、用户反馈和模型输出不得作为事实来源。
- 写入型入库、评测和 trace 命令必须显式使用临时知识库与临时记忆库；不得修改 `data/processed/knowledge.sqlite3` 或 `data/processed/memory.sqlite3`。
- trace/report 仅允许审计摘要：复杂度、通道、命中数、RRF 排名、重排特征合计、来源权威标签、Gate、缺失/冲突代码、补检索差异和延迟。禁止导出来源全文、私有记忆正文、Prompt 正文或原始音频。
- `mock_pdf_stub`、`mock_visual_adapter` 只能如实标识离线/测试不完整适配器；不得宣称 OCR、视觉 embedding、ColPali、VisRAG、GraphRAG 或生产级向量能力已完成。
- 正式知识库 validation 失败属于外部数据状态时必须如实记录，不得静默清理、重建或伪报成功。

## LangGraph 文本主链迁移：用户授权与门禁

本迁移在 P0–P8 和 G0–G8 历史验收之后执行，不修改其历史记录。允许的精确依赖仅为 `langgraph==1.2.9` 与 `langgraph-checkpoint-sqlite==3.1.0`，所有依赖核验、同步和测试仅使用 `D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe` 的 Python 3.11.9 环境；项目 Python 下限仍为 `>=3.11`。文本主链直接迁移到 LangGraph，`AppPipeline` 仍为文本/语音公共边界，语音实时层不进入图编排。`TextQueryRequest`、`TextQueryResponse`、`TextQueryResponse.to_dict()`、CLI 参数和公开 answer/action/trace 字段必须保持不变。

checkpoint 只能使用独立于 `knowledge.sqlite3` 和 `memory.sqlite3` 的本地 SQLite 文件，并且仅保存恢复所必需的最小脱敏状态；严禁写入原始音频、完整 Prompt、私有记忆正文、来源全文、证据正文或密钥。DIRECT 仅处理无事实对话，航空事实必须经 `RetrievalController` 的已审核证据；不安全输入必须在记忆查询、检索或生成之前拒答。必须执行精确依赖契约、CLI 公共响应契约、四路路由与指代消解、恢复/清理/隐私扫描，以及文本、RAG、记忆、反馈、语音和全量 pytest 回归。

### 本迁移允许修改的文件

下列为批准实施计划实际列出的文件；除这些文件外，本迁移不得修改、暂存或新增其他产品文件：

- `src/agent/graph_contracts.py`
- `src/agent/context_resolution.py`
- `src/agent/graph_artifacts.py`
- `src/agent/langgraph_checkpointer.py`
- `src/agent/langgraph_runtime.py`
- `src/agent/runtime.py`
- `src/agent/actions.py`
- `src/agent/retrieval_action.py`
- `src/agent/__init__.py`
- `src/agent/answer_loop.py`
- `src/services/app_pipeline.py`
- `src/core/runtime_settings.py`
- `src/input/query_understanding.py`
- `configs/app.yaml`
- `pyproject.toml`
- `uv.lock`
- `tests/unit/agent/test_langgraph_dependency_contract.py`
- `tests/unit/agent/test_graph_contracts.py`
- `tests/unit/agent/test_context_resolution.py`
- `tests/unit/agent/test_langgraph_checkpointer.py`
- `tests/unit/agent/test_answer_loop.py`
- `tests/unit/core/test_runtime_settings.py`
- `tests/unit/self_check/test_decision_priority.py`
- `tests/integration/app_loop/test_cli_pipeline.py`
- `tests/integration/app_loop/test_langgraph_runtime.py`
- `tests/integration/app_loop/test_langgraph_recovery.py`
- `tests/integration/app_loop/test_generation_runtime.py`
- `tests/integration/app_loop/test_runtime_uses_real_memory_context.py`
- `tests/integration/answer_pipeline/test_answer_generation_regressions.py`
- `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`
- `src/knowledge/retrieval_controller.py`
- `src/memory/controller.py`
- `tests/unit/knowledge/test_rag_runtime_boundaries.py`
- `tests/integration/app_loop/test_agent_decision_execution.py`
- `tests/integration/voice_loop/test_voice_trace_privacy.py`
- `docs/项目总控/task.md`
- `docs/项目总控/spec.md`
- `docs/项目总控/harness.md`
- `docs/项目总控/AUTO_DEV.md`
- `docs/项目总控/STATUS.md`
- `docs/接口与部署/api_contracts.md`
- `docs/接口与部署/deployment_checklist.md`
- `scripts/validate_deployment.py`

Task 7 全量测试诊断后，用户明确授权上述五个补充文件仅用于最小兼容性与测试隔离修复：为 checkpoint 冲突检查公开实际 controller 数据库路径、隔离测试 checkpoint，以及在事件循环关闭前 drain 语音指标写入。该五项是本例外的完整清单，不授权任何其他新增文件或产品范围扩展。

不允许新增 LangChain、LangSmith、Agent Server、云服务、真实模型 provider、向量数据库或生产数据库连接。若指定 Python 3.11.9 环境中的依赖不兼容、SQLite 集成必须持久化禁存内容、恢复必须改变公开契约、实现必须绕过既有 controller、语音不变量被破坏，或同类测试连续三次失败仍无法定位，立即停止并在 `STATUS.md` 记录“待确认”。

## 记忆服务重构专项 M0–M5 门禁

本门禁位于 P0–P8、G0–G8 与已批准 LangGraph 迁移边界之后，不覆盖其历史记录。专项顺序固定为 `M0 → M1 → M2 → M3 → M4 → M5`；当前阶段所有任务、测试、审查与清洁检查通过前不得进入下一阶段，跨阶段任务不得并行。

### 不可变架构与安全边界

- PostgreSQL + pgvector 是唯一权威长期记忆存储。Redis 只承担内部 Streams 和 L2；Neo4j 只是可删除、可从 PostgreSQL 重建的投影；Caffeine 只是 Java L1。
- Python worker 无数据库凭据、正式记忆写入或晋级权限。worker 输出只作为候选，Java 必须按权威来源重新验证并治理。
- memory 只能影响个性化表达、学习状态和对话连续性，不能成为航空事实来源；任何事实断言仍只由 `EvidencePackage` 支持。
- Python 等待 memory 最多 150 ms。Java 超时、不可用、schema 错误、响应损坏、身份绑定不匹配、类型/source/budget 校验失败时，丢弃全部远端长期记忆并使用全新的空长期记忆上下文；RAG 继续完成带 `EvidencePackage` 的回答，正文不暴露技术故障。
- REMOTE 激活后 Java 不可用时不得回退旧 SQLite 长期记忆。M0–M4 不得宣称完成权威切换；只有 M5 全部门槛通过后才允许进入 REMOTE。
- 不允许真实密钥、生产数据库、生产身份服务、外部生产服务、生产部署、Compose、Kubernetes、生产端口或模型文件进入专项产物。
- 不执行任何 Git、分支、worktree、stage、commit、push、merge、PR、reset、checkout 或 clean 操作；不得读取或修改 `.git`、`.worktrees`。

### M0 精确允许清单与依赖门禁

M0 仅允许下列项目文件；`.venv` 仅作为计划批准的 Python 3.11 测试环境，`tmp/memory-system/m0` 仅存任务临时产物并在阶段验收前移除：

- `docs/项目总控/task.md`
- `docs/项目总控/spec.md`
- `docs/项目总控/harness.md`
- `docs/项目总控/AUTO_DEV.md`
- `docs/项目总控/STATUS.md`
- `tests/contracts/test_memory_refactor_governance.py`
- `tests/contracts/test_memory_workspace_layout.py`
- `tests/contracts/test_memory_proto_contract.py`
- `tests/fixtures/memory_contract/v1/text_response.json`
- `tests/fixtures/memory_contract/v1/resolve_applied.bin`
- `scripts/check_memory_workspace_cleanliness.py`
- `scripts/generate_memory_contracts.py`
- `contracts/memory/v1/memory_context.proto`
- `contracts/memory/v1/memory_event.proto`
- `contracts/memory/v1/memory_worker.proto`
- `contracts/memory/v1/memory_management.openapi.yaml`
- `contracts/memory/v1/COMPATIBILITY.md`
- `src/memory/transport/__init__.py`
- `src/memory/transport/generated/__init__.py`
- `src/memory/transport/generated/*_pb2.py`
- `src/memory/transport/generated/*_pb2_grpc.py`
- `pyproject.toml`
- `uv.lock`
- `services/memory-service/pom.xml`
- `services/memory-service/mvnw`
- `services/memory-service/mvnw.cmd`
- `services/memory-service/.mvn/wrapper/**`
- `services/memory-service/src/main/java/com/yilan/memory/MemoryServiceApplication.java`
- `services/memory-service/src/main/resources/application.yaml`
- `services/memory-service/src/test/java/com/yilan/memory/ArchitectureTest.java`
- `services/memory-service/src/test/java/com/yilan/memory/contract/GoldenContractTest.java`

M0 精确锁定 Python 3.11、`grpcio==1.82.1`、`grpcio-tools==1.82.1`、`protobuf==7.35.1`、JDK 21、Maven Wrapper 3.9.16、Spring Boot 4.0.6、Spring gRPC 1.0.3、JUnit 5 与 ArchUnit。M0 不得含 JDBC、Redis、Neo4j、模型依赖或数据服务连接；不得修改 `AppPipeline`、`MemoryController`、`TextQueryRequest`、`TextQueryResponse.to_dict()`、CLI 和公开 answer/action/trace 字段。依赖解析仅使用现有 Python 锁和 Maven Wrapper；一次性 Maven 引导器只可生成锁定 3.9.16 的 wrapper，之后所有 Java 命令只用 `services/memory-service/mvnw.cmd`。

### G0–G8 与 M0–M5 专项门禁表

| 工作包 | 必须保持 | 阶段关闭前门禁 |
| --- | --- | --- |
| M0 | 文档/契约/生成代码/无数据库 skeleton；公开回答十一键基线不变 | governance/layout/Proto/cross-language golden、CLI 与 memory-cannot-be-fact 回归、JDK 21 wrapper clean test、清洁检查 |
| M1 | PostgreSQL-only 权威核心；Redis/Neo4j/model 不存在 | Testcontainers repository/governance/resolve、身份/source closure/幂等/DEGRADED、公开回答回归 |
| M2 | `MemoryPort`、可信 session、150 ms、新空上下文降级、本地加密 outbox | timeout/corrupt/identity/circuit/outbox/prefetch/local-shadow-remote 与 Java-down 有证据回答 |
| M3 | Redis 只传最小 envelope；worker 无状态；Neo4j/cache 可旁路重建 | replay/checkpoint/reconciler/worker validation/drop-rebuild/cache epoch/组件故障闭环 |
| M4 | 默认关闭、主体不可由请求选择、append-only、强 epoch、加密、forget | 匿名/撤回/跨用户/角色/篡改/缓存/forget/retention/replay，泄漏与事实污染为 0 |
| M5 | 只读迁移、shadow 对比、单一权威、有限标签观测 | 160 用例、负载、故障/重放、cutover crash points、Java-down EvidencePackage 和 clean workspace |

### 自动停止条件

出现以下任一项必须立即停止当前任务，在 `STATUS.md` 记录精确证据，且不得进入下一任务或阶段：JDK 21 缺失或 Maven Wrapper 3.9.16 无法生成；需要未批准大型依赖、目录、接口、安全权限或 allowlist 外文件；公开回答或跨语言契约将被破坏；task/spec/harness/AUTO_DEV/批准计划存在真实冲突；需要密钥或真实生产服务；跨用户记忆、撤回后仍可读取、memory-as-fact、forget 数据复活；需要改变核心架构；同一问题连续三次失败且 systematic debugging 仍不能定位。普通可定位测试失败必须先修复，不得删除测试、放宽断言、关闭安全检查或延长 150 ms deadline。

## R0 审阅修复专项门禁（2026-08-16）

本门禁位于 P0–P8、G0–G8、LangGraph 迁移、M0–M5 与真实语音 Provider 专项历史边界之后，不覆盖其历史记录。R0 专项基于三份独立审阅衍生的《最终修复计划.md》，对该计划 72 项问题分阶段修复。

### 不可变架构与安全边界（继承全局 + 专项补充）

- 继承“全局安全边界”全部条款；R0 不得绕过 `evidence_package` 直接生成航空事实，不得把记忆/反馈/Prompt/模型常识当事实来源，不得破坏 `TextQueryRequest`/`TextQueryResponse.to_dict()`/CLI/公开 answer/action/trace 契约（T2-17 除外，已预授权且须同步全部调用方）。
- R0 触及的每个模块仍须遵守该模块原 P harness 不变量：P1（RAG 证据先于 Prompt 注入、candidate/draft 不入正式链路）、P2（MemoryController 唯一长期写入入口、记忆不作事实）、P3（reviewed 才是核心证据、EvidenceGate 只判准入）、P4（evidence_package 唯一事实源、claim 可追溯）、P5（自检不生成事实、回退动作带 reason、循环上限生效）、P6（改写基于 checkpoint、evidence_lock 防新增 claim、改写后重入 P5）、P7（voice 不绕过主链路、低置信 ASR 不入正式检索、barge_in 最高优先）、P8（不为评测改业务逻辑、不伪造 evidence/check/trace、报告脱敏）。
- 不允许真实密钥、真实生产数据库、真实外部生产服务、真实模型 provider 调用；本次仅 mock 闭环 + 单测隔离。真实 DeepSeek 线上验收仍属后续范围。
- 不引入新运行时依赖（`pyproject.toml` 仅允许 T4-1 元数据/分组调整与 T0-7 可能的 `cryptography` 版本号修正）；不重构 SQLite 全局锁架构、不接 FastAPI、不集成 OCR、不切 Java memory-service 灰度。
- `.workbuddy/` 目录是项目数据目录，非临时缓存，禁止删除。

### 逐任务文件白名单

R0 每个任务的“允许修改文件 / 禁止触碰 / 验证检查 / 回滚策略”以 `docs/项目总控/R0审阅修复/harness.md` 的逐任务白名单表为唯一权威。该白名单外文件一律不得修改。跨任务冲突高发文件（`langgraph_runtime.py`、`deepseek_client.py`、`retrieval_controller.py`、`assembler.py`、`self_check/service.py`、`configs/rag.yaml`、`README.md`/`docs/评委速览.md`、`websocket_server.py`、`tests/unit/safety/`）须按白名单文档“三、跨任务冲突预防”表串行修改，前任务 commit 后再开下一任务。

### 改动范围与回滚

- 单次提交不得超出当前任务白名单边界；越权改动立即 `git checkout -- <file>` 回滚并在 STATUS.md 记录违规。
- 每任务开始前确保工作区干净或已 `git stash`；高风险改动先备份到 `tmp/backup/<task_id>/`。
- 每步改动后须通过该任务“验证检查项”，不带着失败进下一步。

### 自动停止条件

出现以下任一项立即停止当前任务，在 `STATUS.md` 记录精确证据，且不进入下一任务：task/spec/harness/R0 子文档与项目既有总控文档存在真实冲突；需要未批准依赖/目录/接口/权限或白名单外文件；公开契约将被破坏（T2-17 除外，已预授权且须同步全部调用方）；需要核心架构选择（T2-7/T2-14/T2-17 除外，已预授权）；需要密钥或真实生产服务；同一问题连续三次失败且无法定位。普通可定位测试失败必须先修复，不得删除测试、放宽断言或关闭安全检查。
