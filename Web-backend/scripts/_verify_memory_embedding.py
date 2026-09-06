"""One-shot verification: confirm memory controller resolves bge_m3 provider."""
from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = PROJECT_ROOT / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from memory.controller import MemoryController
from services.embedding_provider import BGE_M3_Provider

CONFIG_PATH = str(PROJECT_ROOT / "configs" / "memory.yaml")


def main() -> int:
    controller = MemoryController.from_config(CONFIG_PATH)
    try:
        provider_name = controller.config.semantic_index_embedding_provider
        provider = controller.embedding_provider
        print(f"[memory] semantic_index_embedding_provider (resolved): {provider_name}")
        print(f"[memory] embedding_provider class: {type(provider).__name__}")
        print(f"[memory] embedding_provider.provider: {getattr(provider, 'provider', '?')}")
        print(f"[memory] embedding_provider.model: {getattr(provider, 'model', '?')}")
        print(f"[memory] embedding_provider.is_mock: {getattr(provider, 'is_mock', '?')}")
        is_bge = isinstance(provider, BGE_M3_Provider)
        print(f"[memory] is BGE_M3_Provider: {is_bge}")
        if not is_bge:
            print("[memory] ERROR: expected BGE_M3_Provider")
            return 1
        if provider_name != "bge_m3":
            print(f"[memory] ERROR: expected resolved name 'bge_m3', got {provider_name!r}")
            return 1
        print("[memory] OK: memory system embedding is local BGE-M3")
        return 0
    finally:
        controller.repository.close()


if __name__ == "__main__":
    raise SystemExit(main())
