from __future__ import annotations

from concurrent.futures import Future, ThreadPoolExecutor, TimeoutError as FutureTimeout
import hashlib
import hmac
import json
import sqlite3
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, replace
from pathlib import Path
from time import perf_counter
from threading import RLock
from typing import Any, Literal

from langgraph.graph import END, START, StateGraph

from agent.actions import AgentActionExecutor
from agent.artifact_rehydrator import ArtifactRehydrator
from agent.node_registry import NodeRegistry
from agent.nodes.context_resolution import ContextResolutionNode
from agent.nodes.finalize import FinalizeNode
from agent.nodes.generate_draft import GenerateDraftNode
from agent.nodes.retrieve_evidence import RetrieveEvidenceNode
from agent.nodes.retrieve_more import RetrieveMoreNode
from agent.nodes.rewrite import RewriteNode
from agent.nodes.self_check import SelfCheckNode
from agent.nodes.supervisor_route import SupervisorRouteNode
from core.simple_yaml import parse_simple_yaml
from observability.logging_config import get_logger

logger = get_logger(__name__)
from agent.context_resolution import (
    ConversationReferenceResolver,
    ResolvedQuery,
    SupervisorRouter,
)
from agent.graph_artifacts import EphemeralRunArtifacts
from agent.graph_contracts import GraphState, SupervisorRoute, initial_graph_state
from agent.langgraph_checkpointer import LangGraphCheckpointStore
from agent.retrieval_action import RetrievalActionService
from app.api.errors import AppError, ErrorResponse, PipelineExecutionError
from app.api.schemas import TextQueryRequest, TextQueryResponse
from core.action_contracts import AnswerLoopState, RecoveryContext
from core.actions import ActionDecision
from core.answer_contracts import AnswerType, ResponseMode
from core.contracts import EvidencePackage, MemoryContext, RunTrace, SceneState, new_id
from core.errors import ConfigError
from core.runtime_settings import RuntimeSettings
from core.state_machine import AgentState, transition
from core.tracing import TraceProjector, append_audit_event, append_error
from feedback.checkpoint_store import CheckpointStore
from generation.pipeline import AnswerGenerationPipeline, GenerationRequest
from input.query_object import QueryObject
from knowledge.evidence_package import evidence_package_fingerprint
from knowledge.prefetch import PrefetchedQueryEmbedding, RetrievalPrefetch
from knowledge.retrieval_controller import RetrievalController, retrieval_plan_fingerprint
from knowledge.schemas import QueryVariant, QueryVariantKind, RetrievalPlan
from memory.controller import MemoryController
from memory.adapters.local import LocalMemoryPort
from memory.authority_state import AuthorityMode
from memory.port import MemoryApplicationStatus, MemoryPort, MemoryReadResult
from memory.schemas import InteractionEvent, MemoryRetrievalRequest, QueryEmbedding
from memory.session_overlay import SessionMemoryOverlay
from prompts.asset_models import PromptContext
from security.session_identity import current_trusted_identity
from self_check.contracts import (
    CheckIssue,
    CheckReport,
    ClaimCompletenessResult,
    DecisionContext,
    ScoreCard,
)
from self_check.retrieval_directive import RetrievalDirectiveContext
from self_check.safety_checker import OperationalSafetyClassifier, PrePublishSafetyChecker
from self_check.service import SelfCheckService
from services.streaming_policy import ResponseStreamingPolicy


_UNSAFE_ROUTE_MARKER = "graph_input_unsafe"


@dataclass
class _RunScope:
    request: TextQueryRequest
    scene_state: SceneState | None
    trace: RunTrace
    resolution: ResolvedQuery | None = None
    unsafe: bool = False
    answer_plan: object | None = None
    feedback_checkpoint_id: str | None = None
    prefetch_future: Future[RetrievalPrefetch] | None = None
    memory_future: Future[MemoryReadResult] | None = None


class LangGraphAgentRuntime:
    """Run the text-answer workflow with a checkpointed, privacy-minimal graph.

    Graph state contains only routing, recovery, plan, and fingerprint data.
    Typed domain objects remain in ``EphemeralRunArtifacts`` and are rebuilt by
    their public owning controllers if a process-local artifact is unavailable.
    """

    def __init__(
        self,
        *,
        runtime_settings: RuntimeSettings,
        retrieval_controller: RetrievalController,
        memory_controller: MemoryController,
        generation_pipeline: AnswerGenerationPipeline,
        self_check_service: SelfCheckService,
        action_executor: AgentActionExecutor,
        retrieval_action_service: RetrievalActionService,
        checkpoint_store: CheckpointStore,
        graph_checkpoint_store: LangGraphCheckpointStore,
        memory_port: MemoryPort | None = None,
        context_resolver: ConversationReferenceResolver | None = None,
        supervisor_router: SupervisorRouter | None = None,
        entry_mode: str = "cli",
        trace_enabled: bool = True,
    ) -> None:
        self.runtime_settings = runtime_settings
        self.retrieval_controller = retrieval_controller
        self.memory_controller = memory_controller
        self.memory_port = memory_port or LocalMemoryPort(memory_controller)
        self.generation_pipeline = generation_pipeline
        self.self_check_service = self_check_service
        self.action_executor = action_executor
        self.retrieval_action_service = retrieval_action_service
        self.checkpoint_store = checkpoint_store
        self.graph_checkpoint_store = graph_checkpoint_store
        self.context_resolver = context_resolver or ConversationReferenceResolver()
        self.supervisor_router = supervisor_router or SupervisorRouter()
        self.entry_mode = entry_mode
        self.trace_enabled = trace_enabled
        self._memory_read_timeout_ms = self._load_memory_read_timeouts()
        self._rag_prefetch_timeout_ms = self._load_rag_prefetch_timeout()
        self.artifacts = EphemeralRunArtifacts()
        self._safety_classifier = OperationalSafetyClassifier(runtime_settings.safety)
        self._streaming_policy = ResponseStreamingPolicy()
        self._trace_projector = TraceProjector()
        # Per-session serialisation (T29): requests for the same session
        # (or, for session-less requests, the same run) execute one at a
        # time, while requests for different sessions run concurrently.
        # The LLM call therefore no longer sits inside one process-wide
        # critical section. Cross-session shared state (session overlay,
        # per-run artifacts, the sqlite checkpointer) is individually
        # lock-protected, so the narrower scope is safe.
        self._session_locks: dict[str, RLock] = {}
        # Serialises every access to the shared sqlite-backed retrieval
        # store (KnowledgeRepository / SQLiteVectorStore connections).
        # Those connections are not safe for concurrent multi-thread use
        # (verified in T29: concurrent cross-session retrieval corrupts
        # rows and raises SQLITE_MISUSE), so retrieval stays serialized
        # while the LLM generation outside this lock can overlap.
        self._knowledge_lock = RLock()
        self._prefetch_executor = ThreadPoolExecutor(
            max_workers=2,
            thread_name_prefix="memory-rag-prefetch",
        )
        self._session_overlay = SessionMemoryOverlay()
        self._active_scope: ContextVar[_RunScope | None] = ContextVar(
            "langgraph_active_scope", default=None
        )
        self.rehydrator = ArtifactRehydrator(self)
        self.node_registry = NodeRegistry()
        for node in [
            ContextResolutionNode(self),
            SupervisorRouteNode(self),
            RetrieveEvidenceNode(self),
            GenerateDraftNode(self),
            SelfCheckNode(self),
            RewriteNode(self),
            RetrieveMoreNode(self),
            FinalizeNode(self),
        ]:
            self.node_registry.register(node.name, node)
        self._nodes = dict(self.node_registry.items())
        self.compiled_graph = self._compile_graph()

    def run_text_query(self, request: TextQueryRequest) -> TextQueryResponse:
        run_id = request.run_id or new_id("run")
        # The frozen public key remains present with its default null value.
        # The raw request continues through the in-flight scope and graph only.
        trace = RunTrace(run_id=run_id)
        started = perf_counter()
        try:
            request.validate()
            scene_state = self._build_scene_state(request.scene_state)
            normalized_query = self._normalize_query(request.query)
            scene_binding = self._scene_binding(scene_state, request.scene_state)
            request_fingerprint = self._request_fingerprint(
                request, normalized_query, scene_binding
            )
            if scene_state is not None:
                trace.scene_state_id = scene_state.scene_state_id
            append_audit_event(
                trace,
                "langgraph_runtime",
                "received text query",
                entry_mode=self.entry_mode,
            )
            scope = _RunScope(request=request, scene_state=scene_state, trace=trace)
            with self._session_lock_scope(request.session_id or run_id):
                checkpoint_config = self.graph_checkpoint_store.config_for(run_id)
                checkpoint = self.compiled_graph.get_state(checkpoint_config)
                checkpoint_values = checkpoint.values
                is_resume = bool(checkpoint.next) or bool(
                    checkpoint_values.get("terminal")
                )
                if is_resume and not self._resume_fingerprint_matches(
                    checkpoint_values, request_fingerprint
                ):
                    return self._error_response(
                        trace,
                        "LANGGRAPH_RESUME_REQUEST_MISMATCH",
                        "resume request does not match checkpoint",
                    )
                if is_resume:
                    # R0/T2-12: 合法 resume 才递增 resume_attempt 并熔断，防止客户端
                    # 反复重试损坏 checkpoint 造成无限恢复循环（mismatch 不计数）。
                    attempts = int(checkpoint_values.get("resume_attempt", 0)) + 1
                    if attempts > self.runtime_settings.langgraph.max_resume_attempts:
                        try:
                            self.graph_checkpoint_store.delete_thread(run_id)
                        except (sqlite3.OperationalError, KeyError):
                            pass
                        self.artifacts.discard(run_id)
                        return self._error_response(
                            trace,
                            "LANGGRAPH_RESUME_ATTEMPTS_EXCEEDED",
                            "resume attempts exceeded",
                        )
                    self.compiled_graph.update_state(
                        checkpoint_config, {"resume_attempt": attempts}
                    )
                if checkpoint_values.get("terminal") and not checkpoint.next:
                    return self._recover_terminal_checkpoint(run_id, trace)

                token = self._active_scope.set(scope)
                try:
                    if checkpoint.next:
                        self._restore_trace_for_resume(
                            trace, checkpoint.next, checkpoint_values
                        )
                    state = self.compiled_graph.invoke(
                        None
                        if checkpoint.next
                        else initial_graph_state(
                            run_id=run_id,
                            query=normalized_query,
                            source=request.source,
                            user_id=request.user_id,
                            session_id=request.session_id,
                            turn_id=request.turn_id,
                            request_fingerprint=request_fingerprint,
                            scene_state=scene_binding,
                        ),
                        checkpoint_config,
                    )
                finally:
                    self._active_scope.reset(token)

            trace.latency_ms += int((perf_counter() - started) * 1000)
            response = self._response_from_terminal_state(state, scope)
            if state.get("terminal") and response.status == "ok":
                try:
                    self.graph_checkpoint_store.delete_thread(run_id)
                except (sqlite3.OperationalError, KeyError) as exc:
                    # R0/T2-13: 终态清理失败只记告警，不覆盖已成功生成的回答。
                    logger.warning(
                        "graph_checkpoint_cleanup_failed",
                        run_id=run_id,
                        error=str(exc),
                    )
                    append_audit_event(
                        trace,
                        "langgraph_runtime",
                        "checkpoint_cleanup_warning",
                        error=str(exc),
                    )
                self.artifacts.discard(run_id)
            return response
        except AppError as exc:
            return self._error_response(trace, exc.error_code, exc.message)
        except Exception as exc:
            # 注意：不在异常分支无条件 delete_thread——LangGraph 的 resume 机制
            # 依赖异常中断后 checkpoint 保留（next 指针指向中断节点），以便恢复。
            error = PipelineExecutionError()
            append_error(trace, "langgraph_runtime", error.error_code, type(exc).__name__)
            return self._error_response(trace, error.error_code, error.message)

    def _session_lock_for(self, session_key: str) -> RLock:
        """Return the lock serialising runs within one session scope."""
        # ``dict.setdefault`` is atomic under the GIL: a racing thread may
        # create a throwaway lock, but both callers receive the same stored
        # instance, so same-session runs always share one lock.
        lock = self._session_locks.get(session_key)
        if lock is None:
            lock = self._session_locks.setdefault(session_key, RLock())
        return lock

    @contextmanager
    def _session_lock_scope(self, session_key: str):
        """Hold a session lock, then release the map entry to avoid unbounded growth.

        The lock object is dropped from ``_session_locks`` on exit; the
        checkpoint store keeps its own per-run lock, so a racing same-session
        request that re-creates the entry still serialises checkpoint access.
        """
        lock = self._session_lock_for(session_key)
        with lock:
            yield
        self._session_locks.pop(session_key, None)

    def _execute_base_prefetch_locked(
        self, prefetch: RetrievalPrefetch
    ) -> RetrievalPrefetch:
        """Run prefetch retrieval under the shared-store lock (T29)."""
        with self._knowledge_lock:
            return self.retrieval_controller.execute_base_prefetch(prefetch)

    @staticmethod
    def _normalize_query(query: str) -> str:
        return " ".join(query.strip().split())

    @staticmethod
    def _scene_binding(
        scene_state: SceneState | None,
        supplied_payload: dict[str, Any] | None,
    ) -> dict[str, object] | None:
        """Persist only the identifiers needed to replay reference resolution."""
        if scene_state is None:
            return None
        binding: dict[str, object] = {}
        supplied_scene_state_id = (
            supplied_payload.get("scene_state_id")
            if supplied_payload is not None
            else None
        )
        if isinstance(supplied_scene_state_id, str) and supplied_scene_state_id:
            binding["scene_state_id"] = scene_state.scene_state_id
        for key, value in (
            ("aircraft_id", scene_state.aircraft_id),
            ("component_id", scene_state.component_id),
            ("selected_object_id", scene_state.selected_object_id),
        ):
            if value is not None:
                binding[key] = value
        if scene_state.candidate_object_ids:
            binding["candidate_object_ids"] = list(scene_state.candidate_object_ids)
        return binding

    @staticmethod
    def _request_fingerprint(
        request: TextQueryRequest,
        normalized_query: str,
        scene_binding: dict[str, object] | None,
    ) -> str:
        """Return a privacy-safe binding for a resumable request.

        The digest deliberately excludes prompt, memory, audio, and evidence
        bodies. The normalized text query is only used as digest input here.
        """
        payload = {
            "normalized_query": normalized_query,
            "user_id": request.user_id,
            "session_id": request.session_id,
            "turn_id": request.turn_id,
            "source": request.source,
            "scene": scene_binding,
        }
        encoded = json.dumps(
            payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    @staticmethod
    def _resume_fingerprint_matches(state: GraphState, incoming: str) -> bool:
        persisted = state.get("request_fingerprint")
        return isinstance(persisted, str) and bool(persisted) and hmac.compare_digest(
            persisted, incoming
        )

    def _recover_terminal_checkpoint(
        self, run_id: str, trace: RunTrace
    ) -> TextQueryResponse:
        """Remove a finalized checkpoint without replaying terminal side effects."""
        try:
            self.graph_checkpoint_store.delete_thread(run_id)
        except (sqlite3.OperationalError, KeyError) as exc:
            logger.exception(
                "recover_checkpoint_cleanup_failed",
                run_id=run_id,
                error=str(exc),
            )
            self.artifacts.discard(run_id)
            return self._error_response(
                trace,
                "LANGGRAPH_CHECKPOINT_CLEANUP_FAILED",
                "graph checkpoint cleanup failed",
            )
        self.artifacts.discard(run_id)
        return self._error_response(
            trace,
            "LANGGRAPH_TERMINAL_ARTIFACT_MISSING",
            "terminal checkpoint response cannot be reconstructed",
        )

    def close(self) -> None:
        self._session_overlay.clear_all()
        self._prefetch_executor.shutdown(wait=False, cancel_futures=True)
        self.graph_checkpoint_store.close()

    def _compile_graph(self):
        graph = StateGraph(GraphState)
        for name, node in self._nodes.items():
            graph.add_node(name, node)
        graph.add_edge(START, "context_resolution")
        graph.add_edge("context_resolution", "supervisor_route")
        graph.add_conditional_edges(
            "supervisor_route",
            self._supervisor_destination,
            {
                SupervisorRoute.DIRECT.value: "finalize",
                SupervisorRoute.CLARIFY.value: "finalize",
                SupervisorRoute.REFUSE.value: "finalize",
                SupervisorRoute.RETRIEVE.value: "retrieve_evidence",
            },
        )
        graph.add_edge("retrieve_evidence", "generate_draft")
        graph.add_edge("generate_draft", "self_check")
        graph.add_conditional_edges(
            "self_check",
            self._check_destination,
            {
                "REWRITE_ONLY": "rewrite",
                "RETRIEVE_MORE": "retrieve_more",
                "FINALIZE": "finalize",
            },
        )
        graph.add_edge("rewrite", "self_check")
        graph.add_edge("retrieve_more", "retrieve_evidence")
        graph.add_edge("finalize", END)
        return graph.compile(checkpointer=self.graph_checkpoint_store.saver)

    def _context_resolution(self, state: GraphState) -> GraphState:
        scope = self._scope()
        query = scope.request.query
        safety = self._safety_classifier.classify_input(query)
        scope.unsafe = safety.is_unsafe
        # The resolver receives the unsafe flag before any memory controller call.
        initial = self.context_resolver.resolve(
            query,
            scene_state=scope.scene_state,
            unsafe=scope.unsafe,
        )
        scope.resolution = initial
        reason_codes = [*safety.reason_codes, *initial.reason_codes]
        self._transition(scope.trace, AgentState.PROMPT_ROUTED, "query resolved")
        if scope.unsafe:
            reason_codes.append(_UNSAFE_ROUTE_MARKER)
        else:
            # R0/T4-3.8: pre-decision (NOT the authoritative route).  This call
            # runs on the initial resolution *before* memory context is
            # resolved so the retrieval/memory prefetch can start in parallel
            # with context resolution.  The authoritative supervisor decision
            # is made once, later, in the supervisor_route graph node
            # (_supervisor_route -> _supervisor_destination); keep the two
            # phases distinct or the prefetch parallelism is lost.
            initial_route = self.supervisor_router.decide(query, False, initial).route
            if initial_route is SupervisorRoute.DIRECT:
                return {
                    "normalized_query": scope.resolution.query_object.normalized_query,
                    "selected_object_id": scope.resolution.selected_object_id,
                    "selected_object_kind": self._selected_object_kind(scope.resolution),
                    "route_reason_codes": list(dict.fromkeys(reason_codes)),
                }
            self._record_query_audit(scope)
            if (
                initial_route is SupervisorRoute.RETRIEVE
                and self._supports_memory_rag_prefetch()
            ):
                with self._knowledge_lock:
                    prefetch = self.retrieval_controller.prepare_base_prefetch(
                        initial.query_object,
                        scope.scene_state,
                    )
                scope.prefetch_future = self._prefetch_executor.submit(
                    self._execute_base_prefetch_locked,
                    prefetch,
                )
                memory_request, identity = self._memory_request(
                    initial.query_object,
                    self._memory_query_embedding(prefetch.query_embedding),
                )
                scope.memory_future = self._prefetch_executor.submit(
                    self._resolve_memory,
                    memory_request,
                    identity,
                )
                memory_context = self._await_memory_context(state, tier="fast_path")
            elif initial_route is SupervisorRoute.CLARIFY:
                memory_request, identity = self._memory_request(initial.query_object)
                scope.memory_future = self._prefetch_executor.submit(
                    self._resolve_memory,
                    memory_request,
                    identity,
                )
                memory_context = self._await_memory_context(state, tier="normal")
            else:
                memory_context = self._build_memory_context(state, initial.query_object)
            resolved = self.context_resolver.resolve(
                query,
                memory_context,
                scope.scene_state,
                unsafe=False,
            )
            scope.resolution = resolved
            reason_codes.extend(resolved.reason_codes)

        return {
            "normalized_query": scope.resolution.query_object.normalized_query,
            "selected_object_id": scope.resolution.selected_object_id,
            "selected_object_kind": self._selected_object_kind(scope.resolution),
            "route_reason_codes": list(dict.fromkeys(reason_codes)),
        }

    def _supports_memory_rag_prefetch(self) -> bool:
        """Return whether this controller explicitly supports the M2 prefetch flow.

        Existing controller adapters may intentionally override only the public
        ``retrieve_evidence`` seam.  They must keep that behaviour unless they
        explicitly opt into the new prepare/execute/complete protocol.
        """

        return isinstance(self.retrieval_controller, RetrievalController) or (
            getattr(self.retrieval_controller, "supports_memory_rag_prefetch", False)
            is True
        )

    def _supervisor_route(self, state: GraphState) -> GraphState:
        # R0/T4-3.8: this node is the SINGLE authoritative supervisor decision
        # point.  It runs on the fully resolved context (including memory) and
        # the real unsafe flag, and its output drives _supervisor_destination.
        # The earlier decide() inside context_resolution is only a prefetch
        # pre-decision (initial resolution, unsafe=False by construction) and
        # must never be treated as the route.
        scope = self._scope()
        resolution = scope.resolution or self._rehydrate_resolution(state)
        unsafe = scope.unsafe or _UNSAFE_ROUTE_MARKER in state.get(
            "route_reason_codes", []
        )
        decision = self.supervisor_router.decide(
            scope.request.query,
            unsafe,
            resolution,
        )
        return {
            "route": decision.route.value,
            "route_reason_codes": list(
                dict.fromkeys([*state.get("route_reason_codes", []), *decision.reason_codes])
            ),
        }

    @staticmethod
    def _supervisor_destination(
        state: GraphState,
    ) -> Literal["DIRECT", "RETRIEVE", "CLARIFY", "REFUSE"]:
        return state.get("route", SupervisorRoute.REFUSE.value)

    def _retrieve_evidence(self, state: GraphState) -> GraphState:
        scope = self._scope()
        try:
            plan = self._plan_from_state(state)
            if plan is None:
                resolution = scope.resolution or self._rehydrate_resolution(state)
                memory_context = self._memory_context(state, resolution.query_object)
                prefetch = self.artifacts.retrieval_prefetch(state["run_id"])
                if prefetch is None and scope.prefetch_future is not None:
                    try:
                        prefetch = scope.prefetch_future.result(
                            timeout=self._rag_prefetch_timeout_ms / 1000.0
                        )
                    except FutureTimeout:
                        # R0/T2-3: prefetch 卡住时降级回退同步检索路径，不再无限阻塞。
                        logger.warning(
                            "rag_prefetch_degraded",
                            elapsed_ms=self._rag_prefetch_timeout_ms,
                            reason="RAG_PREFETCH_TIMEOUT",
                            run_id=state.get("run_id"),
                        )
                        prefetch = None
                    else:
                        self.artifacts.put_retrieval_prefetch(state["run_id"], prefetch)
                if prefetch is not None:
                    with self._knowledge_lock:
                        evidence = self.retrieval_controller.complete_from_prefetch(
                            prefetch,
                            memory_context,
                        )
                    plan = self._plan_from_payload(evidence.retrieval_plan)
                    self.artifacts.put_evidence_package(state["run_id"], evidence)
                else:
                    with self._knowledge_lock:
                        plan = self.retrieval_controller.plan_retrieval(
                            resolution.query_object,
                            scope.scene_state,
                            memory_context,
                        )
                    evidence = self._rehydrate_evidence(state, plan)
            elif self.artifacts.memory_context(state["run_id"]) is None:
                # A resumed graph must reconstruct private context before it
                # rebuilds evidence; neither object is recovered from SQLite.
                self._memory_context(state, self._query_object(state))
                evidence = self._rehydrate_evidence(state, plan)
            else:
                evidence = self._rehydrate_evidence(state, plan)
            if evidence is None:
                return {
                    "error_code": state.get(
                        "error_code", "RESUME_EVIDENCE_FINGERPRINT_MISMATCH"
                    ),
                    "action_decision": ActionDecision.HUMAN_REVIEW.value,
                }
            self._transition(
                scope.trace,
                AgentState.RETRIEVAL_PLANNED,
                "retrieval plan available",
            )
            self._transition(
                scope.trace,
                AgentState.EVIDENCE_READY,
                evidence.gate_status,
            )
            if evidence.web_search_used:
                logger.info(
                    "web_search_evidence_ready",
                    run_id=state["run_id"],
                    web_search_query=evidence.web_search_query,
                )
            return {
                "retrieval_plan_payload": plan.to_dict(),
                "evidence_fingerprint": evidence_package_fingerprint(evidence),
                "action_decision": "",
                "web_search_used": evidence.web_search_used,
            }
        except ConfigError:
            return {
                "error_code": "RAG_CHANNEL_CONFIGURATION_INVALID",
                "action_decision": ActionDecision.HUMAN_REVIEW.value,
            }
        except Exception:
            logger.exception("retrieval_service_error", step="plan")
            return {
                "error_code": "RETRIEVAL_SERVICE_ERROR",
                "action_decision": ActionDecision.HUMAN_REVIEW.value,
            }

    def _generate_draft(self, state: GraphState) -> GraphState:
        scope = self._scope()
        if state.get("error_code"):
            return self._fail_closed_draft(state, state["error_code"])
        try:
            plan = self._required_plan(state)
            query_object = self._query_object(state)
            memory_context = self._memory_context(state, query_object)
            evidence = self._required_evidence(state, plan)
            request = GenerationRequest(
                run_id=state["run_id"],
                query_object=query_object,
                scene_state=scope.scene_state,
                memory_context=memory_context,
                evidence_package=evidence,
                prompt_bundle=None,
                generation_settings=self.runtime_settings.generation,
                self_check_settings=self.runtime_settings.self_check,
                model_settings=self.runtime_settings.model,
                config_version=self.runtime_settings.generation.route_policy_version,
                retrieve_round=state.get("retrieve_round", 0),
                rewrite_round=state.get("rewrite_round", 0),
            )
            outcome = self.generation_pipeline.generate_outcome(request)
            rescued_evidence = self._web_search_rescue_evidence(outcome, evidence, plan)
            if rescued_evidence is not None:
                evidence = rescued_evidence
                # 兜底搜索改写了证据包：写回 artifacts 并在下方同步 state 指纹，
                # 否则后续 self-check 重新读取证据时会因 fingerprint 不一致
                # 而触发 RESUME_EVIDENCE_FINGERPRINT_MISMATCH fail-closed。
                self.artifacts.put_evidence_package(state["run_id"], rescued_evidence)
                outcome = self.generation_pipeline.generate_outcome(
                    replace(request, evidence_package=rescued_evidence)
                )
            self.artifacts.put_answer_envelope(state["run_id"], outcome.envelope)
            self.artifacts.put_prompt_context(state["run_id"], outcome.prompt_context)
            scope.answer_plan = outcome.plan
            self._transition(scope.trace, AgentState.ANSWER_DRAFTED, "canonical answer generated")
            updates: dict[str, Any] = {
                "draft_fingerprint": outcome.envelope.answer_id
            }
            if rescued_evidence is not None:
                # 兜底搜索改写了证据包，必须同步指纹，否则后续 self-check
                # 重新读取证据时会因 fingerprint 不一致而 fail-closed。
                updates["evidence_fingerprint"] = evidence_package_fingerprint(
                    rescued_evidence
                )
            return updates
        except Exception:
            logger.exception("generate_draft_failed", run_id=state.get("run_id"))
            return self._fail_closed_draft(
                state, state.get("error_code", "GENERATION_SERVICE_ERROR")
            )

    def _web_search_rescue_evidence(
        self,
        outcome,
        evidence: EvidencePackage,
        plan: RetrievalPlan,
    ) -> EvidencePackage | None:
        """生成阶段联网兜底：但凡证据不足（计划非确定性）且尚未联网，补一次搜索。

        检索阶段的触发点（零证据 / 证据门非 confident）覆盖大多数证据不足
        场景，但证据门 confident 而规划层仍无法给出确定性回答（比较维度缺失、
        场景主体无匹配证据、sketch 证据不可压缩等）时检索阶段无法预知，在此
        补齐，保证“证据不足必走联网、尽量给出答案”。安全类与歧义澄清类
        查询不适用：前者必须 fail-closed，后者联网无法消除歧义。
        """
        has_client = getattr(self.retrieval_controller, "has_web_search_client", None)
        apply_fallback = getattr(
            self.retrieval_controller, "apply_web_search_fallback", None
        )
        # plan.allow_definitive_answer 缺失时视为确定性计划（不触发兜底），
        # 兼容测试桩替身与旧版 outcome 契约。
        allow_definitive = getattr(outcome.plan, "allow_definitive_answer", True)
        if not (
            not allow_definitive
            and outcome.route.answer_type is not AnswerType.CLARIFICATION
            and not outcome.route.policy.safety_mode
            and not evidence.web_search_used
            and self.runtime_settings.model.enabled
            and self.runtime_settings.retrieval.web_search.enabled
            and callable(has_client)
            and has_client()
            and callable(apply_fallback)
        ):
            return None
        rescued = apply_fallback(evidence, plan.query_text)
        # apply_web_search_fallback 原地修改并返回同一 package 对象，不能用
        # 身份比较判断是否生效；进入本方法时 evidence.web_search_used 必为
        # False（上方条件已保证），因此以 web_search_used 是否翻转为准。
        if rescued is None or not rescued.web_search_used:
            return None
        logger.info(
            "web_search_rescue_used",
            run_id=getattr(outcome.envelope, "answer_id", ""),
            query=plan.query_text[:80],
        )
        return rescued

    def _self_check(self, state: GraphState) -> GraphState:
        scope = self._scope()
        if state.get("error_code"):
            report = self._terminal_report(
                state["run_id"], ActionDecision.HUMAN_REVIEW, (state["error_code"],)
            )
            self.artifacts.put_check_report(state["run_id"], report)
            self._transition(
                scope.trace,
                AgentState.SELF_CHECKED,
                state["error_code"],
                ActionDecision.HUMAN_REVIEW,
            )
            return {"action_decision": ActionDecision.HUMAN_REVIEW.value}
        try:
            answer = self._required_answer(state)
            plan = self._required_plan(state)
            query_object = self._query_object(state)
            memory_context = self._memory_context(state, query_object)
            evidence = self._required_evidence(state, plan)
            check_scene = scope.scene_state
            if (
                answer.response_mode is ResponseMode.CLARIFICATION_ONLY
                and check_scene is not None
                and query_object.target_component == check_scene.component_id
                and query_object.scene_object_id is None
            ):
                check_scene = None
            report = self.self_check_service.check(
                answer,
                evidence,
                check_scene,
                memory_context,
                self.artifacts.prompt_context(state["run_id"]) or PromptContext(),
                DecisionContext(
                    retrieve_round=state.get("retrieve_round", 0),
                    rewrite_round=state.get("rewrite_round", 0),
                    retrieval_context=(
                        RetrievalDirectiveContext(
                            normalized_query=query_object.normalized_query,
                            answer_plan=scope.answer_plan,
                            retrieval_plan=plan,
                        )
                        if scope.answer_plan is not None
                        else None
                    ),
                ),
            )
            decision = report.action_decision
            if not isinstance(decision, ActionDecision):
                return self._fail_closed_check(state, "UNKNOWN_ACTION_DECISION")
            if decision is ActionDecision.PASS and answer.response_mode is ResponseMode.PURE_SAFETY_REFUSAL:
                decision = ActionDecision.SAFE_RESPONSE
                report = CheckReport(
                    check_report_id=report.check_report_id,
                    claim_supports=report.claim_supports,
                    issues=report.issues,
                    score_card=report.score_card,
                    action_decision=decision,
                    reason_codes=report.reason_codes,
                    claim_completeness=report.claim_completeness,
                )
            if (
                decision is ActionDecision.REWRITE_ONLY
                and state.get("rewrite_round", 0)
                >= self.runtime_settings.self_check.max_rewrite_rounds
            ):
                return self._fail_closed_check(state, "REWRITE_LIMIT_REACHED")
            if (
                decision is ActionDecision.RETRIEVE_MORE
                and state.get("retrieve_round", 0)
                >= self.runtime_settings.self_check.max_retrieve_rounds
            ):
                return self._fail_closed_check(state, "RETRIEVE_LIMIT_REACHED")
            self.artifacts.put_check_report(state["run_id"], report)
            self._transition(
                scope.trace,
                AgentState.SELF_CHECKED,
                "canonical self check completed",
                decision,
            )
            return {"action_decision": decision.value}
        except Exception:
            logger.exception("self_check_failed", run_id=state.get("run_id"))
            return self._fail_closed_check(
                state, state.get("error_code", "SELF_CHECK_SERVICE_ERROR")
            )

    def _check_destination(
        self, state: GraphState
    ) -> Literal["REWRITE_ONLY", "RETRIEVE_MORE", "FINALIZE"]:
        if state.get("error_code"):
            return "FINALIZE"
        decision = self._decision(state.get("action_decision"))
        if decision is ActionDecision.REWRITE_ONLY:
            if state.get("rewrite_round", 0) >= self.runtime_settings.self_check.max_rewrite_rounds:
                return "FINALIZE"
            return "REWRITE_ONLY"
        if decision is ActionDecision.RETRIEVE_MORE:
            if state.get("retrieve_round", 0) >= self.runtime_settings.self_check.max_retrieve_rounds:
                return "FINALIZE"
            return "RETRIEVE_MORE"
        return "FINALIZE"

    def _rewrite(self, state: GraphState) -> GraphState:
        try:
            answer = self._required_answer(state)
            report = self._required_report(state)
            rewritten = self.action_executor.rewrite_only(answer, report)
            self.artifacts.put_answer_envelope(state["run_id"], rewritten)
            self._transition(
                self._scope().trace,
                AgentState.ANSWER_DRAFTED,
                "rewrite candidate applied",
                ActionDecision.REWRITE_ONLY,
            )
            return {"rewrite_round": state.get("rewrite_round", 0) + 1}
        except Exception:
            logger.exception("rewrite_failed", run_id=state.get("run_id"))
            return {"error_code": "REWRITE_SERVICE_ERROR"}

    def _retrieve_more(self, state: GraphState) -> GraphState:
        try:
            plan = self._required_plan(state)
            self._memory_context(state, self._query_object(state))
            evidence = self._required_evidence(state, plan)
            answer = self._required_answer(state)
            report = self._required_report(state)
            recovery_state = AnswerLoopState(
                retrieve_round=state.get("retrieve_round", 0),
                rewrite_round=state.get("rewrite_round", 0),
                last_plan_fingerprint=retrieval_plan_fingerprint(plan),
                last_evidence_fingerprint=evidence_package_fingerprint(evidence),
            )
            with self._knowledge_lock:
                recovered = self.retrieval_action_service.retrieve_more(
                    RecoveryContext(
                        envelope=answer,
                        report=report,
                        previous_plan=plan,
                        previous_evidence=evidence,
                    ),
                    recovery_state,
                )
            if recovered.error_code is not None or not recovered.progress_made:
                return {
                    "error_code": recovered.error_code or "RETRIEVAL_NO_PROGRESS",
                    "action_decision": ActionDecision.HUMAN_REVIEW.value,
                }
            self.artifacts.put_evidence_package(state["run_id"], recovered.evidence_package)
            return {
                "retrieval_plan_payload": recovered.plan.to_dict(),
                "evidence_fingerprint": recovered.state.last_evidence_fingerprint,
                "retrieve_round": recovered.state.retrieve_round,
                "rewrite_round": recovered.state.rewrite_round,
            }
        except Exception:
            logger.exception("retrieval_more_failed", run_id=state.get("run_id"))
            return {
                "error_code": state.get(
                    "error_code", "RETRIEVAL_RECOVERY_SERVICE_ERROR"
                ),
                "action_decision": ActionDecision.HUMAN_REVIEW.value,
            }

    def _finalize(self, state: GraphState) -> GraphState:
        scope = self._scope()
        decision = self._terminal_decision(state)
        reason_codes = tuple(state.get("route_reason_codes", ()))
        if state.get("error_code"):
            decision = ActionDecision.HUMAN_REVIEW
            reason_codes = (state["error_code"],)
        elif state.get("route") == SupervisorRoute.RETRIEVE.value:
            self._rehydrate_retrieval_terminal_artifacts(state, decision)
        answer = self.artifacts.answer_envelope(state["run_id"])
        report = self.artifacts.check_report(state["run_id"])
        if answer is None:
            answer = self.action_executor.canonical_non_factual_envelope(
                run_id=state["run_id"], decision=decision, reason_codes=reason_codes
            )
        if (
            decision is ActionDecision.SAFE_RESPONSE
            and (
                scope.unsafe
                or state.get("route") == SupervisorRoute.REFUSE.value
            )
        ):
            answer = replace(
                answer,
                generation_trace=replace(
                    answer.generation_trace,
                    config_version=self.runtime_settings.generation.route_policy_version,
                    is_final_fact_prompt=False,
                ),
            )
        if report is None:
            report = self._terminal_report(state["run_id"], decision, reason_codes)
        try:
            terminal = self.action_executor.terminal_answer(decision, answer, report)
        except Exception:
            logger.exception("terminal_answer_failed", run_id=state.get("run_id"))
            decision = ActionDecision.HUMAN_REVIEW
            report = self._terminal_report(
                state["run_id"], decision, ("TERMINAL_ACTION_ERROR",)
            )
            terminal = self.action_executor.terminal_answer(
                decision,
                self.action_executor.canonical_non_factual_envelope(
                    run_id=state["run_id"],
                    decision=decision,
                    reason_codes=report.reason_codes,
                ),
                report,
            )
        loop_state = AnswerLoopState(
            retrieve_round=state.get("retrieve_round", 0),
            rewrite_round=state.get("rewrite_round", 0),
            last_plan_fingerprint=(
                retrieval_plan_fingerprint(self._plan_from_state(state))
                if self._plan_from_state(state) is not None
                else ""
            ),
            last_evidence_fingerprint=state.get("evidence_fingerprint", ""),
        )
        safety = PrePublishSafetyChecker(self._safety_classifier).check(terminal.envelope)
        streaming = self._streaming_policy.decide(
            self.runtime_settings.generation,
            terminal.action_decision,
            terminal.envelope,
            safety,
        )
        final_answer = self._trace_projector.finalize_answer(
            terminal.envelope,
            report,
            loop_state,
            terminal.action_decision.value,
            streaming,
        )
        evidence = self.artifacts.evidence_package(state["run_id"])
        if evidence is None:
            evidence = EvidencePackage(gate_status="unclear")
            self.artifacts.put_evidence_package(state["run_id"], evidence)
        self._advance_trace_to_final(scope.trace, terminal.action_decision)
        self._trace_projector.project(scope.trace, final_answer, report, loop_state, streaming)
        self.artifacts.put_answer_envelope(state["run_id"], final_answer)
        self.artifacts.put_check_report(state["run_id"], report)
        scope.feedback_checkpoint_id = self.checkpoint_store.save_checkpoint(
            state["run_id"],
            final_answer,
            evidence,
            report,
            scope.scene_state,
            user_id=scope.request.user_id,
            session_id=scope.request.session_id,
            turn_id=scope.request.turn_id,
        )
        return {
            "terminal": True,
            "action_decision": terminal.action_decision.value,
            "error_code": state.get("error_code"),
        }

    def _response_from_terminal_state(
        self, state: GraphState, scope: _RunScope
    ) -> TextQueryResponse:
        answer = self.artifacts.answer_envelope(state["run_id"])
        evidence = self.artifacts.evidence_package(state["run_id"])
        report = self.artifacts.check_report(state["run_id"])
        if not state.get("terminal") or answer is None or evidence is None or report is None:
            return self._error_response(
                scope.trace,
                "LANGGRAPH_TERMINAL_ARTIFACT_MISSING",
                "graph did not produce a terminal response",
            )
        return TextQueryResponse(
            run_id=state["run_id"],
            status="ok",
            answer=answer.to_public_dict(),
            action_decision=state.get("action_decision"),
            trace=scope.trace.to_dict() if self.trace_enabled else {},
            final_answer_id=answer.answer_id,
            evidence_package_id=evidence.evidence_package_id,
            check_report_id=report.check_report_id,
            self_check_completed=True,
            feedback_checkpoint_id=scope.feedback_checkpoint_id,
        )

    def _build_memory_context(
        self,
        state: GraphState,
        query_object: QueryObject,
        query_embedding: QueryEmbedding | None = None,
    ) -> MemoryContext:
        request, identity = self._memory_request(query_object, query_embedding)
        return self._store_memory_context(
            state,
            self._resolve_memory(request, identity),
            identity,
        )

    def _memory_request(
        self,
        query_object: QueryObject,
        query_embedding: QueryEmbedding | None = None,
    ) -> tuple[MemoryRetrievalRequest, object | None]:
        scope = self._scope()
        identity = current_trusted_identity()
        return (
            MemoryRetrievalRequest(
                user_id=identity.learner_id if identity is not None else scope.request.user_id,
                session_id=identity.session_id if identity is not None else scope.request.session_id,
                query_text=query_object.normalized_query,
                task_type=query_object.intent_type,
                scene_state=(
                    scope.scene_state.to_dict() if scope.scene_state is not None else None
                ),
                query_embedding=query_embedding,
            ),
            identity,
        )

    def _resolve_memory(
        self,
        request: MemoryRetrievalRequest,
        identity: object | None,
    ) -> MemoryReadResult:
        return self.memory_port.resolve(request, identity=identity)

    @staticmethod
    def _memory_query_embedding(
        embedding: PrefetchedQueryEmbedding | None,
    ) -> QueryEmbedding | None:
        if embedding is None:
            return None
        return QueryEmbedding(
            values=embedding.values,
            model_id=embedding.model_id,
            model_version=embedding.model_version,
            dimension=embedding.dimension,
            normalization=embedding.normalization,
        )

    def _await_memory_context(
        self,
        state: GraphState,
        *,
        tier: str = "fast_path",
    ) -> MemoryContext:
        scope = self._scope()
        future = scope.memory_future
        identity = current_trusted_identity()
        if future is None:
            return self._store_memory_context(
                state,
                MemoryReadResult(MemoryApplicationStatus.DEGRADED, MemoryContext()),
                identity,
            )
        timeout_ms = self._memory_read_timeout_ms.get(tier, 150)
        started = perf_counter()
        try:
            result = future.result(timeout=timeout_ms / 1000.0)
        except FutureTimeout:
            elapsed_ms = int((perf_counter() - started) * 1000)
            future.cancel()
            logger.warning(
                "memory_read_degraded",
                tier=tier,
                elapsed_ms=elapsed_ms,
                reason="MEMORY_TIMEOUT",
                run_id=state.get("run_id"),
            )
            result = MemoryReadResult(
                MemoryApplicationStatus.DEGRADED,
                MemoryContext(),
                diagnostic_code="MEMORY_TIMEOUT",
            )
        except Exception:
            elapsed_ms = int((perf_counter() - started) * 1000)
            logger.warning(
                "memory_read_degraded",
                tier=tier,
                elapsed_ms=elapsed_ms,
                reason="MEMORY_UNAVAILABLE",
                run_id=state.get("run_id"),
                exc_info=True,
            )
            result = MemoryReadResult(
                MemoryApplicationStatus.DEGRADED,
                MemoryContext(),
                diagnostic_code="MEMORY_UNAVAILABLE",
            )
        return self._store_memory_context(state, result, identity)

    @staticmethod
    def _load_memory_read_timeouts() -> dict[str, int]:
        """Load tiered memory read timeouts from ``configs/memory.yaml`` (T31).

        ``fast_path`` covers the concurrent prefetch memory read on the hot
        answer path; ``normal`` covers non-hot-path reads (e.g. the CLARIFY
        route).  Missing or invalid values fall back to 150ms per tier so a
        config problem can never lift the memory wait above the documented
        150ms ceiling or break the runtime.
        """
        defaults = {"fast_path": 150, "normal": 150}
        try:
            payload = parse_simple_yaml(
                Path("configs/memory.yaml").read_text(encoding="utf-8")
            )
            timeout_payload = payload.get("memory", {}).get("read_timeout")
            if not isinstance(timeout_payload, dict):
                return defaults
            loaded: dict[str, int] = {}
            for tier, key in (("fast_path", "fast_path_ms"), ("normal", "normal_ms")):
                value = timeout_payload.get(key)
                if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                    return defaults
                loaded[tier] = value
            return loaded
        except Exception:
            return defaults

    @staticmethod
    def _load_rag_prefetch_timeout() -> int:
        """Load the RAG prefetch wait budget from ``configs/rag.yaml`` (R0/T2-3).

        The prefetch future contains query embedding and full-channel retrieval;
        without a timeout a stuck embedding/retrieval can block the request
        indefinitely. Missing or invalid values fall back to 2000ms.
        """
        default = 2000
        try:
            payload = parse_simple_yaml(
                Path("configs/rag.yaml").read_text(encoding="utf-8")
            )
            value = payload.get("rag", {}).get("retrieval", {}).get("prefetch_timeout_ms")
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                return default
            return value
        except Exception:
            return default

    def _store_memory_context(
        self,
        state: GraphState,
        memory_result: MemoryReadResult,
        identity: object | None,
    ) -> MemoryContext:
        scope = self._scope()
        memory_context = (
            memory_result.context
            if isinstance(memory_result, MemoryReadResult)
            and isinstance(memory_result.context, MemoryContext)
            else MemoryContext()
        )
        if isinstance(memory_result, MemoryReadResult):
            append_audit_event(
                scope.trace,
                "memory",
                "memory resolve completed",
                status=memory_result.status.value,
                diagnostic_code=memory_result.diagnostic_code,
            )
        if identity is not None:
            memory_context = self._session_overlay.merge(memory_context, identity.session_id)
        self.artifacts.put_memory_context(state["run_id"], memory_context)
        scope.trace.memory_context_ids.append(memory_context.memory_context_id)
        if AgentState(scope.trace.current_state) in {
            AgentState.INPUT_RECEIVED,
            AgentState.PROMPT_ROUTED,
        }:
            self._transition(
                scope.trace, AgentState.MEMORY_CONTEXT_READY, "memory context retrieved"
            )
        return memory_context

    def _record_query_audit(self, scope: _RunScope) -> None:
        # R0/T3-3: 审计写失败不得拖垮请求，仅告警 + trace 留痕。
        try:
            self._record_query_audit_inner(scope)
        except Exception as exc:
            logger.warning(
                "query_audit_failed",
                run_id=scope.request.run_id,
                error=str(exc),
            )
            append_audit_event(
                scope.trace,
                "memory",
                "query_audit_warning",
                error=str(exc),
            )

    def _record_query_audit_inner(self, scope: _RunScope) -> None:
        if getattr(self.memory_port, "mode", None) in {
            AuthorityMode.CUTOVER_PREPARED,
            AuthorityMode.REMOTE,
        }:
            append_audit_event(
                scope.trace,
                "memory",
                "skipped legacy query audit after cutover preparation",
            )
            return
        request = scope.request
        payload: dict[str, object] = {"query_audit_version": "v1"}
        if scope.scene_state is not None:
            payload["scene_state_id"] = scope.scene_state.scene_state_id
        if request.turn_id:
            payload["turn_id"] = request.turn_id
        event_id = self.memory_controller.record_event(
            InteractionEvent(
                user_id=request.user_id,
                session_id=request.session_id,
                event_type="query_audit",
                source=request.source,
                payload=payload,
                scene_state_id=(
                    scope.scene_state.scene_state_id
                    if scope.scene_state is not None
                    else None
                ),
                confidence=1.0,
                raw_retention="transient",
                processing_status="logged_only",
            )
        )
        append_audit_event(
            scope.trace,
            "memory",
            "recorded query audit event",
            event_id=event_id,
        )

    def _memory_context(self, state: GraphState, query_object: QueryObject) -> MemoryContext:
        memory_context = self.artifacts.memory_context(state["run_id"])
        if memory_context is not None:
            return memory_context
        return self._build_memory_context(state, query_object)

    def _rehydrate_evidence(
        self, state: GraphState, plan: RetrievalPlan
    ) -> EvidencePackage | None:
        cached = self.artifacts.evidence_package(state["run_id"])
        expected = state.get("evidence_fingerprint", "")
        if cached is not None:
            fingerprint = evidence_package_fingerprint(cached)
            if expected and fingerprint != expected:
                state["error_code"] = "RESUME_EVIDENCE_FINGERPRINT_MISMATCH"
                return None
            return cached
        with self._knowledge_lock:
            rebuilt = self.retrieval_controller.retrieve_evidence(plan)
        fingerprint = evidence_package_fingerprint(rebuilt)
        if expected and fingerprint != expected:
            state["error_code"] = "RESUME_EVIDENCE_FINGERPRINT_MISMATCH"
            return None
        self.artifacts.put_evidence_package(state["run_id"], rebuilt)
        return rebuilt

    def _query_object(self, state: GraphState) -> QueryObject:
        scope = self._scope()
        if scope.resolution is not None:
            return scope.resolution.query_object
        return self._rehydrate_resolution(state).query_object

    def _rehydrate_resolution(self, state: GraphState) -> ResolvedQuery:
        plan = self._plan_from_state(state)
        query = str(state.get("normalized_query") or "")
        if not query and plan is not None:
            query = plan.query_text
        scene_state = self._scene_state_from_binding(state.get("scene_state"))
        unsafe = _UNSAFE_ROUTE_MARKER in state.get("route_reason_codes", [])
        resolution = self.context_resolver.resolve(
            query, scene_state=scene_state, unsafe=unsafe
        )
        selected_object_id = state.get("selected_object_id")
        if not selected_object_id:
            return resolution

        selected_object_kind = state.get("selected_object_kind")
        if selected_object_kind not in {"scene", "aircraft", "component", "concept"}:
            selected_object_kind = self._infer_selected_object_kind(
                selected_object_id, resolution, scene_state
            )
        self._apply_persisted_selection(
            resolution.query_object, selected_object_id, selected_object_kind
        )
        candidate_object_ids = tuple(
            dict.fromkeys((selected_object_id, *resolution.candidate_object_ids))
        )
        return ResolvedQuery(
            query_object=resolution.query_object,
            selected_object_id=selected_object_id,
            candidate_object_ids=candidate_object_ids,
            reason_codes=tuple(
                dict.fromkeys((*resolution.reason_codes, "reference_resolution_checkpoint"))
            ),
        )

    @staticmethod
    def _selected_object_kind(resolution: ResolvedQuery) -> str | None:
        selected_object_id = resolution.selected_object_id
        if selected_object_id is None:
            return None
        query_object = resolution.query_object
        candidates = (
            ("scene", query_object.scene_object_id),
            ("aircraft", query_object.target_aircraft),
            ("component", query_object.target_component),
            ("concept", query_object.target_concept),
        )
        return next(
            (kind for kind, object_id in candidates if object_id == selected_object_id),
            None,
        )

    @staticmethod
    def _scene_state_from_binding(payload: object) -> SceneState | None:
        if not isinstance(payload, dict):
            return None
        allowed = {
            "scene_state_id",
            "aircraft_id",
            "component_id",
            "selected_object_id",
            "candidate_object_ids",
        }
        return SceneState.from_dict(
            {key: value for key, value in payload.items() if key in allowed}
        )  # type: ignore[return-value]

    @staticmethod
    def _infer_selected_object_kind(
        selected_object_id: str,
        resolution: ResolvedQuery,
        scene_state: SceneState | None,
    ) -> str:
        if scene_state is not None and selected_object_id in {
            scene_state.selected_object_id,
            *scene_state.candidate_object_ids,
        }:
            return "scene"
        selected_kind = LangGraphAgentRuntime._selected_object_kind(resolution)
        return selected_kind or "concept"

    @staticmethod
    def _apply_persisted_selection(
        query_object: QueryObject,
        selected_object_id: str,
        selected_object_kind: str,
    ) -> None:
        if selected_object_kind == "scene":
            query_object.scene_object_id = selected_object_id
        elif selected_object_kind == "aircraft":
            query_object.target_aircraft = selected_object_id
        elif selected_object_kind == "component":
            query_object.target_component = selected_object_id
        else:
            query_object.target_concept = selected_object_id

    @staticmethod
    def _plan_from_state(state: GraphState) -> RetrievalPlan | None:
        payload = state.get("retrieval_plan_payload")
        if not payload:
            return None
        return LangGraphAgentRuntime._plan_from_payload(payload)

    @staticmethod
    def _plan_from_payload(payload: object) -> RetrievalPlan:
        if isinstance(payload, RetrievalPlan):
            return payload
        if not isinstance(payload, dict):
            raise TypeError("retrieval plan payload must be a mapping")
        raw_variants = payload.get("query_variants", [])
        variants = (
            raw_variants
            if isinstance(raw_variants, list)
            else ([raw_variants] if raw_variants else [])
        )
        hydrated_variants = [
            LangGraphAgentRuntime._query_variant_from_payload(variant)
            for variant in variants
        ]
        return RetrievalPlan.from_dict(
            {**payload, "query_variants": hydrated_variants}
        )

    @staticmethod
    def _query_variant_from_payload(value: object) -> QueryVariant:
        if isinstance(value, QueryVariant):
            return value
        if not isinstance(value, dict):
            return QueryVariant(
                kind=QueryVariantKind.EXACT,
                text=str(value),
                reason="legacy query variant compatibility",
                source_fields=["retrieval_plan.query_variants"],
            )
        raw_source_fields = value.get("source_fields", [])
        return QueryVariant(
            kind=LangGraphAgentRuntime._query_variant_kind(value.get("kind")),
            text=str(value.get("text", "")),
            reason=str(value.get("reason", "")),
            source_fields=(
                [str(field) for field in raw_source_fields]
                if isinstance(raw_source_fields, list)
                else ["retrieval_plan.query_variants"]
            ),
        )

    @staticmethod
    def _query_variant_kind(value: object) -> QueryVariantKind:
        try:
            return QueryVariantKind(str(value))
        except ValueError:
            return QueryVariantKind.EXACT

    def _required_plan(self, state: GraphState) -> RetrievalPlan:
        plan = self._plan_from_state(state)
        if plan is None:
            raise RuntimeError("missing retrieval plan")
        return plan

    def _required_evidence(self, state: GraphState, plan: RetrievalPlan) -> EvidencePackage:
        evidence = self._rehydrate_evidence(state, plan)
        if evidence is None:
            raise RuntimeError(state.get("error_code", "missing evidence"))
        return evidence

    def _rehydrate_retrieval_terminal_artifacts(
        self, state: GraphState, expected_decision: ActionDecision
    ) -> None:
        """Rebuild private terminal artifacts without expanding checkpoint state."""
        plan = self._required_plan(state)
        self._required_evidence(state, plan)

        regenerated_answer = self.artifacts.answer_envelope(state["run_id"]) is None
        if regenerated_answer:
            draft = self._generate_draft(state)
            if draft.get("error_code"):
                raise RuntimeError(draft["error_code"])

        if regenerated_answer or self.artifacts.check_report(state["run_id"]) is None:
            checked = self._self_check(state)
            if checked.get("error_code"):
                raise RuntimeError(checked["error_code"])
            if self._decision(checked.get("action_decision")) is not expected_decision:
                raise RuntimeError("RESUME_TERMINAL_ACTION_MISMATCH")

        self._required_answer(state)
        self._required_report(state)

    def _required_answer(self, state: GraphState):
        answer = self.artifacts.answer_envelope(state["run_id"])
        if answer is None:
            raise RuntimeError("missing answer artifact")
        return answer

    def _required_report(self, state: GraphState) -> CheckReport:
        report = self.artifacts.check_report(state["run_id"])
        if report is None:
            raise RuntimeError("missing check report artifact")
        return report

    def _fail_closed_draft(self, state: GraphState, error_code: str) -> GraphState:
        answer = self.action_executor.canonical_non_factual_envelope(
            run_id=state["run_id"],
            decision=ActionDecision.HUMAN_REVIEW,
            reason_codes=(error_code,),
        )
        self.artifacts.put_answer_envelope(state["run_id"], answer)
        self._transition(self._scope().trace, AgentState.ANSWER_DRAFTED, error_code)
        return {
            "error_code": error_code,
            "draft_fingerprint": answer.answer_id,
            "action_decision": ActionDecision.HUMAN_REVIEW.value,
        }

    def _fail_closed_check(self, state: GraphState, error_code: str) -> GraphState:
        report = self._terminal_report(
            state["run_id"], ActionDecision.HUMAN_REVIEW, (error_code,)
        )
        self.artifacts.put_check_report(state["run_id"], report)
        self._transition(
            self._scope().trace,
            AgentState.SELF_CHECKED,
            error_code,
            ActionDecision.HUMAN_REVIEW,
        )
        return {
            "error_code": error_code,
            "action_decision": ActionDecision.HUMAN_REVIEW.value,
        }

    @staticmethod
    def _decision(raw: str | None) -> ActionDecision:
        try:
            return ActionDecision(raw or ActionDecision.HUMAN_REVIEW.value)
        except ValueError:
            return ActionDecision.HUMAN_REVIEW

    def _terminal_decision(self, state: GraphState) -> ActionDecision:
        route = state.get("route")
        if route == SupervisorRoute.DIRECT.value:
            return ActionDecision.PASS
        if route == SupervisorRoute.CLARIFY.value:
            return ActionDecision.ASK_CLARIFICATION
        if route == SupervisorRoute.REFUSE.value:
            return ActionDecision.SAFE_RESPONSE
        return self._decision(state.get("action_decision"))

    @staticmethod
    def _terminal_report(
        run_id: str, decision: ActionDecision, reason_codes: tuple[str, ...]
    ) -> CheckReport:
        return CheckReport(
            check_report_id=f"{run_id}_{decision.value.lower()}_report",
            claim_supports=(),
            issues=tuple(
                CheckIssue(code, "high", recovery="human_review")
                for code in reason_codes
            ),
            # R0/T3-4: 错误路径分数置 0，不伪装成满分通过。
            score_card=ScoreCard(0.0, 0.0, 0.0, 0.0, 0.0),
            action_decision=decision,
            reason_codes=reason_codes,
            claim_completeness=ClaimCompletenessResult(True, (), (), (), ()),
        )

    def _scope(self) -> _RunScope:
        scope = self._active_scope.get()
        if scope is None:
            raise RuntimeError("langgraph node invoked without an active request scope")
        return scope

    @staticmethod
    def _build_scene_state(payload: dict[str, Any] | None) -> SceneState | None:
        return SceneState.from_dict(payload) if payload is not None else None  # type: ignore[return-value]

    @staticmethod
    def _transition(
        trace: RunTrace,
        next_state: AgentState,
        reason: str,
        action_decision: ActionDecision | None = None,
    ) -> None:
        if trace.current_state != next_state.value:
            transition(trace, next_state, action_decision=action_decision, reason=reason)

    def _advance_trace_to_final(self, trace: RunTrace, decision: ActionDecision) -> None:
        """Close the terminal stage only when the chain really reached self check.

        Short-circuit routes (DIRECT/CLARIFY/REFUSE) stop at ``PROMPT_ROUTED``
        and never run the draft/check stages; their exported trace must not
        retroactively project stages that did not occur.
        """
        if trace.current_state != AgentState.SELF_CHECKED.value:
            return
        self._transition(
            trace,
            AgentState.FINAL_READY,
            "terminal response finalized",
            decision,
        )

    def _restore_trace_for_resume(
        self, trace: RunTrace, next_nodes: tuple[str, ...], state: GraphState
    ) -> None:
        """Rebuild only the trace stage implied by a persisted graph position."""
        # R0/T4-3: an empty next_nodes tuple means the checkpoint has no graph
        # position to resume from — surface that instead of silently restoring
        # no stages (which would misreport the trace as a fresh run).
        if not next_nodes:
            raise PipelineExecutionError("resume checkpoint has no next nodes")
        next_node = next_nodes[0]
        completed_stages = {
            "supervisor_route": (AgentState.PROMPT_ROUTED,),
            "retrieve_evidence": (AgentState.PROMPT_ROUTED,),
            "generate_draft": (
                AgentState.PROMPT_ROUTED,
                AgentState.MEMORY_CONTEXT_READY,
                AgentState.RETRIEVAL_PLANNED,
                AgentState.EVIDENCE_READY,
            ),
            "self_check": (
                AgentState.PROMPT_ROUTED,
                AgentState.MEMORY_CONTEXT_READY,
                AgentState.RETRIEVAL_PLANNED,
                AgentState.EVIDENCE_READY,
                AgentState.ANSWER_DRAFTED,
            ),
            "rewrite": (
                AgentState.PROMPT_ROUTED,
                AgentState.MEMORY_CONTEXT_READY,
                AgentState.RETRIEVAL_PLANNED,
                AgentState.EVIDENCE_READY,
                AgentState.ANSWER_DRAFTED,
                AgentState.SELF_CHECKED,
            ),
            "retrieve_more": (
                AgentState.PROMPT_ROUTED,
                AgentState.MEMORY_CONTEXT_READY,
                AgentState.RETRIEVAL_PLANNED,
                AgentState.EVIDENCE_READY,
                AgentState.ANSWER_DRAFTED,
                AgentState.SELF_CHECKED,
            ),
        }.get(next_node, ())
        if next_node == "finalize":
            completed_stages = (
                (AgentState.PROMPT_ROUTED,)
                if state.get("route")
                in {
                    SupervisorRoute.DIRECT.value,
                    SupervisorRoute.CLARIFY.value,
                    SupervisorRoute.REFUSE.value,
                }
                else (
                    AgentState.PROMPT_ROUTED,
                    AgentState.MEMORY_CONTEXT_READY,
                    AgentState.RETRIEVAL_PLANNED,
                    AgentState.EVIDENCE_READY,
                    AgentState.ANSWER_DRAFTED,
                    AgentState.SELF_CHECKED,
                )
            )
        for stage in completed_stages:
            self._transition(trace, stage, "resumed graph checkpoint")

    def _error_response(
        self, trace: RunTrace, error_code: str, message: str
    ) -> TextQueryResponse:
        append_error(trace, "langgraph_runtime", error_code, message)
        return TextQueryResponse(
            run_id=trace.run_id,
            status="error",
            action_decision="STOP",
            trace=trace.to_dict() if self.trace_enabled else {},
            error=ErrorResponse(error_code=error_code, message=message, run_id=trace.run_id),
        )
