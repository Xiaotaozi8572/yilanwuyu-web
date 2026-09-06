# RAG 召回率修复专项 · 约束规范（harness.md）

本文件为 `task.md` 中 R1–R7 任务的约束规范，与 `spec.md` 的执行步骤一一对应。所有任务必须在本约束下执行。

## 1. 全局安全边界

### 1.1 必须保持的接口契约
- `QueryObject`、`RetrievalPlan`、`EvidencePackage`、`RetrievalHit`、`TextChunk`、`EvidenceGate` 等对外契约的签名与必填字段不得修改
- `BaseContract.validate` 的校验语义（`required_fields` 非空校验）不得放宽
- `RetrievalController`、`RetrievalPlanner` 的公共方法签名不得变更
- `SQLiteVectorStore.search`/`upsert` 的公共签名不得变更

### 1.2 禁止项
- 禁止引入新依赖（包括但不限于新的 pip 包、新的 C 扩展）
- 禁止新建文件（除 `task.md`/`spec.md`/`harness.md` 与明确列出的测试文件）
- 禁止修改目录结构
- 禁止硬编码核心业务规则（型号词表、通用桶列表等必须配置化）
- 禁止绕过 `evidence_package` 直接生成航空事实
- 禁止为评测通过修改业务逻辑绕过真实流程（沿用 `harness.md` P8 约束）
- 禁止修改 `data/processed/knowledge.sqlite3` 或 `data/processed/memory.sqlite3`（基准测试只读）

### 1.3 允许的依赖与工具
- 仅使用仓库现有依赖（jieba、sqlite-vec、numpy、torch CPU 等）
- 测试命令仅使用 `pytest` 与 `python scripts/_rag_bench.py`
- Python 环境使用项目既有 venv

## 2. 备份与回滚规则

### 2.1 改动前备份
每个任务开始前，必须为该任务触及的所有文件创建备份副本，存放于 `tmp/召回修复备份/` 目录：

```bash
# 示例：R1 开始前
mkdir -p tmp/召回修复备份/R1
cp src/knowledge/indexes/keyword_index.py tmp/召回修复备份/R1/
cp src/knowledge/indexes/vector_store.py tmp/召回修复备份/R1/
```

### 2.2 回滚条件
出现以下任一情况立即回滚该任务改动，并在 `docs/项目总控/STATUS.md` 记录：
1. 该任务测试连续三次失败且无法定位原因
2. 全量 `pytest tests/unit tests/integration` 出现非该任务预期的失败
3. 基准测试中任一维度 FusedRecall 较 before 回退
4. 改动意外触及本 `harness.md` 未列出的文件

### 2.3 回滚方式
```bash
# 从备份恢复
cp tmp/召回修复备份/<任务ID>/* <原始路径>/
# 或使用 git（仅恢复未提交改动，不触碰已提交历史）
git checkout -- <文件路径>
```

## 3. 任务文件清单与单次提交边界

每个任务的允许修改文件严格限定如下。单次提交（commit）不得超出当前任务定义的边界，不得跨任务合并提交。

### R1 — aircraft 大小写不敏感过滤

**允许修改文件**：
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_store.py`

**禁止触碰**：
- `src/input/query_understanding.py`（AIRCRAFT_ALIASES 输出值不改）
- `configs/**`
- `data/**`
- 任何测试文件（除非既有测试因行为变更需要同步，且改动仅限断言同步）

**单次提交边界**：三处 `_matches_filters` 的 aircraft 分支改动为一个提交。

### R2 — 移除 concept 严格过滤

**允许修改文件**：
- `src/knowledge/retrieval_planner.py`
- `src/knowledge/evidence_policy.py`（证据门 concept 严格相等门控移除；2026-08-11 用户授权范围扩展）
- `tests/integration/rag_pipeline/test_evidence_relevance_gate.py`（`test_unrelated_reviewed_chunk_does_not_become_confident_evidence` 断言 `target_mismatch`→`weak_mock_vector_only` 同步）

**禁止触碰**：
- `src/input/query_understanding.py`（CONCEPT_ALIASES、_detect_concept 不删）
- `src/knowledge/indexes/**`（_matches_filters 的 concept 分支因 filters 不再含 concept 键自动失效，无需改动）
- 其他测试文件

**单次提交边界**：`RetrievalPlanner.plan` filters 删 concept 键 + `evidence_policy.py` concept 门控移除 + 门控测试断言同步为一个提交。

### R3 — component 通用桶豁免

**允许修改文件**：
- `configs/rag.yaml`
- `src/knowledge/config.py`（RagConfig 字段扩展，若需要）
- `src/knowledge/retrieval_planner.py`（filters 注入 `_general_component_buckets`）
- `src/knowledge/retrieval_controller.py`（配置透传，若需要）
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_store.py`
- `tests/unit/knowledge/test_component_general_bucket.py`（新增单测）

**禁止触碰**：
- `src/input/query_understanding.py`（COMPONENT_ALIASES 不改）
- `data/**`

**单次提交边界**：配置 + 三处 _matches_filters component 分支 + 单测为一个提交。

### R4 — 复杂度分类器优先级修正

**允许修改文件**：
- `src/knowledge/retrieval_planner.py`
- `tests/unit/knowledge/`（既有分类器测试同步、新增因果+部件组合用例）

**禁止触碰**：
- `src/input/query_understanding.py`（意图分类逻辑不改）
- `src/knowledge/indexes/**`

**单次提交边界**：`QueryComplexityClassifier.classify` 顺序调整 + 测试同步为一个提交。

### R5 — 空查询输入防御

**允许修改文件**：
- `src/input/query_understanding.py`
- `tests/unit/input/`（新增空查询用例、同步既有异常断言）

**禁止触碰**：
- `src/core/base_contracts.py`（BaseContract.validate 不改）
- `src/input/query_object.py`（required_fields 不改）
- `src/knowledge/**`

**单次提交边界**：`understand_query` 入口防御 + 测试为一个提交。

### R7 — dense ANN 快速路径

**允许修改文件**：
- `src/knowledge/indexes/vector_store.py`
- `tests/unit/knowledge/`（新增 ANN 路径测试）

**禁止触碰**：
- `src/knowledge/retrieval_planner.py`（filters 注入不改）
- `src/knowledge/retrieval_controller.py`
- `configs/**`

**单次提交边界**：`_search_vector` ANN 判定改写 + 单测为一个提交。

### R6 — 域外/安全守卫（独立工作包，本专项不实施）

**本专项不执行**。预期触及文件仅记录，待独立 P5/G4 工作包授权：
- `src/input/query_understanding.py`
- `src/knowledge/evidence_gate.py`
- `src/self_check/**`

## 4. 每步验证检查项

每个任务改完后，必须依次通过以下检查，全部绿后才能进入下一任务：

### 4.1 静态检查
```bash
# 编译检查（无语法错误）
python -c "import ast; ast.parse(open('<修改文件>',encoding='utf-8').read())"
```

### 4.2 单元测试
```bash
pytest tests/unit -x --tb=short
```
要求：全绿，无 xpass（除非已声明的 xfail）。

### 4.3 集成测试
```bash
pytest tests/integration -x --tb=short
```
要求：全绿。

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
- 不得跨任务合并提交（如 R1 与 R2 不得同一 commit）
- 不得借机修复无关问题（如发现无关 bug，记录到 STATUS.md"待确认"，不在此专项修复）

### 5.2 行数控制
- 每个任务的代码改动行数应与 `spec.md` 描述一致
- R1：~25 行（三处 _matches_filters）
- R2：~5 行（filters 删 1 键 + 注释）
- R3：~30 行（配置 + 三处 component 分支 + 注入）
- R4：~5 行（classify 顺序调整）
- R5：~20 行（入口防御）
- R7：~25 行（_search_vector ANN 判定）
- 若实际改动显著超出上述预估，停止并记录"待确认"

### 5.3 测试同步要求
- 修改生产代码导致既有测试断言失效时，必须同步更新断言（不得删除测试）
- 新增功能（如 R3 通用桶豁免、R5 空查询防御）必须新增对应单测
- 测试改动仅限断言同步与新增用例，不得修改测试框架或 fixture 结构

## 6. 停止条件

出现以下任一情况立即停止，在 `docs/项目总控/STATUS.md` 记录"待确认"：
1. 同一任务测试连续三次失败且无法定位原因
2. 改动需要触及本 `harness.md` 第 3 节未列出的文件
3. 需要新增大型依赖或修改核心目录结构
4. 需要修改 QueryObject/RetrievalPlan/EvidencePackage 等对外接口契约签名
5. 基准测试中任一维度 FusedRecall 较 before 回退
6. task.md、spec.md、harness.md 三者出现明显冲突
7. 需要访问密钥、真实生产数据库、真实外部服务
8. 当前实现会破坏已有接口契约

## 7. 文档一致性要求

- `task.md` 中每个任务（R1–R7）必须在 `spec.md` 中有对应执行步骤
- `task.md` 中每个任务必须在 `harness.md` 第 3 节有对应文件清单
- 三份文档的任务编号（R1–R7）必须完全一致
- 任何文档修改须同步更新三份文档以保持引用一致
- 实施过程中若发现 spec.md 步骤与实际代码不符（行号漂移等），以实际代码为准并更新 spec.md

## 8. 与历史 harness 的关系

- 本专项受 `docs/项目总控/harness.md` 的 P1（src/input/**）、P3（src/knowledge/**、configs/rag.yaml）、P8（scripts/**、tests/**）原 harness 约束
- R5 触及 `src/input/query_understanding.py`，同时受 G2 工作包允许清单约束（该文件在 G2 allowlist 内）
- R6 预期触及 `src/self_check/**`，需独立 P5/G4 工作包授权，本专项不实施
- 不覆盖历史 P0–P8、G0–G8 验收记录
- 完成后在 `docs/项目总控/STATUS.md` 新增专项段落，记录指标与文件清单
