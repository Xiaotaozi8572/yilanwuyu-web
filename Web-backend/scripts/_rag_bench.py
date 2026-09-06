# -*- coding: utf-8 -*-
"""RAG 系统 100 题基准测试（挑战杯项目）。

对真实知识库（data/processed/knowledge.sqlite3，BGE-M3 真实向量）执行
100 道覆盖 5 大维度的中文测试题，采集召回率、排序指标、gate 状态、
每通道延迟、cache hit/miss 与资源占用，并输出 JSON 报告。

维度划分：
  accuracy    信息检索准确性（35 题，带黄金来源）
  ranking     相关性排序（20 题，多候选择优）
  consistency 生成-源文档一致性（20 题，gate 基础 + 小样本真实生成）
  complex     复杂查询（15 题）
  robustness  鲁棒性与边界（10 题）

用法：
  python scripts/_rag_bench.py                  # 完整 100 题 + 诊断回放
  python scripts/_rag_bench.py --gen-sample     # 额外对 3 题跑真实 DeepSeek 生成一致性
  python scripts/_rag_bench.py --only A         # 只跑某个维度
  python scripts/_rag_bench.py --denominator 90 # 召回/排序指标只对 gold 非空题取均值（T17 起新口径）
  python scripts/_rag_bench.py --denominator 100# 召回/排序指标对全量 100 题取均值（旧口径）
  python scripts/_rag_bench.py --channel bm25   # 通道消融：仅 bm25(keyword) 通道
  python scripts/_rag_bench.py --channel dense  # 通道消融：仅 dense 通道
  python scripts/_rag_bench.py --output tmp/xx.json  # 指定结果输出文件
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import math
import os
from pathlib import Path
import sys
from time import perf_counter
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC = PROJECT_ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from input.query_understanding import understand_query
from knowledge.retrieval_controller import RetrievalController

# ---------------------------------------------------------------------------
# 测试题库（100 题）
# gold: 该问题答案应来自的来源 source_id 集合（运行时按库内存在性校验）
# expect: 行为假设（hit=应检索到黄金来源；weak=应判定证据不足拒绝编造）
# ---------------------------------------------------------------------------

BANK: list[dict[str, Any]] = []

def add_q(id_: str, dim: str, query: str, gold: list[str], expect: str = "hit", note: str = "") -> None:
    BANK.append({
        "id": id_, "dimension": dim, "query": query,
        "gold": gold, "expect": expect, "note": note,
    })

# ============ A. 信息检索准确性（35） ============
# --- A01-A20：带型号查询（应命中，但因 aircraft 大小写过滤 bug 预期失败）---
for _i, (_qtext, _g, _n) in enumerate([
    ("C919 的翼展是多少米？", ["c919-science-parameters-v1", "c919-science-specifications-summary-v1"], "翼展参数"),
    ("C919 的巡航马赫数是多少？", ["c919-science-parameters-v1", "c919-science-specifications-summary-v1"], "巡航马赫数"),
    ("C919 的机身全长是多少米？", ["c919-science-parameters-v1"], "机身全长"),
    ("C919 采用的是哪款发动机？", ["c919-science-propulsion-v1", "c919-science-engine-principle-v1"], "LEAP-1C"),
    ("C919 的客舱布局是什么样的？", ["c919-science-parameters-v1", "c919-science-cabin-v1"], "3-3 单过道"),
    ("C919 的国产化率大约是多少？", ["c919-science-localization-rate-v1"], "国产化率"),
    ("C919 首飞是哪一天？", ["c919-science-history-2017-first-flight-v1"], "2017-05-05"),
    ("C919 什么时候取得型号合格证？", ["c919-science-certification-v1", "c919-science-history-2022-2026-delivery-v1"], "TC 取证"),
    ("C919 的最大起飞重量是多少？", ["c919-science-parameters-v1"], "MTOW"),
    ("C919 的实用升限是多少？", ["c919-science-specifications-summary-v1"], "实用升限"),
    ("C919 的制造商是哪家公司？", ["c919-science-specifications-summary-v1"], "COMAC 中国商飞"),
    ("C919 的客舱宽度是多少？", ["c919-science-parameters-v1"], "客舱宽度"),
    ("歼-20 采用的是哪种气动布局？", ["j20-science-canard-aero-v1", "j20-science-canard-wing-v1"], "鸭式布局"),
    ("歼-20 的进气道采用了什么设计？", ["j20-science-dsi-intake-v1"], "DSI 进气道"),
    ("歼-20 装备了什么雷达？", ["j20-science-aesa-principle-v1"], "有源相控阵"),
    ("运-20 的货舱宽度是多少？", ["y20-science-parameters-v1"], "货舱宽度"),
    ("运-20 采用了几台发动机？", ["y20-science-powerplant-v1"], "发动机数量"),
    ("直-20 的最大起飞重量是多少？", ["z20-science-parameters-v1"], "MTOW"),
    ("直-20 装备了什么飞控系统？", ["z20-science-fly-by-wire-v1"], "电传飞控"),
    ("运-20 的机翼是什么结构？", ["y20-science-high-wing-v1"], "上单翼"),
], 1):
    add_q("A%02d" % _i, "accuracy", _qtext, _g, "hit", "带型号查询(预期命中，受 aircraft 大小写过滤影响)：" + _n)

# --- A21-A35：不带型号查询 ---
_A21 = [
    ("超临界翼型的阻力发散马赫数是多少？", ["c919-science-supercritical-airfoil-physics-v1"], "含「阻力」别名→concept过滤"),
    ("激波会产生哪些不利影响？", ["c919-science-supercritical-airfoil-physics-v1"], "无别名过滤"),
    ("电传操纵相比机械操纵有什么优势？", ["c919-science-fly-by-wire-v1"], "无别名过滤"),
    ("涡扇发动机的推力是怎么产生的？", ["c919-science-engine-principle-v1"], "含「发动机」别名→engine过滤"),
    ("翼尖小翼有什么作用？", ["c919-science-drag-breakdown-v1"], "含「翼」别名→wing过滤"),
    ("铝锂合金相比普通铝合金有什么优势？", ["c919-science-aluminum-lithium-v1"], "无别名过滤"),
    ("复合材料有什么特点？", ["c919-science-composite-material-v1"], "无别名过滤"),
    ("什么是飞行包线？", ["j20-science-flight-envelope-v1"], "无别名过滤"),
    ("有源相控阵雷达的工作原理是什么？", ["j20-science-aesa-principle-v1"], "无别名过滤"),
    ("鸭式布局的优势有哪些？", ["j20-science-canard-aero-v1"], "无别名过滤"),
    ("红外隐身的基本原理是什么？", ["j20-science-ir-stealth-v1"], "无别名过滤"),
    ("什么是第五代战斗机？", ["j20-science-5th-gen-definition-v1"], "无别名过滤"),
    ("后掠翼有什么作用？", ["y20-science-sweep-wing-v1"], "无别名过滤"),
    ("旋翼是怎么产生升力的？", ["z20-science-rotor-lift-v1"], "无别名过滤"),
    ("涡升力是什么？", ["j20-science-vortex-lift-v1"], "无别名过滤"),
]
for _i, (_qtext, _g, _n) in enumerate(_A21, 21):
    add_q("A%02d" % _i, "accuracy", _qtext, _g, "hit", _n)

# ============ B. 相关性排序（20） ============
_B = [
    ("激波对翼型性能有哪些影响？", ["c919-science-supercritical-airfoil-physics-v1"]),
    ("超临界翼型相比常规翼型有什么改进？", ["c919-science-supercritical-airfoil-physics-v1"]),
    ("复合材料在航空中有哪些应用？", ["c919-science-composite-material-v1", "c919-science-composite-mechanics-v1"]),
    ("电传飞控的余度设计是怎样的？", ["c919-science-fbw-redundancy-v1", "c919-science-fly-by-wire-v1"]),
    ("航电系统的 IMA 架构是什么？", ["c919-science-avionics-ima-v1"]),
    ("液压系统的余度设计有什么特点？", ["c919-science-hydraulic-system-details-v1"]),
    ("燃油系统的惰性气体系统是做什么的？", ["c919-science-fuel-system-details-v1"]),
    ("起落架的刹车系统由谁提供？", ["c919-science-landing-gear-details-v1"]),
    ("飞机的防冰系统有哪些类型？", ["c919-science-ice-protection-v1"]),
    ("适航取证有哪些符合性方法？", ["c919-science-airworthiness-process-v1"]),
    ("复合材料力学的各向异性是什么？", ["c919-science-composite-mechanics-v1"]),
    ("疲劳与损伤容限设计的原则是什么？", ["c919-science-fatigue-damage-tolerance-v1"]),
    ("电传操纵的控制律有哪些模式？", ["c919-science-fbw-control-laws-v1"]),
    ("S-N 曲线描述的是什么？", ["c919-science-fatigue-damage-tolerance-v1"]),
    ("高速飞行中的气动加热是怎么产生的？", ["j20-science-aerodynamic-heating-v1"]),
    ("弹射座椅的作用是什么？", ["j20-science-ejection-seat-v1"]),
    ("机载数据链的主要功能是什么？", ["j20-science-data-link-v1", "j20-science-datalink-sys-v1"]),
    ("光电瞄准系统用于完成什么任务？", ["j20-science-eots-v1"]),
    ("空中受油系统有什么作用？", ["j20-science-aerial-refueling-v1"]),
    ("大迎角机动的基本原理是什么？", ["j20-science-high-aoa-v1", "j20-science-vortex-lift-v1"]),
]
for _i, (_qtext, _g) in enumerate(_B, 1):
    add_q("B%02d" % _i, "ranking", _qtext, _g, "hit", "多候选择优，测排序/MRR")

# ============ C. 生成-源文档一致性（20） ============
_C = [
    ("C919 的巡航马赫数是多少？", ["c919-science-parameters-v1", "c919-science-specifications-summary-v1"], "hit", "有据可答"),
    ("C919 超临界翼型的阻力发散马赫数是多少？", ["c919-science-supercritical-airfoil-physics-v1"], "hit", "有据可答"),
    ("激波会产生哪些不利影响？", ["c919-science-supercritical-airfoil-physics-v1"], "hit", "有据可答"),
    ("C919 发动机的推力数值是多少？", ["c919-science-engine-bypass-ratio-v1", "c919-science-parameters-v1"], "hit", "参数表"),
    ("C919 的座位数是多少？", ["c919-science-parameters-v1"], "hit", "参数表"),
    ("波音 737 的最大巡航速度是多少？", [], "weak", "域外，应拒绝编造"),
    ("歼-20 的单价是多少？", [], "weak", "未公开，应拒绝"),
    ("C919 油箱的容量是多少升？", ["c919-science-fuel-system-details-v1"], "hit", "参数"),
    ("空客 A380 的翼展是多少？", [], "weak", "域外"),
    ("长征五号火箭的推力是多少？", [], "weak", "域外"),
    ("运-20 的最大载重是多少？", ["y20-science-parameters-v1"], "hit", "参数"),
    ("直-20 的巡航速度是多少？", ["z20-science-parameters-v1"], "hit", "参数"),
    ("C919 的单价是多少？", ["c919-science-commercial-value-v1"], "weak", "可能未定价"),
    ("涡扇发动机的涵道比越大越好吗？", ["c919-science-engine-bypass-ratio-v1"], "hit", "原理权衡"),
    ("C919 采用了钛合金材料吗？", ["c919-science-composite-material-v1", "c919-science-fuselage-structure-details-v1"], "hit", "材料"),
    ("波音 747 是双层客机吗？", [], "weak", "域外"),
    ("C919 的失速速度是多少？", ["c919-science-stall-speed-margin-v1"], "hit", "参数"),
    ("运-8 的用途是什么？", ["y20-science-vs-y8-y9-v1"], "hit", "对比来源"),
    ("C919 有哪些国际合作？", ["c919-science-international-cooperation-v1"], "hit", "合作"),
    ("什么是马赫数？", ["c919-science-mach-number-effects-v1"], "hit", "定义"),
]
for _i, (_qtext, _g, _exp, _n) in enumerate(_C, 1):
    add_q("C%02d" % _i, "consistency", _qtext, _g, _exp, _n)

# ============ D. 复杂查询（15） ============
_D = [
    ("升力与推力有什么区别？", ["c919-science-engine-principle-v1", "c919-science-supercritical-airfoil-physics-v1"], "对比"),
    ("C919 与空客 A320 相比有哪些差异？", ["c919-science-vs-a320-v1", "c919-science-vs-a320-range-payload-v1"], "对比+型号"),
    ("C919 的航程与 A320 相比如何？", ["c919-science-vs-a320-range-payload-v1", "c919-science-vs-a320-v1"], "对比+型号"),
    ("为什么超临界翼型能降低巡航阻力？", ["c919-science-supercritical-airfoil-physics-v1"], "因果"),
    ("C919 的电传飞控与普通飞控有什么区别？", ["c919-science-fbw-control-laws-v1", "c919-science-fly-by-wire-v1"], "对比+型号"),
    ("运-20 与伊尔-76 哪个载重更大？", ["y20-science-vs-il76-v1"], "对比+型号"),
    ("C919 国产化率与制造工艺有什么关系？", ["c919-science-localization-rate-v1", "c919-science-manufacturing-v1"], "跨源聚合"),
    ("C919 的超临界机翼设计经历了哪些风洞试验？", ["c919-science-aero-windtunnel-v1"], "过程性"),
    ("歼-20 的隐身设计与发动机有什么关系？", ["j20-science-em-stealth-v1", "j20-science-engine-nozzle-v1"], "跨源"),
    ("C919 的复合材料占比是多少？", ["c919-science-specifications-summary-v1", "c919-science-composite-material-v1"], "参数聚合"),
    ("C919 的首飞与取证相隔了多久？", ["c919-science-history-2017-first-flight-v1", "c919-science-certification-v1"], "跨源时程"),
    ("C919 适航取证需要满足哪些规章？", ["c919-science-certification-basis-v1"], "多规章"),
    ("直-20 与 UH-60 的定位有什么不同？", ["z20-science-vs-uh60-v1"], "对比+型号"),
    ("涵道比越大，燃油效率越高吗？", ["c919-science-engine-bypass-ratio-v1"], "原理权衡"),
    ("C919 的哪些部件由合资企业供应？", ["c919-science-supplier-system-v1"], "跨源列举"),
]
for _i, (_qtext, _g, _n) in enumerate(_D, 1):
    add_q("D%02d" % _i, "complex", _qtext, _g, "hit", _n)

# ============ E. 鲁棒性与边界（10） ============
_E = [
    ("波音 737 的维修步骤是什么？", [], "weak", "域外+安全敏感"),
    ("升力", ["c919-science-supercritical-airfoil-physics-v1", "c919-science-lift-coefficient-aoa-v1"], "hit", "歧义单概念"),
    ("？？？", [], "weak", "乱码"),
    ("", [], "weak", "空查询"),
    ("C919 怎么进行故障处置？", [], "weak", "安全敏感"),
    ("C919 的飞机翅膀有多长？", ["c919-science-parameters-v1"], "hit", "同义替换"),
    ("请简单一点解释超临界机翼", ["c919-science-supercritical-airfoil-physics-v1"], "hit", "反馈意图 SIMPLIFY"),
    ("你刚才说错了，涵道比不是这个数", ["c919-science-engine-bypass-ratio-v1"], "weak", "反馈 FACT_CHALLENGE"),
    ("C919 与 A320 哪个更好？", ["c919-science-vs-a320-v1"], "hit", "比较需澄清"),
    ("什么是？？", [], "weak", "残缺疑问"),
]
for _i, (_qtext, _g, _exp, _n) in enumerate(_E, 1):
    add_q("E%02d" % _i, "robustness", _qtext, _g, _exp, _n)

assert len(BANK) == 100, len(BANK)

# ---------------------------------------------------------------------------
# 提取与指标
# ---------------------------------------------------------------------------

def _controller_trace(pkg: Any) -> dict[str, Any]:
    return next((e for e in pkg.audit_trace if e.get("stage") == "retrieval_controller"), {})


def _gate_trace(pkg: Any) -> dict[str, Any]:
    return next((e for e in pkg.audit_trace if e.get("stage") == "evidence_relevance_gate"), {})


def _ndcg_at(ranked: list[str], gold: set[str], k: int) -> float:
    """nDCG@k for binary source-level relevance.

    A ranked source contributes ``1 / log2(i + 2)`` when it is in *gold*;
    the ideal ordering (all gold sources first) normalises the score so a
    perfect ranking yields ``1.0``.  Returns NaN when there is no gold.

    Duplicate source_ids (multiple chunks of the same source) count only
    once: the first hit contributes, later duplicates are ignored, so the
    score stays within ``[0, 1]``.
    """
    if not gold:
        return float("nan")
    ideal_hits = min(len(gold), k)
    idcg = sum(1.0 / math.log2(i + 2) for i in range(ideal_hits))
    if idcg == 0.0:
        return 0.0
    seen: set[str] = set()
    dcg = 0.0
    for i, source_id in enumerate(ranked[:k]):
        if source_id in gold and source_id not in seen:
            seen.add(source_id)
            dcg += 1.0 / math.log2(i + 2)
    return dcg / idcg


# --channel 消融映射：bm25 -> keyword（BM25 检索通道）
CHANNEL_OVERRIDES: dict[str, list[str] | None] = {
    "all": None,
    "bm25": ["keyword"],
    "dense": ["dense"],
}


def run_one(controller: RetrievalController, item: dict[str, Any], *,
            relax_filters: bool = False,
            channels: list[str] | None = None) -> dict[str, Any]:
    meta = {k: item[k] for k in ("id", "query", "dimension", "expect", "note", "gold")}
    try:
        qo = understand_query(item["query"])
        plan = controller.plan_retrieval(qo)
        if channels is not None:
            plan = dataclasses.replace(plan, channels=list(channels))
        if relax_filters:
            plan = dataclasses.replace(
                plan,
                filters={"allowed_review_status": list(plan.filters.get("allowed_review_status", ["reviewed"]))},
            )
        started = perf_counter()
        try:
            pkg = controller.retrieve_evidence(plan)
        except Exception as exc:  # noqa: BLE001
            return {**meta, "error": f"{type(exc).__name__}: {exc}", "latency_ms": 0,
                    "filters": dict(plan.filters), "channels": list(plan.channels)}
    except Exception as exc:  # noqa: BLE001 - 理解层异常（如空查询）也作为鲁棒性用例记录
        return {**meta, "error": f"{type(exc).__name__}: {exc}", "latency_ms": 0,
                "filters": {}, "channels": [], "understanding_error": True}
    latency_ms = (perf_counter() - started) * 1000.0
    ctr = _controller_trace(pkg)
    gtr = _gate_trace(pkg)
    exec_audit = {name: dict(a) for name, a in ctr.get("execution_audit", {}).items()}
    embedding = dict(ctr.get("embedding", {}))
    qualified = gtr.get("qualified_candidates", [])
    rejected = gtr.get("rejected_candidates", [])
    fused_sources = {str(c.get("source_id")) for c in [*qualified, *rejected] if c.get("source_id")}
    fused_ids = [str(c.get("chunk_id")) for c in [*qualified, *rejected] if c.get("chunk_id")]
    qualified_sources = {str(c.get("source_id")) for c in qualified if c.get("source_id")}
    ev = []
    for rank, it in enumerate(pkg.evidence_items, 1):
        md = it.metadata or {}
        ev.append({
            "evidence_id": it.evidence_id, "source_id": it.source_id,
            "rank": rank, "retrieval_score": md.get("retrieval_score"),
            "channels": md.get("retrieval_channels") or [],
        })
    ev_sources = [e["source_id"] for e in ev]
    gold = set(item["gold"])
    # R0/T4-3.10b: expand document-level gold into chunk-level gold via the
    # repository (a gold source is considered covered when ANY of its chunks
    # is retrieved).  Missing sources are ignored defensively.
    gold_chunk_ids: set[str] = set()
    for source_id in gold:
        try:
            chunks = controller.repository.list_chunks(source_id=source_id)
        except Exception:  # noqa: BLE001 - 数据缺失时降级为文档级口径
            chunks = []
        gold_chunk_ids.update(chunk.chunk_id for chunk in chunks)
    chunk_recall = (
        1.0 if (gold_chunk_ids and gold_chunk_ids & set(fused_ids)) else 0.0
    )
    qualified_recall = 1.0 if (gold and gold & qualified_sources) else 0.0
    def recall_at(k: int) -> float:
        if not gold:
            return float("nan")
        top = ev_sources[:k] if k < 0 else ev_sources[:k]
        return 1.0 if gold & set(top) else 0.0
    mrrs = []
    for r, e in enumerate(ev, 1):
        if e["source_id"] in gold:
            mrrs.append(1.0 / r)
            break
    mrr = mrrs[0] if mrrs else 0.0
    top5 = ev_sources[:5]
    precision5 = (len(gold & set(top5)) / 5.0) if top5 else 0.0
    ndcg5 = _ndcg_at(ev_sources, gold, 5)
    ndcg10 = _ndcg_at(ev_sources, gold, 10)
    return {
        "id": item["id"], "query": item["query"], "dimension": item["dimension"],
        "expect": item["expect"], "note": item["note"], "gold": list(gold),
        "intent": qo.intent_type, "complexity": str(plan.complexity_level),
        "channels": list(plan.channels), "filters": dict(plan.filters),
        "gate": str(pkg.gate_status), "missing": list(pkg.missing_evidence),
        "latency_ms": round(latency_ms, 1),
        "fused_sources": sorted(fused_sources), "fused_count": len(fused_sources),
        "fused_recall": 1.0 if (gold and gold & fused_sources) else 0.0,
        "qualified_recall": qualified_recall,
        "chunk_recall": chunk_recall,
        "gold_chunk_count": len(gold_chunk_ids),
        "recall@1": recall_at(1), "recall@3": recall_at(3),
        "recall@5": recall_at(5), "recall@10": recall_at(10),
        "recall@all": recall_at(-1) if gold else float("nan"),
        "context_recall": recall_at(-1) if gold else float("nan"),
        "mrr": round(mrr, 4), "precision@5": round(precision5, 4),
        "ndcg@5": round(ndcg5, 4), "ndcg@10": round(ndcg10, 4),
        "evidence": ev, "execution_audit": exec_audit,
        "embedding": embedding,
        "filter_blocked": bool(
            gold and not (gold & fused_sources)
            and any(plan.filters.get(k) for k in ("aircraft", "component", "concept"))
        ),
    }


def _metrics(rows: list[dict[str, Any]], *, gold_only: bool = True) -> dict[str, Any]:
    errs = [r for r in rows if "error" in r]
    rows = [r for r in rows if "error" not in r]
    if not rows:
        return {"n": len(rows), "error_count": len(errs)}
    n = len(rows)
    # weak 题（gold 为空）不参与 fused_recall/recall@k/mrr/precision@5 计算（gold_only=True，
    # T17 起新口径）：它们无黄金来源，fused_recall/mrr/precision@5 恒 0 会拉低均值，且
    # fused_recall 数学上限被锁死在 weak 占比之下。weak 题的鲁棒性由 error_count/gate_dist 等承载。
    # gold_only=False（--denominator 100，旧口径）时召回/排序指标对全量题取均值，
    # weak 题以 0 值参与平均。
    gold_rows = [r for r in rows if r.get("gold")]
    weak_count = n - len(gold_rows)
    recall_pool = gold_rows if gold_only else rows
    def avg(field: str) -> float:
        pool = recall_pool
        vals = []
        for r in pool:
            v = r.get(field)
            if v is None:
                continue
            if v != v:  # NaN：无 gold 的 recall@k 本身无定义
                if gold_only:
                    continue  # 新口径：仅 gold 题参与，NaN 不计
                v = 0.0  # 旧口径：weak 题按 0 参与全量平均
            vals.append(v)
        return round(sum(vals) / len(vals), 4) if vals else 0.0
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
        # 召回/排序指标：分母口径由 recall_pool 控制
        # （gold_only=True 仅 gold 题；gold_only=False 全量题、weak 按 0 参与平均）
        "fused_recall": avg("fused_recall"),
        "qualified_recall": avg("qualified_recall"),
        "chunk_recall": avg("chunk_recall"),
        "recall@1": avg("recall@1"),
        "recall@3": avg("recall@3"),
        "recall@5": avg("recall@5"),
        "recall@10": avg("recall@10"),
        "context_recall": avg("context_recall"),
        "mrr": avg("mrr"),
        "precision@5": avg("precision@5"),
        "ndcg@5": avg("ndcg@5"),
        "ndcg@10": avg("ndcg@10"),
        "filter_blocked": sum(1 for r in rows if r.get("filter_blocked")),
        "gate_dist": gates,
        "latency_ms_mean": round(sum(lat) / len(lat), 1) if lat else 0.0,
        "latency_ms_p50": pct(0.50), "latency_ms_p95": pct(0.95), "latency_ms_max": max(lat) if lat else 0.0,
        "embedding_mock": sum(1 for r in rows if r.get("embedding", {}).get("embedding_is_mock") is True),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default=None, help="A|B|C|D|E 只跑某维度")
    parser.add_argument("--gen-sample", action="store_true", help="对 3 题跑真实 DeepSeek 生成一致性")
    parser.add_argument("--denominator", type=int, choices=(100, 90), default=90,
                        help="召回/排序指标的分母口径：90=仅 gold 非空题（新口径），100=全量题（旧口径）")
    parser.add_argument("--channel", choices=("all", "bm25", "dense"), default="all",
                        help="通道消融：all=按计划全通道，bm25=仅 keyword(BM25)，dense=仅 dense")
    parser.add_argument("--output", default="tmp/rag_bench_results.json", help="结果 JSON 输出路径（相对项目根）")
    args = parser.parse_args()

    bank = [b for b in BANK if args.only is None or b["id"].startswith(args.only)]
    if not bank:
        print("empty bank"); return
    channels = CHANNEL_OVERRIDES[args.channel]

    t_start = perf_counter()
    controller = RetrievalController.from_config("configs/rag.yaml", profile="offline")

    # 包一层 embed 计数（向量库与控制器共享同一 provider 实例）
    embed_counter = {"n": 0, "lat": []}
    orig_embed = controller.embedding_provider.embed
    def counted(texts: list[str]):
        t0 = perf_counter()
        r = orig_embed(texts)
        embed_counter["n"] += 1
        embed_counter["lat"].append((perf_counter() - t0) * 1000.0)
        return r
    controller.embedding_provider.embed = counted  # type: ignore[method-assign]

    rows: list[dict[str, Any]] = []
    diag_rows: list[dict[str, Any]] = []
    for idx, item in enumerate(bank, 1):
        rows.append(run_one(controller, item, channels=channels))
        diag_rows.append(run_one(controller, item, relax_filters=True, channels=channels))
        if idx % 20 == 0:
            print(f"  progress {idx}/{len(bank)}", flush=True)

    # cache 演示：重复查询两次
    qo = understand_query("激波会产生哪些不利影响")
    plan = controller.plan_retrieval(qo)
    p1 = controller.retrieve_evidence(plan)
    c_before = (controller.cache_hits, controller.cache_misses)
    p2 = controller.retrieve_evidence(plan)
    c_after = (controller.cache_hits, controller.cache_misses)

    db_size = Path("data/processed/knowledge.sqlite3").stat().st_size
    try:
        import psutil  # type: ignore
        rss = psutil.Process().memory_info().rss
    except Exception:
        rss = None

    out = PROJECT_ROOT / args.output
    out.parent.mkdir(parents=True, exist_ok=True)
    try:
        gold_only = args.denominator == 90
        overall = _metrics(rows, gold_only=gold_only)
        diag_overall = _metrics(diag_rows, gold_only=gold_only)
        per_dim = {d: _metrics([r for r in rows if r["dimension"] == d], gold_only=gold_only)
                   for d in ("accuracy", "ranking", "consistency", "complex", "robustness")}
    except Exception as exc:  # noqa: BLE001 - 兜底保存原始逐题数据
        dump = {"error": f"{type(exc).__name__}: {exc}", "cases": rows, "diagnostic_cases": diag_rows}
        out.write_text(json.dumps(dump, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        print("聚合失败，已保存原始逐题数据:", out, exc)
        raise

    # 过滤器导致的召回损失（同一问题 as-is vs relaxed）
    filter_loss = 0
    filter_loss_ids: list[str] = []
    for r, d in zip(rows, diag_rows):
        if "error" in r or "error" in d:
            continue
        if r.get("fused_recall") == 0 and d.get("fused_recall") == 1:
            filter_loss += 1
            filter_loss_ids.append(r["id"])

    # 大小写错配统计只针对有 gold 的事实题。弱证据/域外题本来就没有
    # 召回目标，不能把“无 gold”误报成 aircraft 大小写过滤阻断。
    case_block = [
        r["id"] for r in rows
        if r.get("gold")
        and r.get("filters", {}).get("aircraft")
        and r.get("fused_recall") == 0
    ]

    channel_lat: dict[str, list[float]] = {}
    for r in rows:
        for name, a in (r.get("execution_audit") or {}).items():
            if isinstance(a, dict) and "latency_ms" in a:
                channel_lat.setdefault(name, []).append(float(a["latency_ms"]))
    channel_stats = {}
    for name, lats in channel_lat.items():
        lats.sort()
        channel_stats[name] = {
            "n": len(lats), "mean_ms": round(sum(lats) / len(lats), 1),
            "p95_ms": round(lats[min(len(lats) - 1, int(0.95 * len(lats)))], 1),
            "max_ms": round(lats[-1], 1),
        }

    report = {
        "bank_total": len(BANK),
        "run_count": len(rows),
        "denominator": args.denominator,
        "channel": args.channel,
        "overall": overall,
        "diagnostic_relaxed_filters": diag_overall,
        "filter_attributable_recall_loss": {"n": filter_loss, "ids": filter_loss_ids},
        "aircraft_case_blocked_ids": case_block,
        "per_dimension": per_dim,
        "channel_latency": channel_stats,
        "embedding": {
            "provider": controller.embedding_provider.provider,
            "is_mock": getattr(controller.embedding_provider, "is_mock", None),
            "call_count": embed_counter["n"],
            "call_latency_ms_mean": round(sum(embed_counter["lat"]) / len(embed_counter["lat"]), 1) if embed_counter["lat"] else 0,
        },
        "cache": {
            "hits": controller.cache_hits, "misses": controller.cache_misses,
            "hit_rate": round(controller.cache_hits / (controller.cache_hits + controller.cache_misses), 4) if (controller.cache_hits + controller.cache_misses) else 0,
            "demo_repeat_first": {"hits": c_before[0], "misses": c_before[1]},
            "demo_repeat_second": {"hits": c_after[0], "misses": c_after[1], "same_package": p1.evidence_package_id == p2.evidence_package_id},
        },
        "resources": {
            "knowledge_db_bytes": db_size,
            "process_rss_bytes": rss,
        },
        "total_run_seconds": round(perf_counter() - t_start, 1),
        "cases": rows,
        "diagnostic_cases": diag_rows,
    }

    out = PROJECT_ROOT / args.output
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    print("=" * 64)
    print(f"执行题数: {len(rows)}/{len(BANK)}  总耗时 {report['total_run_seconds']}s  "
          f"口径=分母{args.denominator} 通道={args.channel}")
    print(f"整体  FusedRecall={overall.get('fused_recall')}  Recall@1={overall.get('recall@1')} "
          f"@3={overall.get('recall@3')} @5={overall.get('recall@5')} @10={overall.get('recall@10')}")
    print(f"       MRR={overall.get('mrr')}  Precision@5={overall.get('precision@5')}  gate分布={overall.get('gate_dist')}")
    print(f"诊断  放宽过滤后 FusedRecall={diag_overall.get('fused_recall')}  Recall@3={diag_overall.get('recall@3')}")
    print(f"过滤器致召回损失={filter_loss} 题; aircraft大小写阻断={len(case_block)} 题")
    print(f"缓存  命中={controller.cache_hits} 未命中={controller.cache_misses} 命中率={report['cache']['hit_rate']}")
    print(f"通道延迟: {json.dumps(channel_stats, ensure_ascii=False)}")
    print(f"报告已写入: {out}")

    if args.gen_sample:
        from core.settings import load_settings
        from services.deepseek_client import DeepSeekClient, DeepSeekClientConfig
        from services.model_client import ModelMessage, ModelOptions
        _run_generation_sample(rows, controller, load_settings("configs"), DeepSeekClientConfig, DeepSeekClient, ModelMessage, ModelOptions)

    controller.close()


def _run_generation_sample(rows, controller, settings, DeepSeekClientConfig, DeepSeekClient, ModelMessage, ModelOptions) -> None:
    """对 3 道 gate=confident 且黄金在顶部的题跑真实 DeepSeek 生成，检验答案是否忠于证据。"""
    import re
    try:
        config = DeepSeekClientConfig.from_settings(settings)
    except Exception as exc:  # noqa: BLE001
        print("生成样例跳过：无法构造 DeepSeek 配置", exc)
        return
    client = DeepSeekClient(config)
    options = ModelOptions(model_alias="deepseek-v4-flash", timeout_seconds=40, max_retries=1, temperature=0.2)
    picked = [r for r in rows if r.get("gate") == "confident" and r.get("recall@3") == 1.0][:3]
    out = []
    for r in picked:
        ev_text = "\n".join(
            f"[{e['rank']}] ({e['source_id']}) {_evidence_text(controller, e['source_id'])[:400]}"
            for e in r["evidence"][:5]
        )
        messages = (
            ModelMessage(role="system", content="你是一名严谨的航空科普助手。请严格只依据提供的资料回答，不得引入资料外的事实；若资料不足就明确说不知道。"),
            ModelMessage(role="user", content=f"问题：{r['query']}\n\n可用资料：\n{ev_text}\n\n请用中文回答，并列出你使用到的资料编号。"),
        )
        try:
            result = client.complete(messages, options)
            answer = (result.raw_text or "")[:800]
            ground = set(re.findall(r"[0-9]+", ev_text))
            cited = set(re.findall(r"[0-9]+", answer))
            out.append({"id": r["id"], "query": r["query"], "answer": answer,
                        "error_code": result.error_code.value if result.error_code else None,
                        "latency_ms": result.latency_ms, "cites_evidence": bool(cited)})
            print(f"  [gen] {r['id']} {r['query']} -> err={result.error_code} lat={result.latency_ms}ms")
            print("       ", answer[:200].replace(chr(10), " "))
        except Exception as exc:  # noqa: BLE001
            out.append({"id": r["id"], "query": r["query"], "error": str(exc)})
            print("  [gen] 失败", r["id"], exc)
    gen_path = PROJECT_ROOT / "tmp" / "rag_gen_sample.json"
    gen_path.write_text(json.dumps(out, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print("生成样例写入:", gen_path)


def _evidence_text(controller: RetrievalController, source_id: str) -> str:
    for chunk in controller.repository.list_chunks(source_id=source_id):
        if chunk.text and len(chunk.text) > 80:
            return chunk.text
    return ""


if __name__ == "__main__":
    main()
