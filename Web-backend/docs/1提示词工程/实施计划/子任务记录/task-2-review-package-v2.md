# Task 2 Review Package v2

Full current contents after Task 2 review fixes.

## src\prompts\repository.py

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
        for template_id in sorted(self._assets):
            asset = self._assets[template_id]
            if asset.status.value not in self.allowed_runtime_statuses:
                continue
            if intent_type in asset.activation_scope:
                return asset
        return None

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

## src\prompts\router.py

```
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from core.settings import parse_simple_yaml
from input.query_object import QueryObject
from prompts.asset_models import PromptSelection, PromptStatus, TeachingPromptAsset
from prompts.repository import PromptAssetRepository


class PromptStatusError(Exception):
    pass


@dataclass
class PromptRouterConfig:
    default_template_id: str = "aviation_fact_qa"
    clarification_template_id: str = "aviation_basic_safe"
    allowed_runtime_statuses: list[str] = field(default_factory=lambda: ["active", "experimental"])
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
            default_template_id=prompts.get("default_template_id", cls.default_template_id),
            clarification_template_id=prompts.get("clarification_template_id", cls.clarification_template_id),
            allowed_runtime_statuses=prompts.get("allowed_runtime_statuses", ["active", "experimental"]),
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
        asset = self.repository.find_for_intent(query_object.intent_type)
        if asset is None:
            asset = self.repository.get_asset(self.config.default_template_id)
        if asset is None:
            return self._clarification_selection(["prompt_asset"], "default_template_missing")
        self._ensure_runtime_status(asset)

        available = self._available_variables(scene_state, memory_context, evidence_package, output_contract)
        missing = [name for name in self.config.required_final_fact_variables if name not in available]
        if missing:
            return self._clarification_selection(missing, "missing_required_variables")

        version = self.repository.get_active_version(asset.template_id)

        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason="intent_match",
            selected_version=version,
            selected_asset=asset,
        )

    def _clarification_selection(self, missing: list[str], reason: str) -> PromptSelection:
        asset = self.repository.get_asset(self.config.clarification_template_id)
        version = self.repository.get_active_version(asset.template_id)
        self._ensure_runtime_status(asset)
        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason=reason,
            missing_variables=missing,
            selected_asset=asset,
            selected_version=version,
            is_clarification=True,
        )

    def _ensure_runtime_status(self, asset: TeachingPromptAsset) -> None:
        if asset.status.value not in self.config.allowed_runtime_statuses:
            raise PromptStatusError(f"{asset.template_id} status is not routable: {asset.status.value}")

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


def select_prompt(query_object: QueryObject, **kwargs: Any) -> PromptSelection:
    return PromptRouter().select_prompt(query_object, **kwargs)
```

## src\prompts\assembler.py

```
from __future__ import annotations

from typing import Any

from prompts.asset_models import PromptMessageBundle, PromptSelection
from prompts.router import PromptRouterConfig
from services.model_client import ModelMessage


class PromptAssembler:
    def __init__(self, config: PromptRouterConfig | None = None) -> None:
        self.config = config or PromptRouterConfig.from_file()

    def assemble_messages(
        self,
        prompt_selection: PromptSelection,
        evidence_package: Any | None,
        memory_context: Any | None,
        output_contract: Any,
        scene_state: Any | None = None,
        weak_points: list[str] | None = None,
    ) -> PromptMessageBundle:
        values = {
            "system_boundary": "facts must come from evidence_package only",
            "task_type": prompt_selection.task_type,
            "scene_state": scene_state,
            "rag_evidence": evidence_package,
            "memory_context": memory_context,
            "weak_points": weak_points or [],
            "prompt_asset": (
                prompt_selection.selected_version.content
                if prompt_selection.selected_version is not None
                else prompt_selection.template_id
            ),
            "output_contract": output_contract,
        }
        messages = [
            ModelMessage(
                role="system" if name == "system_boundary" else "user",
                content=self._render_section(name, values[name]),
            )
            for name in self.config.injection_order
        ]
        final_fact_prompt = (
            not prompt_selection.is_clarification
            and "rag_evidence" not in prompt_selection.missing_variables
        )
        return PromptMessageBundle(
            messages=messages,
            injection_order=list(self.config.injection_order),
            template_id=prompt_selection.template_id,
            version=prompt_selection.version,
            snapshot_id=prompt_selection.selected_version.snapshot_id if prompt_selection.selected_version else None,
            injection_summary=[
                {"section": name, "role": message.role, "chars": len(message.content)}
                for name, message in zip(self.config.injection_order, messages, strict=False)
            ],
            missing_variables=list(prompt_selection.missing_variables),
            is_final_fact_prompt=final_fact_prompt,
        )

    def _render_section(self, section: str, value: Any) -> str:
        if value is None:
            rendered = "None"
        else:
            rendered = str(value)
        return f"[{section}]\n{rendered}"


def assemble_messages(prompt_selection: PromptSelection, **kwargs: Any) -> PromptMessageBundle:
    return PromptAssembler().assemble_messages(prompt_selection, **kwargs)
```

## configs\prompts.yaml

```
prompts:
  default_template_id: aviation_fact_qa
  clarification_template_id: aviation_basic_safe
  asset_dir: assets/prompts
  allowed_runtime_statuses: [active, experimental]
  required_final_fact_variables: [rag_evidence, output_contract]
  injection_order: [system_boundary, task_type, scene_state, rag_evidence, memory_context, weak_points, prompt_asset, output_contract]
```

## tests\unit\prompts\test_prompt_repository.py

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

## tests\unit\prompts\test_prompt_router.py

```
import json

import pytest

from core.contracts import EvidenceItem, EvidencePackage, SceneState
from input.query_understanding import understand_query
from prompts.repository import PromptAssetRepository
from prompts.router import PromptRouter, PromptRouterConfig, PromptStatusError


def _write_json(path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _build_runtime_asset_tree(root, *, status: str = "active") -> None:
    asset_root = root / "draft_only"
    _write_json(
        asset_root / "asset.json",
        {
            "template_id": "draft_only",
            "title": "Draft Only",
            "task_type": "concept_explanation",
            "status": status,
            "active_version": "v1",
            "activation_scope": ["concept_explanation"],
            "constraints": ["facts_from_evidence_only"],
            "risk_boundaries": ["no_parameter_without_source"],
        },
    )
    _write_json(
        asset_root / "versions" / "v1.json",
        {
            "template_id": "draft_only",
            "version": "v1",
            "content": "Use {{rag_evidence}} only.",
            "review_status": status,
            "snapshot_id": "draft_only_v1",
            "variables": [
                {
                    "name": "rag_evidence",
                    "source": "evidence_package",
                    "value_type": "evidence_summary",
                    "required": True,
                    "missing_behavior": "clarification",
                    "description": "Reviewed evidence only.",
                },
                {
                    "name": "output_contract",
                    "source": "runtime",
                    "value_type": "json_schema_summary",
                    "required": True,
                    "missing_behavior": "error",
                    "description": "Output rules.",
                },
            ],
        },
    )
    _write_json(
        asset_root / "evaluations" / "draft_only_v1.json",
        {
            "snapshot_id": "draft_only_v1",
            "template_id": "draft_only",
            "version": "v1",
            "test_cases": ["basic_concept"],
            "dimension_scores": {"faithfulness": 1.0},
            "violations": [],
            "improvements": ["Boundary is explicit."],
            "final_decision": "approve" if status in {"active", "experimental"} else "reject",
        },
    )


def test_scene_reference_binds_selected_object():
    scene = SceneState(
        aircraft_id="C919",
        component_id="wing",
        selected_object_id="scene_wing_001",
        scene_confidence=0.91,
    )

    query = understand_query("What does this part do?", scene)

    assert query.scene_object_id == "scene_wing_001"
    assert query.target_component == "wing"
    assert query.needs_clarification is False


def test_scene_reference_with_multiple_candidates_needs_clarification():
    scene = SceneState(candidate_object_ids=["obj_a", "obj_b"], scene_confidence=0.55)

    query = understand_query("What is that beside it?", scene)

    assert query.needs_clarification is True
    assert query.scene_binding_candidates == ["obj_a", "obj_b"]


def test_missing_rag_evidence_falls_back_to_clarification_template():
    router = PromptRouter()
    query = understand_query("Explain lift.")

    selection = router.select_prompt(query, scene_state=SceneState(), output_contract={"format": "answer_envelope"})

    assert selection.template_id == "aviation_basic_safe"
    assert selection.route_reason == "missing_required_variables"
    assert "rag_evidence" in selection.missing_variables
    assert selection.is_clarification is True


def test_active_prompt_can_be_selected_when_required_variables_exist():
    router = PromptRouter()
    query = understand_query("Explain lift.")
    evidence = EvidencePackage(
        evidence_items=[EvidenceItem(evidence_id="ev1", source_id="src1", content="reviewed fact")]
    )

    selection = router.select_prompt(
        query,
        scene_state=SceneState(),
        evidence_package=evidence,
        output_contract={"format": "answer_envelope"},
    )

    assert selection.template_id == "aviation_fact_qa"
    assert selection.route_reason == "intent_match"
    assert selection.is_clarification is False


def test_non_runtime_prompt_cannot_enter_runtime_route(tmp_path):
    _build_runtime_asset_tree(tmp_path, status="candidate")
    repository = PromptAssetRepository(tmp_path, allowed_runtime_statuses=["active"])
    router = PromptRouter(
        repository=repository,
        config=PromptRouterConfig(
            default_template_id="draft_only",
            clarification_template_id="draft_only",
            allowed_runtime_statuses=["active"],
            required_final_fact_variables=["rag_evidence", "output_contract"],
        ),
    )

    with pytest.raises(PromptStatusError, match="not routable"):
        router.select_prompt(understand_query("Explain lift."))
```

## tests\unit\prompts\test_prompt_assembler.py

```
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_understanding import understand_query
from prompts.assembler import PromptAssembler
from prompts.router import PromptRouter
from services.model_client import ModelMessage


def test_prompt_assembler_injects_rag_before_prompt_asset():
    query = understand_query("Explain lift around the wing.")
    evidence = EvidencePackage(
        evidence_items=[EvidenceItem(evidence_id="ev_lift", source_id="src_lift", content="reviewed lift fact")]
    )
    selection = PromptRouter().select_prompt(
        query,
        scene_state=SceneState(component_id="wing"),
        memory_context=MemoryContext(),
        evidence_package=evidence,
        output_contract={"type": "answer_envelope"},
    )

    bundle = PromptAssembler().assemble_messages(
        selection,
        evidence_package=evidence,
        memory_context=MemoryContext(session_preference={"style": "plain"}),
        output_contract={"type": "answer_envelope"},
        scene_state=SceneState(component_id="wing"),
    )

    assert bundle.injection_order.index("rag_evidence") < bundle.injection_order.index("prompt_asset")
    assert bundle.is_final_fact_prompt is True
    assert all(isinstance(message, ModelMessage) for message in bundle.messages)
    assert {message.role for message in bundle.messages} <= {"system", "user"}
    assert "{{rag_evidence}}" in bundle.messages[bundle.injection_order.index("prompt_asset")].content
    assert bundle.template_id == selection.template_id
    assert bundle.version == selection.version


def test_missing_rag_evidence_bundle_is_not_final_fact_prompt():
    query = understand_query("Explain lift.")
    selection = PromptRouter().select_prompt(
        query,
        scene_state=SceneState(),
        output_contract={"type": "answer_envelope"},
    )

    bundle = PromptAssembler().assemble_messages(
        selection,
        evidence_package=None,
        memory_context=None,
        output_contract={"type": "answer_envelope"},
        scene_state=SceneState(),
    )

    assert bundle.template_id == "aviation_basic_safe"
    assert bundle.is_final_fact_prompt is False
    assert bundle.missing_variables == ["rag_evidence"]
```

## docs\STATUS.md

```
# STATUS.md

本文件由 Codex 在全自动开发过程中持续更新。

## 当前状态

P0 到 P6 已完成。P7/P8 未纳入本次用户目标，暂未开始。

## 阶段：P0

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立系统开发的共同地基，使后续 Prompt、记忆、RAG、生成、自检、反馈和语音模块都围绕同一套状态机、动作枚举、数据契约、配置和日志规范开发。

### 允许修改范围
- `pyproject.toml`
- `.env.example`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/core/**`
- `tests/unit/core/**`
- `README.md`

### 禁止修改范围
- P1 到 P8 尚未创建的业务模块不得塞入 P0。
- 不得修改原始 Word 文档和用户提供资料。
- 不得将演示数据、Prompt 模板、航空知识库内容写入 `src/core/`。

### 已完成内容
- 建立 Python 工程骨架、pytest 配置、README 和基础配置样例。
- 新增统一 `ActionDecision`、`AgentState`、状态跳转记录和回退链路。
- 新增基础数据契约：`scene_state`、`prompt_asset`、`memory_context`、`evidence_package`、`answer_envelope`、`check_report`、`rewrite_plan`、`voice_turn_event`、`feedback_event`、`run_trace`。
- 新增配置加载、核心异常和运行追踪辅助函数。
- 按用户追加条件，将运行环境调整为 `D:\APP\Python 3.13\Internet\.venv`，并将 `pyproject.toml` 的 `requires-python` 调整为 `>=3.11`。

### 修改文件
- 无

### 新增文件
- `pyproject.toml`
- `.env.example`
- `README.md`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/__init__.py`
- `src/core/__init__.py`
- `src/core/actions.py`
- `src/core/contracts.py`
- `src/core/errors.py`
- `src/core/settings.py`
- `src/core/state_machine.py`
- `src/core/tracing.py`
- `tests/unit/core/test_contracts.py`
- `tests/unit/core/test_state_machine.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core
```

### 测试结果
通过，`10 passed in 0.20s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `brainstorming`（受用户全自动执行指令约束，未设置人工确认门）
- `writing-plans`（以 `docs/spec.md` 作为已有实施计划执行，未新增计划文件以避免越过 P0 harness）
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 技术栈、数据库、消息队列、前端通信协议仍按文档标记为待确认。

### 下一阶段是否可以开始
是。

## 阶段：P1

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
把用户文本问题、3D 场景状态和初步任务意图转成标准 `query_object`，并实现 Prompt 资产的路由、变量填充、注入顺序和风险边界。

### 允许修改范围
- `src/input/**`
- `src/prompts/**`
- `configs/prompts.yaml`
- `tests/unit/prompts/**`
- `tests/unit/core/test_contracts.py`

### 禁止修改范围
- 不得在 P1 修改 `src/knowledge/**` 以绕过检索。
- 不得在 P1 写入长期记忆。
- 不得在 Prompt 模块中写航空事实库内容。

### 已完成内容
- 新增 `QueryObject`、`PromptRunInput` 和场景绑定结果对象。
- 实现文本意图识别、飞机/部件/概念/反馈意图的轻量抽取。
- 实现场景指代绑定：明确选中对象直接绑定，多候选返回 `needs_clarification`。
- 新增 Prompt 资产模型、资产存储、路由器和注入摘要组装器。
- 配置化 Prompt 必填变量和注入顺序，并保证 `rag_evidence` 位于 Prompt 资产之前。
- 缺失 `rag_evidence` 时降级到基础模板，并标记不是事实型最终 Prompt。

### 修改文件
- 无

### 新增文件
- `configs/prompts.yaml`
- `src/input/__init__.py`
- `src/input/query_object.py`
- `src/input/query_understanding.py`
- `src/input/scene_binding.py`
- `src/prompts/__init__.py`
- `src/prompts/asset_models.py`
- `src/prompts/asset_store.py`
- `src/prompts/router.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_router.py`
- `tests/unit/prompts/test_prompt_assembler.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts
```

### 测试结果
通过，`17 passed in 0.20s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 首批 Prompt 模板内容和人工审核流程仍为待确认；当前仅实现非事实性的安全边界模板与路由机制。

### 下一阶段是否可以开始
是。

## 阶段：P2

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立事件日志、热状态、学习者画像、候选记忆、时间衰减和审计机制，让记忆只用于个性化、指代和教学路径，不污染航空事实。

### 允许修改范围
- `src/memory/**`
- `configs/memory.yaml`
- `tests/unit/memory/**`
- `tests/integration/answer_pipeline/**` 中与 `memory_context` 相关的测试

### 禁止修改范围
- 不得修改 `src/knowledge/source_registry.py` 或知识库事实表来写入用户说法。
- 不得在记忆模块中生成最终回答。
- 不得让任意 Agent 直接写长期记忆而不走 `MemoryController`。

### 已完成内容
- 新增事件日志、候选记忆、结构化记忆、记忆状态机和写入结果对象。
- 实现 `MemoryController` 作为唯一长期写入入口。
- 实现事件先行流程：`event_log -> candidate -> temporal normalize -> governance -> store`。
- 实现低置信、高隐私、未登记事件和用户航空事实候选的拒绝治理。
- 实现偏好冲突的 `supersedes/superseded_by` 关系。
- 实现过期/陈旧状态刷新、记忆审计和 `memory_context` 构造。

### 修改文件
- 无

### 新增文件
- `configs/memory.yaml`
- `src/memory/__init__.py`
- `src/memory/audit.py`
- `src/memory/controller.py`
- `src/memory/event_log.py`
- `src/memory/maintenance_jobs.py`
- `src/memory/schemas.py`
- `src/memory/stores.py`
- `src/memory/temporal.py`
- `tests/unit/memory/test_memory_context.py`
- `tests/unit/memory/test_memory_write_flow.py`
- `tests/unit/memory/test_temporal_status.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts tests\unit\memory
```

### 测试结果
通过，`25 passed in 0.18s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 真实用户授权 UI、删除流程、原始语音保留策略仍为待确认。
- 当前存储为内存实现，生产持久化方案待确认。

### 下一阶段是否可以开始
是。

## 阶段：P3

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立“事实由知识检索提供”的核心能力，将文本、场景对象和多模态线索统一入库、检索、门控，并输出可审计的 `evidence_package`。

### 允许修改范围
- `src/knowledge/**`
- `configs/rag.yaml`
- `scripts/ingest_sources.py`
- `data/raw_sources/**`
- `data/processed/**`
- `tests/integration/rag_pipeline/**`

### 禁止修改范围
- 不得在 P3 写回答生成逻辑。
- 不得在知识库中写入用户记忆、用户反馈或模型自由生成事实。
- 不得在检索模块修改 Prompt 资产状态。

### 已完成内容
- 新增 `source_registry`、`scene_object_registry`、文本/PDF/视觉/场景入库适配对象。
- 新增关键词索引、轻量语义索引、混合 RRF 召回、视觉页索引和轻量图索引。
- 实现 `RetrievalController`、`RetrievalPlan`、`EvidencePackageBuilder` 和 `EvidenceGate`。
- 强制仅 `reviewed` 来源可作为核心航空事实证据。
- 场景对象可通过 `selected_object_id` 绑定并过滤部件证据。
- 未审核资料和无文本交叉校验视觉线索不能作为核心事实证据。
- 新增演示入库脚本 `scripts/ingest_sources.py`，并在最终验收中补充直接运行路径初始化。

### 修改文件
- `scripts/ingest_sources.py`

### 新增文件
- `configs/rag.yaml`
- `data/raw_sources/.gitkeep`
- `data/processed/.gitkeep`
- `src/knowledge/__init__.py`
- `src/knowledge/evidence_gate.py`
- `src/knowledge/evidence_package.py`
- `src/knowledge/retrieval_controller.py`
- `src/knowledge/scene_object_registry.py`
- `src/knowledge/schemas.py`
- `src/knowledge/source_registry.py`
- `src/knowledge/ingestion/__init__.py`
- `src/knowledge/ingestion/pdf_ingestor.py`
- `src/knowledge/ingestion/scene_ingestor.py`
- `src/knowledge/ingestion/text_ingestor.py`
- `src/knowledge/ingestion/visual_ingestor.py`
- `src/knowledge/indexes/__init__.py`
- `src/knowledge/indexes/graph_index.py`
- `src/knowledge/indexes/hybrid_index.py`
- `src/knowledge/indexes/keyword_index.py`
- `src/knowledge/indexes/vector_index.py`
- `src/knowledge/indexes/visual_page_index.py`
- `scripts/ingest_sources.py`
- `tests/integration/rag_pipeline/test_basic_retrieval.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts tests\unit\memory tests\integration\rag_pipeline
```

### 测试结果
通过，`31 passed in 0.41s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 最终向量库、图数据库、PDF 解析工具和 embedding 模型仍为待确认。
- 当前 RAG 为本地内存 MVP，接口保持可替换。

### 下一阶段是否可以开始
是。

## 阶段：P4

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
基于 `evidence_package`、`scene_state`、`memory_context` 和 Prompt 路由结果生成可检查、可展示、可追溯的 `answer_envelope`，实现来源绑定和结构化展示块。

### 允许修改范围
- `src/generation/**`
- `tests/unit/generation/**`
- `tests/integration/answer_pipeline/**`
- `configs/app.yaml` 中 generation 相关配置

### 禁止修改范围
- 不得在 P4 修改知识库证据。
- 不得在 P4 直接写长期记忆。
- 不得绕过 P5 直接把 draft 标记为最终安全输出。

### 已完成内容
- 新增回答类型路由、证据草图、生成计划、非流式 grounded generator、来源绑定和展示块构建。
- 参数证据不足时不输出具体数值。
- 每个关键 claim 有 `evidence_id` 或不确定性说明。
- 个性化只改变表达前缀，不改变事实文本。

### 修改文件
- `tests/integration/answer_pipeline/test_grounded_answer.py`

### 新增文件
- `src/generation/__init__.py`
- `src/generation/answer_types.py`
- `src/generation/citation_binding.py`
- `src/generation/display_blocks.py`
- `src/generation/evidence_sketch.py`
- `src/generation/generator.py`
- `src/generation/planner.py`
- `tests/unit/generation/test_answer_type_router.py`
- `tests/unit/generation/test_citation_binding.py`
- `tests/integration/answer_pipeline/test_grounded_answer.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration\rag_pipeline tests\integration\answer_pipeline
```

### 测试结果
通过，`40 passed in 0.32s`。

### 失败修复记录
- 首次运行出现 1 个断言失败：测试用例“解释一下机翼升力”在 P1 中会被识别为 `component_scene`，但 P4 测试期待 `concept_explanation`。
- 根因是 P4 测试夹具与已验收的 P1 意图规则不一致。
- 修复方式：将该 P4 概念解释测试输入改为“解释一下升力”，未修改 P1 已验收逻辑。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 模型 Provider、结构化输出约束方式和前端 `display_blocks` 渲染协议仍为待确认。
- 当前生成器为规则化 MVP，后续接入模型时仍必须保持证据绑定和 P5 自检边界。

### 下一阶段是否可以开始
是。

## 阶段：P5

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
建立独立质量闸门，对回答做事实主张、证据对齐、场景一致性、多模态一致性、记忆/Prompt 边界和安全检查，并通过 `action_decision` 驱动回退。

### 允许修改范围
- `src/self_check/**`
- `src/core/state_machine.py` 中必要的回退边扩展
- `tests/unit/self_check/**`
- `tests/integration/answer_pipeline/**`

### 禁止修改范围
- 不得在 self_check 中直接写长期记忆。
- 不得在 self_check 中直接修改知识库资料。
- 不得让自检模块生成全新事实答案。

### 已完成内容
- 新增 claim 抽取、证据对齐、场景检查、多模态检查、边界/安全检查、评分卡和动作路由。
- `CheckReport` 输出 `score_card`、`issue_list`、`failed_checks`、`action_decision`、`revised_instruction` 和 `audit_log`。
- 高风险无证据 claim 路由到 `RETRIEVE_MORE`。
- Prompt/记忆越权作为事实来源路由到 `REWRITE_ONLY`。
- 场景对象错位路由到 `ASK_CLARIFICATION`。
- 危险操作细节路由到 `SAFE_RESPONSE`。
- 循环超过上限路由到 `STOP`。

### 修改文件
- `tests/unit/self_check/test_decision_router.py`

### 新增文件
- `src/self_check/__init__.py`
- `src/self_check/boundary_checker.py`
- `src/self_check/claim_extractor.py`
- `src/self_check/decision_router.py`
- `src/self_check/evidence_alignment.py`
- `src/self_check/multimodal_checker.py`
- `src/self_check/scene_checker.py`
- `src/self_check/score_card.py`
- `tests/unit/self_check/test_claim_support.py`
- `tests/unit/self_check/test_decision_router.py`
- `tests/integration/answer_pipeline/test_self_check_loop.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration\rag_pipeline tests\integration\answer_pipeline
```

### 测试结果
通过，`48 passed in 0.31s`。

### 失败修复记录
- 首次运行出现 1 个断言失败：测试依赖 `issue_list[0]` 的顺序，但服务按“证据对齐 -> 边界检查”的顺序追加 issue。
- 根因是测试断言过度依赖列表顺序，动作分流已正确为 `REWRITE_ONLY`。
- 修复方式：改为顺序无关地检查 `issue_list` 中存在 `non_evidence_source_used_as_fact`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 人工复核入口、正式评分阈值来源和 LLM Judge 比例仍为待确认。
- 当前阈值通过 `DecisionPolicy` 可注入，后续可接入配置文件。

### 下一阶段是否可以开始
是。

## 阶段：P6

### 完成时间
2026-07-08 00:00 Asia/Shanghai

### 阶段目标
处理用户对上一轮回答的反馈，在证据锁定、来源保持和自检复核下做局部改写，并生成候选记忆。

### 允许修改范围
- `src/feedback/**`
- `src/memory/**` 中 `memory_update_candidate` 接口
- `tests/unit/feedback/**`
- `tests/integration/feedback_loop/**`

### 禁止修改范围
- 不得在 feedback 模块直接修改知识库事实。
- 不得在 feedback 模块直接把偏好写成 active 长期记忆。
- 不得跳过 P5 自检输出改写结果。

### 已完成内容
- 新增反馈意图解析、checkpoint 保存、证据锁定、rewrite planner、受控 rewriter 和 delta_map。
- 表达类反馈保留 `source_binding`；表格转换对无证据维度标记“资料未覆盖”。
- `FACT_CHALLENGE` 生成核查查询和不确定性说明，不直接覆盖事实。
- 安全敏感反馈输出 `SAFE_RESPONSE`，不保留可执行危险步骤。
- 稳定偏好只生成 `memory_update_candidate`，交回 P2 治理。

### 修改文件
- 无

### 新增文件
- `src/feedback/__init__.py`
- `src/feedback/checkpoint_store.py`
- `src/feedback/delta_map.py`
- `src/feedback/evidence_lock.py`
- `src/feedback/feedback_event.py`
- `src/feedback/parser.py`
- `src/feedback/rewrite_planner.py`
- `src/feedback/rewriter.py`
- `tests/unit/feedback/test_evidence_lock.py`
- `tests/unit/feedback/test_feedback_parser.py`
- `tests/integration/feedback_loop/test_rewrite_preserves_sources.py`

### 删除文件
- 无

### 测试命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration
```

### 测试结果
通过，`57 passed in 0.26s`。

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 是否违反 harness.md
否。

### 未完成事项
- 前端是否展示 `delta_map`、最大改写轮数的正式产品值仍为待确认。
- 当前 checkpoint 为内存实现，生产持久化策略待确认。

### 下一阶段是否可以开始
本轮用户目标到 P6 结束；P7/P8 未纳入本次目标，暂不开始。

## 最终验收记录：P0-P6

### 验收时间
2026-07-08 00:00 Asia/Shanghai

### 指定运行环境
- `D:\APP\Python 3.13\Internet\.venv`
- Python 3.11.9
- pytest 9.0.3

### 验收命令
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m compileall -q src scripts
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe scripts\ingest_sources.py
```

### 验收结果
- 全量测试通过：`57 passed in 0.22s`。
- 编译检查通过：`compileall` 退出码为 0。
- 入库脚本启动检查通过：输出 `registered_sources=1 chunks=1`。

### 阶段范围确认
- P0 到 P6 均已有阶段记录。
- 系统工程目录按 `docs/spec.md` 的 P0-P6 范围建立。
- 功能范围限于 `docs/task.md` 的 P0-P6，未提前实现 P7/P8。
- 代码边界未绕过 `docs/harness.md`。

### 剩余待确认
- P7/P8 未执行，因为本轮用户目标明确到 P6。
- 真实 Provider、数据库、前端协议、持久化、人工复核入口和正式部署策略仍按阶段文档保留为待确认。

## 阶段：P0+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
固化当前 MVP 与 plus 工业级升级目标之间的真实差距，建立 P1+ 到 P9+ 的审计基线和 baseline 测试记录。

### 完成内容
- 完整阅读 `docs/task_plus.md`、`docs/spec_plus.md`、`docs/harness_plus.md`、原始三件套、`docs/AUTO_DEV.md`、`docs/STATUS.md` 和 `AGENTS.md`。
- 扫描当前真实目录结构、配置、脚本、测试和文档。
- 新增 `docs/upgrade_audit.md`，记录 MVP 状态、模块差距、Mock/Offline 策略、命名一致性和后续阶段风险。
- 核验未发现 `deepseek-flash 2`，确认 `providers.yaml` 仍为 `pending_confirmation`。
- 明确 `SimpleVectorIndex` 是 token similarity fallback，`GroundedAnswerGenerator` 是规则化 fallback，`VoiceTurnEvent` 只是语音契约。
- 运行 baseline 全量 pytest。

### 修改文件
- `docs/STATUS.md`

### 新增文件
- `docs/upgrade_audit.md`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

### 测试结果
通过，`57 passed in 0.31s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `brainstorming`（以既有 plus 文档作为已确认设计，不设置额外人工确认门）
- `writing-plans`（以 `docs/spec_plus.md` 作为阶段级实施计划来源）
- `verification-before-completion`
- `browser:control-in-app-browser`（已按文档读取；P0+ 不涉及浏览器验证）

### 风险与待确认
- DeepSeek 真实 `model_id`、base_url、结构化输出能力待 P3+ 配置核验。
- CLI / HTTP API 优先级待 P2+ 按 CLI-first、HTTP optional 处理。
- 向量库、OCR/PDF layout、语音 Provider、评测框架均存在 Python 3.13 + Windows 兼容性风险。
- 父级 Git 仓库包含大量与本项目无关的变更，P0+ 未处理这些外部状态。

### 是否可以进入下一阶段
可以。

## 阶段：P9+

### 完成时间
2026-07-09 12:57:51 Asia/Shanghai

### 阶段目标
完成 P0+ 到 P9+ 的全链路工业级验收与演示交付，明确区分 Mock/Offline 验收和真实 Provider / 生产环境验收。

### 完成内容
- 重新读取目标文件 `C:\Users\SONGQI\.codex\attachments\da81fb58-5beb-425e-97e9-da1e4ad9be27\goal-objective.md`，确认本轮目标为 P0+ 到 P9+ 全自动顺序升级，真实 Provider 缺失不是阻塞条件。
- 核对 `docs/task_plus.md`、`docs/spec_plus.md`、`docs/harness_plus.md` 中 P9+ 要求，确认 P9+ 只做交付验收与文档，不修改业务逻辑。
- 运行 P9+ 必需验收命令：全量 pytest、compileall、smoke eval、trace export、deployment validate。
- 额外验证 CLI query smoke 与 `AppPipeline.health_check()`，确认应用入口和 service facade 可运行。
- 新增 `docs/release_acceptance.md`，汇总验收结论、证据、风险清单和真实 Provider 待接入项。
- 新增 `docs/demo_audit_report.md`，记录可复现演示命令、演示场景、trace 审计和演示边界。
- 更新 `README.md`，补充 P9+ 验收与演示审计入口。
- 明确最终结论：已达到工业级工程架构与 Mock/Offline 全链路验收标准；真实 Provider 接入与生产环境验收待后续材料补齐后执行。

### 修改文件
- `README.md`
- `docs/STATUS.md`
- `docs/trace_reports/p9_trace_acceptance.md`（trace export 验证产物）

### 新增文件
- `docs/release_acceptance.md`
- `docs/demo_audit_report.md`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id p9_trace_acceptance
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m app.cli --query "解释一下升力" --run-id "p9_cli_smoke_final" --no-trace
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -c "import json; from services.app_pipeline import AppPipeline; print(json.dumps(AppPipeline().health_check().to_dict(), ensure_ascii=False))"
```

### 测试结果

通过。

- 全量 pytest：`99 passed in 1.95s`，退出码 0。
- compileall：`COMPILEALL=ok`。
- smoke eval：`case_count=3`，`passed_count=3`，`pass_rate=1.0`，`mock_offline=true`，退出码 0。
- trace export：生成 `docs/trace_reports/p9_trace_acceptance.md`，`mock_offline=true`，退出码 0。
- deployment validate：`status=ok`，`missing_files=[]`，`provider_profile=mock`，`real_provider_pending=true`，退出码 0。
- CLI query smoke：退出码 0；无资料时返回 `clarification`，不伪造证据。
- `AppPipeline.health_check()`：返回 `{"status":"ok","entry_mode":"cli","trace_enabled":true}`，退出码 0。

### 补充探测记录
- 曾尝试 `python -m app.cli --health`，返回退出码 2。系统性排查后确认根因是当前 CLI 契约没有 `--health` 参数，health check 位于 `AppPipeline.health_check()`；P9+ 禁止临时修改业务逻辑，因此未新增 CLI 参数，改用契约内 `--query` 和 service facade health 验证入口。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：是
* 是否使用 Mock ASR/TTS：是
* 是否使用 mock embedding：是
* 是否使用 mock visual adapter：是
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`（以 `docs/spec_plus.md` 作为已确认阶段计划；用户要求全自动执行，不新增人工确认门）
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- 真实 DeepSeek API Key、真实 `model_id`、base_url、成本、限流和线上调用验收待后续接入。
- 真实数据库、真实持久化、真实生产部署和运维监控待后续接入。
- 真实 ASR/TTS、麦克风链路、音频保存和隐私策略待后续接入。
- 真实 OCR/PDF layout Provider 和视觉页面检索生产闭环待后续接入。
- 当前 eval 为轻量自研 smoke/mock 评测，不代表真实模型评测或第三方评测框架验收。
- 当前 trace export 为本地 Markdown 产物，不代表生产 observability collector。

### 是否可以进入下一阶段

P0+ 到 P9+ 已完成；本轮全自动升级目标可以结束。后续如需继续，应另开真实 Provider / 生产环境接入阶段。

## 阶段：方案 B 架构修复（P9+ 后）

### 完成时间
2026-07-09 14:05:57 Asia/Shanghai

### 阶段目标
执行用户确认的方案 B：用结构性重构修复 Agent 决策只记录不执行、RAG reviewed chunk 相关性不足仍 confident、安全意图优先级不足、检索排序依赖插入顺序等逻辑问题。

### 完成内容
- 新增 `AgentRuntime`，`AppPipeline` 不再内嵌旧线性业务流程，只委托 runtime 执行。
- 新增 `AgentActionExecutor`，让 `SAFE_RESPONSE`、`ASK_CLARIFICATION`、`REWRITE_ONLY`、`RETRIEVE_MORE`、`STOP` 具备真实执行路径。
- 新增 `safety.policy`，统一检测可执行危险操作细节，并避免把拒绝句误判为危险步骤。
- 重构 `GroundedAnswerGenerator` 的 operation safety 输出，安全意图不复述危险 evidence 原文。
- 新增 `EvidenceCandidate`、`EvidenceRanker`、`EvidenceEligibilityPolicy`，将 RAG 改为 candidate -> rank -> eligibility -> evidence package。
- 删除旧 `src/knowledge/indexes/hybrid_index.py`，不再保留插入顺序式 hybrid 合并路径。
- `RetrievalController` 不再用 `chunks_by_id` 直接收集证据；只有通过相关性资格审查的 reviewed chunk 才进入 `EvidencePackage.evidence_items`。
- `QueryObject` 新增 `safety_flags`，`QueryUnderstandingService` 将维修、故障处置、操作、拆卸、改装等安全意图置于部件意图之前。
- 将缺失证据语义统一为 `no_qualified_evidence`，并在 demo audit 文档中同步更新。

### 修改文件
- `docs/STATUS.md`
- `docs/demo_audit_report.md`
- `docs/release_acceptance.md`
- `src/services/app_pipeline.py`
- `src/input/query_object.py`
- `src/input/query_understanding.py`
- `src/generation/generator.py`
- `src/self_check/boundary_checker.py`
- `src/knowledge/retrieval_controller.py`
- `tests/integration/rag_pipeline/test_basic_retrieval.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`
- `tests/integration/app_loop/test_agent_decision_execution.py`

### 新增文件
- `src/agent/__init__.py`
- `src/agent/actions.py`
- `src/agent/runtime.py`
- `src/safety/__init__.py`
- `src/safety/policy.py`
- `src/knowledge/evidence_ranking.py`
- `src/knowledge/evidence_policy.py`
- `tests/unit/input/test_safety_intent_priority.py`
- `tests/unit/knowledge/test_evidence_ranker.py`
- `tests/unit/self_check/test_safety_policy.py`
- `tests/integration/rag_pipeline/test_evidence_relevance_gate.py`
- `docs/trace_reports/scheme_b_refactor_acceptance.md`

### 删除文件
- `src/knowledge/indexes/hybrid_index.py`

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\input\test_safety_intent_priority.py tests\integration\rag_pipeline\test_evidence_relevance_gate.py tests\unit\knowledge\test_evidence_ranker.py tests\integration\app_loop\test_agent_decision_execution.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\self_check\test_safety_policy.py tests\integration\app_loop\test_agent_decision_execution.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id scheme_b_refactor_acceptance
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
```

### 测试结果

通过。

- 方案 B 目标测试：`8 passed`。
- 安全策略与 Agent 决策测试：`4 passed`。
- 全量 pytest：`109 passed`，退出码 0。
- compileall：`COMPILEALL=ok`。
- smoke eval：`case_count=3`，`passed_count=3`，`pass_rate=1.0`，`mock_offline=true`。
- trace export：生成 `docs/trace_reports/scheme_b_refactor_acceptance.md`，`mock_offline=true`。
- deployment validate：`status=ok`，`missing_files=[]`，`provider_profile=mock`，`real_provider_pending=true`。

### 关键回归场景
- reviewed 资料只有“发动机提供推力”时，询问“解释一下阻力”不会进入 confident evidence，也不会回答发动机推力。
- mock vector store 单独弱命中不能支撑参数或事实回答。
- 普通问答误检索到危险操作资料时，`SAFE_RESPONSE` 会替换原稿，不返回危险步骤。
- 场景对象与回答目标不一致时，`ASK_CLARIFICATION` 返回澄清 answer，不返回原回答。
- 安全拒绝句不会被安全策略误判为危险操作步骤。

### harness_plus.md 边界检查

* 是否跳阶段：否，本节为 P9+ 后用户明确要求的方案 B 修复。
* 是否提前实现后续阶段：否。
* 是否污染全局 Python 环境：否。
* 是否硬编码密钥或模型配置：否。
* 是否删除测试规避失败：否。
* 是否把 MVP 占位描述为工业级能力：否。
* 是否把 Mock/Offline 验收描述为真实生产验收：否。
* 是否引入未确认大型依赖：否。
* 是否访问真实外部服务：否。

### Mock / Offline 状态

* 是否使用 MockModelClient：是。
* 是否使用 Mock ASR/TTS：是。
* 是否使用 mock embedding：是，但 mock vector 命中不能单独支撑 confident evidence。
* 是否使用 mock visual adapter：是。
* 真实 Provider 是否待后续接入：是。

### 风险与待确认
- 真实 embedding provider、真实向量库和线上 RAG 评测仍待后续接入。
- 真实 DeepSeek、真实数据库、真实 ASR/TTS、真实 OCR/PDF layout 和生产环境仍待后续接入。
- 当前方案 B 修复的是 Mock/Offline 架构正确性，不代表真实 Provider 验收。

### 是否可以进入下一阶段

方案 B 已完成；后续可进入真实 Provider / 生产环境接入阶段。

## 阶段：P8+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
建立轻量自研评测体系、E2E 场景、trace 导出和部署配置校验，使 Mock/Offline 链路可评测、可审计、可验证。

### 完成内容
- 新增 `configs/evals.yaml`，定义 smoke suite、报告目录、部署校验文件和 mock profile 规则。
- 新增 `TraceReportExporter`，可将 `RunTrace` 导出为 Markdown。
- 新增 `scripts/run_eval.py --suite smoke`，通过正式 `AppPipeline` 和 `MockVoiceLoop` 执行文本、证据不足、语音 mock 三类 smoke case。
- 新增 `scripts/export_trace_report.py --run-id <run_id>`，生成离线 trace report。
- 新增 `scripts/validate_deployment.py`，检查必要配置文件和 mock provider profile。
- 新增 `docs/eval_plan.md` 和 `docs/deployment_checklist.md`。
- 新增 E2E 场景：文本问答、反馈改写、语音 mock、eval/trace/deployment 脚本。
- 运行 smoke eval、trace export 和 deployment validate，并生成 `docs/eval_reports/smoke_eval.json`、`docs/trace_reports/e2e_trace.md`、`docs/trace_reports/p8_trace_smoke.md`。

### 修改文件
- `docs/STATUS.md`

### 新增文件
- `configs/evals.yaml`
- `src/observability/__init__.py`
- `src/observability/trace_exporter.py`
- `scripts/run_eval.py`
- `scripts/export_trace_report.py`
- `scripts/validate_deployment.py`
- `docs/eval_plan.md`
- `docs/deployment_checklist.md`
- `docs/eval_reports/smoke_eval.json`
- `docs/trace_reports/e2e_trace.md`
- `docs/trace_reports/p8_trace_smoke.md`
- `tests/e2e/scenarios/test_text_qa_flow.py`
- `tests/e2e/scenarios/test_feedback_flow.py`
- `tests/e2e/scenarios/test_voice_flow.py`
- `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\e2e -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id p8_trace_smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
```

### 测试结果
通过，E2E `7 passed`，全量测试 `99 passed in 0.79s`，smoke eval `pass_rate=1.0`，trace export 和 deployment validate 均退出码 0。

### 失败修复记录
- 首次 E2E 出现 2 个失败：trace export 子进程 stdout 在 Windows 中文路径下按 UTF-8 解码失败；feedback E2E 调用了不存在的 `EvidenceLock.build()` 接口。
- 根因分别是脚本 JSON 输出使用非 ASCII 路径文本、E2E 测试未按真实 feedback 函数式接口调用。
- 修复方式：脚本 stdout JSON 改为 ASCII 转义并设置子进程 `PYTHONIOENCODING=utf-8`；feedback E2E 改用 `build_evidence_lock()`、`parse_feedback()`、`plan_rewrite()` 和 `rewrite_answer()`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：是
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- 当前 eval 为轻量自研 smoke/mock 评测，不代表 RAGAS/DeepEval/TruLens 或真实模型评测。
- trace export 基于本地 mock run，不代表生产 observability collector。
- deployment validate 仅验证本地 Mock/Offline 配置完整性，真实生产环境待后续接入。

### 是否可以进入下一阶段
可以。

## 阶段：P7+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
补齐 Mock-first 语音交互模块，包括语音状态机、Mock ASR/TTS、VAD、barge-in、voice metrics、voice query object 和主 pipeline 入口。

### 完成内容
- 新增 `configs/voice.yaml`，默认 ASR/TTS/VAD 均为 mock，原始音频默认不落盘。
- 新增语音状态机，覆盖 `IDLE`、`LISTENING`、`TRANSCRIBING`、`UNDERSTANDING`、`RETRIEVING`、`GENERATING`、`SPEAKING`、`INTERRUPTED`、`REWRITE`、`CLARIFY`。
- 新增 `MockASRProvider`、`MockTTSProvider`、`MockVADService`、`BargeInController`、`VoiceMetricsRecorder` 和 mock transport。
- 新增航空术语纠错，覆盖“航道比 -> 涵道比”“鸡翼 -> 机翼”。
- 新增 `VoiceQueryNormalizer` 和 `VoiceQueryObject`，低置信 ASR 标记 clarification，不进入正式检索。
- 新增 `MockVoiceLoop`，高置信语音输入进入 `AppPipeline`，不绕过 RAG/生成/自检链路。
- 新增 voice 单元测试和 mock voice loop 集成测试。

### 修改文件
- `docs/STATUS.md`

### 新增文件
- `configs/voice.yaml`
- `src/voice/__init__.py`
- `src/voice/session_state.py`
- `src/voice/asr.py`
- `src/voice/tts.py`
- `src/voice/vad.py`
- `src/voice/terminology.py`
- `src/voice/barge_in.py`
- `src/voice/metrics.py`
- `src/voice/transport.py`
- `src/voice/voice_loop.py`
- `src/input/voice_query_normalizer.py`
- `tests/unit/voice/test_voice_state_machine.py`
- `tests/unit/voice/test_query_normalizer.py`
- `tests/unit/voice/test_voice_components.py`
- `tests/integration/voice_loop/test_mock_voice_loop.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\voice tests\integration\voice_loop -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "raw_audio|persist|MockASR|MockTTS|low_confidence|barge|VoiceState|ASRResult|TTSResult|voice_query" src configs tests\unit\voice tests\integration\voice_loop
```

### 测试结果
通过，voice 专项测试 `9 passed`，全量测试 `92 passed in 0.47s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：是
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- 当前语音链路为 Mock ASR/TTS/VAD，不代表真实麦克风、真实 ASR/TTS Provider 或低延迟实时语音能力。
- 原始音频默认不落盘；真实音频保存策略需后续产品与隐私确认。
- 真实 Provider、浏览器音频协议和 WebSocket/WebRTC 接入待后续阶段确认。

### 是否可以进入下一阶段
可以。

## 阶段：P6+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
将 Prompt 从内置小型 store 升级为可版本化、可快照、可回滚的资产库，覆盖教学、知识讲解、语音、自检和兜底模板边界。

### 完成内容
- `configs/prompts.yaml` 新增 `asset_dir`、`snapshot_dir` 和 rollback policy。
- 新增 `assets/prompts/**` 外置 Prompt 资产，覆盖教学解释、知识讲解、语音候选、自检和兜底模板。
- Prompt 资产增加 `risk_boundaries`、`snapshot_id`，Prompt 内容版本增加 `snapshot_id`。
- `PromptAssetStore.from_config()` 优先读取外置资产目录，缺失时保留旧默认模板 fallback。
- `PromptAssetStore` 支持同一 template_id 的版本历史、`activate_version()` 和 `rollback_prompt()`。
- 未审核的 `aviation_voice_spoken` 保持 `candidate` 状态，不能进入 runtime active。
- 新增 Prompt snapshot fixture，并测试 active asset 与 snapshot 对齐。
- 新增版本化、candidate 阻断、快照和 rollback 测试。

### 修改文件
- `configs/prompts.yaml`
- `src/prompts/asset_models.py`
- `src/prompts/asset_store.py`
- `docs/STATUS.md`

### 新增文件
- `assets/prompts/aviation_basic_safe.yaml`
- `assets/prompts/aviation_explain_active.yaml`
- `assets/prompts/aviation_knowledge_explain.yaml`
- `assets/prompts/aviation_self_check.yaml`
- `assets/prompts/aviation_voice_spoken.yaml`
- `tests/fixtures/prompt_snapshots/aviation_explain_active_v2.json`
- `tests/unit/prompts/test_prompt_versioning.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\prompts -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "status: candidate|status: active|snapshot_id|parent_version|rollback|asset_dir|candidate_not_runtime|PromptAssetStore|activate_version" assets configs src\prompts tests\unit\prompts
```

### 测试结果
通过，Prompt 专项测试 `12 passed`，全量测试 `83 passed in 0.40s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- Prompt 内容仍是离线初始模板，需要后续人工评审和真实评测反馈后再扩大 active 范围。
- `aviation_voice_spoken` 已存在但保持 candidate，P7+ 可在语音链路中继续完善，未提前启用。
- Prompt snapshot 目前为轻量 fixture，P8+ 需纳入正式 eval/smoke 回归。

### 是否可以进入下一阶段
可以。

## 阶段：P5+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
补齐多模态对象、页面区域、bbox、layout trace、视觉证据契约和 mock visual adapter，明确缺少 OCR/layout 时的 incomplete 状态。

### 完成内容
- 新增 `ImageInput`、`BoundingBox`、`LayoutTrace`、`PageRegion`、`VisualEvidenceItem` 契约。
- 将 `VisualAsset.bbox` 从裸 tuple 升级为 `BoundingBox`，并增加 `layout_trace`、`incomplete_reasons` 和 metadata。
- 新增 `MockVisualAdapter`，默认输出 mock region，并标记 `visual_evidence_incomplete`。
- `PdfIngestor` 的 mock layout trace 明确标记 OCR/layout 未启用。
- `VisualIngestor` 可将 PageRegion 转为 VisualEvidenceItem；无 text cross-check 时不可作为核心事实证据。
- `VisualPageIndex` 支持 PageRegion 的索引与标签检索。
- `EvidencePackageBuilder` 新增视觉证据转换入口，保留 bbox、layout trace、text cross-check 和 incomplete reasons。
- `configs/rag.yaml` 补齐 visual_search、OCR/layout 开关、text cross-check 要求和视觉处理限制。
- 新增多模态契约测试，覆盖 bbox、layout trace、mock adapter、visual evidence gate。

### 修改文件
- `configs/rag.yaml`
- `src/knowledge/schemas.py`
- `src/knowledge/ingestion/pdf_ingestor.py`
- `src/knowledge/ingestion/visual_ingestor.py`
- `src/knowledge/indexes/visual_page_index.py`
- `src/knowledge/evidence_package.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`
- `docs/STATUS.md`

### 新增文件
- `tests/unit/knowledge/test_multimodal_contracts.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\knowledge tests\integration\rag_pipeline tests\unit\self_check -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "visual_evidence_incomplete|missing_text_cross_check|text_cross_check|usable_as_core_evidence|BoundingBox|LayoutTrace|PageRegion|VisualEvidenceItem|MockVisualAdapter" src tests configs
```

### 测试结果
通过，knowledge/RAG/self_check 相关测试 `19 passed`，全量测试 `78 passed in 0.48s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：是
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- 当前视觉处理为 mock adapter，不代表真实 OCR/PDF layout 能力。
- 无 text cross-check 的视觉线索只可作为候选或 incomplete evidence，不能支撑核心航空事实。
- 真实 OCR/PDF layout Provider 和大图处理策略待后续接入。

### 是否可以进入下一阶段
可以。

## 阶段：P4+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
将 RAG 从仅有 token similarity 的 MVP 检索升级为具备 `EmbeddingProvider`、`VectorStore`、mock embedding 和明确 fallback trace 的工程化知识库边界。

### 完成内容
- 新增 `EmbeddingProvider` 协议、`EmbeddingResult` 和 `MockEmbeddingProvider`。
- 新增 `VectorStore` 协议、`VectorSearchResult` 和 `InMemoryVectorStore`。
- 为 `SimpleVectorIndex` 增加 `simple_token_similarity`、`is_embedding_index=false` 和 fallback reason 标识。
- `RetrievalController` 默认使用 `MockEmbeddingProvider + InMemoryVectorStore`，同时保留 token similarity fallback。
- 检索 trace 新增 embedding provider、是否 mock、vector store provider、fallback index 和 fallback reason。
- `configs/rag.yaml` 补齐 embedding provider、dimension、vector store provider、fallback index 和 trace 开关。
- 新增 VectorStore/EmbeddingProvider 单元测试和 RAG trace 集成测试。

### 修改文件
- `configs/rag.yaml`
- `src/knowledge/indexes/vector_index.py`
- `src/knowledge/retrieval_controller.py`
- `docs/STATUS.md`

### 新增文件
- `src/services/embedding_provider.py`
- `src/knowledge/indexes/vector_store.py`
- `tests/unit/knowledge/test_vector_store_contract.py`
- `tests/integration/rag_pipeline/test_vector_adapter_trace.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\knowledge tests\integration\rag_pipeline -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "simple_token_similarity|token similarity|fallback|embedding_provider|VectorStore|EmbeddingProvider|SimpleVectorIndex" src configs tests docs\STATUS.md docs\upgrade_audit.md
```

### 测试结果
通过，RAG/knowledge 相关测试 `9 passed`，全量测试 `74 passed in 0.39s`。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：是
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- 当前 embedding provider 为 mock，vector store 为内存实现，不代表真实向量库生产能力。
- Chroma、FAISS、sentence-transformers 未引入，仍待 Python 3.13 + Windows 兼容性核验。
- `SimpleVectorIndex` 保留为 token similarity fallback，不得描述为工业级 embedding 检索。

### 是否可以进入下一阶段
可以。

## 阶段：P3+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
建立 `deepseek-flash` 统一模型调用层、MockModelClient、DeepSeek adapter 边界、结构化输出校验、重试策略和规则生成 fallback。

### 完成内容
- 新增 `ModelClient` 协议、`ModelMessage`、`ModelOptions`、`ModelResult` 和模型错误分类。
- 新增 `MockModelClient`，默认可在无密钥环境下返回结构化输出。
- 新增 `DeepSeekModelClient` adapter，使用标准库 HTTP 客户端，缺少密钥或 model_id 时返回结构化错误，不访问真实网络。
- 新增 `StructuredOutputValidator` 和 `ANSWER_ENVELOPE_SCHEMA`，校验模型输出必须为 JSON object 且包含核心字段。
- 新增轻量 `RetryPolicy`，覆盖可重试限流错误。
- 将 `GroundedAnswerGenerator` 改为可选注入 `ModelClient`；模型输出有效时生成结构化 answer，模型失败或结构化错误时回落规则 generator。
- 补齐 `configs/providers.yaml` 中 DeepSeek adapter 的 `chat_path`、fallback、日志和 rate limit 配置项。
- 新增模型服务和生成器回退测试。
- 搜索确认 `urllib`、Authorization、DeepSeek adapter 只存在于 `src/services/**`，业务模块无直连模型 API。

### 修改文件
- `configs/providers.yaml`
- `src/generation/generator.py`
- `docs/STATUS.md`

### 新增文件
- `src/services/model_client.py`
- `src/services/deepseek_client.py`
- `src/services/structured_output.py`
- `src/services/retry_policy.py`
- `tests/unit/services/test_model_client.py`
- `tests/unit/generation/test_model_generation.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\services tests\unit\generation -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "deepseek|DeepSeek|urllib|httpx|requests|Authorization|Bearer" src tests configs docs -g '!src/services/**'
```

### 测试结果
通过，服务/生成测试 `12 passed`，全量测试 `71 passed in 0.40s`。边界搜索未发现业务模块直连模型 API。

### 失败修复记录
- 首次新增模型测试出现 1 个失败：结构化校验将 `claim_candidates: []` 误判为缺失字段。
- 根因是 validator 把空列表等同于缺字段，但空 claim 列表对澄清或无事实主张回答是合法结构。
- 修复方式：缺失判定改为字段不存在、`None`、空字符串或空对象；空列表保留为合法值。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：是
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- `DeepSeekModelClient` 仅实现 adapter 边界，默认不启用真实调用；真实 `DEEPSEEK_MODEL`、base_url、密钥和线上验收待后续接入。
- P3+ 没有把规则 generator 描述为真实 LLM 能力；规则路径仍是 fallback。
- P6+ 仍需将 prompt 资产进一步外置和版本化，当前 P3+ 只保留最小结构化输出指令。

### 是否可以进入下一阶段
可以。

## 阶段：P2+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
建立 CLI-first 应用入口、service/pipeline facade、健康检查、`run_id`、统一结构化错误响应和 app loop smoke 测试。

### 完成内容
- 新增 `src/services/app_pipeline.py`，串联现有 input、retrieval、generation、self_check 模块，API/CLI 不复制业务逻辑。
- 新增 `TextQueryRequest`、`TextQueryResponse`、`HealthStatus` 和 `ErrorResponse` 等本地 API 契约。
- 新增 CLI 入口 `src/app/cli.py` 和 `src/app/main.py`，输出 UTF-8 JSON。
- `configs/app.yaml` 补齐 `entry_mode`、host、port 和 trace 开关。
- 新增 `docs/api_contracts.md`，记录 P2+ 请求、响应、错误和 CLI 命令契约。
- 新增 app loop 集成测试，覆盖 health check、pipeline smoke、结构化错误和 CLI 子进程 smoke。
- 直接运行 CLI smoke，确认无资料时返回结构化 clarification，不伪造证据。

### 修改文件
- `configs/app.yaml`
- `docs/STATUS.md`

### 新增文件
- `docs/api_contracts.md`
- `src/app/__init__.py`
- `src/app/cli.py`
- `src/app/main.py`
- `src/app/api/__init__.py`
- `src/app/api/errors.py`
- `src/app/api/schemas.py`
- `src/services/__init__.py`
- `src/services/app_pipeline.py`
- `tests/integration/app_loop/test_cli_pipeline.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\integration\app_loop -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m app.cli --query "解释一下升力" --run-id "run_p2_cli_smoke" --no-trace
```

### 测试结果
通过，app loop 测试 `4 passed`，全量测试 `64 passed in 0.38s`，CLI smoke 退出码为 0。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：不涉及
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 风险与待确认
- HTTP API 仍为 optional，未引入 FastAPI/uvicorn；如后续需要，须先确认 Python 3.13 + Windows 兼容性。
- 默认 CLI 不预置真实资料库，因此无资料时返回保守 clarification；演示资料与 eval 数据在后续阶段治理。
- P2+ 不接入模型，P3+ 仍需实现统一 ModelClient 和结构化生成边界。

### 是否可以进入下一阶段
可以。

## 阶段：P1+

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
完成 UTF-8 中文编码回归、Mock-first provider 配置补齐、`.env.example` 和 Windows 虚拟环境命令文档化，并保持全量测试通过。

### 完成内容
- 新增 UTF-8 文本文件扫描测试，覆盖项目 Markdown、Python、YAML、TOML、TXT 和 `.env.example` 等文本文件。
- 新增中文检索到规则生成的回归测试，确认中文航空术语可在本地链路中保持 UTF-8。
- 将 `configs/providers.yaml` 从 `pending_confirmation` 调整为默认 `mock` profile，并保留 DeepSeek adapter 的环境变量边界与 `model_id: pending_confirmation`。
- 补齐 `.env.example` 中 DeepSeek 相关环境变量名，不写入真实密钥或真实服务地址。
- 更新 `README.md`，明确必须使用指定虚拟环境解释器运行 pytest，并声明 Mock-first / Offline-first 策略。
- 更新已有配置加载测试，使其匹配 P1+ 的 mock provider 默认值。

### 修改文件
- `.env.example`
- `README.md`
- `configs/providers.yaml`
- `tests/unit/core/test_state_machine.py`
- `docs/STATUS.md`

### 新增文件
- `tests/unit/core/test_encoding_and_config.py`

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\core\test_encoding_and_config.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

### 测试结果
通过，新增测试 `3 passed`，全量测试 `60 passed in 0.26s`。

### 失败修复记录
- 首次运行新增 UTF-8 扫描测试时出现 1 个失败：测试文件自身包含要检测的乱码标记常量，扫描到自身后误报。
- 根因是测试夹具把“坏样例”写进了被扫描范围，不是项目文件出现乱码。
- 修复方式：将乱码标记改为运行时按 Unicode code point 构造，保持扫描标准不变。

### harness_plus.md 边界检查

* 是否跳阶段：否
* 是否提前实现后续阶段：否
* 是否污染全局 Python 环境：否
* 是否硬编码密钥或模型配置：否
* 是否删除测试规避失败：否
* 是否把 MVP 占位描述为工业级能力：否
* 是否把 Mock/Offline 验收描述为真实生产验收：否
* 是否引入未确认大型依赖：否
* 是否访问真实外部服务：否

### Mock / Offline 状态

* 是否使用 MockModelClient：不涉及
* 是否使用 Mock ASR/TTS：不涉及
* 是否使用 mock embedding：是，配置默认 `providers.embedding.default: mock`
* 是否使用 mock visual adapter：不涉及
* 真实 Provider 是否待后续接入：是

### 实际调用过的关键 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 风险与待确认
- 当前只是配置层 mock profile，真正 `MockModelClient` 和 DeepSeek adapter 在 P3+ 实现。
- `DEEPSEEK_MODEL`、真实 base_url 和结构化输出能力仍待 P3+ 配置核验。
- 向量检索仍未升级，`simple_token_similarity` 只作为 P4+ 前的 fallback 标记。

### 是否可以进入下一阶段
可以。

## 阶段：Task 2 (P1 Prompt Asset Repository Migration)

### 完成时间
2026-07-09 Asia/Shanghai

### 阶段目标
修复 Task 2 评审问题，收紧 Prompt Repository 校验，并统一 assembler 输出契约。

### 已完成内容
- 为 `PromptAssetRepository` 增加运行态版本 `snapshot_id` 与评估快照 `snapshot_id` 的严格一致性校验。
- 新增聚焦回归测试，验证快照 id 不一致时 repository 会拒绝加载运行态 prompt。
- 删除 `src/prompts/assembler.py` 中重复定义的 `MessageBundle`。
- `PromptAssembler` 现在返回 canonical `PromptMessageBundle`。
- `PromptMessageBundle.messages` 现在只包含 `services.model_client.ModelMessage`。
- assembler 输出只使用标准角色 `system` 和 `user`。
- 清理 `docs/STATUS.md` 与 `docs/superpowers/plans/_sdd/task-2-report.md` 中 Task 2 相关乱码和无关流程描述。

### 修改文件
- `src/prompts/repository.py`
- `tests/unit/prompts/test_prompt_repository.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `docs/STATUS.md`
- `docs/superpowers/plans/_sdd/task-2-report.md`

### 新增文件
- 无

### 删除文件
- 无

### 测试命令
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q
```

### 测试结果
- `tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q`：`9 passed`
- `tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q`：`14 passed`
- 第二条命令通过。

### 是否违反 harness.md
否。

### 未完成事项
- 无新的 Task 2 阻塞项。

### 下一阶段是否可以开始
可以。

```

## docs\superpowers\plans\_sdd\task-2-report.md

```
# Task 2 Report: Review Fixes

## Summary

This update fixes the reviewed Task 2 issues without restoring `PromptAssetStore`, YAML prompt assets, or compatibility shims.

## Fixes

### Repository validation

- `PromptAssetRepository` now verifies that the runtime version `snapshot_id` exactly matches the loaded evaluation snapshot `snapshot_id`.
- Runtime `active` and `experimental` versions still require a present snapshot and `final_decision == "approve"`.
- Added a focused regression test proving a mismatched snapshot id is rejected.

### Assembler contract

- Removed the duplicate `MessageBundle` contract from `src/prompts/assembler.py`.
- `PromptAssembler` now returns the canonical `PromptMessageBundle`.
- `PromptMessageBundle.messages` now contains only `services.model_client.ModelMessage`.
- Message roles now use only `system` and `user`.
- Updated assembler tests to validate the canonical contract.

### Documentation cleanup

- Rewrote this report in clean text.
- Rewrote the Task 2 appended entry in `docs/STATUS.md` to remove mojibake and irrelevant workflow prose.

## Files changed

- `src/prompts/repository.py`
- `tests/unit/prompts/test_prompt_repository.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `docs/STATUS.md`
- `docs/superpowers/plans/_sdd/task-2-report.md`

## Test execution record

### Command 1

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q
```

Output:

```text
.........                                                                [100%]
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py tests/unit/prompts/test_prompt_assembler.py -q
```

Output:

```text
..............                                                           [100%]
```

## Result

- Required prompt tests passed.
- The second command also passed cleanly on the final verification run.
```

## assets\prompts\asr_correction_prompt\asset.json

```
{
  "template_id": "asr_correction_prompt",
  "title": "ASR correction prompt",
  "task_type": "asr_correction",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "asr_correction"
  ],
  "concept_scope": [
    "speech_normalization"
  ],
  "scene_scope": [
    "voice",
    "scene"
  ],
  "constraints": [
    "normalize_terms_without_adding_facts",
    "surface_uncertain_transcripts"
  ],
  "risk_boundaries": [
    "no_fact_completion_from_partial_audio"
  ]
}
```

## assets\prompts\asr_correction_prompt\evaluations\asr_correction_prompt_v2.json

```
{
  "snapshot_id": "asr_correction_prompt_v2",
  "template_id": "asr_correction_prompt",
  "version": "v2",
  "test_cases": [
    "partial_audio",
    "uncertain_term",
    "scene_reference"
  ],
  "dimension_scores": {
    "fidelity": 0.95,
    "uncertainty_handling": 0.96,
    "scene_alignment": 0.89
  },
  "violations": [],
  "improvements": [
    "Uncertain transcript spans are preserved more safely."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\asr_correction_prompt\versions\v1.json

```
{
  "template_id": "asr_correction_prompt",
  "version": "v1",
  "change_reason": "Initial ASR normalization prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "asr_correction_prompt_v1",
  "variables": [
    {
      "name": "scene_state",
      "source": "scene_state",
      "value_type": "scene_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional scene context for resolving references."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are normalizing an aviation learner's transcript.\n1. Resolve likely term boundaries and obvious transcription fragments.\n2. Use {{scene_state}} only to help with references such as this part or that one.\n3. Mark any phrase that remains uncertain instead of replacing it with a guessed fact.\n4. Follow {{output_contract}} exactly."
}
```

## assets\prompts\asr_correction_prompt\versions\v2.json

```
{
  "template_id": "asr_correction_prompt",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Improve uncertainty handling for partial transcripts.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "asr_correction_prompt_v2",
  "variables": [
    {
      "name": "scene_state",
      "source": "scene_state",
      "value_type": "scene_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional scene context for resolving references."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are normalizing an aviation learner's transcript.\n1. Reconstruct the most likely query wording without adding facts.\n2. Use {{scene_state}} only to interpret visible references.\n3. If a term or number is still uncertain, keep it marked as uncertain instead of filling it in.\n4. Produce a clean normalized query plus any clarification need.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_basic_safe\asset.json

```
{
  "template_id": "aviation_basic_safe",
  "title": "Clarification-safe prompt",
  "task_type": "clarification",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "clarification",
    "fallback"
  ],
  "concept_scope": [
    "missing_context"
  ],
  "scene_scope": [
    "text",
    "scene"
  ],
  "constraints": [
    "do_not_guess_facts",
    "ask_for_missing_context",
    "stay_brief"
  ],
  "risk_boundaries": [
    "no_unsupported_facts",
    "no_real_provider_claims"
  ]
}
```

## assets\prompts\aviation_basic_safe\evaluations\aviation_basic_safe_v2.json

```
{
  "snapshot_id": "aviation_basic_safe_v2",
  "template_id": "aviation_basic_safe",
  "version": "v2",
  "test_cases": [
    "missing_evidence",
    "missing_scene",
    "missing_parameter_source"
  ],
  "dimension_scores": {
    "safety": 1.0,
    "clarity": 0.93,
    "brevity": 0.9
  },
  "violations": [],
  "improvements": [
    "Clarifying questions are more targeted."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\aviation_basic_safe\versions\v1.json

```
{
  "template_id": "aviation_basic_safe",
  "version": "v1",
  "change_reason": "Initial safe clarification prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_basic_safe_v1",
  "variables": [
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are a safe clarification prompt.\n1. State that reviewed evidence or context is missing.\n2. Name the missing piece in plain language.\n3. Ask one clarifying question that would unblock the next step.\n4. Do not guess any aviation fact.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_basic_safe\versions\v2.json

```
{
  "template_id": "aviation_basic_safe",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Make the clarification request more explicit and learner-friendly.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_basic_safe_v2",
  "variables": [
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are a safe clarification prompt.\n1. Explain that the current evidence or context is not enough for a factual answer.\n2. Name the missing source, scene detail, or question detail briefly.\n3. Ask one focused clarification question.\n4. Keep the reply short and avoid guessing.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_component_explain\asset.json

```
{
  "template_id": "aviation_component_explain",
  "title": "Scene-bound component explanation",
  "task_type": "component_scene",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "component_scene"
  ],
  "concept_scope": [
    "component_role",
    "scene_reference"
  ],
  "scene_scope": [
    "scene"
  ],
  "constraints": [
    "facts_from_evidence_only",
    "anchor_to_selected_object",
    "state_uncertainty_when_scene_is_weak"
  ],
  "risk_boundaries": [
    "no_hidden_part_claims",
    "no_operation_steps"
  ]
}
```

## assets\prompts\aviation_component_explain\evaluations\aviation_component_explain_v2.json

```
{
  "snapshot_id": "aviation_component_explain_v2",
  "template_id": "aviation_component_explain",
  "version": "v2",
  "test_cases": [
    "selected_component",
    "ambiguous_scene",
    "scene_object_mismatch"
  ],
  "dimension_scores": {
    "faithfulness": 0.98,
    "scene_alignment": 0.95,
    "clarity": 0.9
  },
  "violations": [],
  "improvements": [
    "Selected object anchoring is easier to audit."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\aviation_component_explain\versions\v1.json

```
{
  "template_id": "aviation_component_explain",
  "version": "v1",
  "change_reason": "Initial scene-aware teaching prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_component_explain_v1",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed evidence about the selected component."
    },
    {
      "name": "scene_state",
      "source": "scene_state",
      "value_type": "scene_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Current selected object and scene confidence."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are a scene-aware aviation tutor.\n1. Inspect {{scene_state}} to confirm which object is selected.\n2. Read {{rag_evidence}} and keep only claims that clearly match that object.\n3. Explain the component by naming it, describing its supported role, and tying the explanation back to the visible scene.\n4. If the scene or evidence is ambiguous, ask for clarification instead of guessing.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_component_explain\versions\v2.json

```
{
  "template_id": "aviation_component_explain",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Clarify object anchoring and ambiguity handling.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_component_explain_v2",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed evidence about the selected component."
    },
    {
      "name": "scene_state",
      "source": "scene_state",
      "value_type": "scene_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Current selected object and scene confidence."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are a scene-aware aviation tutor.\n1. Confirm the selected object from {{scene_state}} before drafting.\n2. Filter {{rag_evidence}} down to facts that clearly describe that same object.\n3. Teach in order: identify the component, explain its supported role, then connect it to what the learner is currently viewing.\n4. If the scene binding is weak or evidence does not match the selected object, say so and ask one targeted clarification question.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_concept_correction\asset.json

```
{
  "template_id": "aviation_concept_correction",
  "title": "Concept correction prompt",
  "task_type": "concept_correction",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "concept_correction",
    "fact_challenge"
  ],
  "concept_scope": [
    "misconception_repair"
  ],
  "scene_scope": [
    "text",
    "scene"
  ],
  "constraints": [
    "facts_from_evidence_only",
    "acknowledge_uncertainty",
    "respect_learner_challenge"
  ],
  "risk_boundaries": [
    "no_defensive_tone",
    "no_unsourced_reversal"
  ]
}
```

## assets\prompts\aviation_concept_correction\evaluations\aviation_concept_correction_v2.json

```
{
  "snapshot_id": "aviation_concept_correction_v2",
  "template_id": "aviation_concept_correction",
  "version": "v2",
  "test_cases": [
    "partial_correction",
    "unsupported_claim",
    "respectful_repair"
  ],
  "dimension_scores": {
    "faithfulness": 0.99,
    "clarity": 0.91,
    "tone_control": 0.93
  },
  "violations": [],
  "improvements": [
    "Unsupported segments are now surfaced explicitly."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\aviation_concept_correction\versions\v1.json

```
{
  "template_id": "aviation_concept_correction",
  "version": "v1",
  "change_reason": "Initial misconception repair prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_concept_correction_v1",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed evidence used to confirm or correct a claim."
    },
    {
      "name": "memory_context",
      "source": "memory_context",
      "value_type": "learner_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional learner context for tone only."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are an aviation tutor correcting a learner-facing idea with care.\n1. Read {{rag_evidence}} and decide which parts of the challenged statement are supported, unsupported, or still unclear.\n2. State the supported correction plainly.\n3. Explain why the correction follows from the evidence.\n4. Use {{memory_context}} only to soften the teaching style, never as a fact source.\n5. If evidence cannot settle the disagreement, say what remains unverified.\n6. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_concept_correction\versions\v2.json

```
{
  "template_id": "aviation_concept_correction",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Make supported versus unsupported claims explicit.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_concept_correction_v2",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed evidence used to confirm or correct a claim."
    },
    {
      "name": "memory_context",
      "source": "memory_context",
      "value_type": "learner_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional learner context for tone only."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are an aviation tutor correcting a learner-facing idea with care.\n1. Separate the challenged statement into supported, unsupported, and still-open parts using {{rag_evidence}}.\n2. Give the corrected answer first.\n3. Explain the correction with evidence-linked reasoning, not authority claims.\n4. Use {{memory_context}} only to tune tone and pacing.\n5. If the evidence does not fully resolve the challenge, state the remaining uncertainty and ask for the next helpful source or question.\n6. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_fact_qa\asset.json

```
{
  "template_id": "aviation_fact_qa",
  "title": "Aviation factual answer",
  "task_type": "concept_explanation",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "concept_explanation",
    "knowledge_explanation",
    "comparison",
    "parameter_fact"
  ],
  "concept_scope": [
    "aviation_concept",
    "aircraft_component",
    "parameter_question"
  ],
  "scene_scope": [
    "text",
    "scene"
  ],
  "constraints": [
    "facts_from_evidence_only",
    "cite_reviewed_sources",
    "state_uncertainty_when_missing"
  ],
  "risk_boundaries": [
    "no_parameter_without_source",
    "no_operation_steps"
  ]
}
```

## assets\prompts\aviation_fact_qa\evaluations\aviation_fact_qa_v2.json

```
{
  "snapshot_id": "aviation_fact_qa_v2",
  "template_id": "aviation_fact_qa",
  "version": "v2",
  "test_cases": [
    "basic_concept",
    "parameter_without_source",
    "scene_component"
  ],
  "dimension_scores": {
    "faithfulness": 1.0,
    "clarity": 0.92,
    "scene_alignment": 0.9
  },
  "violations": [],
  "improvements": [
    "Evidence boundary is explicit."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\aviation_fact_qa\versions\v1.json

```
{
  "template_id": "aviation_fact_qa",
  "version": "v1",
  "change_reason": "Initial structured evidence-first teaching prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_fact_qa_v1",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed evidence that is the only fact source."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output requirements."
    },
    {
      "name": "scene_state",
      "source": "scene_state",
      "value_type": "scene_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional summary of the current visual scene."
    }
  ],
  "content": "You are an aviation science tutor working from reviewed evidence only.\n1. Read {{rag_evidence}} and identify the claims it directly supports.\n2. Decide whether the learner needs a concept explanation, a comparison, or a sourced parameter answer.\n3. Use {{scene_state}} only to point at the currently selected object, never to invent hidden facts.\n4. Teach in order: direct answer, short supporting explanation, and one brief follow-up check.\n5. If evidence is incomplete, say what is missing before you ask for clarification.\n6. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_fact_qa\versions\v2.json

```
{
  "template_id": "aviation_fact_qa",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Add explicit evidence boundary and teaching order.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_fact_qa_v2",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed local evidence used as the only fact source."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Answer envelope output requirements."
    },
    {
      "name": "scene_state",
      "source": "scene_state",
      "value_type": "scene_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Current 3D or visual scene summary."
    }
  ],
  "content": "You are an aviation science tutor. Explain only facts supported by {{rag_evidence}}.\n1. Extract the supported claims before drafting.\n2. Match the answer style to the question type without adding outside facts.\n3. Use {{scene_state}} only to ground references to the current object.\n4. Present the answer in a teaching order: direct answer, why it matters, then one learner-friendly check question.\n5. If evidence is insufficient, say what is missing and ask a clarification question.\n6. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_feedback_rewrite\asset.json

```
{
  "template_id": "aviation_feedback_rewrite",
  "title": "Feedback rewrite prompt",
  "task_type": "feedback_rewrite",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "feedback"
  ],
  "concept_scope": [
    "answer_rewrite"
  ],
  "scene_scope": [
    "text",
    "scene"
  ],
  "constraints": [
    "preserve_supported_claims",
    "do_not_add_new_facts",
    "respond_to_feedback_signal"
  ],
  "risk_boundaries": [
    "no_unsourced_expansion",
    "no_removed_safety_caveats"
  ]
}
```

## assets\prompts\aviation_feedback_rewrite\evaluations\aviation_feedback_rewrite_v2.json

```
{
  "snapshot_id": "aviation_feedback_rewrite_v2",
  "template_id": "aviation_feedback_rewrite",
  "version": "v2",
  "test_cases": [
    "simplify_request",
    "shorten_request",
    "format_transform"
  ],
  "dimension_scores": {
    "faithfulness": 0.98,
    "rewrite_control": 0.94,
    "clarity": 0.91
  },
  "violations": [],
  "improvements": [
    "Preserve-versus-change planning reduces drift."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\aviation_feedback_rewrite\versions\v1.json

```
{
  "template_id": "aviation_feedback_rewrite",
  "version": "v1",
  "change_reason": "Initial controlled rewrite prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_feedback_rewrite_v1",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Evidence lock for the current answer."
    },
    {
      "name": "memory_context",
      "source": "memory_context",
      "value_type": "learner_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional style preferences."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are rewriting an aviation tutoring answer after feedback.\n1. Treat {{rag_evidence}} as the locked fact boundary.\n2. Identify which parts of the original answer can stay and which must be simplified, shortened, or reformatted.\n3. Use {{memory_context}} only to adapt tone.\n4. Preserve supported claims and safety caveats.\n5. If the requested rewrite would require new facts, say so instead of inventing them.\n6. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_feedback_rewrite\versions\v2.json

```
{
  "template_id": "aviation_feedback_rewrite",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Strengthen preserve-versus-change planning.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_feedback_rewrite_v2",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Evidence lock for the current answer."
    },
    {
      "name": "memory_context",
      "source": "memory_context",
      "value_type": "learner_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional style preferences."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are rewriting an aviation tutoring answer after feedback.\n1. Treat {{rag_evidence}} as the locked fact boundary.\n2. Make a preserve-versus-change plan before drafting: keep supported claims, adjust explanation style, and retain safety caveats.\n3. Use {{memory_context}} only to tune tone and pacing.\n4. If the requested rewrite needs unsupported detail, say what cannot be added.\n5. Produce the revised answer in a compact teaching order.\n6. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_quiz_reinforcement\asset.json

```
{
  "template_id": "aviation_quiz_reinforcement",
  "title": "Quiz reinforcement prompt",
  "task_type": "quiz_reinforcement",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "quiz_reinforcement"
  ],
  "concept_scope": [
    "retrieval_practice"
  ],
  "scene_scope": [
    "text",
    "scene"
  ],
  "constraints": [
    "facts_from_evidence_only",
    "ask_one_check_question",
    "adapt_difficulty_cautiously"
  ],
  "risk_boundaries": [
    "no_trick_questions_without_support",
    "no_unsourced_feedback"
  ]
}
```

## assets\prompts\aviation_quiz_reinforcement\evaluations\aviation_quiz_reinforcement_v2.json

```
{
  "snapshot_id": "aviation_quiz_reinforcement_v2",
  "template_id": "aviation_quiz_reinforcement",
  "version": "v2",
  "test_cases": [
    "single_fact_recall",
    "thin_evidence",
    "weak_point_focus"
  ],
  "dimension_scores": {
    "faithfulness": 0.97,
    "clarity": 0.9,
    "pedagogy": 0.92
  },
  "violations": [],
  "improvements": [
    "Hinting behavior is now more consistent."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\aviation_quiz_reinforcement\versions\v1.json

```
{
  "template_id": "aviation_quiz_reinforcement",
  "version": "v1",
  "change_reason": "Initial retrieval-practice prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_quiz_reinforcement_v1",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed evidence from which the quiz prompt must be built."
    },
    {
      "name": "weak_points",
      "source": "runtime",
      "value_type": "learner_topics",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional weak points to emphasize."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are an aviation tutor creating one short reinforcement check.\n1. Read {{rag_evidence}} and select one supported idea worth recalling.\n2. Use {{weak_points}} only to choose emphasis, not to add facts.\n3. Give a short explanation, then one learner-friendly quiz question, then the expected reasoning path.\n4. If the evidence is too thin to support a quiz item, ask for a narrower topic.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_quiz_reinforcement\versions\v2.json

```
{
  "template_id": "aviation_quiz_reinforcement",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Improve scaffolding and weak-point handling.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_quiz_reinforcement_v2",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Reviewed evidence from which the quiz prompt must be built."
    },
    {
      "name": "weak_points",
      "source": "runtime",
      "value_type": "learner_topics",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional weak points to emphasize."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are an aviation tutor creating one short reinforcement check.\n1. Read {{rag_evidence}} and choose one supported idea worth recalling.\n2. Use {{weak_points}} only to decide emphasis and wording difficulty.\n3. Teach in three moves: concise reminder, one check question, then one hint that preserves the learner's chance to answer.\n4. If evidence is insufficient for a trustworthy question, say so and ask for a narrower topic.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_self_check\asset.json

```
{
  "template_id": "aviation_self_check",
  "title": "Self-check prompt",
  "task_type": "self_check",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "self_check"
  ],
  "concept_scope": [
    "claim_review",
    "boundary_review"
  ],
  "scene_scope": [
    "text",
    "scene"
  ],
  "constraints": [
    "check_claim_support",
    "check_scene_alignment",
    "check_safety"
  ],
  "risk_boundaries": [
    "no_new_facts_during_check"
  ]
}
```

## assets\prompts\aviation_self_check\evaluations\aviation_self_check_v2.json

```
{
  "snapshot_id": "aviation_self_check_v2",
  "template_id": "aviation_self_check",
  "version": "v2",
  "test_cases": [
    "unsupported_claim",
    "scene_mismatch",
    "safety_check"
  ],
  "dimension_scores": {
    "faithfulness": 0.99,
    "risk_detection": 0.94,
    "consistency": 0.92
  },
  "violations": [],
  "improvements": [
    "Review order is easier to audit."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\aviation_self_check\versions\v1.json

```
{
  "template_id": "aviation_self_check",
  "version": "v1",
  "change_reason": "Initial self-check prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_self_check_v1",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Evidence used to verify the answer."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are an aviation answer checker.\n1. Review the drafted answer against {{rag_evidence}}.\n2. Mark which claims are supported, weak, or unsafe.\n3. Check scene references only when they are explicitly grounded.\n4. Do not add new facts while reviewing.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\aviation_self_check\versions\v2.json

```
{
  "template_id": "aviation_self_check",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Clarify evidence-only checking order.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_self_check_v2",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Evidence used to verify the answer."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are an aviation answer checker.\n1. Read {{rag_evidence}} before inspecting the drafted answer.\n2. Review the answer for supported claims, unsupported claims, scene mismatches, and safety issues.\n3. Report only what the evidence and boundaries justify.\n4. Never add a new fact or repair a missing source by guessing.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\barge_in_feedback_prompt\asset.json

```
{
  "template_id": "barge_in_feedback_prompt",
  "title": "Barge-in feedback prompt",
  "task_type": "barge_in_feedback",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "barge_in",
    "voice_feedback"
  ],
  "concept_scope": [
    "interrupted_voice_feedback"
  ],
  "scene_scope": [
    "voice",
    "scene"
  ],
  "constraints": [
    "resume_from_interruption",
    "preserve_supported_claims",
    "keep_response_compact"
  ],
  "risk_boundaries": [
    "no_new_facts_after_interrupt"
  ]
}
```

## assets\prompts\barge_in_feedback_prompt\evaluations\barge_in_feedback_prompt_v2.json

```
{
  "snapshot_id": "barge_in_feedback_prompt_v2",
  "template_id": "barge_in_feedback_prompt",
  "version": "v2",
  "test_cases": [
    "interrupt_to_shorten",
    "interrupt_to_simplify",
    "interrupt_without_new_facts"
  ],
  "dimension_scores": {
    "rewrite_control": 0.95,
    "brevity": 0.94,
    "faithfulness": 0.98
  },
  "violations": [],
  "improvements": [
    "Interruption recovery is more compact."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\barge_in_feedback_prompt\versions\v1.json

```
{
  "template_id": "barge_in_feedback_prompt",
  "version": "v1",
  "change_reason": "Initial interruption-aware rewrite prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "barge_in_feedback_prompt_v1",
  "variables": [
    {
      "name": "memory_context",
      "source": "memory_context",
      "value_type": "learner_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional pacing preferences."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime spoken output rules."
    }
  ],
  "content": "You are handling a spoken interruption.\n1. Identify what part of the answer should continue and what should change.\n2. Use {{memory_context}} only to adapt pace or phrasing.\n3. Keep the revised reply shorter than the interrupted one.\n4. Do not add new facts after the interruption.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\barge_in_feedback_prompt\versions\v2.json

```
{
  "template_id": "barge_in_feedback_prompt",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Tighten interruption recovery and brevity rules.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "barge_in_feedback_prompt_v2",
  "variables": [
    {
      "name": "memory_context",
      "source": "memory_context",
      "value_type": "learner_summary",
      "required": false,
      "missing_behavior": "omit",
      "description": "Optional pacing preferences."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime spoken output rules."
    }
  ],
  "content": "You are handling a spoken interruption.\n1. Identify the user's new request and decide what to keep from the interrupted answer.\n2. Use {{memory_context}} only for pacing and tone.\n3. Resume with a shorter, sharper version that preserves supported claims.\n4. If the interruption asks for unsupported detail, say what cannot be added.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\experimental_teaching_strategy\asset.json

```
{
  "template_id": "experimental_teaching_strategy",
  "title": "Experimental teaching strategy",
  "task_type": "experimental_teaching_strategy",
  "status": "candidate",
  "active_version": "v2",
  "activation_scope": [
    "experimental_teaching_strategy"
  ],
  "concept_scope": [
    "teaching_experiment"
  ],
  "scene_scope": [
    "text",
    "scene",
    "voice"
  ],
  "constraints": [
    "facts_from_evidence_only",
    "compare_teaching_orders",
    "mark_as_non_runtime"
  ],
  "risk_boundaries": [
    "candidate_not_runtime",
    "no_unsourced_creativity"
  ]
}
```

## assets\prompts\experimental_teaching_strategy\evaluations\experimental_teaching_strategy_v2.json

```
{
  "snapshot_id": "experimental_teaching_strategy_v2",
  "template_id": "experimental_teaching_strategy",
  "version": "v2",
  "test_cases": [
    "teaching_order_compare",
    "candidate_label",
    "no_runtime_selection"
  ],
  "dimension_scores": {
    "faithfulness": 0.96,
    "experiment_clarity": 0.88,
    "governance": 0.94
  },
  "violations": [],
  "improvements": [
    "Candidate-only labeling is explicit."
  ],
  "final_decision": "hold"
}
```

## assets\prompts\experimental_teaching_strategy\versions\v1.json

```
{
  "template_id": "experimental_teaching_strategy",
  "version": "v1",
  "change_reason": "Initial candidate prompt for teaching-order experiments.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "candidate",
  "snapshot_id": "experimental_teaching_strategy_v1",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Evidence boundary for the experiment."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are prototyping a teaching strategy.\n1. Read {{rag_evidence}} first.\n2. Draft one supported explanation order.\n3. Note where the strategy might help or confuse a learner.\n4. Keep all claims within evidence.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\experimental_teaching_strategy\versions\v2.json

```
{
  "template_id": "experimental_teaching_strategy",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Add explicit candidate-only labeling and evaluation hooks.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "candidate",
  "snapshot_id": "experimental_teaching_strategy_v2",
  "variables": [
    {
      "name": "rag_evidence",
      "source": "evidence_package",
      "value_type": "evidence_summary",
      "required": true,
      "missing_behavior": "clarification",
      "description": "Evidence boundary for the experiment."
    },
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime output rules."
    }
  ],
  "content": "You are prototyping a teaching strategy.\n1. Read {{rag_evidence}} first and list the claims that may be taught.\n2. Compare two supported explanation orders briefly.\n3. Note the likely learner tradeoff without claiming it is approved for runtime.\n4. Keep every claim inside the evidence boundary.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\spoken_answer_style_prompt\asset.json

```
{
  "template_id": "spoken_answer_style_prompt",
  "title": "Spoken answer style prompt",
  "task_type": "spoken_answer_style",
  "status": "active",
  "active_version": "v2",
  "activation_scope": [
    "spoken_answer_style"
  ],
  "concept_scope": [
    "voice_delivery"
  ],
  "scene_scope": [
    "voice",
    "text"
  ],
  "constraints": [
    "preserve_supported_claims",
    "prefer_short_sentences",
    "keep_follow_up_optional"
  ],
  "risk_boundaries": [
    "no_new_facts_for_fluency"
  ]
}
```

## assets\prompts\spoken_answer_style_prompt\evaluations\spoken_answer_style_prompt_v2.json

```
{
  "snapshot_id": "spoken_answer_style_prompt_v2",
  "template_id": "spoken_answer_style_prompt",
  "version": "v2",
  "test_cases": [
    "short_spoken_answer",
    "follow_up_optional",
    "no_fact_expansion"
  ],
  "dimension_scores": {
    "clarity": 0.94,
    "spoken_flow": 0.93,
    "faithfulness": 0.98
  },
  "violations": [],
  "improvements": [
    "Pacing is more consistent for TTS."
  ],
  "final_decision": "approve"
}
```

## assets\prompts\spoken_answer_style_prompt\versions\v1.json

```
{
  "template_id": "spoken_answer_style_prompt",
  "version": "v1",
  "change_reason": "Initial spoken delivery prompt.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "spoken_answer_style_prompt_v1",
  "variables": [
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime spoken output rules."
    }
  ],
  "content": "You are preparing a spoken aviation tutoring answer.\n1. Keep the opening sentence short.\n2. Follow with two or three simple sentences that preserve the supported claims.\n3. End with one optional follow-up question only if it helps.\n4. Do not add new facts for fluency.\n5. Follow {{output_contract}} exactly."
}
```

## assets\prompts\spoken_answer_style_prompt\versions\v2.json

```
{
  "template_id": "spoken_answer_style_prompt",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Clarify pacing and short-sentence style.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "spoken_answer_style_prompt_v2",
  "variables": [
    {
      "name": "output_contract",
      "source": "runtime",
      "value_type": "json_schema_summary",
      "required": true,
      "missing_behavior": "error",
      "description": "Runtime spoken output rules."
    }
  ],
  "content": "You are preparing a spoken aviation tutoring answer.\n1. Open with the direct answer in one short sentence.\n2. Add two or three short follow-up sentences that keep the supported claims intact.\n3. Pause the flow naturally by separating ideas instead of adding detail.\n4. End with one optional check question only when it helps the learner.\n5. Follow {{output_contract}} exactly."
}
```

## Deleted File Checks
- src/prompts/asset_store.py exists: False
- tests/unit/prompts/test_prompt_versioning.py exists: False
- assets/prompts/aviation_basic_safe.yaml exists: False
- assets/prompts/aviation_explain_active.yaml exists: False
- assets/prompts/aviation_knowledge_explain.yaml exists: False
- assets/prompts/aviation_self_check.yaml exists: False
- assets/prompts/aviation_voice_spoken.yaml exists: False
