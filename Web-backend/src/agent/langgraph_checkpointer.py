from __future__ import annotations

import os
from pathlib import Path
import sqlite3
from threading import RLock

from langgraph.checkpoint.sqlite import SqliteSaver

from core.errors import ConfigError


def _normalized_path(path: str | Path) -> str:
    return os.path.normcase(str(Path(path).expanduser().resolve()))


class LangGraphCheckpointStore:
    """Own the dedicated SQLite connection used for graph checkpoints.

    Parameters
    ----------
    checkpoint_path:
        Path to the SQLite database file for graph checkpoints.
    rag_database_path, memory_database_path:
        Used for validation — the checkpoint path must differ from both.
    checkpoint_locks:
        Optional shared dict mapping run_id → RLock for per-run write
        serialisation.  When set, every checkpoint write acquires the
        lock for the affected run_id before touching SQLite.
    """

    def __init__(
        self,
        checkpoint_path: str | Path,
        *,
        rag_database_path: str | Path,
        memory_database_path: str | Path,
        checkpoint_locks: dict[str, RLock] | None = None,
    ) -> None:
        if not str(checkpoint_path).strip():
            raise ConfigError("langgraph.checkpoint_path", "must be a non-empty string")

        checkpoint_key = _normalized_path(checkpoint_path)
        if checkpoint_key in {
            _normalized_path(rag_database_path),
            _normalized_path(memory_database_path),
        }:
            raise ConfigError(
                "langgraph.checkpoint_path",
                "must differ from the configured RAG and memory database paths",
            )

        self.path = Path(checkpoint_key)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(self.path, check_same_thread=False)
        # WAL mode enables concurrent reads without blocking checkpoint writes (T13).
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.execute("PRAGMA synchronous=NORMAL")
        self.saver = SqliteSaver(self.connection)
        self._checkpoint_locks = checkpoint_locks
        self._closed = False

    def _run_lock(self, run_id: str) -> RLock | None:
        """Acquire the per-run checkpoint lock if a shared dict is configured."""
        if self._checkpoint_locks is None:
            return None
        # R0/T3-3: setdefault 原子创建锁，避免并发下两个线程各建一把锁。
        return self._checkpoint_locks.setdefault(run_id, RLock())

    def config_for(self, run_id: str) -> dict[str, dict[str, str]]:
        return {"configurable": {"thread_id": run_id}}

    def delete_thread(self, run_id: str) -> None:
        lock = self._run_lock(run_id)
        if lock is not None:
            with lock:
                self.saver.delete_thread(run_id)
        else:
            self.saver.delete_thread(run_id)

    def raw_storage_bytes(self) -> bytes:
        """Return raw bytes from the database and its SQLite sidecars for test scans."""
        if not self._closed:
            self.connection.commit()
        return b"".join(
            candidate.read_bytes()
            for candidate in (
                self.path,
                Path(f"{self.path}-wal"),
                Path(f"{self.path}-shm"),
            )
            if candidate.exists()
        )

    def contains_raw_storage_text(self, text: str) -> bool:
        return text.encode("utf-8") in self.raw_storage_bytes()

    def close(self) -> None:
        """Close the SQLite connection after AppPipeline has stopped using the graph."""
        if not self._closed:
            self.connection.close()
            self._closed = True
