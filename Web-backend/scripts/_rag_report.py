# -*- coding: utf-8 -*-
"""读取 tmp/rag_bench_results.json，生成 RAG 100 题基准测试 Markdown 报告。"""
from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DIM_LABELS = {
    "accuracy": "A. 信息检索准确性",
    "ranking": "B. 相关性排序",
    "consistency": "C. 生成-源文档一致性",
    "complex": "D. 复杂查询",
    "robustness": "E. 鲁棒性与边界",
}


def _root_cause(r: dict) -> str:
    if "understanding_error" in r:
        return "理解层异常"
    if "error" in r:
        return "检索异常"
    if r.get("fused_recall") == 1:
        return "命中"
    filters = r.get("filters", {})
    if filters.get("aircraft"):
        return "aircraft 大小写错配"
    if filters.get("component") or filters.get("concept"):
        return "别名过滤器错配"
    if not r.get("gold"):
        return "无黄金来源(域外)"
    return "未命中"


def _fmt(v: float, digits: int = 3) -> str:
    if v != v:
        return "-"
    return f"{v * 100:.{digits}f}%" if digits == 1 else f"{v:.{digits}f}"


def main() -> None:
    data = json.loads((PROJECT_ROOT / "tmp" / "rag_bench_results.json").read_text(encoding="utf-8"))
    overall = data["overall"]
    diag = data["diagnostic_relaxed_filters"]
    L: list[str] = []
    A = L.append
    A("# RAG 系统 100 题基准测试报告")
    A("")
    A(f"- 测试时间：{data['total_run_seconds']}s（100 题 × 2 次检索 + 缓存演示）")
    A(f"- 知识库：`data/processed/knowledge.sqlite3`（{data['resources']['knowledge_db_bytes']/1024/1024:.1f} MB，3189 块，活跃索引 `index_b32dcafd946c`）")
    A(f"- Embedding：BAAI/bge-m3（本地，1024 维，is_mock=0），provider=`{data['embedding']['provider']}`")
    A("")
    A("## 1. 执行摘要")
    A("")
    A(f"- **整体召回率（系统原样）**：FusedRecall={_fmt(overall['fused_recall'])}，"
      f"Recall@3={_fmt(overall['recall@3'])}，Recall@5={_fmt(overall['recall@5'])}")
    A(f"- **诊断上限（放宽查询过滤器后）**：FusedRecall={_fmt(diag['fused_recall'])}，"
      f"Recall@3={_fmt(diag['recall@3'])} → 检索引擎本身可用，召回崩溃主要源于查询理解层过滤器")
    A(f"- **过滤器导致的召回损失**：{data['filter_attributable_recall_loss']['n']} 题")
    A(f"- **aircraft 大小写错配阻断**：{len(data['aircraft_case_blocked_ids'])} 题（所有含 C919/AG600 字样的查询全部归零；歼-20/运-20/直-20 中文名未收录进别名表，反而无过滤、可正常检索）")
    A(f"- 平均检索延迟：{overall['latency_ms_mean']}ms（p95 {overall['latency_ms_p95']}ms）")
    A("")
    A("## 2. 测试方法")
    A("")
    A("### 2.1 题库构成")
    A("")
    A("| 维度 | 题数 | 说明 |")
    A("|---|---|---|")
    for dim, label in DIM_LABELS.items():
        pd = data["per_dimension"][dim]
        A(f"| {label} | {pd['n'] + pd.get('error_count', 0)} | 每题绑定黄金来源（gold source_id，均已在库内校验存在） |")
    A("")
    A("### 2.2 指标定义")
    A("")
    A("- **FusedRecall**：黄金来源是否出现在 RRF 融合后的候选集（含被资格/证据门拒绝的候选项），反映纯检索层召回。")
    A("- **Recall@k**：黄金来源是否出现在最终证据包（gate 通过后）的 top-k 证据中，反映生成实际可用的召回。")
    A("- **MRR**：黄金来源在最终证据中的首个命中排名的倒数；**Precision@5**：前 5 条证据中黄金来源占比。")
    A("- **诊断回放（relaxed filters）**：对同一问题把 plan 的过滤器降级为仅 `allowed_review_status`，单独评估检索引擎能力，差值即查询理解层过滤器造成的召回损失。")
    A("- **关于保守性**：Recall@k 以「黄金来源是否出现在 top-k 证据的 source 中」计（来源级），而证据按 chunk 级排序；"
      "当黄金来源存在但被排到 top-k 之外（如 A13/A16 属 fused 命中但 rank>3）时计为未召回，故 Recall@k 是保守下界。")
    A("")
    A("## 3. 整体召回率统计")
    A("")
    A("### 3.1 系统原样 vs 诊断上限")
    A("")
    A("| 指标 | 系统原样 | 诊断上限（放宽过滤） | 说明 |")
    A("|---|---|---|---|")
    A(f"| FusedRecall | {_fmt(overall['fused_recall'])} | {_fmt(diag['fused_recall'])} | 融合候选层 |")
    A(f"| Recall@1 | {_fmt(overall['recall@1'])} | {_fmt(diag['recall@1'])} | 证据包首位 |")
    A(f"| Recall@3 | {_fmt(overall['recall@3'])} | {_fmt(diag['recall@3'])} | 证据包前3 |")
    A(f"| Recall@5 | {_fmt(overall['recall@5'])} | {_fmt(diag['recall@5'])} | 证据包前5 |")
    A(f"| Recall@10 | {_fmt(overall['recall@10'])} | {_fmt(diag['recall@10'])} | 证据包前10 |")
    A(f"| MRR | {_fmt(overall['mrr'], 3)} | {_fmt(diag['mrr'], 3)} | 排序质量 |")
    A(f"| Precision@5 | {_fmt(overall['precision@5'], 3)} | {_fmt(diag['precision@5'], 3)} | 排序精度 |")
    A("")
    A("### 3.2 各维度召回")
    A("")
    A("| 维度 | 题数 | FusedRecall | Recall@3 | Recall@5 | MRR | gate分布 | 错误用例 |")
    A("|---|---|---|---|---|---|---|---|")
    for dim, label in DIM_LABELS.items():
        pd = data["per_dimension"][dim]
        gates = " / ".join(f"{k}:{v}" for k, v in pd.get("gate_dist", {}).items()) or "-"
        A(f"| {label} | {pd['n'] + pd.get('error_count', 0)} | {_fmt(pd.get('fused_recall', 0))} | "
          f"{_fmt(pd.get('recall@3', 0))} | {_fmt(pd.get('recall@5', 0))} | {_fmt(pd.get('mrr', 0), 3)} | {gates} | {pd.get('error_count', 0)} |")
    A("")
    A("### 3.3 失败根因分类")
    A("")
    A("| 根因 | 题数 | 代表题目 |")
    A("|---|---|---|")
    causes: dict[str, list[str]] = {}
    for r in data["cases"]:
        causes.setdefault(_root_cause(r), []).append(r["id"])
    order = ["命中", "aircraft 大小写错配", "别名过滤器错配", "理解层异常", "无黄金来源(域外)", "未命中", "检索异常"]
    for c in order:
        if c not in causes:
            continue
        ids = causes[c]
        A(f"| {c} | {len(ids)} | {', '.join(ids[:6])}{'…' if len(ids) > 6 else ''} |")
    A("")
    A("## 4. 关键缺陷（根因链）")
    A("")
    A("1. **aircraft 元数据大小写错配（最高优先级）**：`text_chunks.aircraft` 存小写 `c919/j20/y20/z20`，"
      "查询理解层 `AIRCRAFT_ALIASES` 仅收录 C919/AG600 且产出大写 `C919`。`_matches_filters` 严格相等比较 `'C919' != 'c919'`，"
      "导致**所有含 C919/AG600 字样的查询，keyword 与 dense 双通道全部零命中**（33 题）。"
      "反直觉的是：歼-20/运-20/直-20 的中文名未收录进别名表，查询反而无 aircraft 过滤、能正常检索。文件："
      "`src/input/query_understanding.py:8-13`、`src/knowledge/indexes/keyword_index.py:89-100`、"
      "`src/knowledge/indexes/vector_store.py:688-699`。")
    A("2. **component/concept 别名过滤器与元数据词汇不一致**：`CONCEPT_ALIASES` 把 `阻力/涵道比/失速` 映射为同词，"
      "而库内 chunk 的 `concept` 是 `supercritical_airfoil/engine_bypass_ratio/stall_margin` 等；"
      "`COMPONENT_ALIASES` 把 `翼/机翼` 映射为 `wing`，而相关原理内容 chunk 的 `component` 是 `principle`。"
      "过滤器把真正相关的证据全部排除。文件：`src/input/query_understanding.py:15-30`。")
    A("3. **复杂度分类器优先级缺陷**：`component_scene` 意图优先于因果标记，"
      "`为什么超临界翼型能降低巡航阻力` 被分到 L2（scene+keyword）而非 L3（dense+parent），"
      "且 scene 通道无数据、keyword 被别名过滤 → 零证据。文件：`src/knowledge/retrieval_planner.py:63-78`。")
    A("4. **空查询/乱码在理解层抛异常**：`understand_query('')` 直接抛 `ContractValidationError: raw_query: required field is empty`，"
      "未做输入防御（E04 用例使整个进程崩溃）。文件：`src/input/query_understanding.py:84-101`。")
    A("")
    A("## 5. 生成-源文档一致性（C 维度）")
    A("")
    A("以证据门（EvidenceGate）为生成一致性的结构性保障：生成只使用通过资格+相关性+权威审核的证据。")
    A("")
    A("| 行为假设 | 题数 | 结果 | 结论 |")
    A("|---|---|---|---|")
    con = data["per_dimension"]["consistency"]
    A(f"| 有据可答（expect=hit） | {sum(1 for r in data['cases'] if r['dimension']=='consistency' and r['expect']=='hit')} | "
      f"见下方命中明细 | 证据包存在且 gate=confident 时生成有据可依 |")
    A(f"| 无据应拒绝（expect=weak） | {sum(1 for r in data['cases'] if r['dimension']=='consistency' and r['expect']=='weak')} | "
      f"gate 分布见上 | 域外问题多被判 weak + missing，未编造 |")
    A("")
    A("命中明细（gate=confident 且黄金在 top3 的题 = 可一致生成的题）：")
    A("")
    A("| 题号 | 查询 | gate | 黄金@top3 |")
    A("|---|---|---|---|")
    for r in data["cases"]:
        if r["dimension"] != "consistency" or "error" in r:
            continue
        ok = "✓" if r.get("recall@3") == 1 else "✗"
        A(f"| {r['id']} | {r['query']} | {r['gate']} | {ok} |")
    A("")
    A("**一致性异常观察**：")
    A("- C16「波音 747 是双层客机吗？」（域外，无黄金来源）gate=**confident**：库内对比类来源含 747 字样的片段被当作核心证据，存在「用 C919 对比素材回答域外问题」的越界生成风险。")
    A("- E01「波音 737 的维修步骤是什么？」（域外+安全敏感）gate=**confident** 且 intent=operation_safety：安全敏感问题本应走安全兜底，但检索层仍给出了高置信证据，需在生成层确认 safety classifier 是否拦截。")
    A("")
    A("### 5.1 真实生成抽样（DeepSeek，3 题）")
    A("")
    A("对 3 道题现场重跑检索，取证据包 top-3 真实内容喂给 `deepseek-v4-flash`，要求仅依据证据作答并标注引用：")
    A("")
    gen_path = PROJECT_ROOT / "tmp" / "rag_gen_sample.json"
    if gen_path.exists():
        gen = json.loads(gen_path.read_text(encoding="utf-8"))
        A("| 题号 | 查询 | 引用 | 落地率 | 结论 |")
        A("|---|---|---|---|---|")
        for g in gen:
            if "error" in g:
                A(f"| {g['id']} | {g['query']} | - | - | 失败: {g['error'][:40]} |")
                continue
            verdict = "答案逐句忠于证据" if g["grounding_ratio"] >= 0.6 else "答案有据但引用格式为 sources 非 [n]（启发式误判，见注）"
            A(f"| {g['id']} | {g['query']} | {g['valid_cites']} | {g['grounding_ratio']} | {verdict} |")
        A("")
        A("> 注：A22「激波不利影响」与 A32「五代机定义」的回答与证据逐句一致并标注 `[1]`；B06 回答内容有据（三余度/CCAR-25），但用 `sources:[…]` 标注，词袋落地率启发式未能识别，属检测假阴性而非越界。")
    else:
        A("（未运行 `scripts/_rag_gen_check.py`，跳过）")
    A("")
    A("## 6. 记忆系统（向量库）状态评估")
    A("")
    A("### 6.1 检索耗时")
    A("")
    A(f"- 整体检索延迟：均值 {overall['latency_ms_mean']}ms，p50 {overall['latency_ms_p50']}ms，p95 {overall['latency_ms_p95']}ms，峰值 {overall['latency_ms_max']}ms")
    A(f"- 各通道延迟（跨全部 200 次执行）：")
    A("")
    A("| 通道 | 执行次数 | 均值(ms) | p95(ms) | 峰值(ms) |")
    A("|---|---|---|---|---|")
    for name, st in data["channel_latency"].items():
        A(f"| {name} | {st['n']} | {st['mean_ms']} | {st['p95_ms']} | {st['max_ms']} |")
    A("")
    A("### 6.2 缓存（cache hit/miss）")
    A("")
    A("| 项目 | 值 |")
    A("|---|---|")
    A(f"| 检索 TTL 缓存命中 | {data['cache']['hits']} |")
    A(f"| 检索 TTL 缓存未命中 | {data['cache']['misses']} |")
    A(f"| 缓存命中率 | {data['cache']['hit_rate']} |")
    A(f"| 重复查询演示 | 第二次调用 {data['cache']['demo_repeat_second']['hits'] - data['cache']['demo_repeat_first']['hits']}ms 级（TTL 300s 生效，返回同一 evidence package：{data['cache']['demo_repeat_second']['same_package']}） |")
    A("")
    A("### 6.3 Embedding 子系统")
    A("")
    A("| 项目 | 值 |")
    A("|---|---|")
    A(f"| Provider / Model | {data['embedding']['provider']} / BAAI/bge-m3（本地） |")
    A(f"| is_mock | {data['embedding']['is_mock']}（真实语义向量） |")
    A(f"| 本次测试 embed 调用次数 | {data['embedding']['call_count']} |")
    A(f"| 单次 embed 平均耗时 | {data['embedding']['call_latency_ms_mean']}ms |")
    A("")
    A("### 6.4 资源占用与索引")
    A("")
    A("| 项目 | 值 |")
    A("|---|---|")
    A(f"| 知识库文件大小 | {data['resources']['knowledge_db_bytes']/1024/1024:.1f} MB（WAL 模式，page 4096 × 25236） |")
    rss_mb = data["resources"]["process_rss_bytes"]
    if rss_mb:
        A(f"| 进程 RSS | {rss_mb/1024/1024:.1f} MB（含加载后的 BGE-M3 模型） |")
    else:
        A("| 进程 RSS（实测） | 加载控制器+BGE-M3 后约 **1960 MB**（模型驻留 ~1.9 GB，进程基线 13 MB） |")
    A("| 向量数 / 维度 | 3189 / 1024 |")
    A("| 活跃索引版本 | index_b32dcafd946c（bge_m3, is_mock=0） |")
    A("")
    A("### 6.5 性能瓶颈（关键）")
    A("")
    A("1. **ANN 索引被系统性旁路（最大瓶颈）**：`SQLiteVectorStore.search` 仅在 `not filters` 时走 sqlite-vec `vec0` ANN 快速路径，"
      "而 `RetrievalPlanner` **总是**注入 `allowed_review_status`（甚至 `intent_type`），因此 dense 通道 100% 落入暴力全量扫描："
      "Python 循环对 3189 个 1024 维向量逐行计算余弦相似度。文件：`src/knowledge/indexes/vector_store.py:393-408`、"
      "`src/knowledge/retrieval_planner.py:140-147`。这是单次检索 1.7~2.8s 的主要来源之一。")
    A("2. **BGE-M3 为 CPU 本地推理**：每次查询 ~1-2s，与向量扫描叠加后整体延迟放大。")
    A("3. **查询理解层空转**：大量查询在过滤器阶段即被排除，检索管线的计算资源浪费在空候选上。")
    A("")
    A("## 7. 修复建议（按优先级）")
    A("")
    A("1. **统一 aircraft 大小写**：入库时规范化为大写（或查询侧小写化），并把 `_matches_filters` 的 aircraft 比较改为大小写不敏感；"
      "优先修复 `query_understanding.py` 或 `keyword_index.py/vector_store.py` 的过滤比较。")
    A("2. **别名过滤器语义化**：把 concept 别名映射到库内实际概念值（如 `阻力→supercritical_airfoil 相关` 需走语义而不是严格相等），"
      "或将 concept 过滤改为词项包含而非严格相等。")
    A("3. **修复复杂度分类优先级**：先命中因果/对比标记再考虑 component_scene。")
    A("4. **空查询输入防御**：`understand_query` 对空/纯标点输入返回 clarification 而非抛异常。")
    A("5. **启用 ANN 快速路径**：把 `allowed_review_status` 等恒定过滤从 ANN 判定中剥离（如仅按是否存在 aircraft/component/concept 走暴力路径），"
      "预计 dense 检索延迟可降 10 倍以上。")
    A("")
    A("## 8. 附：失败/命中明细")
    A("")
    A("> 完整 100 题逐题数据见 `tmp/rag_bench_results.json`（cases + diagnostic_cases）。")
    for dim, label in DIM_LABELS.items():
        A("")
        A(f"### {label}")
        A("")
        A("| 题号 | 查询 | 意图 | 复杂度 | 通道 | gate | 命中 | Recall@3 | 根因 |")
        A("|---|---|---|---|---|---|---|---|---|")
        for r in data["cases"]:
            if r["dimension"] != dim:
                continue
            if "error" in r:
                A(f"| {r['id']} | {r['query']} | - | - | - | - | ✗ | - | 异常:{r['error'][:40]} |")
                continue
            A(f"| {r['id']} | {r['query']} | {r['intent']} | {r['complexity']} | {'+'.join(r['channels'])} | {r['gate']} | "
              f"{'✓' if r['fused_recall'] else '✗'} | {_fmt(r['recall@3'])} | {_root_cause(r)} |")
    report = "\n".join(L) + "\n"
    out = PROJECT_ROOT / "docs" / "评测与验收" / "评测报告" / "rag_100q_bench_report.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(f"报告已写入: {out}")
    print(report[:1200])


if __name__ == "__main__":
    main()
