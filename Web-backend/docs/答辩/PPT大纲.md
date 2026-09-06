# 答辩 PPT 大纲 — 翼览无余 AI 智能导师系统

> 任务：T33 答辩材料制作。本文档为 PPT 逐页大纲（页号、标题、要点、数据来源），每页口播稿见
> `docs/答辩/讲稿.md`（30–40 秒/页）；演示视频脚本见 `docs/答辩/视频脚本.md`；现场预检见
> `docs/答辩/现场预检清单.md`（T35）。
> 口径约束：本大纲全部表述对照 T26 能力口径复核——数字人为"协议已定义、3D 端 SDK 对接为下一阶段"，
> 多模态通道（visual/scene/graph）为协议预留未启用，语义对齐以确定性证据校验器为准（NLI 判定为规划项），
> 记忆系统当前 backend: local 未切流（T32）。未标注实测来源的数字一律不出现。

---

## P1 封面（30 秒）

- 标题：翼览无余 —— 面向航空科普的证据驱动智能导师
- 一句话定位：回答有证据、可追溯、经自检的航空科普 AI 智能导师
- 副标题：LangGraph 状态机 + 证据门控闭环 + 级联语音交互
- 数据来源：无

## P2 问题定义（40 秒）

- 场景痛点：航空科普答疑对象以未成年人为主，问答必须"可解释、不编造、可审计"
- 三个核心问题：
  1. 怎么保证"回答有证据"——事实只能来自已审核知识库，不能来自模型自由发挥
  2. 怎么保证"错答可拦截"——证据不足时必须保守澄清，而不是给一个听起来合理的数字
  3. 怎么保证"过程可审计"——每一轮回答的证据链、自检报告、改写记录都能追溯
- 对标结论：端到端语音大模型（如 Realtime / Omni）是黑盒音频流，无法在流内插入证据门与自检，与本项目
  "可信优先"目标冲突（选型论证见 `docs/adr/ADR-025-级联语音选型.md`）
- 数据来源：无

## P3 总体架构（40 秒）

- 主链：输入理解 → 记忆上下文 → RAG 证据检索 → 证据门 → 回答生成 → 自检 → 反馈改写 → 语音交互
- 编排：LangGraph 状态机（`langgraph==1.2.9`），顺序/条件分支/恢复/可观测性由图编排承担；
  `AppPipeline` 是文本与语音的唯一公共边界
- 记忆：本地 SQLite backend（`configs/memory.yaml` backend: local）；Java memory-service 已完成
  M0–M5 开发与契约测试，作为权威化演进设计经 AuthorityMode 状态机灰度切换，当前未切流（T32 口径）
- 语音：级联选型——faster-whisper 本地 ASR → 文本问答流水线 → edge-tts TTS（ADR-025），
  语音只是入口与出口，决策与事实链留在可审计文本流水线内
- 数据来源：`docs/项目总控/spec.md` LangGraph 授权节、`docs/adr/ADR-025-级联语音选型.md`

## P4 证据门与状态机（40 秒）

- 检索：六通道（keyword/dense/parent/table 启用；scene/visual/graph 协议预留未启用，T18/T26 口径）
  → RRF 融合 → 七特征重排 → 四态证据门（confident / weak / conflict / unclear）
- 门控语义（fail-closed）：只有已审核（reviewed）来源可作核心证据；gate 非 confident 时不得生成
  确定回答，转保守澄清或追问，禁止输出未经证据支撑的数值
- 状态流：`INPUT_RECEIVED → PROMPT_ROUTED → MEMORY_CONTEXT_READY → RETRIEVAL_PLANNED →
  EVIDENCE_READY → ANSWER_DRAFTED → SELF_CHECKED → FINAL_READY`（trace 可导出审计）
- 数据来源：`docs/评测与验收/demo_audit_report.md`、`docs/检索召回修复二期/task.md`（H8–H12 根因矩阵）

## P5 核心创新一：fail-closed 证据门控闭环（40 秒）

- 一句话创新：**没有合格证据就拒绝给确定答案**——证据门是"默认拒绝"的安全闸，不是"尽量通过"的过滤器
- 实现要点：
  - 证据资格：`allowed_core_review_status: [reviewed]`，draft/candidate 资料不能支撑核心事实
  - 门态驱动动作：weak/conflict/unclear 时走 ASK_CLARIFICATION / SAFE_RESPONSE / 保守回答，
    不编造参数数值（P4 规格"参数问题无权威证据时禁止输出具体数值"）
  - 量化验证（T23）：100 题 LLM-judge 复核，**误拦率 0/71 = 0%，漏拦率 0/29 = 0%**
    ——被门放行的回答没有一条被判定为错误，被门拦下的弱证据没有一条可被证据正确作答的漏放
- 数据来源：`docs/项目总控/spec.md` P3/P4/P5、`docs/项目总控/STATUS.md` T23

## P6 核心创新二：Claim 级自纠错（40 秒）

- 一句话创新：**不信任"整段生成"，逐条主张（Claim）对齐证据并复核**，未对齐的 claim 触发回退动作
- 实现要点：
  - P5 自检：`ClaimExtractor` 拆解主张 → `EvidenceAligner` 映射 claim→evidence → 场景/多模态/边界/
    安全检查 → 评分 → `DecisionRouter` 输出 PASS / REWRITE_ONLY / RETRIEVE_MORE / ASK_CLARIFICATION /
    HUMAN_REVIEW / SAFE_RESPONSE / STOP
  - P6 反馈改写：证据锁定（EvidenceLock）+ 来源保持，FACT_CHALLENGE 不允许直接改事实，
    必须补检索；改写后送回 P5 自检
  - 语义对齐口径（T26）：当前采用**确定性证据校验器**（token 重叠 + 否定检测）；NLI 语义判定
    （SemanticJudge）为规划项（生产配置 `allow_semantic_judge_for_low_risk=false`），不宣称已启用
- 数据来源：`docs/项目总控/spec.md` P5/P6、T26 口径记录

## P7 实测数据（40 秒）

- 全量自动化测试：**1500 collected / 0 failed**（`pytest tests/ -q`，T04/T05，Python 3.13.14）
- 检索指标（T17/T22，90 题新口径、排除 10 道弱证据题）：
  - FusedRecall **0.9889**（89/90）｜ Recall@3 **0.7000** ｜ MRR **0.6173** ｜ Recall@1 0.5000 ｜ Recall@5 0.7778
  - 目标对照：FusedRecall ≥0.95 ✓、Recall@3 ≥0.70 ✓、MRR ≥0.50 ✓；复核组与基线完全一致，数字可复现
  - 消融（T22）：dense 单通道 Recall@3/MRR 最高（0.7556/0.6541）；bm25 提供词项兜底（FusedRecall 0.9111，3 题仅 bm25 命中）；融合 FusedRecall 最高（0.9889）
- 可信度（T23）：faithfulness 均值 **4.94**（confident 4.986 / weak 4.828，满分 5）、relevance 4.92；
  verdict：correct 99 / wrong 0 / unverifiable 1（E10 乱码题证据为空）
- 语音实测（T13）：首响延迟 / CER / 打断成功率——**待回填，来源 T13**（20 句真实麦克风实测，
  达标线：首响延迟 ≤5s、二次对话 ≤3s；未回填前不宣称达标量化成绩）
- 数据来源：`docs/评测与验收/评测报告/rag_100q_bench_report.md`、`docs/评测与验收/语音实测报告.md`

## P8 合规与隐私（30 秒）

- 许可：edge-tts 6.1.19 实证为 **LGPL-3.0**，直接 import 不触发传染；源码交付 + 标准依赖声明形态
  无合规障碍（最坏情形分析：仅"打包分发合并产物"需未来评估）（T28）
- 出境边界：送 TTS 的文本仅限答案口语投影三字段（结论短句 + 口语化步骤 + 追问提示），
  不含用户原始转写与语音；原始音频本地处理不落盘（`raw_audio_persist_enabled=false` 配置级硬拒绝）
- 降级：edge-tts 端点失效时双阶段降级 `display_answer_only`（启动期/播放中均覆盖，带错误码与审计留痕）
- 未成年人：默认不落盘 + 记忆默认不授权长期（`default_long_term_allowed=false`、30 天过期、隐私惩罚权重最高）；
  诚实边界：无实名/年龄门禁，商用前需补监护人同意流程（记为待确认，不臆造已实现）
- 数据来源：`docs/合规与隐私边界.md`、`docs/adr/ADR-025-级联语音选型.md`

## P9 演进路线（30 秒）

- 短期（答辩后）：
  1. T13 真实语音 20 句实测回填（首响延迟/CER/打断成功率）
  2. 现场预检与模拟答辩彩排 3 次（T35 清单）
  3. Java memory-service 灰度切流（LOCAL → SHADOW → CUTOVER_PREPARED → REMOTE，当前 LOCAL）
- 中期：
  4. 数字人 3D 端 SDK 对接（协议已定义，T26 口径，不提前宣称能力）
  5. 多模态通道按需启用（visual/scene/graph 协议预留，数据入库与审核后开启）
  6. NLI 语义判定（SemanticJudge）评估启用
- 长期：
  7. 生产部署与真实模型评测、人工审核闭环
- 数据来源：`docs/评测与验收/release_acceptance.md` 已知缺口表、T32 口径

---

## 附录：全篇口径自查表（对照 T26）

| 表述 | 口径 | 是否超标 |
|---|---|---|
| 多模态通道 | visual/scene/graph `enabled:false`，协议预留未启用 | 否 |
| 数字人 | 协议已定义，3D 端 SDK 对接为下一阶段 | 否 |
| 语义对齐/相似度 | 确定性证据校验器（token 重叠+否定检测）为当前实现；NLI 为规划项 | 否 |
| 多智能体 | 不实施拆分（multi_agent_assessment 结论） | 否 |
| Java 记忆服务 | 开发与契约测试完成，backend: local 未切流 | 否 |
| 语音能力 | faster-whisper 本地 ASR + edge-tts TTS 级联已实现；端到端模型未引入 | 否 |
| 实测数字 | 全部标注 T17/T22/T23/T04/T05 来源；T13 语音数字待回填 | 否 |
