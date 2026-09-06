from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from app.api.schemas import TextQueryRequest
from memory.controller import MemoryController
from observability.trace_exporter import TraceReportExporter
from services.app_pipeline import AppPipeline


def export_trace(
    run_id: str,
    *,
    rag_config: str = "configs/rag.yaml",
    memory_config: str = "configs/memory.yaml",
) -> Path:
    memory = MemoryController.from_config(memory_config)
    with AppPipeline(rag_config_path=rag_config, memory_controller=memory) as pipeline:
        response = pipeline.run_text_query(
            TextQueryRequest(query="解释一下升力", run_id=run_id)
        )
    output_path = PROJECT_ROOT / "docs" / "评测与验收" / "追踪报告" / f"{run_id}.md"
    return TraceReportExporter().export_markdown(response.trace, output_path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Export a markdown trace report for an offline mock run.")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--rag-config", default="configs/rag.yaml")
    parser.add_argument("--memory-config", default="configs/memory.yaml")
    args = parser.parse_args(argv)
    path = export_trace(
        args.run_id,
        rag_config=args.rag_config,
        memory_config=args.memory_config,
    )
    print(json.dumps({"run_id": args.run_id, "path": str(path), "mock_offline": True}, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
