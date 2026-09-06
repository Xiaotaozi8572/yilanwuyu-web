#!/usr/bin/env python3
"""离线 mock demo 兜底脚本（T14）：一条命令启动的离线演示。

- 不读任何 API 密钥环境变量（本脚本不出现 os.environ 读密钥的逻辑）。
- 强制全 mock：LLM = MockModelClient（providers.llm.enabled=False）、
  Embedding = MockEmbeddingProvider（RetrievalController.for_offline_test）。
  文本演示路径不加载语音模块，ASR/TTS 不涉及模型下载与网络。
- 演示三个案例：
  ① 正常事实问答：证据链 + 引用（source_binding）输出；
  ② 证据不足问题触发拒答：知识库无对应证据时澄清而非编造；
  ③ 用户反馈"简单点"触发 SIMPLIFY 改写（feedback 层公开接口）。
- 依赖：仅 Python 标准库 + 项目 src 现有 mock 能力。

用法（项目根目录）：
    .venv-review/Scripts/python.exe scripts/demo_offline.py
    # 或显式清空密钥后运行：
    set DEEPSEEK_API_KEY= && set DASHSCOPE_API_KEY= && set NVIDIA_API_KEY= \\
    .venv-review/Scripts/python.exe scripts/demo_offline.py
"""
from __future__ import annotations

from dataclasses import replace
from pathlib import Path
import os
import sys
import tempfile

PROJECT_ROOT = Path(__file__).resolve().parents[1]
# 兜底演示必须可从任意工作目录启动；chdir 只改进程目录，不读任何环境变量。
os.chdir(PROJECT_ROOT)
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from observability.logging_config import configure_logging

# 结构化日志统一走 stderr，demo 输出（阶段轨迹/证据 ID）走 stdout。
configure_logging()

from app.api.schemas import TextQueryRequest
from core.actions import ActionDecision
from core.contracts import FeedbackEvent
from core.runtime_settings import RuntimeSettings
from core.settings import load_settings
from feedback.evidence_lock import build_evidence_lock
from feedback.parser import parse_feedback
from feedback.rewrite_planner import plan_rewrite
from feedback.rewriter import rewrite_answer
from knowledge.retrieval_controller import RetrievalController
from knowledge.schemas import ReviewStatus, SourceRecord, TextChunk
from services.app_pipeline import AppPipeline

# 阶段轨迹 -> 演示口径的阶段名（检索→证据门→自检→产出）。
_STAGE_LABELS = {
    "PROMPT_ROUTED": "路由",
    "MEMORY_CONTEXT_READY": "记忆",
    "RETRIEVAL_PLANNED": "检索",
    "EVIDENCE_READY": "证据门",
    "ANSWER_DRAFTED": "草稿",
    "SELF_CHECKED": "自检",
    "FINAL_READY": "产出",
    "FEEDBACK_RECEIVED": "反馈",
    "REWRITE_PLANNED": "改写规划",
}

# 离线知识种子：两条已审核来源（升力 / 喷气发动机），内容为航空科普常识。
_SEED_SOURCES = (
    (
        "src_demo_lift",
        "Reviewed wing lift note",
        "机翼",
        "升力",
        (
            "机翼通过翼型和迎角改变周围气流压力分布，从而产生升力。"
            "机翼上下表面的压强差形成向上的气动力。",
            "机翼使气流向下偏转，飞机受到向上的反作用力。"
            "升力的大小与飞行速度、机翼面积和空气密度有关。",
            "巡航阶段机翼产生的升力与飞机重力平衡，飞机保持稳定平飞。",
        ),
    ),
    (
        "src_demo_engine",
        "Reviewed jet engine note",
        "发动机",
        "推力",
        (
            "喷气发动机吸入空气、压缩、燃烧后高速喷出燃气，形成向前的推力。",
            "涡扇发动机由风扇、压气机、燃烧室、涡轮和喷管组成。",
        ),
    ),
)


def _offline_runtime_settings(tmp_dir: Path) -> RuntimeSettings:
    """真实配置加载 + 强制 mock：LLM 禁用（ModelFactory 自动给 MockModelClient）。"""
    settings = RuntimeSettings.from_settings(load_settings("configs"))
    return replace(
        settings,
        model=replace(settings.model, enabled=False),
        langgraph=replace(
            settings.langgraph,
            checkpoint_path=str(tmp_dir / "demo_checkpoints.sqlite3"),
        ),
    )


def _seed_offline_knowledge(controller: RetrievalController) -> list[str]:
    """用公开 API 向离线（内存）知识库写入已审核证据，返回 chunk 列表。

    与 tests/helpers/knowledge_seed.py 同一套组合：register_source +
    repository.write_chunks + keyword_index.add_many + vector_store.upsert，
    全部为 src 公开接口。
    """
    chunks: list[TextChunk] = []
    for source_id, title, component, concept, texts in _SEED_SOURCES:
        controller.source_registry.register_source(
            SourceRecord(
                source_id=source_id,
                title=title,
                source_type="text",
                authority_level="project_reviewed",
                review_status=ReviewStatus.REVIEWED,
            )
        )
        for order, text in enumerate(texts, start=1):
            chunks.append(
                TextChunk(
                    source_id=source_id,
                    text=text,
                    chunk_order=order,
                    component=component,
                    concept=concept,
                    knowledge_type="explanation",
                    review_status=ReviewStatus.REVIEWED,
                )
            )
    embedding_result = controller.embedding_provider.embed(
        [chunk.text for chunk in chunks]
    )
    controller.repository.write_chunks(chunks)
    controller.keyword_index.add_many(chunks)
    controller.vector_store.upsert(chunks, embedding_result.vectors)
    return [chunk.chunk_id for chunk in chunks]


def _stage_trail(trace: dict) -> str:
    """把 trace.transitions 折叠成 检索→证据门→自检→产出 口径的轨迹串。"""
    trail: list[str] = []
    for entry in trace.get("transitions", []):
        to_state = entry.get("to_state")
        label = _STAGE_LABELS.get(to_state, to_state)
        reason = entry.get("reason") or ""
        if not trail or trail[-1] != label:
            trail.append(label)
        else:
            continue
        if label == "证据门":
            # 证据门状态在 retrieval audit 里以 gate_status 呈现。
            gate = (
                trace.get("rag_audit_summary", {}).get("gate_status")
                or trace.get("rag_audit_summary", {}).get("evidence_gate_status")
            )
            if gate:
                trail[-1] = f"证据门({gate})"
    # 若有循环（RETRIEVE_MORE / REWRITE_ONLY），在轨迹后标注轮次。
    rounds = []
    for entry in trace.get("transitions", []):
        decision = entry.get("action_decision")
        if decision == ActionDecision.RETRIEVE_MORE.value:
            rounds.append("补检索")
        elif decision == ActionDecision.REWRITE_ONLY.value:
            rounds.append("内部改写")
    suffix = (" [" + ",".join(rounds) + "]") if rounds else ""
    return " → ".join(trail) + suffix


def _print_case(
    index: int,
    title: str,
    query: str,
    response,
    *,
    extra: str = "",
) -> None:
    answer = response.answer or {}
    trace = response.trace or {}
    evidence_ids = trace.get("evidence_ids") or []
    print("=" * 72)
    print(f"案例{index}：{title}")
    print(f"  问题：{query}")
    print(f"  阶段轨迹：{_stage_trail(trace) or '（无阶段记录）'}")
    print(f"  动作决策：{response.action_decision or '—'}")
    print(f"  证据包 ID：{response.evidence_package_id or '—'}")
    if evidence_ids:
        print(f"  关键证据 ID：{', '.join(evidence_ids)}")
    else:
        print("  关键证据 ID：无（未检索到可用证据）")
    main_answer = answer.get("main_answer") or ""
    if main_answer:
        print(f"  回答：{main_answer}")
    source_binding = answer.get("source_binding") or {}
    if source_binding:
        print(
            "  引用输出（claim → evidence）："
            + "; ".join(f"{k}→{v}" for k, v in source_binding.items())
        )
    if extra:
        print(extra)
    print()


def _case1_answer(pipeline: AppPipeline) -> tuple[object, str]:
    response = pipeline.run_text_query(
        TextQueryRequest(run_id="demo_offline_case1", query="机翼是如何产生升力的？")
    )
    _print_case(1, "正常事实问答（证据链 + 引用输出）", "机翼是如何产生升力的？", response)
    return response, response.feedback_checkpoint_id or ""


def _case2_refuse(pipeline: AppPipeline) -> object:
    response = pipeline.run_text_query(
        TextQueryRequest(
            run_id="demo_offline_case2",
            query="C919的巡航速度是多少？",
        )
    )
    _print_case(
        2,
        "证据不足 → 拒答（澄清而非编造）",
        "C919的巡航速度是多少？",
        response,
        extra="  （知识库无该数据证据：拒绝给出编造数值，要求澄清/确认资料。）",
    )
    return response


def _case3_rewrite(pipeline: AppPipeline, checkpoint_id: str) -> object:
    checkpoint = pipeline.get_feedback_checkpoint(checkpoint_id)
    evidence_lock = build_evidence_lock(checkpoint)
    feedback_event = FeedbackEvent(
        run_id="demo_offline_case1",
        feedback_text="简单点",
        source="text",
    )
    parse_result = parse_feedback(feedback_event, checkpoint)
    rewrite_plan = plan_rewrite(parse_result, evidence_lock)
    revised = rewrite_answer(rewrite_plan, checkpoint, evidence_lock)

    print("=" * 72)
    print("案例3：用户反馈“简单点” → 触发 SIMPLIFY 改写")
    print(f"  反馈意图：{rewrite_plan.feedback_intent}")
    print(f"  改写动作：{', '.join(rewrite_plan.rewrite_actions)}")
    print(f"  锁定的证据绑定（改写后保留）：{rewrite_plan.preserved_source_binding}")
    print(f"  改写后回答：{revised.main_answer}")
    print(f"  改写后引用证据：{', '.join(revised.evidence_refs) or '（无）'}")
    print(f"  改写日志：{rewrite_plan.rewrite_log[-1]}")
    print()
    return revised


def main() -> int:
    print("=" * 72)
    print("翼览无余 AI 智能导师 — 离线 mock 演示（T14 兜底路径）")
    print("  强制全 mock：LLM=MockModelClient / Embedding=MockEmbeddingProvider")
    print("  ASR/TTS：文本演示不加载语音模块（无需模型、无需网络）")
    print("  密钥：本演示不读取任何 API 密钥环境变量，可断网运行")
    print("=" * 72)

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="demo_offline_") as tmp:
        runtime_settings = _offline_runtime_settings(Path(tmp))
        controller = RetrievalController.for_offline_test()
        try:
            seeded = _seed_offline_knowledge(controller)
            with AppPipeline(
                retrieval_controller=controller,
                runtime_settings=runtime_settings,
                entry_mode="demo",
                trace_enabled=True,
            ) as pipeline:
                print(f"  离线知识库种子：{len(seeded)} 条已审核 chunk 写入（内存）")
                print()

                # 案例1：正常事实问答。
                response1, checkpoint_id = _case1_answer(pipeline)
                trace1 = response1.trace or {}
                evidence_ids1 = trace1.get("evidence_ids") or []
                if (
                    response1.status == "ok"
                    and response1.action_decision == ActionDecision.PASS.value
                    and evidence_ids1
                    and (response1.answer or {}).get("source_binding")
                ):
                    print("  [PASS] 案例1：产出证据链并输出引用。")
                else:
                    failures.append("案例1")
                    print(f"  [FAIL] 案例1：action={response1.action_decision}")

                # 案例2：证据不足拒答。
                response2 = _case2_refuse(pipeline)
                if (
                    response2.status == "ok"
                    and response2.action_decision
                    == ActionDecision.ASK_CLARIFICATION.value
                    and (response2.trace or {}).get("evidence_ids") == []
                ):
                    print("  [PASS] 案例2：无证据时不编造，触发澄清/拒答。")
                else:
                    failures.append("案例2")
                    print(f"  [FAIL] 案例2：action={response2.action_decision}")

                # 案例3：反馈“简单点”改写。
                if checkpoint_id:
                    revised = _case3_rewrite(pipeline, checkpoint_id)
                    if (
                        revised.main_answer.startswith("Simple: ")
                        and revised.evidence_refs
                    ):
                        print("  [PASS] 案例3：SIMPLIFY 改写完成且保留证据引用。")
                    else:
                        failures.append("案例3")
                        print("  [FAIL] 案例3：改写结果异常。")
                else:
                    failures.append("案例3")
                    print("  [FAIL] 案例3：无反馈 checkpoint 可用。")

        finally:
            controller.close()

    print("=" * 72)
    if failures:
        print(f"演示结束：{len(failures)} 个案例未通过（{', '.join(failures)}）。")
        return 1
    print("演示结束：三个案例全部通过。全程离线，未读取任何密钥环境变量。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
