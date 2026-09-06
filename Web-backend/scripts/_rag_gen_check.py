# -*- coding: utf-8 -*-
"""真实生成一致性抽样（v2）：对 3 道题现场重跑检索，把证据包中 top-3 证据项的**真实内容**
喂给 DeepSeek，要求仅依据证据作答并标注引用；再校验答案是否忠于证据（越界/编造检测）。"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "src"))
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from core.settings import load_settings
from input.query_understanding import understand_query
from knowledge.retrieval_controller import RetrievalController
from services.deepseek_client import DeepSeekClientConfig, DeepSeekModelClient
from services.model_client import ModelMessage, ModelOptions


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def _retrieve(controller: RetrievalController, query: str) -> list[dict]:
    qo = understand_query(query)
    plan = controller.plan_retrieval(qo)
    pkg = controller.retrieve_evidence(plan)
    items = []
    for rank, it in enumerate(pkg.evidence_items, 1):
        if rank > 5:
            break
        content = (it.content or "")[:700]
        if not content:
            continue
        items.append({"n": rank, "source": it.source_id, "text": content})
    return items


def main() -> None:
    settings = load_settings("configs")
    config = DeepSeekClientConfig.from_settings(settings)
    api_key = config.api_key or os.environ.get(config.api_key_env, "")
    os.environ[config.api_key_env] = api_key
    os.environ.setdefault(config.base_url_env, str(settings.get("providers.llm.deepseek.base_url") or "https://api.deepseek.com"))
    os.environ.setdefault(config.model_env, str(settings.get("providers.llm.deepseek.model") or config.model_id))
    client = DeepSeekModelClient(config)
    options = ModelOptions(model_alias="deepseek-v4-flash", timeout_seconds=45, max_retries=1, temperature=0.2)

    # 选 3 道覆盖不同领域的题（来源互不相同）
    questions = [
        ("A22", "激波会产生哪些不利影响？"),
        ("B06", "液压系统的余度设计有什么特点？"),
        ("A32", "什么是第五代战斗机？"),
    ]

    controller = RetrievalController.from_config("configs/rag.yaml", profile="offline")
    out = []
    for qid, query in questions:
        items = _retrieve(controller, query)
        if not items:
            print(f"[gen] {qid} 无证据可喂"); continue
        ev_text = "\n".join(f"[{x['n']}] {x['text']}" for x in items)
        messages = (
            ModelMessage(role="system", content="你是严谨的航空科普助手。只依据提供的资料作答，禁止使用资料外知识；资料未覆盖就说不知道；回答末尾用[n]列出依据的资料编号。"),
            ModelMessage(role="user", content=f"问题：{query}\n\n资料：\n{ev_text}"),
        )
        try:
            res = client.complete(messages, options)
            answer = (res.raw_text or "").strip()
            ans_norm = _norm(answer)
            cited = sorted({int(m) for m in re.findall(r"\[\s*(\d+)\s*\]", answer)})
            valid_cites = [c for c in cited if 1 <= c <= len(items)]
            evidence_all = _norm(" ".join(x["text"] for x in items))
            sentences = re.split(r"(?<=[。！？!?.])\s*", answer)
            grounded = 0
            total_sent = 0
            for s in sentences:
                core = _norm(s)
                if len(core) < 6:
                    continue
                total_sent += 1
                words = [w for w in re.split(r"[\W_]+", core) if len(w) >= 2]
                if len(words) >= 3 and sum(1 for w in words if w in evidence_all) / len(words) >= 0.6:
                    grounded += 1
            grounding = round(grounded / max(1, total_sent), 2)
            out.append({
                "id": qid, "query": query,
                "evidence_sources": [x["source"] for x in items],
                "answer": answer[:700],
                "cited": cited, "valid_cites": valid_cites,
                "grounding_ratio": grounding, "grounded_sentences": grounded, "total_sentences": total_sent,
                "error_code": res.error_code.value if res.error_code else None,
                "latency_ms": res.latency_ms,
            })
            print(f"[gen] {qid} {query} | err={res.error_code} lat={res.latency_ms}ms 引用={valid_cites} 落地率={grounding}/{total_sent}")
            print("   答:", answer.replace(chr(10), " ")[:200])
        except Exception as exc:  # noqa: BLE001
            print("[gen] 失败", qid, type(exc).__name__, exc)
            out.append({"id": qid, "query": query, "error": f"{type(exc).__name__}: {exc}"})

    controller.close()
    path = PROJECT_ROOT / "tmp" / "rag_gen_sample.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print("写入:", path)


if __name__ == "__main__":
    main()
