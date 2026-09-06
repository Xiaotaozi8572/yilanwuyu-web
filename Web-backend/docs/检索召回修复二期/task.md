# RAG 召回率修复二期 · 任务分解（task.md）

## 工作包定位

- **工作包名称**：RAG 召回率修复二期（任务编号 R8–R12）
- **历史关系**：承接一期 R1–R7（FusedRecall 0.404→0.85），位于历史 P0–P8、G0–G8 之后，不创建新的 P 阶段，不覆盖历史验收记录
- **触发依据**：R1–R7 完成后 Recall@3=0.4333 未达 0.65 目标，诊断发现门控层（evidence_policy）与排序层（reranker）存在 R1/R3 未覆盖的延伸 bug
- **指标基线**（R1–R7 后，旧口径含 10 weak 题）：FusedRecall=0.85，Recall@3=0.4333，MRR=0.335
- **指标基线**（新口径排除 weak 题，R8 后生效）：FusedRecall=0.9444（85/90），Recall@3=0.4333
- **诊断上限**（放宽过滤）：FusedRecall=0.81，Recall@3=0.70
- **本工作包目标**：FusedRecall ≥0.95（新口径），Recall@3 ≥0.70，MRR ≥0.50
- **约束规范**：见同目录 `harness.md`
- **执行步骤**：见同目录 `spec.md`

## 指标口径变更（R8 引入，用户已确认）

- 旧口径：`fused_recall` = 100 题均值（含 10 题 `expect=weak` 且 `gold=[]`，这 10 题恒 0）
- 新口径：`fused_recall` / `recall@k` / `mrr` 只对 `gold` 非空的题（90 题）取均值，weak 题用 NaN 排除
- 旧口径数学上限 = 90/100 = 0.90；新口径上限 = 90/90 = 1.0，0.95 可达
- 诊断上限 0.81（旧口径）对应新口径 = 0.81/0.90 = 0.90（仍需超越诊断上限，靠门控层修复 R9/R10）
- 注：实测 BANK 中 `gold=[]` 的 weak 题为 10 个（C06/C07/C09/C10/C16/E01/E03/E04/E05/E10），其中 **C09（空客 A380 翼展）** 在文档初稿中被漏记；R8 实现按 `r.get("gold")` 过滤，分母以实际 90 为准

## 根因验证矩阵（已对照代码 + bench 实测）

| # | 根因 | 验证结论 | 证据 | 受影响题 |
|---|---|---|---|---|
| H8 | evidence_policy aircraft 严格相等 | ✅ 属实，R1 未覆盖门控层 | [evidence_policy.py:73](file:///d:/APP/Python%203.13/挑战杯/src/knowledge/evidence_policy.py#L73) `candidate.chunk.aircraft != target_aircraft`；R1 后 target_aircraft=C919(大写)、chunk.aircraft=c919(小写) → target_mismatch 拒绝。31 题证据包完全为空（gate=weak，missing=no_qualified_evidence） | A01–A12、C01/C02/C04/C05/C08/C14/C15/C17/C19、D02–D12、E06/E09（31 题） |
| H9 | evidence_policy component 严格相等 | ✅ 属实，R3 未覆盖门控层 | [evidence_policy.py:76](file:///d:/APP/Python%203.13/挑战杯/src/knowledge/evidence_policy.py#L76) `candidate.chunk.component != target_component`；R3 通用桶豁免只在检索层 `_matches_filters`，门控层仍严格相等 → 通用桶 chunk 被拒 | 同 H8 的 31 题（component 过滤叠加） |
| H10 | reranker _scene_match 严格相等 | ✅ 属实 | [reranking.py:201](file:///d:/APP/Python%203.13/挑战杯/src/knowledge/reranking.py#L201) `actual == expected`；aircraft c919 vs C919 不匹配 → scene_match=0 → query_relevance 权重 0.30 中 scene_match 部分(0.20)丢失 → 排序分降低，gold 不进 top-3 | A14/A15/A16/A21/A24/A25/A27/A34/B01/B02/C12/D04/E02/E07（14 题 gold 进了证据包但不在 top-3） |
| H11 | COMPONENT_ALIASES 映射过粗 | ✅ 属实 | [query_understanding.py:15-22](file:///d:/APP/Python%203.13/挑战杯/src/input/query_understanding.py#L15-L22) "翼/机翼→wing"、"发动机→engine"；DB component 桶为 rotor/high_wing/propulsion/powerplant/drag-breakdown，wing/engine 桶不存在 → 含"翼"查询 component=wing 把所有非 wing 桶排除 | A17(发动机→engine, gold=propulsion)、A20(翼尖小翼→wing, gold=drag-breakdown)、A33(旋翼→wing, gold=rotor-lift) |
| H12 | RRF 等权无通道权重 | ⚠️ 次要 | [fusion.py:81](file:///d:/APP/Python%203.13/挑战杯/src/knowledge/fusion.py#L81) `1/(rrf_k+rank)` 所有通道等权；keyword 通道词项多易压过 dense 语义匹配 | 排序质量问题（H10 修复后影响减小） |

## 任务总览表

| 任务 | 标题 | 优先级 | 硬依赖 | 预期 FusedRecall 增益（新口径） | 预期 Recall@3 增益 | 所属 harness |
|---|---|---|---|---|---|---|
| R8 | bench 口径修正（排除 weak 题） | P0 | 无 | +0.084（口径重算） | +0.059（口径重算） | P8（scripts/**） |
| R9 | evidence_policy 门控层对齐 | P0 | 无 | +0.00 | +0.35（31 题证据包复活） | P3 |
| R10 | reranker _scene_match 对齐 | P1 | R9（先解证据包空） | +0.00 | +0.10（14 题排序提升） | P3 |
| R11 | COMPONENT_ALIASES 精确化 | P1 | 无 | +0.033（3 题 fused 复活） | +0.02 | P1/G2 |
| R12 | RRF 通道权重配置化 | P2 | R9/R10 | +0.00 | +0.02 | P3 |

> D09（j20 隐身+发动机跨源）、D15（supplier-system 检索质量）需查询改写/同义词扩展，超出本轮范围，记待确认。

## 详细任务定义

### R8 — bench 口径修正（排除 weak 题）

- **目标**：`_metrics` 函数对 `fused_recall`/`recall@k`/`mrr` 只对 `gold` 非空的题取均值，让 0.95 数学可达
- **优先级**：P0
- **依赖**：无
- **触及模块**：`scripts/_rag_bench.py`
- **验收标准**：
  1. `overall.fused_recall` 分母为 90（有 gold 题数），非 100
  2. weak 题（C06/C07/C09/C10/C16/E01/E03/E04/E05/E10）不参与 fused_recall/recall@k/mrr 计算
  3. R8 后 fused_recall = 85/90 ≈ 0.9444（R1–R7 后实际值，无代码改动纯口径重算）
  4. `pytest tests/unit` 无新增失败（bench 脚本无对应单测，仅静态检查）
- **预期收益**：口径修正，0.95 可达

### R9 — evidence_policy 门控层对齐

- **目标**：把 R1（aircraft 大小写不敏感）与 R3（component 通用桶豁免）的修复延伸到 evidence_policy 门控层，消除 31 题"gold 进 RRF 但被 target_mismatch 拒绝导致证据包空"
- **优先级**：P0（最高 ROI）
- **依赖**：无（R1/R3 已完成）
- **触及模块**：`src/knowledge/evidence_policy.py`
- **验收标准**：
  1. 31 题（A01–A12、C01/C02/C04/C05/C08/C14/C15/C17/C19、D02–D12、E06/E09）证据包非空，gate 由 weak 升为 confident
  2. Recall@3（新口径）从 ~0.49 提升至 ≥0.65
  3. `pytest tests/unit tests/integration` 全量通过，证据门测试无回归
- **预期收益**：Recall@3 +0.35（31 题证据包复活，多数 gold 直接进 top-3）

### R10 — reranker _scene_match 对齐

- **目标**：把 aircraft 大小写不敏感 + component 通用桶豁免延伸到 reranker 的 `_scene_match`，让 c919 chunk 在 C919 查询下 scene_match 得分正常
- **优先级**：P1
- **依赖**：R9（先解决证据包空，否则无候选可排序）
- **触及模块**：`src/knowledge/reranking.py`
- **验收标准**：
  1. A14/A15/A16/A21/A24/A25/A27/A34/B01/B02/C12/D04/E02/E07 中 gold 进入证据包 top-3 的题数增加
  2. Recall@3（新口径）≥0.70
  3. MRR ≥0.50
  4. `pytest tests/unit tests/integration` 全量通过
- **预期收益**：Recall@3 +0.10，MRR +0.05

### R11 — COMPONENT_ALIASES 精确化

- **目标**：修正"翼/机翼→wing"、"发动机→engine"过粗映射，使 A17/A20/A33 的 gold 不被错误 component 过滤排除
- **优先级**：P1
- **依赖**：无
- **触及模块**：`src/input/query_understanding.py`
- **验收标准**：
  1. A17（涡扇发动机推力）/A20（翼尖小翼）/A33（旋翼升力）fused_recall 由 0 变 1
  2. FusedRecall（新口径）≥0.95（90 题召回 ≥86 题）
  3. `pytest tests/unit tests/integration` 全量通过
- **预期收益**：FusedRecall +0.033（3 题复活）

### R12 — RRF 通道权重配置化

- **目标**：给 RRF 融合加通道权重（dense 权重高于 keyword），配置驱动，优化排序质量
- **优先级**：P2
- **依赖**：R9/R10（先解决证据包空与 scene_match）
- **触及模块**：`configs/rag.yaml`、`src/knowledge/config.py`、`src/knowledge/fusion.py`
- **验收标准**：
  1. RRF 支持通道权重配置（如 `dense: 1.2, keyword: 1.0`）
  2. Recall@3/MRR 不回退，且有提升或持平
  3. `pytest tests/unit tests/integration` 全量通过
- **预期收益**：Recall@3 +0.02（微调，主要验证不回退）

## 执行顺序建议

```
阶段 A（口径 + 门控层修复，逼近 Recall@3 0.70）:
  R8 → R9 → R10      R8 改口径让 0.95 可达；R9 修 31 题证据包空；R10 修排序

阶段 B（检索层补漏 + 排序微调）:
  R11 → R12          R11 修 3 题 fused_recall；R12 排序微调

完成后预期: FusedRecall ≥0.95, Recall@3 ≥0.70, MRR ≥0.50
```

每步完成后须在 `docs/项目总控/STATUS.md` 记录 before/after 指标，并跑 `pytest tests/unit tests/integration` 全量回归。

## 停止条件

1. 同一任务测试连续三次失败且无法定位原因
2. 改动需要触及 `harness.md` 未列出的文件
3. 需要新增大型依赖或修改核心目录结构
4. 需要修改 QueryObject/RetrievalPlan/EvidencePackage 等对外接口契约签名
5. 基准测试中任一维度较 before 回退
6. task.md / spec.md / harness.md 三者出现明显冲突
