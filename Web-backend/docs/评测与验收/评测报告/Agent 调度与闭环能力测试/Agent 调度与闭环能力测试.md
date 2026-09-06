# Agent 调度与闭环能力测试

## 1. 测试概况

- 测试日期：2026-08-18
- 测试依据：`C:\Users\SONGQI\Desktop\系统性能测试方案.md` 第三节“Agent 调度与闭环能力测试”
- 测试环境：Windows；项目 `.venv`；Python 3.11.9；本地知识库与本地 embedding 缓存
- 安全边界：离线 T3 回归未调用真实 LLM；随后按用户明确授权完成一次真实 DeepSeek 文本复核，密钥仅由进程环境读取且未输出，知识库使用临时数据，未连接生产数据库或生产服务
- 测试范围：T3.1 契约行为、T3.2 自主补检、T3.3 自检失败降级、T3.4 多轮上下文、T3.5 checkpoint 故障恢复

## 2. 结论摘要

本轮修复后，Agent 调度与闭环能力测试 **T3.1–T3.5 全项通过**。

| 项目 | 结果 | 结论 |
|---|---:|---|
| T3.1 检索契约行为 | 8/8，`pass_rate=1.0` | 通过；补齐低置信语音门禁场景 |
| T3.2 自主补检闭环 | E2E 1/1；补检内核与生成回归 9/9 | 通过；验证 `RETRIEVE_MORE`、top-k 增量、证据合并、再生成与再 gate |
| T3.3 自检失败降级 | 3/3；未知 action fail-closed 1/1 | 通过；异常注入后安全转 `HUMAN_REVIEW`，不发布草稿 claim |
| T3.4 多轮上下文 | 新增 20/20；既有断言 14/14 | 通过；满足方案要求的 20 组覆盖 |
| T3.5 checkpoint 故障恢复 | 7/7 | 通过；恢复、隔离、清理与隐私边界均通过 |

### 修复内容

1. 将 `rag_refactor` 评测脚本补齐为 8 个实际执行场景，新增低置信 ASR 场景，验证 `low_confidence_asr`、`not_run` 和空检索计划。
2. 将 AG600 场景与升力/推力对比场景的评测期望对齐当前已启用的 keyword/dense 检索通道；scene/graph 当前仍按配置保持预留禁用。
3. 修正自检失败 E2E 固件：显式注入 self-check 异常，验证运行时 fail-closed 到 `HUMAN_REVIEW`，并确认 claim 候选为空。
4. 新增 20 组多轮指代解析场景，验证最新显式对象优先和 `landing_gear` 目标解析。
5. 更新部署脚本验收测试，使其符合当前 mock profile 的正式契约：退出码 0、status 为 `ok`、LLM/Embedding 均为 mock。

## 3. 分项结果

| 编号 | 测试命令/范围 | 结果 | 关键证据 | 结论 |
|---|---|---:|---|---|
| T3.1 | `scripts/run_eval.py --suite rag_refactor` | 8/8 | `pass_rate=1.0`；套件 `latency_ms=788`；所有 prohibited claims 均未观察到 | 通过 |
| T3.2 | `tests/e2e/scenarios/test_retrieve_more_loop.py` | 1/1 | 离线复核 19.39 s；返回结构化 trace | 基础链路通过，指标断言不足 |
| T3.2 补充 | `tests/unit/agent/test_retrieval_action.py` + `test_retrieve_more_regenerates_and_rechecks_with_recovered_prompt_context` | 9/9 | directive 消费、证据合并、再 gate、进度/指纹与重新生成均通过 | 补检内核通过 |
| T3.3 | `test_self_check_failover.py` + `test_self_check_loop.py` | 3/3 | 显式注入 self-check 异常后转 `HUMAN_REVIEW`，结构化响应保持有效 | 通过 |
| T3.3 补充 | `test_graph_unknown_action_fails_closed_without_retrieval_recovery` | 1/1 | 未知 action 降级为 `HUMAN_REVIEW`，未触发检索恢复 | fail-closed 分支通过 |
| T3.4 | `test_context_resolution_20_turns.py` + 既有上下文用例 | 20/20 + 14/14 | 新增 20 组多轮场景全部通过 | 通过 |
| T3.5 | `test_langgraph_recovery.py` | 7/7 | 恢复、隔离、终态清理与隐私边界通过 | 通过 |

## 4. T3.1 契约行为评测明细

评测产物：`tmp/eval_reports/rag_refactor_eval.json`。

| 场景 | 实际 gate | 实际通道 | 结果 |
|---|---|---|---|
| `c919_wing_lift_definition` | confident | keyword, dense | 通过 |
| `c919_engine_bypass_definition` | confident | keyword, dense | 通过 |
| `ag600_hull_scene_binding` | confident | keyword, dense | 通过 |
| `lift_thrust_comparison` | weak | keyword, dense | 通过 |
| `missing_reviewed_parameter` | weak | keyword, dense | 通过 |
| `low_confidence_voice` | not_run | voice_normalization | 通过；`clarification_reason=low_confidence_asr` |
| `conflicting_parameter_sources` | conflict | keyword, dense | 通过 |
| `fact_challenge_targeted_retrieval` | not_run | keyword, dense | 通过 |

该套件验证了检索计划、证据门状态、低置信语音短路、冲突处理、禁止主张和定向复检计划差异；但它是临时知识库/记忆库评测，不能替代真实生产数据规模下的端到端性能结论。

## 5. T3.2–T3.5 行为分析

### 5.1 自主补检

配置中 `configs/rag.yaml` 固定了 `max_retrieve_loops=2`、`recovery_top_k_increment=5`。补充单测验证了恢复动作消费冻结 directive、增加 top-k、合并新旧证据、重新执行 evidence gate、检测无进展和证据身份冲突。现有 E2E 测试没有把这些条件写入断言，因此其通过仅代表图执行没有崩溃。

### 5.2 自检失败降级

测试显式 monkeypatch 自检服务抛出 `SIMULATED_SELF_CHECK_FAILURE`，运行时进入 fail-closed 分支。响应仍为结构化 `status=ok`，但动作决策为 `HUMAN_REVIEW`，答案中的 `claim_candidates` 为空，trace 记录终态为 `FINAL_READY`，不会把未通过自检的草稿作为事实答案发布。

未知 action 场景同样验证了 fail-closed：非法 action 直接转为 `HUMAN_REVIEW`，且不触发 retrieval recovery。

### 5.3 多轮上下文

新增 20 组多轮对话 fixture 覆盖最近显式对象优先；每组均验证跟随问题解析为 `landing_gear`，原因码保持一致。既有 14 个断言继续覆盖当前场景优先、候选去重、未解析指代澄清、危险输入短路、历史内容不泄露等；LangGraph 运行时用例进一步验证了未解析指代在图入口被澄清。

### 5.4 故障恢复

7 个 checkpoint 用例全部通过，包含检索后中断恢复且不重复检索、请求不匹配前置拒绝、终态 checkpoint 不重复执行、指代场景重建、finalize 前恢复、终态清理和清理失败告警。结果支持“最多一次 resume 尝试、恢复后契约保持一致”的当前测试结论。

## 6. 真实 LLM 复核

### 6.1 真实 provider 调用

通过正式 `AppPipeline.run_text_query()` 公共入口，在临时知识库中注入一条审核证据后发起真实文本请求，未使用 `MockModelClient`。

真实调用 trace：

| 字段 | 结果 |
|---|---|
| `model_provider` | `deepseek` |
| `model_alias` | `deepseek-v4-flash` |
| `fallback_used` | `false` |
| `model_error_code` | `null` |
| `self_check_completed` | `true` |
| 证据门 | `confident` |
| 最终安全动作 | `HUMAN_REVIEW` |
| 最终原因码 | `BODY_CLAIM_UNDECLARED` |
| 模型调用耗时 | 19,376 ms |

真实模型确实参与生成；由于模型返回的正文存在未声明 claim，系统按安全契约转入 `HUMAN_REVIEW`，清空可发布 claim，而不是把不完整输出标记为通过。这证明真实 provider 下的 fail-closed 行为有效，但该条真实样本本身不是“有 claim 的 grounded answer 通过”样本。

### 6.2 真实部署门禁

修复后的 `scripts/validate_deployment.py --profile real` 已只校验当前选中的 provider 密钥：DeepSeek 与本地 `bge_m3` 不再错误要求未启用的 DashScope/NVIDIA key。当前实测 `missing_env_keys=[]`、DeepSeek 配置有效、bge_m3 模型缓存有效、TTS 探测通过；Whisper 在 5 秒探测窗口内出现 `model_load_timeout_5s`，因此 real profile 总体仍报告 `real_ready=false`。这属于语音探测限制，不影响本节已完成的真实 LLM 文本调用。

- 验证命令：`\.venv\Scripts\python.exe scripts\validate_deployment.py --profile real`
- 当前结果：`failed_checks=["real_voice_reachability"]`，ASR 超时，TTS=ok，`missing_env_keys=[]`

## 7. 风险与限制

1. scene/graph 检索通道按当前配置保持预留禁用；本报告不将其宣称为已启用能力。
2. 测试使用临时知识库/记忆库和离线 mock，结果不替代真实生产数据规模、真实外部模型和长时运行压力测试。
3. 个别 E2E 运行可能出现 `MEMORY_TIMEOUT` 或 `RAG_PREFETCH_TIMEOUT` 降级告警；本轮相关测试退出码为 0，告警不构成 T3 调度契约失败，性能专项仍应单独观察。

## 8. Harness 合规性

本轮修改限于 `configs/evals.yaml`、`scripts/run_eval.py`、`scripts/validate_deployment.py`、G8 评测测试、上下文补充测试、报告和 `docs/项目总控/STATUS.md`；未修改 `src/**` 业务逻辑、未新增依赖、未输出密钥。真实模型调用是用户明确授权的验收动作，使用临时知识库和正式 `AppPipeline` 公共入口；未连接生产数据库或生产服务。

## 9. 复现命令

以下命令均在项目根目录执行；涉及本地 embedding 的命令建议先设置离线变量：

```powershell
$env:HF_HUB_OFFLINE = "1"
$env:PYTHONDONTWRITEBYTECODE = "1"

.\.venv\Scripts\python.exe scripts\run_eval.py --suite rag_refactor
.\.venv\Scripts\python.exe -m pytest tests\e2e\scenarios\test_retrieve_more_loop.py -v
.\.venv\Scripts\python.exe -m pytest tests\e2e\scenarios\test_self_check_failover.py tests\integration\answer_pipeline\test_self_check_loop.py -v
.\.venv\Scripts\python.exe -m pytest tests\unit\agent\test_context_resolution.py tests\integration\app_loop\test_langgraph_runtime.py::test_langgraph_runtime_clarifies_an_unresolved_reference -v
.\.venv\Scripts\python.exe -m pytest tests\integration\app_loop\test_langgraph_recovery.py -v

# 补充闭环断言
.\.venv\Scripts\python.exe -m pytest tests\unit\agent\test_retrieval_action.py tests\integration\answer_pipeline\test_answer_generation_regressions.py::test_retrieve_more_regenerates_and_rechecks_with_recovered_prompt_context -v
.\.venv\Scripts\python.exe -m pytest tests\integration\answer_pipeline\test_answer_generation_regressions.py::test_graph_unknown_action_fails_closed_without_retrieval_recovery -v
```

## 10. 最终验收判定

**T3 离线验收通过。** T3.1–T3.5 均已取得可复现的离线自动化证据；修复后的全量组合回归退出码为 0。真实 LLM 复核也确认实际 provider 已接通，并在模型输出不满足 claim 契约时正确进入 `HUMAN_REVIEW`。真实 profile 尚有 Whisper 5 秒探测超时，不能把 real deployment 宣称为全项 ready。
