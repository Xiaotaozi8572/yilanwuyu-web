# RAG 召回率修复二期 · 约束规范（harness.md）

本文件为 `task.md` 中 R8–R12 任务的约束规范，与 `spec.md` 的执行步骤一一对应。所有任务必须在本约束下执行。

## 1. 全局安全边界

### 1.1 必须保持的接口契约
- `QueryObject`、`RetrievalPlan`、`EvidencePackage`、`FusedHit`、`RankedEvidence`、`RetrievalHit`、`TextChunk`、`EvidenceGate` 等对外契约的签名与必填字段不得修改
- `BaseContract.validate` 的校验语义不得放宽
- `RetrievalController`、`RetrievalPlanner`、`EvidenceEligibilityPolicy`、`FeatureReranker`、`reciprocal_rank_fusion` 的公共方法签名变更仅限新增带默认值的可选参数（不破坏既有调用）
- `EvidenceEligibilityPolicy.__init__` 与 `FeatureReranker.__init__` 新增参数必须带默认值，确保既有调用不破坏

### 1.2 禁止项
- 禁止引入新依赖（包括但不限于新的 pip 包、新的 C 扩展）
- 禁止新建文件（除 `task.md`/`spec.md`/`harness.md` 与明确列出的测试文件）
- 禁止修改目录结构
- 禁止硬编码核心业务规则（型号词表、通用桶列表、通道权重必须配置化）
- 禁止绕过 `evidence_package` 直接生成航空事实
- 禁止为评测通过修改业务逻辑绕过真实流程（沿用 `harness.md` P8 约束）
- 禁止修改 `data/processed/knowledge.sqlite3` 或 `data/processed/memory.sqlite3`（基准测试只读）
- 禁止修改 `scripts/_rag_bench.py` 的 BANK 常量（题库不动）
- 禁止修改 `scripts/_rag_bench.py` 的 `run_one` 函数中 `fused_recall`/`recall_at` 的单题计算定义（只改 `_metrics` 的聚合口径）

### 1.3 允许的依赖与工具
- 仅使用仓库现有依赖（jieba、sqlite-vec、numpy、torch CPU、sentence-transformers 等）
- 测试命令仅使用 `pytest` 与 `python scripts/_rag_bench.py`
- Python 环境使用项目既有 venv

## 2. 备份与回滚规则

### 2.1 改动前备份
每个任务开始前，必须为该任务触及的所有文件创建备份副本，存放于 `tmp/召回修复二期备份/` 目录：

```bash
# 示例：R9 开始前
mkdir -p tmp/召回修复二期备份/R9
cp src/knowledge/evidence_policy.py tmp/召回修复二期备份/R9/
cp src/knowledge/retrieval_controller.py tmp/召回修复二期备份/R9/
```

### 2.2 回滚条件
出现以下任一情况立即回滚该任务改动，并在 `docs/项目总控/STATUS.md` 记录：
1. 该任务测试连续三次失败且无法定位原因
2. 全量 `pytest tests/unit tests/integration` 出现非该任务预期的失败（不含 STATUS.md 已记录的预存在失败）
3. 基准测试中任一维度（FusedRecall/Recall@3/MRR）较 before 回退
4. 改动意外触及本 `harness.md` 未列出的文件

### 2.3 回滚方式
```bash
# 从备份恢复
cp tmp/召回修复二期备份/<任务ID>/* <原始路径>/
# 或使用 git（仅恢复未提交改动，不触碰已提交历史）
git checkout -- <文件路径>
```

## 3. 任务文件清单与单次提交边界

每个任务的允许修改文件严格限定如下。单次提交（commit）不得超出当前任务定义的边界，不得跨任务合并提交。

### R8 — bench 口径修正（排除 weak 题）

**允许修改文件**：
- `scripts/_rag_bench.py`（仅 `_metrics` 函数的聚合逻辑）

**禁止触碰**：
- `scripts/_rag_bench.py` 的 BANK 常量、`run_one` 函数、`_controller_trace`/`_gate_trace` 等其他函数
- `src/**`（任何生产代码）
- `tests/**`（bench 脚本无对应单测，仅静态检查）
- `configs/**`

**单次提交边界**：`_metrics` 函数改动为一个提交。

### R9 — evidence_policy 门控层对齐

**允许修改文件**：
- `src/knowledge/evidence_policy.py`（`__init__` 加参数 + `_evaluate` 的 aircraft/component 分支）
- `src/knowledge/retrieval_controller.py`（构造 `EvidenceEligibilityPolicy` 处注入 `general_component_buckets`）
- `tests/integration/rag_pipeline/test_evidence_relevance_gate.py`（断言同步，R2 已授权该文件）

**禁止触碰**：
- `src/knowledge/indexes/**`（检索层已由 R1/R3 修复，不动）
- `src/knowledge/retrieval_planner.py`（filters 注入不动）
- `src/knowledge/reranking.py`（R10 范围）
- `configs/**`
- `data/**`

**单次提交边界**：evidence_policy + retrieval_controller 注入 + 测试同步为一个提交。

### R10 — reranker _scene_match 对齐

**允许修改文件**：
- `src/knowledge/reranking.py`（`FeatureReranker.__init__` 加参数 + `_scene_match` 改写）
- `src/knowledge/retrieval_controller.py`（构造 `FeatureReranker` 处注入 `general_component_buckets`）
- `tests/unit/knowledge/test_reranker_scene_match.py`（新增单测）

**禁止触碰**：
- `src/knowledge/evidence_policy.py`（R9 范围）
- `src/knowledge/indexes/**`
- `src/knowledge/fusion.py`（R12 范围）
- `configs/**`

**单次提交边界**：reranking + retrieval_controller 注入 + 单测为一个提交。

### R11 — COMPONENT_ALIASES 精确化

**允许修改文件**：
- `src/input/query_understanding.py`（`COMPONENT_ALIASES` 常量 + `_detect_component`）
- `tests/unit/input/`（新增别名测试）

**禁止触碰**：
- `src/knowledge/**`（检索/门控/排序层不动）
- `configs/**`
- `data/**`

**单次提交边界**：COMPONENT_ALIASES 修正 + 单测为一个提交。

### R12 — RRF 通道权重配置化

**允许修改文件**：
- `configs/rag.yaml`（`retrieval.channel_weights` 新增）
- `src/knowledge/config.py`（`RetrievalConfig.channel_weights` 字段 + 解析）
- `src/knowledge/fusion.py`（`reciprocal_rank_fusion` 加 `channel_weights` 可选参数）
- `src/knowledge/retrieval_controller.py`（调用处传参）
- `tests/unit/knowledge/test_fusion.py`（新增/更新 RRF 测试）

**禁止触碰**：
- `src/knowledge/evidence_policy.py`
- `src/knowledge/reranking.py`
- `src/knowledge/indexes/**`
- `src/input/**`

**单次提交边界**：配置 + config + fusion + controller + 单测为一个提交。

## 4. 每步验证检查项

每个任务改完后，必须依次通过以下检查，全部绿后才能进入下一任务：

### 4.1 静态检查
```bash
python -c "import ast; ast.parse(open('<修改文件>',encoding='utf-8').read())"
```

### 4.2 单元测试
```bash
pytest tests/unit -x --tb=short
```
要求：全绿，无新增失败（不含 STATUS.md 已记录的 7 个预存在失败）。

### 4.3 集成测试
```bash
pytest tests/integration -x --tb=short
```
要求：无新增失败（不含 STATUS.md 已记录的 12 个预存在失败）。

### 4.4 任务级基准验证
按 `spec.md` 中该任务的验证命令执行，对照 `task.md` 中该任务的验收标准逐项核对。

### 4.5 全量回归（每个任务完成前）
```bash
pytest tests/unit tests/integration
```
要求：无新增失败。

## 5. 改动范围限制

### 5.1 单次提交边界
- 单次 git commit 不得超出当前任务定义的文件清单
- 不得跨任务合并提交（如 R9 与 R10 不得同一 commit）
- 不得借机修复无关问题（如发现无关 bug，记录到 STATUS.md"待确认"，不在此专项修复）

### 5.2 行数控制
- R8：~20 行（_metrics 函数）
- R9：~25 行（evidence_policy __init__ + _evaluate + controller 注入）
- R10：~25 行（reranking __init__ + _scene_match + controller 注入 + 单测）
- R11：~15 行（COMPONENT_ALIASES + _detect_component）
- R12：~30 行（configs + config + fusion + controller + 单测）
- 若实际改动显著超出上述预估，停止并记录"待确认"

### 5.3 测试同步要求
- 修改生产代码导致既有测试断言失效时，必须同步更新断言（不得删除测试）
- 新增功能（如 R9 通用桶豁免、R10 _scene_match、R12 通道权重）必须新增对应单测
- 测试改动仅限断言同步与新增用例，不得修改测试框架或 fixture 结构

## 6. 停止条件

出现以下任一情况立即停止，在 `docs/项目总控/STATUS.md` 记录"待确认"：
1. 同一任务测试连续三次失败且无法定位原因
2. 改动需要触及本 `harness.md` 第 3 节未列出的文件
3. 需要新增大型依赖或修改核心目录结构
4. 需要修改 QueryObject/RetrievalPlan/EvidencePackage/FusedHit/RankedEvidence 等对外接口契约签名
5. 基准测试中任一维度（FusedRecall/Recall@3/MRR）较 before 回退
6. task.md / spec.md / harness.md 三者出现明显冲突
7. 需要访问密钥、真实生产数据库、真实外部服务
8. 当前实现会破坏已有接口契约
9. `EvidenceEligibilityPolicy.__init__` 或 `FeatureReranker.__init__` 或 `reciprocal_rank_fusion` 的新增参数无法带默认值（会破坏既有调用）

## 7. 文档一致性要求

- `task.md` 中每个任务（R8–R12）必须在 `spec.md` 中有对应执行步骤
- `task.md` 中每个任务必须在 `harness.md` 第 3 节有对应文件清单
- 三份文档的任务编号（R8–R12）必须完全一致
- 任何文档修改须同步更新三份文档以保持引用一致
- 实施过程中若发现 spec.md 步骤与实际代码不符（行号漂移等），以实际代码为准并更新 spec.md

## 8. 与历史 harness 的关系

- 本专项受 `docs/项目总控/harness.md` 的 P1（src/input/**）、P3（src/knowledge/**、configs/rag.yaml）、P8（scripts/**、tests/**）原 harness 约束
- R8 触及 `scripts/_rag_bench.py`，受 P8 约束；只改 `_metrics` 聚合口径，不改 BANK/`run_one` 单题定义
- R11 触及 `src/input/query_understanding.py`，同时受 G2 工作包允许清单约束（该文件在 G2 allowlist 内）
- R9/R10 触及 `src/knowledge/evidence_policy.py` 与 `src/knowledge/reranking.py`，受 P3 约束；`EvidenceEligibilityPolicy.__init__` 与 `FeatureReranker.__init__` 新增参数必须带默认值
- R9 同步 `tests/integration/rag_pipeline/test_evidence_relevance_gate.py` 沿用 R2 已授权范围
- 不覆盖历史 P0–P8、G0–G8、R1–R7 验收记录
- 完成后在 `docs/项目总控/STATUS.md` 新增二期段落，记录指标与文件清单

## 9. 指标口径说明（R8 引入）

- R8 前口径：`fused_recall` = 100 题均值（含 10 题 weak 无 gold，恒 0 拉低均值，数学上限 0.90）
- R8 后口径：`fused_recall`/`recall@k`/`mrr`/`precision@5` 只对 `gold` 非空的题（90 题）取均值；weak 题清单实测为 C06/C07/C09/C10/C16/E01/E03/E04/E05/E10（10 题，含初稿漏记的 C09 空客 A380）
- 口径变更仅影响 `_metrics` 聚合，不影响 `run_one` 单题定义（`fused_recall` 单题仍为 `1.0 if (gold and gold & fused_sources) else 0.0`）
- 本口径变更经用户确认（"改口径排除 weak 题"），非为评测通过而作弊
