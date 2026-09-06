# RAG 召回率修复二期 · 执行步骤（spec.md）

本文件为 `task.md` 中 R8–R12 每个任务的详细执行步骤。改动范围精确到函数与行号，所有行号依据仓库当前 HEAD（R1–R7 后状态，已实际核对）。

## 通用约定

- 改动前先按 `harness.md` 中对应任务的备份规则保留原始代码
- 每步改完后立即跑该任务的验证命令，再进入下一任务
- 不修改 `QueryObject`、`RetrievalPlan`、`EvidencePackage`、`FusedHit`、`RankedEvidence` 等对外契约的签名与必填字段
- 不引入新依赖，不新建文件（除明确列出的测试文件），不改目录结构
- 代码注释使用中文，与既有文件风格一致

---

## R8 — bench 口径修正（排除 weak 题）

### 触及文件
- `scripts/_rag_bench.py`

### 改动范围
`_metrics` 函数（行 278-307）的 `avg` 计算逻辑。

### 改动步骤

**步骤 1**：修改 `scripts/_rag_bench.py` 的 `_metrics` 函数，让 `fused_recall`/`recall@k`/`mrr`/`precision@5` 只对 `gold` 非空的题取均值：

```python
def _metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    errs = [r for r in rows if "error" in r]
    rows = [r for r in rows if "error" not in r]
    if not rows:
        return {"n": len(rows), "error_count": len(errs)}
    n = len(rows)
    # weak 题（gold 为空）不参与 fused_recall/recall@k/mrr 计算：
    # 它们无黄金来源，fused_recall 恒 0 会拉低均值且数学上限被锁死在 0.91。
    # weak 题的鲁棒性由 error_count/gate_dist 等指标承载。
    gold_rows = [r for r in rows if r.get("gold")]
    weak_count = n - len(gold_rows)
    def avg(field: str, subset: list[dict[str, Any]] | None = None) -> float:
        pool = subset if subset is not None else rows
        vals = [r[field] for r in pool
                if r.get(field) is not None and r[field] == r[field]]
        return round(sum(vals) / len(vals), 4) if vals else 0.0
    def arr(field: str, subset: list[dict[str, Any]] | None = None) -> list[float]:
        pool = subset if subset is not None else rows
        return [r[field] for r in pool
                if r.get(field) is not None and r[field] == r[field]]
    lat = sorted(r["latency_ms"] for r in rows)
    def pct(p: float) -> float:
        return round(lat[min(len(lat) - 1, int(p * len(lat)))], 1) if lat else 0.0
    gates: dict[str, int] = {}
    for r in rows:
        gates[r["gate"]] = gates.get(r["gate"], 0) + 1
    return {
        "n": len(rows),
        "gold_n": len(gold_rows),
        "weak_n": weak_count,
        "error_count": len(errs),
        # 召回/排序指标只对有 gold 的题取均值（新口径）
        "fused_recall": avg("fused_recall", gold_rows),
        "recall@1": avg("recall@1", gold_rows),
        "recall@3": avg("recall@3", gold_rows),
        "recall@5": avg("recall@5", gold_rows),
        "recall@10": avg("recall@10", gold_rows),
        "mrr": avg("mrr", gold_rows),
        "precision@5": avg("precision@5", gold_rows),
        "filter_blocked": sum(1 for r in rows if r.get("filter_blocked")),
        "gate_dist": gates,
        "latency_ms_mean": round(sum(lat) / len(lat), 1) if lat else 0.0,
        "latency_ms_p50": pct(0.50), "latency_ms_p95": pct(0.95), "latency_ms_max": max(lat) if lat else 0.0,
        "embedding_mock": sum(1 for r in rows if r.get("embedding", {}).get("embedding_is_mock") is True),
    }
```

**步骤 2**：确认 `diagnostic_relaxed_filters` 也走同一 `_metrics`（bench 主函数对诊断回放复用 `_metrics`），无需额外改动。

### 预期行为
- `overall.fused_recall` 分母为 90（gold_n），非 100
- `overall.gold_n=90`、`overall.weak_n=10` 新增字段
- weak 题（C06/C07/C09/C10/C16/E01/E03/E04/E05/E10）不拉低均值
- R8 后无代码改动，纯口径重算：fused_recall = 85/90 ≈ 0.9444

> 注：实测 BANK 中 `gold=[]` 的 weak 题为 10 个，含初稿漏记的 C09（空客 A380 翼展，BANK 定义 `gold=[]`、`expect=weak`）；分母以实际 90 为准。

### 验证方式
```bash
# 静态检查
python -c "import ast; ast.parse(open('scripts/_rag_bench.py',encoding='utf-8').read())"

# 跑全量基准确认口径
python scripts/_rag_bench.py
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); o=d['overall']; print('gold_n=',o.get('gold_n'),'weak_n=',o.get('weak_n'),'fused_recall=',o['fused_recall'],'recall@3=',o['recall@3'],'mrr=',o['mrr'])"
```
预期：`gold_n=90`、`weak_n=10`、`fused_recall≈0.9444`、`recall@3≈0.4333`、`mrr≈0.3723`。

---

## R9 — evidence_policy 门控层对齐

### 触及文件
- `src/knowledge/evidence_policy.py`
- `tests/integration/rag_pipeline/test_evidence_relevance_gate.py`（断言同步）

### 改动范围
`EvidenceEligibilityPolicy._evaluate`（行 51-126）的 aircraft/component 比较分支（行 73-78）。

### 改动步骤

**步骤 1**：修改 `src/knowledge/evidence_policy.py` 的 `_evaluate`，aircraft 比较改大小写不敏感，component 加通用桶豁免。在 `__init__` 增加 `general_component_buckets` 参数：

```python
class EvidenceEligibilityPolicy:
    def __init__(
        self,
        source_registry: SourceRegistry,
        config: EligibilityConfig,
        allowed_review_status: set[str],
        chunk_lookup: Callable[[str], TextChunk | None],
        general_component_buckets: tuple[str, ...] = (),
    ) -> None:
        self.source_registry = source_registry
        self.config = config
        self.allowed_review_status = allowed_review_status
        self.chunk_lookup = chunk_lookup
        # 通用桶列表从 RetrievalController 注入（来源 configs/rag.yaml），
        # 与检索层 _matches_filters 保持一致（R3 修复的延伸）。
        self.general_component_buckets = tuple(general_component_buckets)
```

**步骤 2**：修改 `_evaluate` 的 aircraft/component 比较分支（行 73-78）：

```python
        filters = retrieval_plan.filters
        target_component = filters.get("component")
        target_aircraft = filters.get("aircraft")

        if target_aircraft and candidate.chunk.aircraft is not None:
            # R1 延伸：入库 aircraft 小写（c919），查询层产出大写（C919）。
            # 检索层 _matches_filters 已大小写不敏感，门控层须对齐，
            # 否则 gold 进 RRF 后被 target_mismatch 拒绝、证据包为空。
            if candidate.chunk.aircraft.lower() != str(target_aircraft).lower():
                candidate.rejection_reasons.append("target_mismatch")
                return
        if target_component and candidate.chunk.component is not None:
            # R3 延伸：通用桶（principle/history/overall/comparison/application）
            # 承载跨部件原理内容，门控层须与检索层同步豁免，否则 R3 召回的
            # 通用桶 chunk 在门控层又被 target_mismatch 拒绝。
            if candidate.chunk.component != target_component:
                if candidate.chunk.component not in self.general_component_buckets:
                    candidate.rejection_reasons.append("target_mismatch")
                    return
```

**步骤 3**：同步修改 qualification_reasons 判定（行 84-87），aircraft 匹配也用大小写不敏感：

```python
        if target_aircraft and candidate.chunk.aircraft is not None:
            if candidate.chunk.aircraft.lower() == str(target_aircraft).lower():
                candidate.qualification_reasons.append("aircraft_match")
        if target_component and candidate.chunk.component == target_component:
            candidate.qualification_reasons.append("component_match")
```

**步骤 4**：在 `src/knowledge/retrieval_controller.py` 构造 `EvidenceEligibilityPolicy` 处注入 `general_component_buckets`。grep `EvidenceEligibilityPolicy(` 定位构造点，传入 `self.config.retrieval.general_component_buckets`。

**步骤 5**：检查 `tests/integration/rag_pipeline/test_evidence_relevance_gate.py` 是否有断言 `target_mismatch` 拒绝 aircraft 大小写不一致候选的用例。若有，同步更新为断言 `aircraft_match` qualification。R2 已授权该文件同步。

### 预期行为
- C919 查询下 c919 chunk 不再被 `target_mismatch` 拒绝
- `component=wing` 查询下 `component=principle` chunk 不再被 `target_mismatch` 拒绝（通用桶豁免）
- 31 题证据包由空变非空，gate 由 weak 升 confident
- Recall@3 大幅提升

### 验证方式
```bash
# 静态检查
python -c "import ast; ast.parse(open('src/knowledge/evidence_policy.py',encoding='utf-8').read())"

# 单测+集成
pytest tests/unit tests/integration

# 跑全量基准
python scripts/_rag_bench.py
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); o=d['overall']; print('fused_recall=',o['fused_recall'],'recall@3=',o['recall@3'],'mrr=',o['mrr']); hit=[c for c in d['cases'] if c.get('expect')=='hit' and 'error' not in c]; empty=[c for c in hit if c['fused_recall']==1 and len(c.get('evidence',[]))==0]; print('证据包空:',len(empty),[c['id'] for c in empty])"
```
预期：证据包空题数 31→≤5；`recall@3` ≥0.65。

---

## R10 — reranker _scene_match 对齐

### 触及文件
- `src/knowledge/reranking.py`
- `tests/unit/knowledge/`（新增 _scene_match 测试）

### 改动范围
`FeatureReranker._scene_match`（行 188-201）的 aircraft/component 比较。

### 改动步骤

**步骤 1**：修改 `src/knowledge/reranking.py` 的 `FeatureReranker`，在 `__init__` 增加 `general_component_buckets` 参数：

```python
class FeatureReranker:
    def __init__(
        self,
        weights: Mapping[str, float],
        source_registry: SourceRegistry,
        authority_scores: Mapping[str, float] | None = None,
        cross_encoder: CrossEncoderReranker | None = None,
        general_component_buckets: tuple[str, ...] = (),
    ) -> None:
        self.weights = weights
        self.source_registry = source_registry
        self.authority_scores = authority_scores or {}
        self.cross_encoder = cross_encoder
        self.general_component_buckets = tuple(general_component_buckets)
```

**步骤 2**：修改 `_scene_match`（行 188-201），aircraft 大小写不敏感、component 通用桶豁免：

```python
    def _scene_match(self, chunk: TextChunk, context: RetrievalPlan) -> float:
        fields = [
            ("aircraft", chunk.aircraft),
            ("component", chunk.component),
        ]
        requested = [
            (name, actual, context.filters.get(name))
            for name, actual in fields
            if context.filters.get(name)
        ]
        if not requested:
            return 0.0
        matched = 0
        for name, actual, expected in requested:
            if actual is None:
                continue
            if name == "aircraft":
                # R1 延伸：入库小写，查询大写，大小写不敏感比较
                if actual.lower() == str(expected).lower():
                    matched += 1
            elif name == "component":
                # R3 延伸：通用桶豁免——通用桶 chunk 视为匹配
                if actual == expected or actual in self.general_component_buckets:
                    matched += 1
        return matched / len(requested) if requested else 0.0
```

> 注：原 `_scene_match` 含 concept 字段（行 192），R2 已移除 concept 过滤，这里同步删除 concept 维度。

**步骤 3**：在 `src/knowledge/retrieval_controller.py` 构造 `FeatureReranker` 处注入 `general_component_buckets`。grep `FeatureReranker(` 定位构造点。

**步骤 4**：新增单测 `tests/unit/knowledge/test_reranker_scene_match.py`，验证：
- `aircraft=c919` chunk 在 `filters.aircraft=C919` 下 `_scene_match` 得 1.0
- `component=principle` chunk 在 `filters.component=wing` 下 `_scene_match` 得 1.0（通用桶豁免）
- `component=landing_gear` chunk 在 `filters.component=wing` 下 `_scene_match` 得 0.0（非通用桶严格相等）

### 预期行为
- c919 chunk 在 C919 查询下 `_scene_match` 由 0.0 变 1.0
- 排序分 `weighted_total` 提升，gold 更易进 top-3
- 14 题 gold 不在 top-3 的题部分修复

### 验证方式
```bash
pytest tests/unit tests/integration

python scripts/_rag_bench.py
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); o=d['overall']; print('recall@3=',o['recall@3'],'mrr=',o['mrr'])"
```
预期：`recall@3` ≥0.70，`mrr` ≥0.50。

---

## R11 — COMPONENT_ALIASES 精确化

### 触及文件
- `src/input/query_understanding.py`
- `tests/unit/input/`（新增别名测试）

### 改动范围
`COMPONENT_ALIASES`（行 15-22）与 `_detect_component`（行 192-200 左右）。

### 改动步骤

**步骤 1**：先确认 DB component 桶实际值，确定哪些别名映射需要修正：

```bash
python -c "import sqlite3; c=sqlite3.connect('data/processed/knowledge.sqlite3'); [print(r[0],r[1]) for r in c.execute('SELECT component, COUNT(*) FROM text_chunks GROUP BY component ORDER BY 2 DESC')]"
```

**步骤 2**：根据 DB 实际桶值修正 `src/input/query_understanding.py` 的 `COMPONENT_ALIASES`。核心原则：**无法精确映射到 DB 真实桶的别名不产出 target_component**（让 keyword+dense 语义匹配承担，而非用不存在的桶过滤）。预期修正：

```python
COMPONENT_ALIASES = {
    # "翼/机翼" 不再统一映射为 wing——DB 中 wing 桶仅占少数，
    # 旋翼/翼尖小翼/超临界翼型各有不同 component 桶（rotor/drag-breakdown/principle）。
    # 让"翼"类查询不产出 target_component，由 keyword+dense 语义匹配。
    # 保留精确映射：
    "起落架": "landing_gear",
    "航电": "avionics",
    "液压": "hydraulic",
    "燃油": "fuel_system",
    "飞控": "fly_by_wire",
    # "发动机" 不映射为 engine（DB 无 engine 桶，有 powerplant/propulsion/engine_nacelle）
    # 让"发动机"类查询不产出 target_component
}
```

> 具体保留哪些映射需对照 DB 实际桶值。原则：只保留 DB 真实存在的 component 桶的映射，删除会产生空桶的映射。

**步骤 3**：检查 `_detect_component` 是否有兜底逻辑（如未命中别名返回 None），确保移除映射后 `target_component=None`。

**步骤 4**：新增单测验证：A17/A20/A33 查询的 `target_component` 为 None（不再产出 wing/engine）。

### 预期行为
- A17/A20/A33 查询不再注入 `component=wing/engine` 过滤
- 这 3 题的 gold 不被 component 过滤排除
- fused_recall 由 0 变 1

### 验证方式
```bash
pytest tests/unit tests/integration

python scripts/_rag_bench.py --only A
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); cases={c['id']:c for c in d['cases']}; [print(i, 'fused=', cases[i]['fused_recall'], 'filters=', cases[i]['filters'].get('component')) for i in ['A17','A20','A33'] if i in cases]"
```
预期：A17/A20/A33 `fused_recall=1.0`，`filters.component=None`。

---

## R12 — RRF 通道权重配置化

### 触及文件
- `configs/rag.yaml`
- `src/knowledge/config.py`
- `src/knowledge/fusion.py`
- `tests/unit/knowledge/test_fusion.py`（若存在，否则新增）

### 改动范围
`reciprocal_rank_fusion`（行 66-85）的 RRF 计算逻辑。

### 改动步骤

**步骤 1**：在 `configs/rag.yaml` 的 `retrieval:` 段新增通道权重配置：

```yaml
retrieval:
  # ...既有配置...
  # RRF 通道权重：默认等权 1.0。dense 语义匹配更准，可适当提权。
  channel_weights:
    keyword: 1.0
    dense: 1.2
    parent: 1.1
    scene: 0.8
    table: 1.0
    visual: 0.8
    graph: 1.0
```

**步骤 2**：在 `src/knowledge/config.py` 的 `RetrievalConfig` 新增 `channel_weights: dict[str, float]` 字段，`load_rag_config` 解析时读取（带默认空 dict）。

**步骤 3**：修改 `src/knowledge/fusion.py` 的 `reciprocal_rank_fusion`，接受可选 `channel_weights` 参数：

```python
def reciprocal_rank_fusion(
    channel_hits: dict[str, list[RetrievalHit]],
    rrf_k: int,
    channel_weights: dict[str, float] | None = None,
) -> list[FusedHit]:
    if rrf_k <= 0:
        raise ValueError("rrf_k must be greater than zero")
    weights = channel_weights or {}
    merged: dict[str, FusedHit] = {}
    for channel, hits in channel_hits.items():
        weight = weights.get(channel, 1.0)
        for rank, hit in enumerate(hits, start=1):
            item = merged.setdefault(
                hit.item_id,
                FusedHit(item_id=hit.item_id, source_id=hit.source_id),
            )
            if not item.source_id and hit.source_id:
                item.source_id = hit.source_id
            # RRF 加通道权重：权重 * (1/(rrf_k+rank))
            item.rrf_score += weight * (1.0 / (rrf_k + rank))
            item.channels.append(channel)
            item.channel_ranks[channel] = rank
            item.hits.append(hit)
    return sorted(merged.values(), key=lambda item: (-item.rrf_score, item.item_id))
```

**步骤 4**：在 `src/knowledge/retrieval_controller.py` 调用 `reciprocal_rank_fusion` 处（行 1193）传入 `self.config.retrieval.channel_weights`。

**步骤 5**：新增/更新单测验证带权重的 RRF 计算正确。

### 预期行为
- dense 通道候选因权重 1.2 获得更高 rrf_score
- gold 来源（多为 dense 语义匹配命中）更易排在前面
- Recall@3/MRR 提升

### 验证方式
```bash
pytest tests/unit tests/integration

python scripts/_rag_bench.py
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); o=d['overall']; print('recall@3=',o['recall@3'],'mrr=',o['mrr'])"
```
预期：`recall@3` 不回退且有提升或持平。

---

## 全量验收（所有任务完成后）

```bash
# 1. 全量单元+集成测试
pytest tests/unit tests/integration

# 2. 完整 100 题基准测试
python scripts/_rag_bench.py

# 3. 指标对照（新口径）
python -c "import json; d=json.load(open('tmp/rag_bench_results.json',encoding='utf-8')); o=d['overall']; print('gold_n=',o.get('gold_n'),'fused_recall=',o['fused_recall'],'recall@3=',o['recall@3'],'mrr=',o['mrr'],'filter_loss=',d['filter_attributable_recall_loss']['n'],'errors=',o['error_count'])"
```

达标线（新口径）：FusedRecall ≥0.95，Recall@3 ≥0.70，MRR ≥0.50，filter_loss ≤2，error_count=0。

完成后在 `docs/项目总控/STATUS.md` 追加二期段落，记录最终指标与修改文件清单。
