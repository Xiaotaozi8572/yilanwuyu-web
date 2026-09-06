# RAG 召回率修复专项 · 任务分解（task.md）

## 工作包定位

- **工作包名称**：RAG 召回率修复专项（任务编号 R1–R7）
- **历史关系**：位于 P0–P8、G0–G8 历史验收之后，不创建新的 P 阶段，不覆盖历史 P0–P8 与 G0–G8 验收记录
- **触发依据**：`docs/评测与验收/评测报告/rag_100q_bench_report.md`（100 题基准测试）
- **指标基线**（系统原样）：FusedRecall=0.404，Recall@3=0.378，MRR=0.305
- **诊断上限**（放宽过滤器）：FusedRecall=0.879，Recall@3=0.700，MRR=0.566
- **本工作包目标**：FusedRecall ≥ 0.85，Recall@3 ≥ 0.65，MRR ≥ 0.50
- **约束规范**：见同目录 `harness.md`
- **执行步骤**：见同目录 `spec.md`

## 指标口径（沿用基准测试，不得更改）

- FusedRecall：黄金来源是否出现在 RRF 融合候选集（纯检索层）
- Recall@k：黄金来源是否出现在最终证据包 top-k（生成实际可用，来源级计数，证据按 chunk 级排序，故为保守下界）
- 每题执行两遍取均值；诊断回放（relax filters）用于区分检索引擎能力 vs 查询理解层

## 任务总览表

| 任务 | 标题 | 优先级 | 硬依赖 | 预期 FusedRecall 增益 | 预期 Recall@3 增益 | 所属 harness |
|---|---|---|---|---|---|---|
| R1 | aircraft 大小写不敏感过滤 | P0（最高） | 无 | +0.33 | +0.30 | P3 |
| R2 | 移除 concept 严格过滤 | P0 | 无 | +0.11 | +0.08 | P3 |
| R3 | component 通用桶豁免 | P1 | 无（建议 R2 后） | +0.08 | +0.05 | P3 |
| R4 | 复杂度分类器优先级修正 | P1 | 无 | +0.00 | +0.02~0.04 | P3 |
| R5 | 空查询输入防御 | P1 | 无 | +0.00 | +0.00（error 1→0）| P1/G2 |
| R7 | dense ANN 快速路径 | P2 | 无（建议 R1–R3 后） | +0.00 | +0.00（延迟 10x）| P3 |
| R6 | 域外/安全守卫 | P2 | 独立工作包 | +0.00 | +0.00（质量/安全）| P5/G4 |

> R6 触及 self_check/evidence_gate 语义，属核心架构边界，须单独申请工作包授权后实施，不随本专项一并落地。

## 详细任务定义

### R1 — aircraft 大小写不敏感过滤

- **目标**：消除"库内存小写 `c919`、查询层产出大写 `C919`、严格相等过滤双通道归零"的召回崩溃
- **优先级**：P0（最高 ROI）
- **依赖**：无
- **触及模块**：`src/knowledge/indexes/keyword_index.py`、`src/knowledge/indexes/vector_store.py`
- **验收标准**：
  1. `tmp/rag_bench_results.json` 中 A01–A12、C01、C02、C04、C05、C08、C13、C15、C17、C19、D02、D03、D05、D07、D08、D10、D11、D12、D15、E05、E06、E09 共 33 题 `fused_recall` 由 0.0 变为 1.0
  2. `report.aircraft_case_blocked_ids` 为空数组
  3. `report.filter_attributable_recall_loss.n` 从 47 降至 ≤14
  4. `pytest tests/unit tests/integration` 全量通过，无回归
- **预期收益**：FusedRecall 0.404 → ~0.73

### R2 — 移除 concept 严格过滤

- **目标**：消除"concept 别名产出中文同词（阻力/涵道比/升力/失速）、库内 concept 是英文 snake_case（supercritical_airfoil/engine_bypass_ratio/…）、严格相等命中恒为 0"的纯负向过滤器
- **优先级**：P0
- **依赖**：无
- **触及模块**：`src/knowledge/retrieval_planner.py`、`src/knowledge/evidence_policy.py`（证据门 concept 严格相等门控同步移除）、`tests/integration/rag_pipeline/test_evidence_relevance_gate.py`（断言同步）
- **验收标准**：
  1. A21、A24、A34、A35、C14、C17、D01、D04、D14、E02、E08 等含 concept 别名题 `fused_recall` 由 0.0 变为 1.0
  2. `plan.filters` 不再含 `concept` 键
  3. 证据门不再以 concept 严格相等拒绝/资格化；`test_unrelated_reviewed_chunk_does_not_become_confident_evidence` 断言同步为 `weak_mock_vector_only` 且保持 weak 拒绝语义
  4. `pytest tests/unit tests/integration` 全量通过
- **预期收益**：FusedRecall ~0.73 → ~0.84

### R3 — component 通用桶豁免

- **目标**：避免"component=wing/engine 把 principle(697)/history(527) 等通用原理桶整体排除"，让原理题的黄金答案块不被部件过滤剔除
- **优先级**：P1
- **依赖**：无硬依赖（建议 R2 后实施，便于干净验证）
- **触及模块**：`configs/rag.yaml`、`src/knowledge/indexes/keyword_index.py`、`src/knowledge/indexes/vector_store.py`、`src/knowledge/retrieval_controller.py`（配置注入）
- **验收标准**：
  1. A17、A20、A21、A24、A25、B01、B02、E07 等含 component 别名题 `fused_recall` 由 0.0 变为 1.0
  2. 通用桶列表（principle/history/overall/comparison/application）配置化，无硬编码
  3. `pytest tests/unit tests/integration` 全量通过，新增 1 条通用桶豁免单测
- **预期收益**：FusedRecall ~0.84 → ~0.87（逼近 0.879 诊断上限）

### R4 — 复杂度分类器优先级修正

- **目标**：把因果标记检查提到 component_scene 之前，使"为什么超临界翼型能降低巡航阻力"等因果题走 L3(dense+parent) 而非 L2(scene+keyword)
- **优先级**：P1
- **依赖**：无
- **触及模块**：`src/knowledge/retrieval_planner.py`
- **验收标准**：
  1. D04、D14 复杂度由 L2 变为 L3
  2. D04、D14 通道由 scene+keyword 变为 dense+parent
  3. `pytest tests/unit tests/integration` 全量通过，新增 1 条因果+部件组合用例
- **预期收益**：FusedRecall +0.00（已被 R2/R3 解锁）；Recall@3 +0.02~0.04；MRR 提升

### R5 — 空查询输入防御

- **目标**：`understand_query("")` 与 `understand_query("？？？")` 不再抛 ContractValidationError，返回 `needs_clarification=True` 的 QueryObject
- **优先级**：P1
- **依赖**：无
- **触及模块**：`src/input/query_understanding.py`
- **验收标准**：
  1. E04 不再记为 error，`needs_clarification=True`，`clarification_reason="empty_or_unintelligible_input"`
  2. E03（？？？）、E10（什么是？？）同样返回 `needs_clarification=True`
  3. `pytest tests/unit tests/integration` 全量通过，新增 2 条空/标点输入用例
- **预期收益**：FusedRecall +0.00（E04 无黄金源）；error_count 1→0

### R7 — dense ANN 快速路径

- **目标**：把"是否走 ANN"判定从"filters 是否非空"改为"是否存在结构性过滤（aircraft/component/concept）"，让 `allowed_review_status`/`intent_type` 不再阻断 ANN
- **优先级**：P2
- **依赖**：无硬依赖（建议 R1–R3 后实施，便于验证过滤器语义稳定）
- **触及模块**：`src/knowledge/indexes/vector_store.py`
- **验收标准**：
  1. dense 通道均值延迟从 ~2.9s 降至 ≤0.5s
  2. 整体检索 p95 从 ~10s 降至 ≤1.5s
  3. FusedRecall/Recall@3 不回退（ANN 与暴力路径结果等价）
  4. `pytest tests/unit tests/integration` 全量通过
- **预期收益**：召回 +0.00；性能 10x 提升

### R6 — 域外/安全守卫（独立工作包，本专项不实施）

- **目标**：对未在知识库 aircraft 词表内的型号（波音737/747、空客A380、长征五号等）标记 `out_of_domain`，gate 强制 weak+missing；`intent=operation_safety` 时 evidence_gate 强制 weak
- **优先级**：P2
- **依赖**：需独立申请 P5/G4 工作包授权
- **触及模块**：`src/self_check/**`、`src/knowledge/evidence_gate.py`、`src/input/query_understanding.py`
- **验收标准**：C16、E01 gate 由 confident 变为 weak；不误伤 D02/D06/D13 双向型号对比题
- **本专项地位**：仅记录待办，不在本工作包实施

## 执行顺序建议

```
阶段 A（Quick wins，逼近 FusedRecall 上限）:
  R1 → R2 → R3       顺序执行，每步独立可回滚
  完成后 FusedRecall ≥ 0.85，达成核心目标

阶段 B（排序与鲁棒性）:
  R4 → R5            可并行，无相互依赖

阶段 C（性能与质量）:
  R7 → R6(独立)      R7 为主，R6 转独立工作包
```

每步完成后须在 `docs/项目总控/STATUS.md` 记录 before/after 指标，并跑 `pytest tests/unit tests/integration` 全量回归。

## 停止条件

出现以下任一情况立即停止，在 `STATUS.md` 记录"待确认"：
1. 同一任务测试连续三次失败且无法定位原因
2. 改动需要触及 `harness.md` 未列出的文件
3. 需要新增大型依赖或修改核心目录结构
4. 需要修改 QueryObject/RetrievalPlan/EvidencePackage 等对外接口契约签名
5. 任一维度 FusedRecall 较 before 回退
