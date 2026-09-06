# Task 3 Review Package

## src/prompts/router.py

```
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from core.settings import parse_simple_yaml
from input.query_object import QueryObject
from prompts.asset_models import PromptSelection, TeachingPromptAsset
from prompts.repository import PromptRepositoryError
from prompts.repository import PromptAssetRepository


class PromptStatusError(Exception):
    pass


@dataclass
class PromptRouterConfig:
    default_template_id: str = "aviation_fact_qa"
    clarification_template_id: str = "aviation_basic_safe"
    required_final_fact_variables: list[str] = field(default_factory=lambda: ["rag_evidence", "output_contract"])
    injection_order: list[str] = field(
        default_factory=lambda: [
            "system_boundary",
            "task_type",
            "scene_state",
            "rag_evidence",
            "memory_context",
            "weak_points",
            "prompt_asset",
            "output_contract",
        ]
    )

    @classmethod
    def from_file(cls, path: str | Path = "configs/prompts.yaml") -> "PromptRouterConfig":
        prompts = parse_simple_yaml(Path(path).read_text(encoding="utf-8")).get("prompts", {})
        return cls(
            default_template_id=prompts.get("default_template_id", "aviation_fact_qa"),
            clarification_template_id=prompts.get("clarification_template_id", "aviation_basic_safe"),
            required_final_fact_variables=prompts.get(
                "required_final_fact_variables",
                ["rag_evidence", "output_contract"],
            ),
            injection_order=prompts.get("injection_order", cls().injection_order),
        )


class PromptRouter:
    def __init__(
        self,
        repository: PromptAssetRepository | None = None,
        config: PromptRouterConfig | None = None,
    ) -> None:
        self.repository = repository or PromptAssetRepository.from_config()
        self.config = config or PromptRouterConfig.from_file()

    def select_prompt(
        self,
        query_object: QueryObject,
        scene_state: Any | None = None,
        memory_context: Any | None = None,
        evidence_package: Any | None = None,
        output_contract: Any | None = None,
    ) -> PromptSelection:
        available = self._available_variables(scene_state, memory_context, evidence_package, output_contract)
        missing = [name for name in self.config.required_final_fact_variables if name not in available]
        if missing:
            return self._clarification(missing)

        asset = self._find_asset_for_intent(query_object.intent_type)
        route_reason = "intent_scope_match"
        if asset is None:
            asset = self.repository.get_asset(self.config.default_template_id)
            route_reason = "default_template_fallback"

        self._ensure_runtime_status(asset)
        version = self.repository.get_active_version(asset.template_id)
        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason=route_reason,
            selected_asset=asset,
            selected_version=version,
        )

    def _clarification(self, missing: list[str]) -> PromptSelection:
        asset = self.repository.get_asset(self.config.clarification_template_id)
        self._ensure_runtime_status(asset)
        version = self.repository.get_active_version(asset.template_id)
        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason="missing_required_variables",
            missing_variables=missing,
            selected_asset=asset,
            selected_version=version,
            is_clarification=True,
        )

    def _available_variables(
        self,
        scene_state: Any | None,
        memory_context: Any | None,
        evidence_package: Any | None,
        output_contract: Any | None,
    ) -> set[str]:
        available: set[str] = set()
        if scene_state is not None:
            available.add("scene_state")
        if memory_context is not None:
            available.add("memory_context")
        if evidence_package is not None and getattr(evidence_package, "evidence_items", None):
            available.add("rag_evidence")
        if output_contract is not None:
            available.add("output_contract")
        return available

    def _find_asset_for_intent(self, intent_type: str) -> TeachingPromptAsset | None:
        assets = getattr(self.repository, "_assets", {})
        for template_id in sorted(assets):
            asset = assets[template_id]
            if intent_type not in asset.activation_scope:
                continue
            self._ensure_runtime_status(asset)
            return asset
        try:
            return self.repository.find_for_intent(intent_type)
        except PromptRepositoryError:
            return None

    def _ensure_runtime_status(self, asset: TeachingPromptAsset) -> None:
        if asset.status.value not in self.repository.allowed_runtime_statuses:
            raise PromptStatusError(f"{asset.template_id} status is not runtime routable: {asset.status.value}")
```

## src/prompts/assembler.py

```
from __future__ import annotations

import re
from typing import Any

from prompts.asset_models import PromptMessageBundle, PromptSelection
from services.model_client import ModelMessage


class PromptAssembler:
    def assemble_messages(
        self,
        selection: PromptSelection,
        variables: dict[str, str],
        injection_order: list[str],
    ) -> PromptMessageBundle:
        if selection.selected_version is None:
            raise ValueError("prompt selection must include selected_version for assembly")

        rendered_prompt = self._render(selection.selected_version.content, variables)
        sections = {
            "system_boundary": "Facts must come only from reviewed evidence. Memory and prompt assets are not fact sources.",
            "task_type": variables.get("task_type", ""),
            "scene_state": variables.get("scene_state", ""),
            "rag_evidence": variables.get("rag_evidence", ""),
            "memory_context": variables.get("memory_context", ""),
            "weak_points": variables.get("weak_points", ""),
            "prompt_asset": rendered_prompt,
            "output_contract": variables.get("output_contract", ""),
        }

        system_content = "\n\n".join(
            part for part in [sections["system_boundary"], sections["output_contract"]] if part
        )
        user_content = "\n\n".join(
            f"[{name}]\n{sections[name]}"
            for name in injection_order
            if name not in {"system_boundary", "output_contract"} and sections.get(name, "")
        )
        messages = [
            ModelMessage(role="system", content=system_content),
            ModelMessage(role="user", content=user_content),
        ]

        return PromptMessageBundle(
            messages=messages,
            injection_order=list(injection_order),
            template_id=selection.template_id,
            version=selection.version,
            snapshot_id=selection.selected_version.snapshot_id,
            injection_summary=self._summary(sections, injection_order),
            missing_variables=list(selection.missing_variables),
            is_final_fact_prompt=not selection.is_clarification,
        )

    def _render(self, template: str, variables: dict[str, str]) -> str:
        def replace(match: re.Match[str]) -> str:
            name = match.group(1)
            return variables.get(name, "")

        return re.sub(r"\{\{([a-zA-Z0-9_]+)\}\}", replace, template)

    def _summary(self, sections: dict[str, str], injection_order: list[str]) -> list[dict[str, Any]]:
        return [
            {
                "section": name,
                "chars": len(sections.get(name, "")),
                "present": bool(sections.get(name, "")),
            }
            for name in injection_order
        ]
```

## src/prompts/runtime.py

```
from __future__ import annotations

import json
from typing import Any

from core.contracts import EvidencePackage, MemoryContext, SceneState
from input.query_object import QueryObject
from prompts.asset_models import PromptMessageBundle, PromptSelection
from prompts.assembler import PromptAssembler
from prompts.repository import PromptAssetRepository
from prompts.router import PromptRouter, PromptRouterConfig


class PromptRuntime:
    def __init__(
        self,
        repository: PromptAssetRepository,
        router: PromptRouter,
        assembler: PromptAssembler,
        config: PromptRouterConfig,
    ) -> None:
        self.repository = repository
        self.router = router
        self.assembler = assembler
        self.config = config

    @classmethod
    def from_config(cls, path: str = "configs/prompts.yaml") -> "PromptRuntime":
        repository = PromptAssetRepository.from_config(path)
        config = PromptRouterConfig.from_file(path)
        router = PromptRouter(repository=repository, config=config)
        assembler = PromptAssembler()
        return cls(repository=repository, router=router, assembler=assembler, config=config)

    def prepare_route(
        self,
        query_object: QueryObject,
        scene_state: SceneState | None = None,
        memory_context: MemoryContext | None = None,
        evidence_package: EvidencePackage | None = None,
        output_contract: dict[str, Any] | None = None,
    ) -> PromptSelection:
        return self.router.select_prompt(
            query_object=query_object,
            scene_state=scene_state,
            memory_context=memory_context,
            evidence_package=evidence_package,
            output_contract=output_contract or {"type": "answer_envelope"},
        )

    def assemble_bundle(
        self,
        query_object: QueryObject,
        scene_state: SceneState | None = None,
        memory_context: MemoryContext | None = None,
        evidence_package: EvidencePackage | None = None,
        output_contract: dict[str, Any] | None = None,
        weak_points: list[str] | None = None,
    ) -> PromptMessageBundle:
        resolved_output_contract = output_contract or {"type": "answer_envelope"}
        selection = self.prepare_route(
            query_object=query_object,
            scene_state=scene_state,
            memory_context=memory_context,
            evidence_package=evidence_package,
            output_contract=resolved_output_contract,
        )
        variables = self._bind_variables(
            query_object=query_object,
            scene_state=scene_state,
            memory_context=memory_context,
            evidence_package=evidence_package,
            output_contract=resolved_output_contract,
            weak_points=weak_points or [],
            selection=selection,
        )
        return self.assembler.assemble_messages(selection, variables, self.config.injection_order)

    def _bind_variables(
        self,
        query_object: QueryObject,
        scene_state: SceneState | None,
        memory_context: MemoryContext | None,
        evidence_package: EvidencePackage | None,
        output_contract: dict[str, Any],
        weak_points: list[str],
        selection: PromptSelection,
    ) -> dict[str, str]:
        explanation_preference = ""
        learner_level = ""
        if memory_context is not None:
            explanation_preference = str(memory_context.session_preference.get("explanation_preference", ""))
            learner_level = str(memory_context.learner_profile.get("level", ""))

        return {
            "task_type": selection.task_type or query_object.intent_type,
            "query": self._format_query(query_object),
            "aircraft": query_object.target_aircraft or "",
            "component": query_object.target_component or "",
            "concept": query_object.target_concept or "",
            "learner_level": learner_level,
            "explanation_preference": explanation_preference,
            "scene_state": self._format_scene_state(scene_state),
            "rag_evidence": self._format_evidence(evidence_package),
            "memory_context": self._format_memory_context(memory_context),
            "weak_points": self._format_weak_points(weak_points),
            "output_contract": self._format_output_contract(output_contract),
        }

    def _format_query(self, query_object: QueryObject) -> str:
        details = [
            f"raw_query={query_object.raw_query}",
            f"normalized_query={query_object.normalized_query}",
            f"intent_type={query_object.intent_type}",
        ]
        if query_object.target_aircraft:
            details.append(f"target_aircraft={query_object.target_aircraft}")
        if query_object.target_component:
            details.append(f"target_component={query_object.target_component}")
        if query_object.target_concept:
            details.append(f"target_concept={query_object.target_concept}")
        if query_object.scene_object_id:
            details.append(f"scene_object_id={query_object.scene_object_id}")
        return "\n".join(details)

    def _format_scene_state(self, scene_state: SceneState | None) -> str:
        if scene_state is None:
            return ""
        details = []
        if scene_state.aircraft_id:
            details.append(f"aircraft_id={scene_state.aircraft_id}")
        if scene_state.component_id:
            details.append(f"component_id={scene_state.component_id}")
        if scene_state.hotspot_label:
            details.append(f"hotspot_label={scene_state.hotspot_label}")
        if scene_state.camera_view:
            details.append(f"camera_view={scene_state.camera_view}")
        if scene_state.selected_object_id:
            details.append(f"selected_object_id={scene_state.selected_object_id}")
        return "\n".join(details)

    def _format_memory_context(self, memory_context: MemoryContext | None) -> str:
        if memory_context is None:
            return ""
        blocks = []
        if memory_context.session_preference:
            blocks.append(
                "session_preference="
                + json.dumps(memory_context.session_preference, ensure_ascii=False, sort_keys=True)
            )
        if memory_context.learner_profile:
            blocks.append(
                "learner_profile=" + json.dumps(memory_context.learner_profile, ensure_ascii=False, sort_keys=True)
            )
        if memory_context.misconception_record:
            blocks.append(
                "misconception_record="
                + json.dumps(memory_context.misconception_record, ensure_ascii=False, sort_keys=True)
            )
        return "\n".join(blocks)

    def _format_evidence(self, evidence_package: EvidencePackage | None) -> str:
        if evidence_package is None or not evidence_package.evidence_items:
            return ""
        return "\n".join(
            f"- [{item.evidence_id}] {item.content}" for item in evidence_package.evidence_items
        )

    def _format_output_contract(self, output_contract: dict[str, Any]) -> str:
        return json.dumps(output_contract, ensure_ascii=False, sort_keys=True)

    def _format_weak_points(self, weak_points: list[str]) -> str:
        if not weak_points:
            return ""
        return "\n".join(f"- {item}" for item in weak_points)
```

## src/prompts/__init__.py

```
"""Prompt asset routing and assembly."""

from prompts.asset_models import (
    PromptContentVersion,
    PromptEvaluationSnapshot,
    PromptMessageBundle,
    PromptSelection,
    PromptStatus,
    PromptVariableSpec,
    TeachingPromptAsset,
)
from prompts.assembler import PromptAssembler
from prompts.repository import PromptAssetRepository, PromptRepositoryError
from prompts.router import PromptRouter, PromptRouterConfig, PromptStatusError
from prompts.runtime import PromptRuntime

__all__ = [
    "PromptAssembler",
    "PromptAssetRepository",
    "PromptContentVersion",
    "PromptEvaluationSnapshot",
    "PromptMessageBundle",
    "PromptRepositoryError",
    "PromptRouter",
    "PromptRouterConfig",
    "PromptRuntime",
    "PromptSelection",
    "PromptStatus",
    "PromptStatusError",
    "PromptVariableSpec",
    "TeachingPromptAsset",
]
```

## tests/unit/prompts/test_prompt_router.py

```
import pytest

from core.contracts import EvidenceItem, EvidencePackage, SceneState
from input.query_object import QueryObject
from prompts.repository import PromptAssetRepository
from prompts.router import PromptRouter, PromptStatusError


def _evidence() -> EvidencePackage:
    return EvidencePackage(
        evidence_items=[EvidenceItem(evidence_id="ev1", source_id="src1", content="reviewed fact")]
    )


def test_router_selects_active_asset_for_intent():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
    )

    selection = router.select_prompt(
        query,
        evidence_package=_evidence(),
        output_contract={"type": "answer_envelope"},
    )

    assert selection.template_id == "aviation_fact_qa"
    assert selection.version == "v2"
    assert selection.route_reason == "intent_scope_match"


def test_missing_rag_evidence_routes_to_clarification_prompt():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
    )

    selection = router.select_prompt(query, scene_state=SceneState(), output_contract={"type": "answer_envelope"})

    assert selection.template_id == "aviation_basic_safe"
    assert selection.is_clarification is True
    assert selection.route_reason == "missing_required_variables"
    assert "rag_evidence" in selection.missing_variables


def test_candidate_assets_are_not_runtime_routable():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="q", normalized_query="q", intent_type="experimental_teaching_strategy")

    with pytest.raises(PromptStatusError):
        router.select_prompt(query, evidence_package=_evidence(), output_contract={"type": "answer_envelope"})
```

## tests/unit/prompts/test_prompt_assembler.py

```
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_object import QueryObject
from prompts.runtime import PromptRuntime


def test_prompt_runtime_injects_rag_before_prompt_asset_and_uses_model_messages():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
    )
    evidence = EvidencePackage(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev_lift",
                source_id="src_lift",
                content="Lift depends on airflow evidence.",
            )
        ]
    )

    bundle = runtime.assemble_bundle(
        query_object=query,
        scene_state=SceneState(component_id="wing"),
        memory_context=MemoryContext(session_preference={"explanation_preference": "plain"}),
        evidence_package=evidence,
        output_contract={"type": "answer_envelope"},
        weak_points=["confuses lift and thrust"],
    )

    assert bundle.injection_order.index("rag_evidence") < bundle.injection_order.index("prompt_asset")
    assert all(message.role in {"system", "user"} for message in bundle.messages)
    combined = "\n".join(message.content for message in bundle.messages)
    assert "Lift depends on airflow evidence." in combined
    assert "aviation science tutor" in combined.lower()
    assert bundle.injection_summary[0]["section"] == "system_boundary"
```

## tests/unit/prompts/test_prompt_runtime.py

```
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_object import QueryObject
from prompts import PromptAssetRepository, PromptAssembler, PromptRouter, PromptRuntime


def test_prompt_runtime_from_config_builds_repository_router_and_assembler():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")

    assert isinstance(runtime.repository, PromptAssetRepository)
    assert isinstance(runtime.router, PromptRouter)
    assert isinstance(runtime.assembler, PromptAssembler)
    assert runtime.config.default_template_id == "aviation_fact_qa"


def test_prepare_route_returns_prompt_selection():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
    )

    selection = runtime.prepare_route(query_object=query, scene_state=SceneState())

    assert selection.template_id == "aviation_basic_safe"
    assert selection.is_clarification is True
    assert selection.route_reason == "missing_required_variables"


def test_assemble_bundle_binds_runtime_variables_without_context_role_or_prompt_leak_in_summary():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Explain lift",
        normalized_query="Explain lift",
        intent_type="concept_explanation",
        target_component="wing",
        target_concept="lift",
    )
    evidence = EvidencePackage(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev_lift",
                source_id="src_lift",
                content="Lift depends on airflow across the wing.",
            )
        ]
    )

    bundle = runtime.assemble_bundle(
        query_object=query,
        scene_state=SceneState(component_id="wing", selected_object_id="wing_001"),
        memory_context=MemoryContext(
            learner_profile={"level": "beginner"},
            session_preference={"explanation_preference": "plain"},
        ),
        evidence_package=evidence,
        output_contract={"type": "answer_envelope"},
        weak_points=["confuses lift and thrust"],
    )

    assert bundle.template_id == "aviation_fact_qa"
    assert bundle.is_final_fact_prompt is True
    assert len(bundle.messages) == 2
    assert all(message.role != "context" for message in bundle.messages)
    rendered_text = "\n".join(message.content for message in bundle.messages)
    assert "{{rag_evidence}}" not in rendered_text
    assert "wing_001" in rendered_text
    assert "confuses lift and thrust" in rendered_text
    assert "Lift depends on airflow across the wing." in rendered_text
    assert all("aviation science tutor" not in str(item).lower() for item in bundle.injection_summary)
```

## docs/superpowers/plans/_sdd/task-3-report.md

```
# Task 3 Report: Refactor Router, Assembler, and Prompt Runtime

## Status

Completed.

## Scope

Implemented Task 3 in the requested prompt runtime surface:

- `PromptRouter` now only selects prompt assets and versions.
- `PromptRuntime.from_config("configs/prompts.yaml")` now constructs repository, router, assembler, and config.
- `PromptRuntime.prepare_route(...)` returns `PromptSelection`.
- `PromptRuntime.assemble_bundle(...)` performs runtime variable binding and returns canonical `PromptMessageBundle`.
- No second message bundle type was introduced.
- No `context` model role is emitted.
- `injection_summary` remains summary-only and does not store full prompt text.

## Files Changed

- `D:\APP\Python 3.13\鎸戞垬鏉痋src\prompts\router.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋src\prompts\assembler.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋src\prompts\runtime.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋src\prompts\__init__.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋tests\unit\prompts\test_prompt_router.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋tests\unit\prompts\test_prompt_assembler.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋tests\unit\prompts\test_prompt_runtime.py`

## Implementation Notes

### Router

- Moved router responsibility back to asset/version selection only.
- Missing required final-fact variables now route to `clarification_template_id` with:
  - `route_reason="missing_required_variables"`
  - `is_clarification=True`
- Candidate/draft/deprecated intent matches are treated as non-runtime-routable and raise `PromptStatusError` instead of silently falling through.

### Assembler

- `PromptAssembler` now accepts:
  - `PromptSelection`
  - bound `variables: dict[str, str]`
  - `injection_order`
- It renders prompt placeholders into the selected prompt content and emits exactly two `ModelMessage` instances:
  - one `system`
  - one `user`
- `PromptMessageBundle.injection_summary` only records per-section metadata (`section`, `chars`, `present`).

### Runtime

- Added `PromptRuntime` as the public runtime entry point for routing plus assembly.
- `assemble_bundle(...)` binds summaries from:
  - `query_object`
  - `scene_state`
  - `memory_context`
  - `evidence_package`
  - `output_contract`
  - `weak_points`
- Bound evidence is injected before prompt asset content in the assembled user message.

### Exports

- `src/prompts/__init__.py` now exports:
  - `PromptRuntime`
  - `PromptAssetRepository`
  - `PromptRouter`
  - `PromptAssembler`
  - prompt domain models and related errors/config

## Test Commands and Output

### Command 1

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_router.py tests/unit/prompts/test_prompt_assembler.py tests/unit/prompts/test_prompt_runtime.py -q
```

Output:

```text
.......                                                                  [100%]
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
...................                                                      [100%]
```

## Constraints Check

- No changes were made to `agent`, `generation`, or `voice`.
- No old `PromptAssetStore`, flat YAML prompt assets, or compatibility shims were restored.
- No second message bundle type was introduced.
- No `context` role is emitted.
- No new dependencies were added.

## Concerns

- `PromptRouter` now inspects repository-loaded assets to distinguish 鈥渘o matching asset鈥?from 鈥渕atching but non-routable asset鈥? This keeps runtime behavior correct for candidate-only matches without reopening repository shape changes in Task 3.
```
