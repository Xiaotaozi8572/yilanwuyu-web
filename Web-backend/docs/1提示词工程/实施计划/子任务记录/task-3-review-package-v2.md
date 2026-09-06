# Task 3 Review Package v2

## src/prompts/repository.py

```
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from core.settings import parse_simple_yaml
from prompts.asset_models import (
    PromptContentVersion,
    PromptEvaluationSnapshot,
    PromptStatus,
    PromptVariableSpec,
    TeachingPromptAsset,
)


class PromptRepositoryError(Exception):
    pass


class PromptAssetRepository:
    def __init__(
        self,
        asset_dir: str | Path,
        allowed_runtime_statuses: list[str] | None = None,
    ) -> None:
        self.asset_dir = Path(asset_dir)
        self.allowed_runtime_statuses = set(allowed_runtime_statuses or ["active", "experimental"])
        self._assets: dict[str, TeachingPromptAsset] = {}
        self._versions: dict[str, dict[str, PromptContentVersion]] = {}
        self._snapshots: dict[tuple[str, str], PromptEvaluationSnapshot] = {}
        self._load()

    @classmethod
    def from_config(cls, path: str | Path = "configs/prompts.yaml") -> "PromptAssetRepository":
        config_path = Path(path)
        config = parse_simple_yaml(config_path.read_text(encoding="utf-8"))
        prompts = config.get("prompts", {})
        asset_dir = prompts.get("asset_dir")
        if not asset_dir:
            raise PromptRepositoryError("prompts.asset_dir is required")
        resolved_asset_dir = Path(asset_dir)
        if not resolved_asset_dir.is_absolute():
            resolved_asset_dir = (config_path.parent.parent / resolved_asset_dir).resolve()
        return cls(
            resolved_asset_dir,
            allowed_runtime_statuses=prompts.get("allowed_runtime_statuses", ["active", "experimental"]),
        )

    def get_asset(self, template_id: str) -> TeachingPromptAsset:
        try:
            return self._assets[template_id]
        except KeyError as exc:
            raise PromptRepositoryError(f"prompt asset not found: {template_id}") from exc

    def get_version(self, template_id: str, version: str) -> PromptContentVersion:
        try:
            return self._versions[template_id][version]
        except KeyError as exc:
            raise PromptRepositoryError(f"prompt version not found: {template_id}@{version}") from exc

    def get_active_version(self, template_id: str) -> PromptContentVersion:
        asset = self.get_asset(template_id)
        return self.get_version(template_id, asset.active_version)

    def get_evaluation_snapshot(self, template_id: str, version: str) -> PromptEvaluationSnapshot:
        try:
            return self._snapshots[(template_id, version)]
        except KeyError as exc:
            raise PromptRepositoryError(f"evaluation snapshot not found: {template_id}@{version}") from exc

    def find_for_intent(self, intent_type: str) -> TeachingPromptAsset | None:
        for asset in self.find_assets_for_intent(intent_type):
            if asset.status.value not in self.allowed_runtime_statuses:
                continue
            return asset
        return None

    def find_assets_for_intent(self, intent_type: str) -> list[TeachingPromptAsset]:
        matched_assets: list[TeachingPromptAsset] = []
        for template_id in sorted(self._assets):
            asset = self._assets[template_id]
            if intent_type in asset.activation_scope:
                matched_assets.append(asset)
        return matched_assets

    def list_versions(self, template_id: str) -> list[str]:
        return sorted(self._versions.get(template_id, {}))

    def _load(self) -> None:
        if not self.asset_dir.exists():
            raise PromptRepositoryError(f"prompt asset directory does not exist: {self.asset_dir}")
        for asset_root in sorted(path for path in self.asset_dir.iterdir() if path.is_dir()):
            asset = self._load_asset(asset_root / "asset.json")
            versions = self._load_versions(asset_root / "versions")
            snapshots = self._load_snapshots(asset_root / "evaluations")
            self._validate_asset(asset, versions, snapshots)
            self._assets[asset.template_id] = asset
            self._versions[asset.template_id] = versions
            self._snapshots.update(snapshots)

    def _validate_asset(
        self,
        asset: TeachingPromptAsset,
        versions: dict[str, PromptContentVersion],
        snapshots: dict[tuple[str, str], PromptEvaluationSnapshot],
    ) -> None:
        if asset.active_version not in versions:
            raise PromptRepositoryError(f"active version missing: {asset.template_id}@{asset.active_version}")
        if asset.status in {PromptStatus.ACTIVE, PromptStatus.EXPERIMENTAL}:
            active_version = versions[asset.active_version]
            if not active_version.snapshot_id:
                raise PromptRepositoryError(
                    f"runtime active version missing snapshot id: {asset.template_id}@{asset.active_version}"
                )
            snapshot_key = (asset.template_id, asset.active_version)
            if snapshot_key not in snapshots:
                raise PromptRepositoryError(
                    f"runtime active version missing evaluation snapshot: {asset.template_id}@{asset.active_version}"
                )
            if snapshots[snapshot_key].final_decision != "approve":
                raise PromptRepositoryError(
                    f"runtime active version lacks approval: {asset.template_id}@{asset.active_version}"
                )
            if snapshots[snapshot_key].snapshot_id != active_version.snapshot_id:
                raise PromptRepositoryError(
                    f"runtime active version snapshot mismatch: {asset.template_id}@{asset.active_version}"
                )

    def _load_asset(self, path: Path) -> TeachingPromptAsset:
        data = self._read_json(path)
        return TeachingPromptAsset(
            template_id=data["template_id"],
            title=data["title"],
            task_type=data["task_type"],
            concept_scope=list(data.get("concept_scope", [])),
            scene_scope=list(data.get("scene_scope", [])),
            status=PromptStatus(data["status"]),
            active_version=data["active_version"],
            activation_scope=list(data.get("activation_scope", [])),
            constraints=list(data.get("constraints", [])),
            risk_boundaries=list(data.get("risk_boundaries", [])),
        )

    def _load_versions(self, root: Path) -> dict[str, PromptContentVersion]:
        if not root.exists():
            raise PromptRepositoryError(f"versions directory missing: {root}")
        versions: dict[str, PromptContentVersion] = {}
        for path in sorted(root.glob("*.json")):
            data = self._read_json(path)
            version = PromptContentVersion(
                template_id=data["template_id"],
                version=data["version"],
                content=data["content"],
                parent_version=data.get("parent_version"),
                change_reason=data.get("change_reason", ""),
                created_at=data.get("created_at", ""),
                review_status=PromptStatus(data["review_status"]),
                snapshot_id=data.get("snapshot_id"),
                variables=[self._load_variable(item) for item in data.get("variables", [])],
            )
            versions[version.version] = version
        return versions

    def _load_snapshots(self, root: Path) -> dict[tuple[str, str], PromptEvaluationSnapshot]:
        if not root.exists():
            return {}
        snapshots: dict[tuple[str, str], PromptEvaluationSnapshot] = {}
        for path in sorted(root.glob("*.json")):
            data = self._read_json(path)
            snapshot = PromptEvaluationSnapshot(
                snapshot_id=data["snapshot_id"],
                template_id=data["template_id"],
                version=data["version"],
                test_cases=list(data.get("test_cases", [])),
                dimension_scores=dict(data.get("dimension_scores", {})),
                violations=list(data.get("violations", [])),
                improvements=list(data.get("improvements", [])),
                final_decision=data["final_decision"],
            )
            snapshots[(snapshot.template_id, snapshot.version)] = snapshot
        return snapshots

    def _load_variable(self, item: dict[str, Any]) -> PromptVariableSpec:
        return PromptVariableSpec(
            name=item["name"],
            source=item["source"],
            value_type=item.get("value_type", "text"),
            required=bool(item.get("required", False)),
            missing_behavior=item.get("missing_behavior", "omit"),
            description=item.get("description", ""),
        )

    def _read_json(self, path: Path) -> dict[str, Any]:
        if not path.exists():
            raise PromptRepositoryError(f"prompt repository file missing: {path}")
        return json.loads(path.read_text(encoding="utf-8"))
```

## src/prompts/router.py

```
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from core.settings import parse_simple_yaml
from input.query_object import QueryObject
from prompts.asset_models import PromptSelection, PromptVariableSpec, TeachingPromptAsset
from prompts.repository import PromptAssetRepository


class PromptStatusError(Exception):
    pass


class PromptVariableError(Exception):
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

        asset = self._find_asset_for_intent(query_object.intent_type)
        route_reason = "intent_scope_match"
        if asset is None:
            asset = self.repository.get_asset(self.config.default_template_id)
            route_reason = "default_template_fallback"

        selection = self._build_selection(asset.template_id, route_reason=route_reason)
        follow_up = self._resolve_missing_required_variables(selection, available)
        return follow_up or selection

    def _clarification(self, missing: list[str], available: set[str]) -> PromptSelection:
        selection = self._build_selection(
            self.config.clarification_template_id,
            route_reason="missing_required_variables",
            missing_variables=missing,
            is_clarification=True,
        )
        follow_up = self._resolve_missing_required_variables(selection, available)
        return follow_up or selection

    def _build_selection(
        self,
        template_id: str,
        route_reason: str,
        missing_variables: list[str] | None = None,
        is_clarification: bool = False,
    ) -> PromptSelection:
        asset = self.repository.get_asset(template_id)
        self._ensure_runtime_status(asset)
        version = self.repository.get_active_version(asset.template_id)
        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason=route_reason,
            missing_variables=list(missing_variables or []),
            selected_asset=asset,
            selected_version=version,
            is_clarification=is_clarification,
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
        matching_assets = self.repository.find_assets_for_intent(intent_type)
        for asset in matching_assets:
            if asset.status.value in self.repository.allowed_runtime_statuses:
                return asset
        if matching_assets:
            status_summary = ", ".join(f"{asset.template_id}:{asset.status.value}" for asset in matching_assets)
            raise PromptStatusError(
                f"matching prompt assets are not runtime routable for intent {intent_type}: {status_summary}"
            )
        return None

    def _resolve_missing_required_variables(
        self,
        selection: PromptSelection,
        available: set[str],
    ) -> PromptSelection | None:
        version = selection.selected_version
        if version is None:
            raise PromptVariableError(f"selected prompt has no version bound: {selection.template_id}")

        missing_specs = [spec for spec in version.variables if spec.required and spec.name not in available]
        if not missing_specs:
            return None

        if selection.template_id == self.config.clarification_template_id:
            self._raise_missing_variable_error(selection, missing_specs)

        error_specs = [spec for spec in missing_specs if spec.missing_behavior == "error"]
        if error_specs:
            self._raise_missing_variable_error(selection, error_specs)

        clarification_specs = [spec for spec in missing_specs if spec.missing_behavior == "clarification"]
        if clarification_specs:
            return self._clarification([spec.name for spec in clarification_specs], available)

        self._raise_missing_variable_error(selection, missing_specs)

    def _raise_missing_variable_error(
        self,
        selection: PromptSelection,
        missing_specs: list[PromptVariableSpec],
    ) -> None:
        missing_text = ", ".join(f"{spec.name}({spec.missing_behavior})" for spec in missing_specs)
        raise PromptVariableError(
            f"prompt {selection.template_id}@{selection.version} missing required runtime variables: {missing_text}"
        )

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

## tests/unit/prompts/test_prompt_repository.py

```
import pytest

from prompts.asset_models import PromptStatus, TeachingPromptAsset
from prompts.repository import PromptAssetRepository, PromptRepositoryError


def test_repository_loads_active_asset_and_real_versions():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    asset = repo.get_asset("aviation_fact_qa")
    version = repo.get_active_version("aviation_fact_qa")

    assert asset.status == PromptStatus.ACTIVE
    assert version.version == asset.active_version
    assert "{{rag_evidence}}" in version.content
    assert repo.list_versions("aviation_fact_qa") == ["v1", "v2"]


def test_active_version_requires_approved_evaluation_snapshot():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    snapshot = repo.get_evaluation_snapshot("aviation_fact_qa", "v2")

    assert snapshot.final_decision == "approve"
    assert snapshot.snapshot_id == "aviation_fact_qa_v2"


def test_candidate_asset_is_loadable_but_not_runtime_eligible():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    asset = repo.get_asset("experimental_teaching_strategy")

    assert asset.status == PromptStatus.CANDIDATE
    assert repo.find_for_intent("experimental_teaching_strategy") is None
    assert [item.template_id for item in repo.find_assets_for_intent("experimental_teaching_strategy")] == [
        "experimental_teaching_strategy"
    ]


def test_missing_asset_raises_structured_repository_error():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    with pytest.raises(PromptRepositoryError, match="prompt asset not found"):
        repo.get_asset("missing_prompt")


def test_find_for_intent_returns_runtime_asset():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    asset = repo.find_for_intent("knowledge_explanation")

    assert asset is not None
    assert asset.template_id == "aviation_fact_qa"


def test_runtime_search_skips_candidate_assets_even_if_intent_matches():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    matched_assets = [
        asset
        for asset in (
            repo.find_for_intent("spoken_answer_style"),
            repo.find_for_intent("experimental_teaching_strategy"),
        )
        if isinstance(asset, TeachingPromptAsset)
    ]

    assert all(asset.status in {PromptStatus.ACTIVE, PromptStatus.EXPERIMENTAL} for asset in matched_assets)


def test_find_assets_for_intent_returns_all_status_matches():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")

    matched_assets = repo.find_assets_for_intent("spoken_answer_style")

    assert [asset.template_id for asset in matched_assets] == ["spoken_answer_style_prompt"]
    assert matched_assets[0].status == PromptStatus.ACTIVE


def test_runtime_active_version_rejects_mismatched_snapshot_id(tmp_path):
    asset_root = tmp_path / "assets" / "sample_prompt"
    versions_root = asset_root / "versions"
    evaluations_root = asset_root / "evaluations"
    versions_root.mkdir(parents=True)
    evaluations_root.mkdir()

    (asset_root / "asset.json").write_text(
        """
{
  "template_id": "sample_prompt",
  "title": "Sample prompt",
  "task_type": "concept_explanation",
  "status": "active",
  "active_version": "v1",
  "activation_scope": ["concept_explanation"]
}
""".strip(),
        encoding="utf-8",
    )
    (versions_root / "v1.json").write_text(
        """
{
  "template_id": "sample_prompt",
  "version": "v1",
  "content": "Use {{rag_evidence}} only.",
  "review_status": "active",
  "snapshot_id": "sample_prompt_v1_expected",
  "variables": []
}
""".strip(),
        encoding="utf-8",
    )
    (evaluations_root / "sample_prompt_v1.json").write_text(
        """
{
  "snapshot_id": "sample_prompt_v1_actual",
  "template_id": "sample_prompt",
  "version": "v1",
  "final_decision": "approve"
}
""".strip(),
        encoding="utf-8",
    )

    with pytest.raises(PromptRepositoryError, match="runtime active version snapshot mismatch"):
        PromptAssetRepository(tmp_path / "assets")
```

## tests/unit/prompts/test_prompt_router.py

```
import pytest

from core.contracts import EvidenceItem, EvidencePackage, SceneState
from input.query_object import QueryObject
from prompts.repository import PromptAssetRepository
from prompts.router import PromptRouter, PromptStatusError, PromptVariableError


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


@pytest.mark.parametrize(
    ("intent_type", "expected_template_id", "scene_state"),
    [
        ("asr_correction", "asr_correction_prompt", SceneState(component_id="engine")),
        ("spoken_answer_style", "spoken_answer_style_prompt", None),
        ("barge_in", "barge_in_feedback_prompt", None),
    ],
)
def test_non_rag_runtime_prompts_route_without_rag_evidence(
    intent_type: str,
    expected_template_id: str,
    scene_state: SceneState | None,
):
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query=intent_type, normalized_query=intent_type, intent_type=intent_type)

    selection = router.select_prompt(
        query,
        scene_state=scene_state,
        output_contract={"type": "answer_envelope"},
    )

    assert selection.template_id == expected_template_id
    assert selection.route_reason == "intent_scope_match"
    assert selection.is_clarification is False


def test_missing_error_variable_raises_for_selected_prompt():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="spoken", normalized_query="spoken", intent_type="spoken_answer_style")

    with pytest.raises(PromptVariableError, match="output_contract"):
        router.select_prompt(query)


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


def test_prepare_route_selects_non_rag_prompt_when_version_contract_allows_it():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(
        raw_query="Please make it short enough to speak",
        normalized_query="Please make it short enough to speak",
        intent_type="spoken_answer_style",
    )

    selection = runtime.prepare_route(query_object=query)

    assert selection.template_id == "spoken_answer_style_prompt"
    assert selection.route_reason == "intent_scope_match"
    assert selection.is_clarification is False


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

- The original Task 3 router still used a global required-variable gate and private repository access; the review fix below corrects both behaviors.

## Task 3 Review Fix Addendum

### Review Fix Summary

- Router now selects the runtime-eligible asset/version before validating required variables.
- Required variables now come from the selected version's own `PromptVariableSpec` entries where `required=true`.
- Missing `clarification` variables now route to `clarification_template_id`.
- Missing `error` variables now raise a prompt variable error instead of silently rerouting.
- Router no longer reads repository private `_assets`; it uses the public `find_assets_for_intent(...)` repository method.
- Added regression coverage for `asr_correction`, `spoken_answer_style`, and `barge_in` routing without `rag_evidence`.

### Additional Files Changed

- `D:\APP\Python 3.13\鎸戞垬鏉痋src\prompts\repository.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋src\prompts\router.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋tests\unit\prompts\test_prompt_repository.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋tests\unit\prompts\test_prompt_router.py`
- `D:\APP\Python 3.13\鎸戞垬鏉痋tests\unit\prompts\test_prompt_runtime.py`

### Command 1

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_router.py tests/unit/prompts/test_prompt_runtime.py -q
```

Output:

```text
...................                                                      [100%]
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
.........................                                                [100%]
```
```
