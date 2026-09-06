# -*- coding: utf-8 -*-
"""faithfulness 与 answer relevance 补测（T23）：LLM-as-judge 两维打分 + 证据门误拦/漏拦率。

对 scripts/_rag_bench.py 同一题库（100 题）逐题现场重跑检索（真实知识库
data/processed/knowledge.sqlite3，BGE-M3 真实向量），取证据包 top-3 内容与
问题一起交给 DeepSeek（LLM-as-judge，读 DEEPSEEK_API_KEY 环境变量）：

  1) 依据证据撰写参考答案（证据不足必须拒答，不得编造）；
  2) 对答案打两维分：
     - faithful_score（0-5）：答案是否被证据蕴含；
     - relevance_score（0-5）：答案是否直接回答用户问题；
  3) 给出 verdict（correct / wrong / unverifiable）。

随后统计：
  - 证据门四态分布（confident / weak / conflict / unclear）；
  - 误拦率 = P(verdict=wrong | gate=confident)（confident 但答案错）；
  - 漏拦率 = P(verdict=wrong | gate=weak)（weak 放行但答案错；证据不足时恰当拒答计 correct）；
  - 域外题（gold 为空）逐条复核，含 C16「波音 747 是双层客机吗？」类实例：
    gate=confident 但 judge 无据可答的「门误判」实例。

只读评测：不修改知识库 / 检索引擎 / 证据门，不改变业务逻辑，禁止修改证据门实现与阈值。
密钥只来自用户环境变量 DEEPSEEK_API_KEY（DeepSeekClientConfig.api_key_env 指定），
仓库内不出现密钥。

用法：
  python scripts/_rag_faithfulness.py                        # 全量 100 题
  python scripts/_rag_faithfulness.py --limit 10             # 只跑前 10 题（冒烟）
  python scripts/_rag_faithfulness.py --start 50 --limit 50  # 分批续跑
  python scripts/_rag_faithfulness.py --output tmp/rag_faithfulness_results.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[1]
for _p in (PROJECT_ROOT / "src", PROJECT_ROOT / "scripts"):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import _rag_bench  # noqa: E402 - 复用同一题库 BANK（模块导入即构建题库，与 bench 完全一致）
from core.settings import load_settings  # noqa: E402
from input.query_understanding import understand_query  # noqa: E402
from knowledge.retrieval_controller import RetrievalController  # noqa: E402
from services.deepseek_client import (  # noqa: E402
    DeepSeekClientConfig,
    DeepSeekModelClient,
)
from services.model_client import ModelMessage, ModelOptions  # noqa: E402

BANK = _rag_bench.BANK

JUDGE_SYSTEM = (
    "你是一名严格的评测裁判（LLM-as-judge），负责评估「检索增强生成」系统的答案质量。"
    "你的判断必须只依据给定证据材料，不引入任何外部常识（除了判断证据是否足以作答外）。"
)

JUDGE_USER = """请完成两步任务：

【第 1 步】依据下方「证据材料」撰写一段参考答案（中文，50-200 字）。
规则：
- 只能使用证据材料中出现的事实，不得引入证据外的事实，不得编造。
- 如果证据材料不足以回答用户问题，请直接回答「证据不足，无法回答」，这是正确行为，不要编造。

【第 2 步】对参考答案打两维分并给出定性结论：
- faithful_score（0-5）：答案中的事实性陈述是否全部被证据材料蕴含。
  5=全部陈述都能在证据中找到依据；4=基本有据、仅措辞差异；3=大部分有据但存在轻微外推；
  2=约一半无据或与证据矛盾；1=大部分无据或与证据矛盾；0=完全编造。
- relevance_score（0-5）：答案是否直接、完整地回答了用户问题。
  5=直接完整回答；4=直接回答但略有省略；3=部分回答；2=只答边角；1=答非所问；0=完全无关。
  若因证据不足而拒答：当证据确实不足时给 4-5 分（该场景下拒答恰当）；当证据其实充足却拒答时给 0-1 分。
- verdict：三选一。
  correct=答案忠实于证据且正确回答了问题（或证据确实不足时恰当拒答）；
  wrong=答案存在编造、与证据矛盾、误导或答非所问（含证据充足却拒答）；
  unverifiable=证据材料完全为空、无法作答也无法判定。

只输出一个 JSON 对象，不要输出任何其他文字：
{{"answer": "参考答案全文", "faithful_score": 0, "relevance_score": 0,
 "verdict": "correct|wrong|unverifiable", "faithful_reason": "一句话依据",
 "relevance_reason": "一句话依据"}}

用户问题：{query}

证据材料：
{evidence}
"""

REFUSAL_MARKERS = ("证据不足", "无法回答", "无法作答")


def _retrieve_for_judge(controller: RetrievalController, query: str) -> dict[str, Any]:
    """现场重跑检索，返回 gate 状态与 top-3 证据内容（供 judge 使用）。"""
    qo = understand_query(query)
    plan = controller.plan_retrieval(qo)
    pkg = controller.retrieve_evidence(plan)
    items = []
    for rank, it in enumerate(pkg.evidence_items, 1):
        if rank > 3:
            break
        content = (it.content or "").strip()
        if not content:
            continue
        items.append({
            "n": rank,
            "source": it.source_id,
            "text": content[:600],
        })
    return {"gate": str(pkg.gate_status), "items": items}


def _judge_prompt(query: str, items: list[dict[str, Any]]) -> tuple[ModelMessage, ...]:
    if items:
        evidence_block = "\n\n".join(
            f"[{it['n']}]（来源 {it['source']}）{it['text']}" for it in items
        )
    else:
        evidence_block = "（无）——本次检索未返回任何证据材料。"
    user = JUDGE_USER.format(query=query, evidence=evidence_block)
    return (
        ModelMessage(role="system", content=JUDGE_SYSTEM),
        ModelMessage(role="user", content=user),
    )


def _parse_judge(raw_text: str) -> dict[str, Any] | None:
    """解析 judge 的 JSON 输出；先整段 json.loads，失败则抽取首个 {...} 块。"""
    text = (raw_text or "").strip()
    candidates: list[str] = []
    if text.startswith("{"):
        candidates.append(text)
    for m in re.finditer(r"\{.*\}", text, re.DOTALL):
        candidates.append(m.group(0))
    for cand in candidates:
        try:
            parsed = json.loads(cand)
        except json.JSONDecodeError:
            continue
        if not isinstance(parsed, dict):
            continue
        if "answer" not in parsed or "verdict" not in parsed:
            continue
        return parsed
    return None


def _is_refusal(answer: str) -> bool:
    return any(marker in (answer or "") for marker in REFUSAL_MARKERS)


def _judge_verdict(parsed: dict[str, Any]) -> str:
    return str(parsed.get("verdict") or "unverifiable").strip().lower()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0, help="只评测前 N 题（0=全量 100 题）")
    parser.add_argument("--start", type=int, default=0, help="从第 start 题开始（0 基）")
    parser.add_argument("--output", default="tmp/rag_faithfulness_results.json", help="结果 JSON 输出路径")
    args = parser.parse_args()

    bank = BANK[args.start:] if args.limit <= 0 else BANK[args.start:args.start + args.limit]
    if not bank:
        print("empty bank"); return

    settings = load_settings("configs")
    config = DeepSeekClientConfig.from_settings(settings)
    # 密钥从用户环境变量读取；仓库内不出现密钥。base_url/model 有配置兜底。
    api_key = config.api_key or os.environ.get(config.api_key_env, "")
    os.environ[config.api_key_env] = api_key
    os.environ.setdefault(
        config.base_url_env,
        str(settings.get("providers.llm.deepseek.base_url") or "https://api.deepseek.com"),
    )
    os.environ.setdefault(
        config.model_env,
        str(settings.get("providers.llm.deepseek.model") or config.model_id),
    )
    client = DeepSeekModelClient(config)
    options = ModelOptions(
        model_alias="deepseek-v4-flash", timeout_seconds=45, max_retries=2, temperature=0.2,
    )

    controller = RetrievalController.from_config("configs/rag.yaml", profile="offline")
    out_path = PROJECT_ROOT / args.output
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cases: list[dict[str, Any]] = []
    started = time.perf_counter()
    for idx, item in enumerate(bank, 1):
        qid = item["id"]
        rec: dict[str, Any] = {
            "id": qid, "query": item["query"], "dimension": item["dimension"],
            "expect": item["expect"], "gold": list(item["gold"]),
        }
        try:
            retrieval = _retrieve_for_judge(controller, item["query"])
        except Exception as exc:  # noqa: BLE001 - 理解层异常（空查询/乱码等）如实记录
            rec.update({"error": f"{type(exc).__name__}: {exc}"})
            cases.append(rec)
            print(f"  [{idx}/{len(bank)}] {qid} 检索失败: {exc}", flush=True)
            continue
        rec["gate"] = retrieval["gate"]
        rec["evidence_sources"] = [it["source"] for it in retrieval["items"]]
        try:
            result = client.complete(_judge_prompt(item["query"], retrieval["items"]), options)
            rec["llm_error"] = result.error_code.value if result.error_code else None
            rec["latency_ms"] = result.latency_ms
            parsed = _parse_judge(result.raw_text) if result.error_code is None else None
            if parsed is None:
                rec["judge_parse_error"] = True
                rec["raw"] = (result.raw_text or "")[:300]
            else:
                rec["answer"] = str(parsed.get("answer") or "")[:500]
                rec["faithful_score"] = parsed.get("faithful_score")
                rec["relevance_score"] = parsed.get("relevance_score")
                rec["verdict"] = _judge_verdict(parsed)
                rec["faithful_reason"] = str(parsed.get("faithful_reason") or "")[:200]
                rec["relevance_reason"] = str(parsed.get("relevance_reason") or "")[:200]
        except Exception as exc:  # noqa: BLE001 - 单题失败不影响整体
            rec["error"] = f"{type(exc).__name__}: {exc}"
        cases.append(rec)
        if idx % 10 == 0 or idx == len(bank):
            _save(out_path, bank, cases, started)
            print(f"  [{idx}/{len(bank)}] 已保存 {len(cases)} 题", flush=True)

    _save(out_path, bank, cases, started)
    summary = _summarize(cases)
    print("=" * 64)
    print(f"评测题数: {len(cases)}  总耗时 {round(time.perf_counter() - started)}s  输出: {out_path}")
    print(f"证据门四态分布: {summary['gate_dist']}")
    print(f"faithfulness 均值: 总体 {summary['overall']['faithful_mean']}  "
          f"(confident {summary['by_gate'].get('confident', {}).get('faithful_mean')} / "
          f"weak {summary['by_gate'].get('weak', {}).get('faithful_mean')})")
    print(f"relevance 均值: 总体 {summary['overall']['relevance_mean']}")
    print(f"误拦率 (confident 但答案错): {summary['wrong_rate_confident']}  "
          f"({summary['wrong_count_confident']}/{summary['confident_n']})")
    print(f"漏拦率 (weak 放行但答案错): {summary['wrong_rate_weak']}  "
          f"({summary['wrong_count_weak']}/{summary['weak_n']})")
    print(f"域外题 gate=confident 但 judge 拒答/无法作答: {len(summary['confident_but_unanswerable_ids'])} 题 "
          f"{summary['confident_but_unanswerable_ids']}")
    controller.close()


def _save(out_path: Path, bank: list[dict[str, Any]], cases: list[dict[str, Any]],
          started: float) -> None:
    out_path.write_text(json.dumps({
        "bank_total": len(bank),
        "run_count": len(cases),
        "run_seconds": round(time.perf_counter() - started, 1),
        "method_note": "LLM-as-judge(deepseek-v4-flash) 每题 1 次调用；faithful/relevance 0-5 两维打分",
        "cases": cases,
    }, ensure_ascii=False, indent=2, default=str), encoding="utf-8")


def _summarize(cases: list[dict[str, Any]]) -> dict[str, Any]:
    judged = [c for c in cases if "error" not in c and "judge_parse_error" not in c and c.get("verdict")]
    errs = [c for c in cases if "error" in c]
    parse_errs = [c for c in cases if c.get("judge_parse_error")]
    gate_dist: dict[str, int] = {}
    for c in cases:
        if c.get("gate"):
            gate_dist[c["gate"]] = gate_dist.get(c["gate"], 0) + 1

    def means(pool: list[dict[str, Any]]) -> dict[str, Any]:
        fs = [float(c["faithful_score"]) for c in pool if isinstance(c.get("faithful_score"), (int, float))]
        rs = [float(c["relevance_score"]) for c in pool if isinstance(c.get("relevance_score"), (int, float))]
        return {
            "faithful_mean": round(sum(fs) / len(fs), 4) if fs else None,
            "relevance_mean": round(sum(rs) / len(rs), 4) if rs else None,
            "correct_n": sum(1 for c in pool if c["verdict"] == "correct"),
            "wrong_n": sum(1 for c in pool if c["verdict"] == "wrong"),
            "unverifiable_n": sum(1 for c in pool if c["verdict"] == "unverifiable"),
        }

    by_gate: dict[str, dict[str, Any]] = {}
    for g in ("confident", "weak", "conflict", "unclear"):
        pool = [c for c in judged if c.get("gate") == g]
        if pool:
            by_gate[g] = means(pool)

    confident_pool = [c for c in judged if c.get("gate") == "confident"]
    weak_pool = [c for c in judged if c.get("gate") == "weak"]
    wrong_confident = sum(1 for c in confident_pool if c["verdict"] == "wrong")
    wrong_weak = sum(1 for c in weak_pool if c["verdict"] == "wrong")

    # 域外题（gold 为空）中 gate=confident 但 judge 拒答/无法作答的门误判实例
    confident_but_unanswerable = [
        c for c in judged
        if not c.get("gold") and c.get("gate") == "confident"
        and (_is_refusal(c.get("answer", "")) or c["verdict"] == "unverifiable")
    ]

    return {
        "judged_n": len(judged),
        "error_n": len(errs),
        "parse_error_n": len(parse_errs),
        "gate_dist": gate_dist,
        "overall": means(judged),
        "by_gate": by_gate,
        "confident_n": len(confident_pool),
        "weak_n": len(weak_pool),
        "wrong_count_confident": wrong_confident,
        "wrong_rate_confident": round(wrong_confident / len(confident_pool), 4) if confident_pool else None,
        "wrong_count_weak": wrong_weak,
        "wrong_rate_weak": round(wrong_weak / len(weak_pool), 4) if weak_pool else None,
        "confident_but_unanswerable_ids": [c["id"] for c in confident_but_unanswerable],
    }


if __name__ == "__main__":
    main()
