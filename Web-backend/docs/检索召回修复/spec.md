# RAG 召回率修复专项 · 执行步骤（spec.md）

本文件为 `task.md` 中 R1–R7 每个任务的详细执行步骤。改动范围精确到函数与行号，所有行号依据仓库当前 HEAD（已实际核对，非引用报告）。

## 通用约定

- 改动前先按 `harness.md` 中对应任务的备份规则保留原始代码
- 每步改完后立即跑该任务的验证命令，再进入下一任务
- 不修改 `QueryObject`、`RetrievalPlan`、`EvidencePackage`、`RetrievalHit`、`TextChunk` 等对外契约的签名与必填字段
- 不引入新依赖，不新建文件，不改目录结构
- 代码注释使用中文，与既有文件风格一致

---

## R1 — aircraft 大小写不敏感过滤

### 触及文件
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_store.py`

### 改动范围
三处 `_matches_filters` 的 aircraft 分支：
1. `keyword_index.py:89-100` 模块级函数 `_matches_filters`
2. `vector_store.py:688-699` `SQLiteVectorStore._matches_filters` 静态方法
3. `vector_store.py:56-63` `InMemoryVectorStore._matches_filters` 方法（当前仅判 aircraft/component，需同步统一）

### 改动步骤

**步骤 1**：修改 `src/knowledge/indexes/keyword_index.py` 的 `_matches_filters`

将 aircraft 分支从严格相等改为大小写不敏感比较，且对 `actual is None` 显式拒绝（避免未标注 aircraft 的 chunk 误匹配）：

```python
def _matches_filters(chunk: TextChunk, filters: dict[str, Any]) -> bool:
    for field_name in ("aircraft", "component", "concept"):
        expected = filters.get(field_name)
        if expected is None or (isinstance(expected, str) and not expected.strip()):
            continue
        actual = getattr(chunk, field_name)
        if actual is None:
            # chunk 未标注该字段，不与具体过滤值匹配
            return False
        if field_name == "aircraft":
            # 入库 aircraft 为小写（c919/j20/y20/z20），查询层可能产出
            # 规范大小写（C919/AG600）。统一小写比较，避免双通道归零。
            if actual.lower() != str(expected).lower():
                return False
        else:
            if actual != expected:
                return False
    allowed_statuses = filters.get("allowed_review_status")
    if allowed_statuses and chunk.review_status.value not in allowed_statuses:
        return False
    return True
```

**步骤 2**：修改 `src/knowledge/indexes/vector_store.py` 的 `SQLiteVectorStore._matches_filters`（行 688-699）为与步骤 1 相同的实现。

**步骤 3**：修改 `src/knowledge/indexes/vector_store.py` 的 `InMemoryVectorStore._matches_filters`（行 56-63），把现有 `if aircraft and chunk.aircraft and chunk.aircraft != aircraft` 改为大小写不敏感且无值时拒绝：

```python
    def _matches_filters(self, chunk: TextChunk, filters: dict[str, Any]) -> bool:
        for field_name in ("aircraft", "component"):
            expected = filters.get(field_name)
            if expected is None or (isinstance(expected, str) and not expected.strip()):
                continue
            actual = getattr(chunk, field_name)
            if actual is None:
                return False
            if field_name == "aircraft":
                if actual.lower() != str(expected).lower():
                    return False
            else:
                if actual != expected:
                    return False
        return True
```

### 预期行为
- `aircraft="C919"` 过滤下，DB 中 `aircraft="c919"` 的 chunk 通过过滤
- `aircraft="c919"` 与 `aircraft="C919"` 等价
- `aircraft=None`（未标注）的 chunk 在有 aircraft 过滤时被拒绝（避免误匹配）
- 不影响 `aircraft=None` 查询（无过滤路径不变）

### 验证方式
```bash
# 1. 单元测试全量回归
pytest tests/unit tests/integration

# 2. 跑含型号的 A/C/D 子集
python scripts/_rag_bench.py --only A
python scripts/_rag_bench.py --only C
python scripts/_rag_bench.py --only D

# 3. 检查指标
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); print('blocked_ids:', d['aircraft_case_blocked_ids']); print('filter_loss:', d['filter_attributable_recall_loss']['n']); print('fused_recall:', d['overall']['fused_recall'])"
```
预期：`aircraft_case_blocked_ids` 为空，`filter_attributable_recall_loss.n` ≤14，`fused_recall` ≥0.70。

---

## R2 — 移除 concept 严格过滤

### 触及文件
- `src/knowledge/retrieval_planner.py`
- `src/knowledge/evidence_policy.py`（证据门 concept 严格相等门控同步移除，用户 2026-08-11 授权范围扩展）
- `tests/integration/rag_pipeline/test_evidence_relevance_gate.py`（断言同步，用户 2026-08-11 授权）

### 改动范围
`RetrievalPlanner.plan`（行 96-152）的 `filters` 字典（行 140-148）；`EvidenceEligibilityPolicy._evaluate`（行 69-89）的 concept 目标过滤分支。

### 改动步骤

**步骤 1**：修改 `src/knowledge/retrieval_planner.py` 的 `RetrievalPlanner.plan` 返回的 `filters` 字典，删除 `"concept": query_object.target_concept` 键：

```python
            filters={
                "aircraft": query_object.target_aircraft
                    or (scene_state.aircraft_id if scene_state else None),
                "component": query_object.target_component
                    or (scene_state.component_id if scene_state else None),
                # concept 过滤移除：库内 concept 为英文 snake_case
                # (supercritical_airfoil/engine_bypass_ratio/...)，而
                # CONCEPT_ALIASES 产出中文同词；严格相等过滤命中恒为 0，
                # 反而把相关 principle 块全部排除（见 rag_100q_bench_report §4.2）。
                # target_concept 仍随 QueryObject 下传，仅不再作为检索硬过滤。
                "intent_type": query_object.intent_type,
                "allowed_review_status": list(self.config.gate.allowed_core_review_status),
            },
```

**步骤 2**：保留 `src/input/query_understanding.py` 的 `CONCEPT_ALIASES` 与 `_detect_concept` 不动（`target_concept` 仍用于 comparison_subjects 指代消解、下游展示与记忆系统）。

**步骤 3**：检查 `_matches_filters`（R1 已改）的 concept 分支：因 `filters` 不再含 concept 键，`filters.get("concept")` 返回 None → `continue`，分支自动失效，无需额外改动。

**步骤 4**（用户授权范围扩展）：修改 `src/knowledge/evidence_policy.py` 的 `EvidenceEligibilityPolicy._evaluate`，移除 `target_concept` 读取与 concept 级 `target_mismatch` 拒绝、`concept_match` 资格理由：

```python
        filters = retrieval_plan.filters
        target_component = filters.get("component")
        target_aircraft = filters.get("aircraft")

        if target_aircraft and candidate.chunk.aircraft != target_aircraft:
            candidate.rejection_reasons.append("target_mismatch")
            return
        if target_component and candidate.chunk.component != target_component:
            candidate.rejection_reasons.append("target_mismatch")
            return

        # concept 目标过滤移除：库内 concept 为英文 snake_case
        # (supercritical_airfoil/engine_bypass_ratio/...)，查询侧 CONCEPT_ALIASES
        # 产出中文同词，严格相等在检索层与证据门恒不命中、属纯负向过滤。
        # 概念级相关性改由下方 lexical_overlap / semantic_score 门控承担。
        if target_aircraft and candidate.chunk.aircraft == target_aircraft:
            candidate.qualification_reasons.append("aircraft_match")
        if target_component and candidate.chunk.component == target_component:
            candidate.qualification_reasons.append("component_match")
```

> 若不移除，`plan.filters` 不再含 concept 键后该分支因 `target_concept=None` 自然失效，
> 但显式移除可保持代码可审计，并让既有门控测试的拒绝理由断言同步可预测。
> 注意：仅凭「plan.filters 删 concept 键」会让
> `test_unrelated_reviewed_chunk_does_not_become_confident_evidence` 断言
> `target_mismatch` 失败——无关块改以 `weak_mock_vector_only` 被拒，见步骤 5。

**步骤 5**：同步 `tests/integration/rag_pipeline/test_evidence_relevance_gate.py` 的
`test_unrelated_reviewed_chunk_does_not_become_confident_evidence`：将
`assert any("target_mismatch" in item["rejection_reasons"] ...)` 改为断言
`weak_mock_vector_only`（该 mock fixture 下确定性拒绝理由），并加注释说明
concept 严格相等门控已移除、语义相关性由词项/语义阈值承担。

### 预期行为
- `plan.filters` 不再含 `concept` 键
- 含 concept 别名（阻力/涵道比/升力/失速）的查询不再被 concept 过滤排除
- 证据门不再以 concept 严格相等拒绝/资格化（aircraft/component 的 target_mismatch 保留）
- `target_concept` 仍出现在 QueryObject，供下游使用
- 门控测试同步后保持 weak 拒绝语义

### 验证方式
```bash
pytest tests/unit tests/integration

python scripts/_rag_bench.py --only A   # 验证 A21/A24/A34/A35 复活
python scripts/_rag_bench.py --only D   # 验证 D01/D04/D14
python scripts/_rag_bench.py --only E   # 验证 E02/E08

python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); ids=['A21','A24','A34','A35','D01','D04','D14','E02','E08']; cases={c['id']:c for c in d['cases']}; [print(i, 'filters=', cases[i]['filters'], 'fr=', cases[i]['fused_recall']) for i in ids if i in cases]"
```
预期：上述题 `fused_recall` 由 0.0 变为 1.0；`filters` 不含 `concept` 键。

---

## R3 — component 通用桶豁免

### 触及文件
- `configs/rag.yaml`
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_store.py`
- `src/knowledge/retrieval_controller.py`（配置注入）
- `src/knowledge/config.py`（RagConfig 字段，若配置对象需要新字段承载）

### 改动范围
1. `configs/rag.yaml` `retrieval:` 段新增 `general_component_buckets`
2. `RagConfig` 读取该配置（若 RagConfig 已有 retrieval 子对象，加字段；否则通过 controller 注入 filters 的 `_general_component_buckets`）
3. 三处 `_matches_filters` 的 component 分支增加通用桶豁免逻辑

### 改动步骤

**步骤 1**：在 `configs/rag.yaml` 的 `retrieval:` 段新增配置：

```yaml
retrieval:
  # ...既有配置...
  # 通用 component 桶：承载跨部件原理/历史/对比内容，不参与具体 component 严格过滤
  general_component_buckets: [principle, history, overall, comparison, application]
```

**步骤 2**：在 `src/knowledge/retrieval_controller.py` 装配 plan 时，把通用桶列表注入 filters（具体注入点为 plan 装配或 `_execute_channel` 调用 search 前的 filter 透传处）。若 RetrievalPlanner 已能访问 `self.config.retrieval`，则在 `RetrievalPlanner.plan` 的 filters 字典中直接加入：

```python
            filters={
                "aircraft": ...,
                "component": ...,
                "intent_type": query_object.intent_type,
                "allowed_review_status": list(self.config.gate.allowed_core_review_status),
                "_general_component_buckets": tuple(
                    self.config.retrieval.general_component_buckets
                ),
            },
```

> 若 `RagConfig.retrieval` 无 `general_component_buckets` 属性，需在 `src/knowledge/config.py` 的 retrieval 配置 dataclass 中新增该字段（带默认值 `()`），并在 `configs/rag.yaml` 加载处读取。此改动属 P3 允许范围。

**步骤 3**：修改 `src/knowledge/indexes/keyword_index.py` 的 `_matches_filters`，component 分支增加通用桶豁免（在 R1 已改的基础上）：

```python
        if field_name == "component":
            expected_component = filters.get("component")
            if not expected_component:
                continue
            if actual is None:
                return False
            if actual == expected_component:
                continue
            # 通用桶（principle/history/overall/comparison/application）承载
            # 跨部件原理内容，不被具体 component 过滤排除——否则"超临界翼型"
            # 等原理题的 principle 答案块会被 wing 过滤整体剔除。
            general_buckets = filters.get("_general_component_buckets") or ()
            if actual in general_buckets:
                continue
            return False
```

**步骤 4**：`src/knowledge/indexes/vector_store.py` 的 `SQLiteVectorStore._matches_filters`（行 688-699）与 `InMemoryVectorStore._matches_filters`（行 56-63）同步加入相同的 component 通用桶豁免逻辑。

**步骤 5**：新增单测 `tests/unit/knowledge/test_component_general_bucket.py`（或在既有检索测试文件中追加），验证：`component=wing` 过滤下，`component=principle` 的 chunk 仍通过；`component=engine`（DB 无此桶）时 `component=powerplant` 的 chunk 被拒绝。

### 预期行为
- `component=wing` 查询能召回 `component=principle/history/overall/comparison/application` 的 chunk
- `component=wing` 查询仍只命中 `wing` 与通用桶，不命中 `landing_gear` 等其他具体桶
- 通用桶列表配置化，不硬编码在 `_matches_filters` 内

### 验证方式
```bash
pytest tests/unit tests/integration

python scripts/_rag_bench.py --only A   # 验证 A17/A20/A21/A24/A25 复活
python scripts/_rag_bench.py --only B   # 验证 B01/B02 复活
python scripts/_rag_bench.py --only E   # 验证 E07

python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); print('fused_recall:', d['overall']['fused_recall'])"
```
预期：`fused_recall` ≥0.85；A17/A20/A25/B01/B02/E07 `fused_recall` 由 0.0 变为 1.0。

---

## R4 — 复杂度分类器优先级修正

### 触及文件
- `src/knowledge/retrieval_planner.py`
- `tests/unit/knowledge/`（新增/修改分类器测试）

### 改动范围
`QueryComplexityClassifier.classify`（行 66-78）的判定顺序。

### 改动步骤

**步骤 1**：修改 `src/knowledge/retrieval_planner.py` 的 `QueryComplexityClassifier.classify`，把因果标记检查（`_CAUSAL_MARKERS`）提到 `component_scene` 之前：

```python
    def classify(self, query_object: QueryObject) -> QueryComplexity:
        text = query_object.normalized_query or query_object.raw_query
        intent = query_object.intent_type

        if query_object.target_aircraft and self._contains(text, _APPLICATION_MARKERS):
            return QueryComplexity.L5
        if intent == "comparison" or self._contains(text, _COMPARISON_MARKERS):
            return QueryComplexity.L4
        # 因果/原理问题优先于 component_scene：避免"为什么…翼型…"
        # 因含"翼"被误判为部件场景而走空 scene 通道，错失 dense+parent。
        if self._contains(text, _CAUSAL_MARKERS):
            return QueryComplexity.L3
        if intent == "component_scene" or self._contains(text, _SCENE_MARKERS):
            return QueryComplexity.L2
        return QueryComplexity.L1
```

**步骤 2**：检查既有测试是否有断言 D04/D14 复杂度=L2 的用例，若有则同步改为 L3（grep `L2`、`component_scene` 在 `tests/unit/knowledge/` 与 `tests/integration/rag_pipeline/`）。

**步骤 3**：新增单测：因果+部件组合查询（如"为什么超临界翼型能降低巡航阻力"）应分类为 L3，通道为 dense+parent。

### 预期行为
- D04、D14 复杂度 L2→L3，通道 scene+keyword→dense+parent
- 纯部件场景题（无因果标记）仍为 L2
- 对比题仍为 L4

### 验证方式
```bash
pytest tests/unit tests/integration

python scripts/_rag_bench.py --only D
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); cases={c['id']:c for c in d['cases']}; [print(i, 'complexity=', cases[i]['complexity'], 'channels=', cases[i]['channels'], 'recall@3=', cases[i]['recall@3']) for i in ['D04','D14'] if i in cases]"
```
预期：D04/D14 `complexity=L3`，`channels=['dense','parent']`，`recall@3` 不回退。

---

## R5 — 空查询输入防御

### 触及文件
- `src/input/query_understanding.py`
- `tests/unit/input/`（新增空查询用例）

### 改动范围
`QueryUnderstandingService.understand_query`（行 50-116）入口处。

### 改动步骤

**步骤 1**：修改 `src/input/query_understanding.py` 的 `understand_query` 方法，在 `normalized = " ".join(raw_query.strip().split())` 之后、原逻辑之前，加入输入防御：

```python
    def understand_query(
        self,
        raw_query: str,
        scene_state: SceneState | None = None,
        dialogue_context: list[str] | None = None,
    ) -> QueryObject:
        stripped = (raw_query or "").strip()
        normalized = " ".join(stripped.split())
        # 输入防御：空查询或纯标点/符号——不抛异常，返回需澄清的 QueryObject。
        # raw_query/normalized_query 均必填非空（BaseContract.required_fields），
        # 故用占位串通过校验，真实空意图通过 needs_clarification 表达。
        is_empty_input = not normalized or not any(
            ch.isalnum() for ch in normalized
        )
        if is_empty_input:
            return QueryObject(
                raw_query=normalized or "(empty)",
                normalized_query=normalized or "(empty)",
                intent_type="concept_explanation",
                target_aircraft=None,
                target_component=None,
                target_concept=None,
                safety_flags=[],
                feedback_intent=None,
                needs_clarification=True,
                clarification_reason="empty_or_unintelligible_input",
                intent_signals=IntentSignals(),
                metadata={"dialogue_turns": len(dialogue_context or []),
                          "empty_input": True},
            )
        # ...原逻辑保持不变（feedback_intent 之后的代码）
```

> 注：spec 原稿仅对 `raw_query` 用占位串，但 `normalized_query` 同为
> `required_fields`，空串 `""` 会触发 `BaseContract.validate` 拒绝
> （见 `src/core/base_contracts.py:26-30`），故 `normalized_query` 也需占位串。

**步骤 2**：检查既有测试是否有"空查询应抛 ContractValidationError"的断言（grep `ContractValidationError` 在 `tests/unit/input/`），若有则改为断言 `needs_clarification is True`。

**步骤 3**：新增单测：
- `understand_query("")` → `needs_clarification=True`，`clarification_reason="empty_or_unintelligible_input"`
- `understand_query("？？？")` → `needs_clarification=True`
- `understand_query("什么是？？")` → 正常处理（含字母数字，不走空输入分支）

### 预期行为
- 空查询不再抛异常
- E04 在 bench 中不再记为 error，`needs_clarification=True`
- 含字母数字的正常查询不受影响

### 验证方式
```bash
pytest tests/unit tests/integration

python -c "from input.query_understanding import understand_query; q=understand_query(''); print('needs_clarification=', q.needs_clarification, 'reason=', q.clarification_reason)"
python -c "from input.query_understanding import understand_query; q=understand_query('？？？'); print('needs_clarification=', q.needs_clarification)"

python scripts/_rag_bench.py --only E
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); cases={c['id']:c for c in d['cases']}; print('E04:', cases.get('E04',{}).get('error','none'), 'needs_clarification=', cases.get('E04',{}).get('needs_clarification'))"
```
预期：E04 无 error，`error_count` 由 1 降为 0。

---

## R7 — dense ANN 快速路径

### 触及文件
- `src/knowledge/indexes/vector_store.py`
- `tests/unit/knowledge/`（新增 ANN 路径测试）

### 改动范围
`SQLiteVectorStore._search_vector`（行 371-451）的 ANN 判定与后置过滤逻辑。

### 改动步骤

**步骤 1**：修改 `src/knowledge/indexes/vector_store.py` 的 `_search_vector`，把 ANN 判定从"filters 是否非空"改为"是否存在结构性过滤（aircraft/component/concept）"：

```python
    _STRUCTURAL_FILTER_KEYS = ("aircraft", "component", "concept")

    def _search_vector(
        self,
        query_embedding: list[float],
        *,
        top_k: int,
        filters: dict[str, Any] | None,
        query_variant: str,
        is_mock: bool,
    ) -> list[RetrievalHit]:
        if top_k <= 0:
            return []
        if self.embedding_dimension is None:
            return []
        if len(query_embedding) != self.embedding_dimension:
            raise ContractValidationError(
                "query_embedding.dimension",
                f"expected {self.embedding_dimension}, received {len(query_embedding)}",
            )

        # ANN 路径：无结构性过滤（aircraft/component/concept）时可用。
        # allowed_review_status 与 intent_type 是元数据/软约束，通过 ANN
        # 后置过滤处理，不再阻断快速路径。
        filters = filters or {}
        has_structural_filter = any(
            filters.get(k) for k in self._STRUCTURAL_FILTER_KEYS
        )
        if self._ann_available and not has_structural_filter:
            try:
                ann_hits = self._ann_search(
                    query_embedding,
                    top_k=max(top_k * 3, top_k),  # 多取再后置过滤
                    query_variant=query_variant,
                    is_mock=is_mock,
                )
                # 后置过滤 review_status（reviewed 才是核心证据）
                allowed_statuses = filters.get("allowed_review_status")
                if allowed_statuses:
                    ann_hits = [
                        h for h in ann_hits
                        if h.metadata.get("review_status") in allowed_statuses
                    ]
                ann_hits = ann_hits[:top_k]
                if ann_hits:
                    return ann_hits
            except Exception:
                pass  # 回退暴力路径

        # 暴力路径（结构性过滤存在时）：保持原逻辑
        rows = self.connection.execute(
            """ ... """  # 既有 SQL 不变
        ).fetchall()
        # ...后续打分、排序逻辑不变
```

**步骤 2**：确认 `_ann_search`（行 453-509）已对 ANN 命中做 `review_status == REVIEWED` 校验（行 484-485 已有），后置 `allowed_review_status` 过滤在 ANN 结果上叠加。

**步骤 3**：新增单测：
- 无结构性过滤时走 ANN 路径（mock `allowed_review_status` 存在）
- 有 aircraft 过滤时走暴力路径
- ANN 返回不足 top_k 时回退暴力路径

### 预期行为
- 无 aircraft/component/concept 过滤的查询走 ANN
- 含结构性过滤的查询仍走暴力路径（结果等价）
- dense 通道延迟显著下降

### 验证方式
```bash
pytest tests/unit tests/integration

python scripts/_rag_bench.py --only A   # 对比 dense 通道延迟
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); print('dense:', d['channel_latency'].get('dense')); print('overall p95:', d['overall']['latency_ms_p95'])"
```
预期：dense `mean_ms` ≤500，整体 `latency_ms_p95` ≤1500；FusedRecall/Recall@3 不回退。

---

## R6 — 域外/安全守卫（独立工作包，本专项不实施）

### 触及文件（预期，需独立授权）
- `src/input/query_understanding.py`
- `src/knowledge/evidence_gate.py`
- `src/self_check/**`

### 改动步骤（仅记录待办，不在本专项执行）

**步骤 1**：在 `_detect_aircraft` 或独立 domain guard 中，对未在知识库 `aircraft` 词表（c919/j20/y20/z20）内的型号（波音737/747、空客A380/A320、长征五号、运-8、伊尔-76、UH-60）标记 `out_of_domain=True`。需区分"对比主体"（D02/D06/D13 中 A320/伊尔-76/UH-60 是正向证据来源）与"被问主体"。

**步骤 2**：evidence_gate 对 `out_of_domain=True` 的被问主体强制 weak+missing。

**步骤 3**：`intent=operation_safety` 时 evidence_gate 强制 weak，不判 confident（交 P5 self_check 工作包）。

### 验证方式（待独立工作包授权后执行）
```bash
python scripts/_rag_bench.py --only C   # 验证 C16 gate confident→weak
python scripts/_rag_bench.py --only E   # 验证 E01 gate confident→weak
# 确认 D02/D06/D13 对比题不受影响
```

---

## 全量验收（所有任务完成后）

```bash
# 1. 全量单元+集成测试
pytest tests/unit tests/integration

# 2. 完整 100 题基准测试
python scripts/_rag_bench.py

# 3. 指标对照
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); o=d['overall']; print('FusedRecall=', o['fused_recall'], 'Recall@3=', o['recall@3'], 'MRR=', o['mrr'], 'filter_loss=', d['filter_attributable_recall_loss']['n'], 'case_blocked=', len(d['aircraft_case_blocked_ids']), 'errors=', o['error_count'])"
```

达标线：FusedRecall ≥0.85，Recall@3 ≥0.65，MRR ≥0.50，filter_loss ≤5，case_blocked=0，error_count=0。

完成后在 `docs/项目总控/STATUS.md` 记录最终指标与修改文件清单。
