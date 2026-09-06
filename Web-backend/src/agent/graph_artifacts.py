from __future__ import annotations

from dataclasses import dataclass
from threading import RLock

from core.answer_contracts import AnswerEnvelope
from core.contracts import EvidencePackage, MemoryContext
from knowledge.prefetch import RetrievalPrefetch
from prompts.asset_models import PromptContext
from self_check.contracts import CheckReport


@dataclass
class _RunArtifacts:
    """Non-serializable values permitted to exist only for one process run."""

    memory_context: MemoryContext | None = None
    retrieval_prefetch: RetrievalPrefetch | None = None
    evidence_package: EvidencePackage | None = None
    answer_envelope: AnswerEnvelope | None = None
    check_report: CheckReport | None = None
    prompt_context: PromptContext | None = None


class EphemeralRunArtifacts:
    """Lock-protected process-memory artifact cache keyed by graph run id.

    This type deliberately has no serialization API. LangGraph state holds
    fingerprints and routing data only; private memory and evidence bodies are
    kept in this process-local cache until a node can safely rehydrate them.
    """

    def __init__(self) -> None:
        self._runs: dict[str, _RunArtifacts] = {}
        self._lock = RLock()

    def memory_context(self, run_id: str) -> MemoryContext | None:
        return self._get(run_id).memory_context

    def evidence_package(self, run_id: str) -> EvidencePackage | None:
        return self._get(run_id).evidence_package

    def retrieval_prefetch(self, run_id: str) -> RetrievalPrefetch | None:
        return self._get(run_id).retrieval_prefetch

    def answer_envelope(self, run_id: str) -> AnswerEnvelope | None:
        return self._get(run_id).answer_envelope

    def check_report(self, run_id: str) -> CheckReport | None:
        return self._get(run_id).check_report

    def prompt_context(self, run_id: str) -> PromptContext | None:
        return self._get(run_id).prompt_context

    def put_memory_context(self, run_id: str, value: MemoryContext) -> None:
        self._set(run_id, "memory_context", value, MemoryContext)

    def put_retrieval_prefetch(self, run_id: str, value: RetrievalPrefetch) -> None:
        self._set(run_id, "retrieval_prefetch", value, RetrievalPrefetch)

    def put_evidence_package(self, run_id: str, value: EvidencePackage) -> None:
        self._set(run_id, "evidence_package", value, EvidencePackage)

    def put_answer_envelope(self, run_id: str, value: AnswerEnvelope) -> None:
        self._set(run_id, "answer_envelope", value, AnswerEnvelope)

    def put_check_report(self, run_id: str, value: CheckReport) -> None:
        self._set(run_id, "check_report", value, CheckReport)

    def put_prompt_context(self, run_id: str, value: PromptContext) -> None:
        self._set(run_id, "prompt_context", value, PromptContext)

    def discard(self, run_id: str) -> None:
        with self._lock:
            self._runs.pop(run_id, None)

    def _get(self, run_id: str) -> _RunArtifacts:
        with self._lock:
            return self._runs.get(run_id, _RunArtifacts())

    def _set(self, run_id: str, field: str, value: object, expected: type[object]) -> None:
        if not isinstance(value, expected):
            raise TypeError(f"{field} must be {expected.__name__}")
        with self._lock:
            artifacts = self._runs.setdefault(run_id, _RunArtifacts())
            setattr(artifacts, field, value)
