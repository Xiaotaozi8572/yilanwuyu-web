# Task 2 Review Package

Full current contents for files touched by Task 2. Git diff is unavailable because the parent repo is in rebase and this project tree is mostly untracked.

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

## src/prompts/assembler.py

```
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from prompts.asset_models import PromptSelection
from prompts.router import PromptRouterConfig


@dataclass
class MessageBundle:
    messages: list[dict[str, Any]]
    injection_order: list[str]
    selected_template_id: str
    missing_variables: list[str] = field(default_factory=list)
    route_reason: str | None = None
    is_final_fact_prompt: bool = True


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
    ) -> MessageBundle:
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
            {"role": "system" if name == "system_boundary" else "context", "section": name, "content": values[name]}
            for name in self.config.injection_order
        ]
        final_fact_prompt = not prompt_selection.is_clarification and "rag_evidence" not in prompt_selection.missing_variables
        return MessageBundle(
            messages=messages,
            injection_order=list(self.config.injection_order),
            selected_template_id=prompt_selection.template_id,
            missing_variables=list(prompt_selection.missing_variables),
            route_reason=prompt_selection.route_reason,
            is_final_fact_prompt=final_fact_prompt,
        )


def assemble_messages(prompt_selection: PromptSelection, **kwargs: Any) -> MessageBundle:
    return PromptAssembler().assemble_messages(prompt_selection, **kwargs)
```

## configs/prompts.yaml

```
prompts:
  default_template_id: aviation_fact_qa
  clarification_template_id: aviation_basic_safe
  asset_dir: assets/prompts
  allowed_runtime_statuses: [active, experimental]
  required_final_fact_variables: [rag_evidence, output_contract]
  injection_order: [system_boundary, task_type, scene_state, rag_evidence, memory_context, weak_points, prompt_asset, output_contract]
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
```

## tests/unit/prompts/test_prompt_router.py

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

## tests/unit/prompts/test_prompt_assembler.py

```
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_understanding import understand_query
from prompts.assembler import PromptAssembler
from prompts.router import PromptRouter


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
    assert "{{rag_evidence}}" in str(bundle.messages[bundle.injection_order.index("prompt_asset")]["content"])


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

    assert bundle.selected_template_id == "aviation_basic_safe"
    assert bundle.is_final_fact_prompt is False
    assert bundle.route_reason == "missing_required_variables"
```

## docs/STATUS.md

```
# STATUS.md

鏈枃浠剁敱 Codex 鍦ㄥ叏鑷姩寮€鍙戣繃绋嬩腑鎸佺画鏇存柊銆?
## 褰撳墠鐘舵€?
P0 鍒?P6 宸插畬鎴愩€侾7/P8 鏈撼鍏ユ湰娆＄敤鎴风洰鏍囷紝鏆傛湭寮€濮嬨€?
## 闃舵锛歅0

### 瀹屾垚鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 闃舵鐩爣
寤虹珛绯荤粺寮€鍙戠殑鍏卞悓鍦板熀锛屼娇鍚庣画 Prompt銆佽蹇嗐€丷AG銆佺敓鎴愩€佽嚜妫€銆佸弽棣堝拰璇煶妯″潡閮藉洿缁曞悓涓€濂楃姸鎬佹満銆佸姩浣滄灇涓俱€佹暟鎹绾︺€侀厤缃拰鏃ュ織瑙勮寖寮€鍙戙€?
### 鍏佽淇敼鑼冨洿
- `pyproject.toml`
- `.env.example`
- `configs/app.yaml`
- `configs/providers.yaml`
- `src/core/**`
- `tests/unit/core/**`
- `README.md`

### 绂佹淇敼鑼冨洿
- P1 鍒?P8 灏氭湭鍒涘缓鐨勪笟鍔℃ā鍧椾笉寰楀鍏?P0銆?- 涓嶅緱淇敼鍘熷 Word 鏂囨。鍜岀敤鎴锋彁渚涜祫鏂欍€?- 涓嶅緱灏嗘紨绀烘暟鎹€丳rompt 妯℃澘銆佽埅绌虹煡璇嗗簱鍐呭鍐欏叆 `src/core/`銆?
### 宸插畬鎴愬唴瀹?- 寤虹珛 Python 宸ョ▼楠ㄦ灦銆乸ytest 閰嶇疆銆丷EADME 鍜屽熀纭€閰嶇疆鏍蜂緥銆?- 鏂板缁熶竴 `ActionDecision`銆乣AgentState`銆佺姸鎬佽烦杞褰曞拰鍥為€€閾捐矾銆?- 鏂板鍩虹鏁版嵁濂戠害锛歚scene_state`銆乣prompt_asset`銆乣memory_context`銆乣evidence_package`銆乣answer_envelope`銆乣check_report`銆乣rewrite_plan`銆乣voice_turn_event`銆乣feedback_event`銆乣run_trace`銆?- 鏂板閰嶇疆鍔犺浇銆佹牳蹇冨紓甯稿拰杩愯杩借釜杈呭姪鍑芥暟銆?- 鎸夌敤鎴疯拷鍔犳潯浠讹紝灏嗚繍琛岀幆澧冭皟鏁翠负 `D:\APP\Python 3.13\Internet\.venv`锛屽苟灏?`pyproject.toml` 鐨?`requires-python` 璋冩暣涓?`>=3.11`銆?
### 淇敼鏂囦欢
- 鏃?
### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍10 passed in 0.20s`銆?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `brainstorming`锛堝彈鐢ㄦ埛鍏ㄨ嚜鍔ㄦ墽琛屾寚浠ょ害鏉燂紝鏈缃汉宸ョ‘璁ら棬锛?- `writing-plans`锛堜互 `docs/spec.md` 浣滀负宸叉湁瀹炴柦璁″垝鎵ц锛屾湭鏂板璁″垝鏂囦欢浠ラ伩鍏嶈秺杩?P0 harness锛?- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- 鎶€鏈爤銆佹暟鎹簱銆佹秷鎭槦鍒椼€佸墠绔€氫俊鍗忚浠嶆寜鏂囨。鏍囪涓哄緟纭銆?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鏄€?
## 闃舵锛歅1

### 瀹屾垚鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 闃舵鐩爣
鎶婄敤鎴锋枃鏈棶棰樸€?D 鍦烘櫙鐘舵€佸拰鍒濇浠诲姟鎰忓浘杞垚鏍囧噯 `query_object`锛屽苟瀹炵幇 Prompt 璧勪骇鐨勮矾鐢便€佸彉閲忓～鍏呫€佹敞鍏ラ『搴忓拰椋庨櫓杈圭晫銆?
### 鍏佽淇敼鑼冨洿
- `src/input/**`
- `src/prompts/**`
- `configs/prompts.yaml`
- `tests/unit/prompts/**`
- `tests/unit/core/test_contracts.py`

### 绂佹淇敼鑼冨洿
- 涓嶅緱鍦?P1 淇敼 `src/knowledge/**` 浠ョ粫杩囨绱€?- 涓嶅緱鍦?P1 鍐欏叆闀挎湡璁板繂銆?- 涓嶅緱鍦?Prompt 妯″潡涓啓鑸┖浜嬪疄搴撳唴瀹广€?
### 宸插畬鎴愬唴瀹?- 鏂板 `QueryObject`銆乣PromptRunInput` 鍜屽満鏅粦瀹氱粨鏋滃璞°€?- 瀹炵幇鏂囨湰鎰忓浘璇嗗埆銆侀鏈?閮ㄤ欢/姒傚康/鍙嶉鎰忓浘鐨勮交閲忔娊鍙栥€?- 瀹炵幇鍦烘櫙鎸囦唬缁戝畾锛氭槑纭€変腑瀵硅薄鐩存帴缁戝畾锛屽鍊欓€夎繑鍥?`needs_clarification`銆?- 鏂板 Prompt 璧勪骇妯″瀷銆佽祫浜у瓨鍌ㄣ€佽矾鐢卞櫒鍜屾敞鍏ユ憳瑕佺粍瑁呭櫒銆?- 閰嶇疆鍖?Prompt 蹇呭～鍙橀噺鍜屾敞鍏ラ『搴忥紝骞朵繚璇?`rag_evidence` 浣嶄簬 Prompt 璧勪骇涔嬪墠銆?- 缂哄け `rag_evidence` 鏃堕檷绾у埌鍩虹妯℃澘锛屽苟鏍囪涓嶆槸浜嬪疄鍨嬫渶缁?Prompt銆?
### 淇敼鏂囦欢
- 鏃?
### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍17 passed in 0.20s`銆?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- 棣栨壒 Prompt 妯℃澘鍐呭鍜屼汉宸ュ鏍告祦绋嬩粛涓哄緟纭锛涘綋鍓嶄粎瀹炵幇闈炰簨瀹炴€х殑瀹夊叏杈圭晫妯℃澘涓庤矾鐢辨満鍒躲€?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鏄€?
## 闃舵锛歅2

### 瀹屾垚鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 闃舵鐩爣
寤虹珛浜嬩欢鏃ュ織銆佺儹鐘舵€併€佸涔犺€呯敾鍍忋€佸€欓€夎蹇嗐€佹椂闂磋“鍑忓拰瀹¤鏈哄埗锛岃璁板繂鍙敤浜庝釜鎬у寲銆佹寚浠ｅ拰鏁欏璺緞锛屼笉姹℃煋鑸┖浜嬪疄銆?
### 鍏佽淇敼鑼冨洿
- `src/memory/**`
- `configs/memory.yaml`
- `tests/unit/memory/**`
- `tests/integration/answer_pipeline/**` 涓笌 `memory_context` 鐩稿叧鐨勬祴璇?
### 绂佹淇敼鑼冨洿
- 涓嶅緱淇敼 `src/knowledge/source_registry.py` 鎴栫煡璇嗗簱浜嬪疄琛ㄦ潵鍐欏叆鐢ㄦ埛璇存硶銆?- 涓嶅緱鍦ㄨ蹇嗘ā鍧椾腑鐢熸垚鏈€缁堝洖绛斻€?- 涓嶅緱璁╀换鎰?Agent 鐩存帴鍐欓暱鏈熻蹇嗚€屼笉璧?`MemoryController`銆?
### 宸插畬鎴愬唴瀹?- 鏂板浜嬩欢鏃ュ織銆佸€欓€夎蹇嗐€佺粨鏋勫寲璁板繂銆佽蹇嗙姸鎬佹満鍜屽啓鍏ョ粨鏋滃璞°€?- 瀹炵幇 `MemoryController` 浣滀负鍞竴闀挎湡鍐欏叆鍏ュ彛銆?- 瀹炵幇浜嬩欢鍏堣娴佺▼锛歚event_log -> candidate -> temporal normalize -> governance -> store`銆?- 瀹炵幇浣庣疆淇°€侀珮闅愮銆佹湭鐧昏浜嬩欢鍜岀敤鎴疯埅绌轰簨瀹炲€欓€夌殑鎷掔粷娌荤悊銆?- 瀹炵幇鍋忓ソ鍐茬獊鐨?`supersedes/superseded_by` 鍏崇郴銆?- 瀹炵幇杩囨湡/闄堟棫鐘舵€佸埛鏂般€佽蹇嗗璁″拰 `memory_context` 鏋勯€犮€?
### 淇敼鏂囦欢
- 鏃?
### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts tests\unit\memory
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍25 passed in 0.18s`銆?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- 鐪熷疄鐢ㄦ埛鎺堟潈 UI銆佸垹闄ゆ祦绋嬨€佸師濮嬭闊充繚鐣欑瓥鐣ヤ粛涓哄緟纭銆?- 褰撳墠瀛樺偍涓哄唴瀛樺疄鐜帮紝鐢熶骇鎸佷箙鍖栨柟妗堝緟纭銆?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鏄€?
## 闃舵锛歅3

### 瀹屾垚鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 闃舵鐩爣
寤虹珛鈥滀簨瀹炵敱鐭ヨ瘑妫€绱㈡彁渚涒€濈殑鏍稿績鑳藉姏锛屽皢鏂囨湰銆佸満鏅璞″拰澶氭ā鎬佺嚎绱㈢粺涓€鍏ュ簱銆佹绱€侀棬鎺э紝骞惰緭鍑哄彲瀹¤鐨?`evidence_package`銆?
### 鍏佽淇敼鑼冨洿
- `src/knowledge/**`
- `configs/rag.yaml`
- `scripts/ingest_sources.py`
- `data/raw_sources/**`
- `data/processed/**`
- `tests/integration/rag_pipeline/**`

### 绂佹淇敼鑼冨洿
- 涓嶅緱鍦?P3 鍐欏洖绛旂敓鎴愰€昏緫銆?- 涓嶅緱鍦ㄧ煡璇嗗簱涓啓鍏ョ敤鎴疯蹇嗐€佺敤鎴峰弽棣堟垨妯″瀷鑷敱鐢熸垚浜嬪疄銆?- 涓嶅緱鍦ㄦ绱㈡ā鍧椾慨鏀?Prompt 璧勪骇鐘舵€併€?
### 宸插畬鎴愬唴瀹?- 鏂板 `source_registry`銆乣scene_object_registry`銆佹枃鏈?PDF/瑙嗚/鍦烘櫙鍏ュ簱閫傞厤瀵硅薄銆?- 鏂板鍏抽敭璇嶇储寮曘€佽交閲忚涔夌储寮曘€佹贩鍚?RRF 鍙洖銆佽瑙夐〉绱㈠紩鍜岃交閲忓浘绱㈠紩銆?- 瀹炵幇 `RetrievalController`銆乣RetrievalPlan`銆乣EvidencePackageBuilder` 鍜?`EvidenceGate`銆?- 寮哄埗浠?`reviewed` 鏉ユ簮鍙綔涓烘牳蹇冭埅绌轰簨瀹炶瘉鎹€?- 鍦烘櫙瀵硅薄鍙€氳繃 `selected_object_id` 缁戝畾骞惰繃婊ら儴浠惰瘉鎹€?- 鏈鏍歌祫鏂欏拰鏃犳枃鏈氦鍙夋牎楠岃瑙夌嚎绱笉鑳戒綔涓烘牳蹇冧簨瀹炶瘉鎹€?- 鏂板婕旂ず鍏ュ簱鑴氭湰 `scripts/ingest_sources.py`锛屽苟鍦ㄦ渶缁堥獙鏀朵腑琛ュ厖鐩存帴杩愯璺緞鍒濆鍖栥€?
### 淇敼鏂囦欢
- `scripts/ingest_sources.py`

### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit\core tests\unit\prompts tests\unit\memory tests\integration\rag_pipeline
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍31 passed in 0.41s`銆?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- 鏈€缁堝悜閲忓簱銆佸浘鏁版嵁搴撱€丳DF 瑙ｆ瀽宸ュ叿鍜?embedding 妯″瀷浠嶄负寰呯‘璁ゃ€?- 褰撳墠 RAG 涓烘湰鍦板唴瀛?MVP锛屾帴鍙ｄ繚鎸佸彲鏇挎崲銆?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鏄€?
## 闃舵锛歅4

### 瀹屾垚鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 闃舵鐩爣
鍩轰簬 `evidence_package`銆乣scene_state`銆乣memory_context` 鍜?Prompt 璺敱缁撴灉鐢熸垚鍙鏌ャ€佸彲灞曠ず銆佸彲杩芥函鐨?`answer_envelope`锛屽疄鐜版潵婧愮粦瀹氬拰缁撴瀯鍖栧睍绀哄潡銆?
### 鍏佽淇敼鑼冨洿
- `src/generation/**`
- `tests/unit/generation/**`
- `tests/integration/answer_pipeline/**`
- `configs/app.yaml` 涓?generation 鐩稿叧閰嶇疆

### 绂佹淇敼鑼冨洿
- 涓嶅緱鍦?P4 淇敼鐭ヨ瘑搴撹瘉鎹€?- 涓嶅緱鍦?P4 鐩存帴鍐欓暱鏈熻蹇嗐€?- 涓嶅緱缁曡繃 P5 鐩存帴鎶?draft 鏍囪涓烘渶缁堝畨鍏ㄨ緭鍑恒€?
### 宸插畬鎴愬唴瀹?- 鏂板鍥炵瓟绫诲瀷璺敱銆佽瘉鎹崏鍥俱€佺敓鎴愯鍒掋€侀潪娴佸紡 grounded generator銆佹潵婧愮粦瀹氬拰灞曠ず鍧楁瀯寤恒€?- 鍙傛暟璇佹嵁涓嶈冻鏃朵笉杈撳嚭鍏蜂綋鏁板€笺€?- 姣忎釜鍏抽敭 claim 鏈?`evidence_id` 鎴栦笉纭畾鎬ц鏄庛€?- 涓€у寲鍙敼鍙樿〃杈惧墠缂€锛屼笉鏀瑰彉浜嬪疄鏂囨湰銆?
### 淇敼鏂囦欢
- `tests/integration/answer_pipeline/test_grounded_answer.py`

### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration\rag_pipeline tests\integration\answer_pipeline
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍40 passed in 0.32s`銆?
### 澶辫触淇璁板綍
- 棣栨杩愯鍑虹幇 1 涓柇瑷€澶辫触锛氭祴璇曠敤渚嬧€滆В閲婁竴涓嬫満缈煎崌鍔涒€濆湪 P1 涓細琚瘑鍒负 `component_scene`锛屼絾 P4 娴嬭瘯鏈熷緟 `concept_explanation`銆?- 鏍瑰洜鏄?P4 娴嬭瘯澶瑰叿涓庡凡楠屾敹鐨?P1 鎰忓浘瑙勫垯涓嶄竴鑷淬€?- 淇鏂瑰紡锛氬皢璇?P4 姒傚康瑙ｉ噴娴嬭瘯杈撳叆鏀逛负鈥滆В閲婁竴涓嬪崌鍔涒€濓紝鏈慨鏀?P1 宸查獙鏀堕€昏緫銆?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- 妯″瀷 Provider銆佺粨鏋勫寲杈撳嚭绾︽潫鏂瑰紡鍜屽墠绔?`display_blocks` 娓叉煋鍗忚浠嶄负寰呯‘璁ゃ€?- 褰撳墠鐢熸垚鍣ㄤ负瑙勫垯鍖?MVP锛屽悗缁帴鍏ユā鍨嬫椂浠嶅繀椤讳繚鎸佽瘉鎹粦瀹氬拰 P5 鑷杈圭晫銆?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鏄€?
## 闃舵锛歅5

### 瀹屾垚鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 闃舵鐩爣
寤虹珛鐙珛璐ㄩ噺闂搁棬锛屽鍥炵瓟鍋氫簨瀹炰富寮犮€佽瘉鎹榻愩€佸満鏅竴鑷存€с€佸妯℃€佷竴鑷存€с€佽蹇?Prompt 杈圭晫鍜屽畨鍏ㄦ鏌ワ紝骞堕€氳繃 `action_decision` 椹卞姩鍥為€€銆?
### 鍏佽淇敼鑼冨洿
- `src/self_check/**`
- `src/core/state_machine.py` 涓繀瑕佺殑鍥為€€杈规墿灞?- `tests/unit/self_check/**`
- `tests/integration/answer_pipeline/**`

### 绂佹淇敼鑼冨洿
- 涓嶅緱鍦?self_check 涓洿鎺ュ啓闀挎湡璁板繂銆?- 涓嶅緱鍦?self_check 涓洿鎺ヤ慨鏀圭煡璇嗗簱璧勬枡銆?- 涓嶅緱璁╄嚜妫€妯″潡鐢熸垚鍏ㄦ柊浜嬪疄绛旀銆?
### 宸插畬鎴愬唴瀹?- 鏂板 claim 鎶藉彇銆佽瘉鎹榻愩€佸満鏅鏌ャ€佸妯℃€佹鏌ャ€佽竟鐣?瀹夊叏妫€鏌ャ€佽瘎鍒嗗崱鍜屽姩浣滆矾鐢便€?- `CheckReport` 杈撳嚭 `score_card`銆乣issue_list`銆乣failed_checks`銆乣action_decision`銆乣revised_instruction` 鍜?`audit_log`銆?- 楂橀闄╂棤璇佹嵁 claim 璺敱鍒?`RETRIEVE_MORE`銆?- Prompt/璁板繂瓒婃潈浣滀负浜嬪疄鏉ユ簮璺敱鍒?`REWRITE_ONLY`銆?- 鍦烘櫙瀵硅薄閿欎綅璺敱鍒?`ASK_CLARIFICATION`銆?- 鍗遍櫓鎿嶄綔缁嗚妭璺敱鍒?`SAFE_RESPONSE`銆?- 寰幆瓒呰繃涓婇檺璺敱鍒?`STOP`銆?
### 淇敼鏂囦欢
- `tests/unit/self_check/test_decision_router.py`

### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration\rag_pipeline tests\integration\answer_pipeline
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍48 passed in 0.31s`銆?
### 澶辫触淇璁板綍
- 棣栨杩愯鍑虹幇 1 涓柇瑷€澶辫触锛氭祴璇曚緷璧?`issue_list[0]` 鐨勯『搴忥紝浣嗘湇鍔℃寜鈥滆瘉鎹榻?-> 杈圭晫妫€鏌モ€濈殑椤哄簭杩藉姞 issue銆?- 鏍瑰洜鏄祴璇曟柇瑷€杩囧害渚濊禆鍒楄〃椤哄簭锛屽姩浣滃垎娴佸凡姝ｇ‘涓?`REWRITE_ONLY`銆?- 淇鏂瑰紡锛氭敼涓洪『搴忔棤鍏冲湴妫€鏌?`issue_list` 涓瓨鍦?`non_evidence_source_used_as_fact`銆?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- 浜哄伐澶嶆牳鍏ュ彛銆佹寮忚瘎鍒嗛槇鍊兼潵婧愬拰 LLM Judge 姣斾緥浠嶄负寰呯‘璁ゃ€?- 褰撳墠闃堝€奸€氳繃 `DecisionPolicy` 鍙敞鍏ワ紝鍚庣画鍙帴鍏ラ厤缃枃浠躲€?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鏄€?
## 闃舵锛歅6

### 瀹屾垚鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 闃舵鐩爣
澶勭悊鐢ㄦ埛瀵逛笂涓€杞洖绛旂殑鍙嶉锛屽湪璇佹嵁閿佸畾銆佹潵婧愪繚鎸佸拰鑷澶嶆牳涓嬪仛灞€閮ㄦ敼鍐欙紝骞剁敓鎴愬€欓€夎蹇嗐€?
### 鍏佽淇敼鑼冨洿
- `src/feedback/**`
- `src/memory/**` 涓?`memory_update_candidate` 鎺ュ彛
- `tests/unit/feedback/**`
- `tests/integration/feedback_loop/**`

### 绂佹淇敼鑼冨洿
- 涓嶅緱鍦?feedback 妯″潡鐩存帴淇敼鐭ヨ瘑搴撲簨瀹炪€?- 涓嶅緱鍦?feedback 妯″潡鐩存帴鎶婂亸濂藉啓鎴?active 闀挎湡璁板繂銆?- 涓嶅緱璺宠繃 P5 鑷杈撳嚭鏀瑰啓缁撴灉銆?
### 宸插畬鎴愬唴瀹?- 鏂板鍙嶉鎰忓浘瑙ｆ瀽銆乧heckpoint 淇濆瓨銆佽瘉鎹攣瀹氥€乺ewrite planner銆佸彈鎺?rewriter 鍜?delta_map銆?- 琛ㄨ揪绫诲弽棣堜繚鐣?`source_binding`锛涜〃鏍艰浆鎹㈠鏃犺瘉鎹淮搴︽爣璁扳€滆祫鏂欐湭瑕嗙洊鈥濄€?- `FACT_CHALLENGE` 鐢熸垚鏍告煡鏌ヨ鍜屼笉纭畾鎬ц鏄庯紝涓嶇洿鎺ヨ鐩栦簨瀹炪€?- 瀹夊叏鏁忔劅鍙嶉杈撳嚭 `SAFE_RESPONSE`锛屼笉淇濈暀鍙墽琛屽嵄闄╂楠ゃ€?- 绋冲畾鍋忓ソ鍙敓鎴?`memory_update_candidate`锛屼氦鍥?P2 娌荤悊銆?
### 淇敼鏂囦欢
- 鏃?
### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest tests\unit tests\integration
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍57 passed in 0.26s`銆?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- 鍓嶇鏄惁灞曠ず `delta_map`銆佹渶澶ф敼鍐欒疆鏁扮殑姝ｅ紡浜у搧鍊间粛涓哄緟纭銆?- 褰撳墠 checkpoint 涓哄唴瀛樺疄鐜帮紝鐢熶骇鎸佷箙鍖栫瓥鐣ュ緟纭銆?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鏈疆鐢ㄦ埛鐩爣鍒?P6 缁撴潫锛汸7/P8 鏈撼鍏ユ湰娆＄洰鏍囷紝鏆備笉寮€濮嬨€?
## 鏈€缁堥獙鏀惰褰曪細P0-P6

### 楠屾敹鏃堕棿
2026-07-08 00:00 Asia/Shanghai

### 鎸囧畾杩愯鐜
- `D:\APP\Python 3.13\Internet\.venv`
- Python 3.11.9
- pytest 9.0.3

### 楠屾敹鍛戒护
```bash
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m pytest
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe -m compileall -q src scripts
D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe scripts\ingest_sources.py
```

### 楠屾敹缁撴灉
- 鍏ㄩ噺娴嬭瘯閫氳繃锛歚57 passed in 0.22s`銆?- 缂栬瘧妫€鏌ラ€氳繃锛歚compileall` 閫€鍑虹爜涓?0銆?- 鍏ュ簱鑴氭湰鍚姩妫€鏌ラ€氳繃锛氳緭鍑?`registered_sources=1 chunks=1`銆?
### 闃舵鑼冨洿纭
- P0 鍒?P6 鍧囧凡鏈夐樁娈佃褰曘€?- 绯荤粺宸ョ▼鐩綍鎸?`docs/spec.md` 鐨?P0-P6 鑼冨洿寤虹珛銆?- 鍔熻兘鑼冨洿闄愪簬 `docs/task.md` 鐨?P0-P6锛屾湭鎻愬墠瀹炵幇 P7/P8銆?- 浠ｇ爜杈圭晫鏈粫杩?`docs/harness.md`銆?
### 鍓╀綑寰呯‘璁?- P7/P8 鏈墽琛岋紝鍥犱负鏈疆鐢ㄦ埛鐩爣鏄庣‘鍒?P6銆?- 鐪熷疄 Provider銆佹暟鎹簱銆佸墠绔崗璁€佹寔涔呭寲銆佷汉宸ュ鏍稿叆鍙ｅ拰姝ｅ紡閮ㄧ讲绛栫暐浠嶆寜闃舵鏂囨。淇濈暀涓哄緟纭銆?
## 闃舵锛歅0+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
鍥哄寲褰撳墠 MVP 涓?plus 宸ヤ笟绾у崌绾х洰鏍囦箣闂寸殑鐪熷疄宸窛锛屽缓绔?P1+ 鍒?P9+ 鐨勫璁″熀绾垮拰 baseline 娴嬭瘯璁板綍銆?
### 瀹屾垚鍐呭
- 瀹屾暣闃呰 `docs/task_plus.md`銆乣docs/spec_plus.md`銆乣docs/harness_plus.md`銆佸師濮嬩笁浠跺銆乣docs/AUTO_DEV.md`銆乣docs/STATUS.md` 鍜?`AGENTS.md`銆?- 鎵弿褰撳墠鐪熷疄鐩綍缁撴瀯銆侀厤缃€佽剼鏈€佹祴璇曞拰鏂囨。銆?- 鏂板 `docs/upgrade_audit.md`锛岃褰?MVP 鐘舵€併€佹ā鍧楀樊璺濄€丮ock/Offline 绛栫暐銆佸懡鍚嶄竴鑷存€у拰鍚庣画闃舵椋庨櫓銆?- 鏍搁獙鏈彂鐜?`deepseek-flash 2`锛岀‘璁?`providers.yaml` 浠嶄负 `pending_confirmation`銆?- 鏄庣‘ `SimpleVectorIndex` 鏄?token similarity fallback锛宍GroundedAnswerGenerator` 鏄鍒欏寲 fallback锛宍VoiceTurnEvent` 鍙槸璇煶濂戠害銆?- 杩愯 baseline 鍏ㄩ噺 pytest銆?
### 淇敼鏂囦欢
- `docs/STATUS.md`

### 鏂板鏂囦欢
- `docs/upgrade_audit.md`

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

### 娴嬭瘯缁撴灉
閫氳繃锛宍57 passed in 0.31s`銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氫笉娑夊強
* 鏄惁浣跨敤 mock embedding锛氫笉娑夊強
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `brainstorming`锛堜互鏃㈡湁 plus 鏂囨。浣滀负宸茬‘璁よ璁★紝涓嶈缃澶栦汉宸ョ‘璁ら棬锛?- `writing-plans`锛堜互 `docs/spec_plus.md` 浣滀负闃舵绾у疄鏂借鍒掓潵婧愶級
- `verification-before-completion`
- `browser:control-in-app-browser`锛堝凡鎸夋枃妗ｈ鍙栵紱P0+ 涓嶆秹鍙婃祻瑙堝櫒楠岃瘉锛?
### 椋庨櫓涓庡緟纭
- DeepSeek 鐪熷疄 `model_id`銆乥ase_url銆佺粨鏋勫寲杈撳嚭鑳藉姏寰?P3+ 閰嶇疆鏍搁獙銆?- CLI / HTTP API 浼樺厛绾у緟 P2+ 鎸?CLI-first銆丠TTP optional 澶勭悊銆?- 鍚戦噺搴撱€丱CR/PDF layout銆佽闊?Provider銆佽瘎娴嬫鏋跺潎瀛樺湪 Python 3.13 + Windows 鍏煎鎬ч闄┿€?- 鐖剁骇 Git 浠撳簱鍖呭惈澶ч噺涓庢湰椤圭洰鏃犲叧鐨勫彉鏇达紝P0+ 鏈鐞嗚繖浜涘閮ㄧ姸鎬併€?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅9+

### 瀹屾垚鏃堕棿
2026-07-09 12:57:51 Asia/Shanghai

### 闃舵鐩爣
瀹屾垚 P0+ 鍒?P9+ 鐨勫叏閾捐矾宸ヤ笟绾ч獙鏀朵笌婕旂ず浜や粯锛屾槑纭尯鍒?Mock/Offline 楠屾敹鍜岀湡瀹?Provider / 鐢熶骇鐜楠屾敹銆?
### 瀹屾垚鍐呭
- 閲嶆柊璇诲彇鐩爣鏂囦欢 `C:\Users\SONGQI\.codex\attachments\da81fb58-5beb-425e-97e9-da1e4ad9be27\goal-objective.md`锛岀‘璁ゆ湰杞洰鏍囦负 P0+ 鍒?P9+ 鍏ㄨ嚜鍔ㄩ『搴忓崌绾э紝鐪熷疄 Provider 缂哄け涓嶆槸闃诲鏉′欢銆?- 鏍稿 `docs/task_plus.md`銆乣docs/spec_plus.md`銆乣docs/harness_plus.md` 涓?P9+ 瑕佹眰锛岀‘璁?P9+ 鍙仛浜や粯楠屾敹涓庢枃妗ｏ紝涓嶄慨鏀逛笟鍔￠€昏緫銆?- 杩愯 P9+ 蹇呴渶楠屾敹鍛戒护锛氬叏閲?pytest銆乧ompileall銆乻moke eval銆乼race export銆乨eployment validate銆?- 棰濆楠岃瘉 CLI query smoke 涓?`AppPipeline.health_check()`锛岀‘璁ゅ簲鐢ㄥ叆鍙ｅ拰 service facade 鍙繍琛屻€?- 鏂板 `docs/release_acceptance.md`锛屾眹鎬婚獙鏀剁粨璁恒€佽瘉鎹€侀闄╂竻鍗曞拰鐪熷疄 Provider 寰呮帴鍏ラ」銆?- 鏂板 `docs/demo_audit_report.md`锛岃褰曞彲澶嶇幇婕旂ず鍛戒护銆佹紨绀哄満鏅€乼race 瀹¤鍜屾紨绀鸿竟鐣屻€?- 鏇存柊 `README.md`锛岃ˉ鍏?P9+ 楠屾敹涓庢紨绀哄璁″叆鍙ｃ€?- 鏄庣‘鏈€缁堢粨璁猴細宸茶揪鍒板伐涓氱骇宸ョ▼鏋舵瀯涓?Mock/Offline 鍏ㄩ摼璺獙鏀舵爣鍑嗭紱鐪熷疄 Provider 鎺ュ叆涓庣敓浜х幆澧冮獙鏀跺緟鍚庣画鏉愭枡琛ラ綈鍚庢墽琛屻€?
### 淇敼鏂囦欢
- `README.md`
- `docs/STATUS.md`
- `docs/trace_reports/p9_trace_acceptance.md`锛坱race export 楠岃瘉浜х墿锛?
### 鏂板鏂囦欢
- `docs/release_acceptance.md`
- `docs/demo_audit_report.md`

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id p9_trace_acceptance
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m app.cli --query "瑙ｉ噴涓€涓嬪崌鍔? --run-id "p9_cli_smoke_final" --no-trace
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -c "import json; from services.app_pipeline import AppPipeline; print(json.dumps(AppPipeline().health_check().to_dict(), ensure_ascii=False))"
```

### 娴嬭瘯缁撴灉

閫氳繃銆?
- 鍏ㄩ噺 pytest锛歚99 passed in 1.95s`锛岄€€鍑虹爜 0銆?- compileall锛歚COMPILEALL=ok`銆?- smoke eval锛歚case_count=3`锛宍passed_count=3`锛宍pass_rate=1.0`锛宍mock_offline=true`锛岄€€鍑虹爜 0銆?- trace export锛氱敓鎴?`docs/trace_reports/p9_trace_acceptance.md`锛宍mock_offline=true`锛岄€€鍑虹爜 0銆?- deployment validate锛歚status=ok`锛宍missing_files=[]`锛宍provider_profile=mock`锛宍real_provider_pending=true`锛岄€€鍑虹爜 0銆?- CLI query smoke锛氶€€鍑虹爜 0锛涙棤璧勬枡鏃惰繑鍥?`clarification`锛屼笉浼€犺瘉鎹€?- `AppPipeline.health_check()`锛氳繑鍥?`{"status":"ok","entry_mode":"cli","trace_enabled":true}`锛岄€€鍑虹爜 0銆?
### 琛ュ厖鎺㈡祴璁板綍
- 鏇惧皾璇?`python -m app.cli --health`锛岃繑鍥為€€鍑虹爜 2銆傜郴缁熸€ф帓鏌ュ悗纭鏍瑰洜鏄綋鍓?CLI 濂戠害娌℃湁 `--health` 鍙傛暟锛宧ealth check 浣嶄簬 `AppPipeline.health_check()`锛汸9+ 绂佹涓存椂淇敼涓氬姟閫昏緫锛屽洜姝ゆ湭鏂板 CLI 鍙傛暟锛屾敼鐢ㄥ绾﹀唴 `--query` 鍜?service facade health 楠岃瘉鍏ュ彛銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氭槸
* 鏄惁浣跨敤 Mock ASR/TTS锛氭槸
* 鏄惁浣跨敤 mock embedding锛氭槸
* 鏄惁浣跨敤 mock visual adapter锛氭槸
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`锛堜互 `docs/spec_plus.md` 浣滀负宸茬‘璁ら樁娈佃鍒掞紱鐢ㄦ埛瑕佹眰鍏ㄨ嚜鍔ㄦ墽琛岋紝涓嶆柊澧炰汉宸ョ‘璁ら棬锛?- `systematic-debugging`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- 鐪熷疄 DeepSeek API Key銆佺湡瀹?`model_id`銆乥ase_url銆佹垚鏈€侀檺娴佸拰绾夸笂璋冪敤楠屾敹寰呭悗缁帴鍏ャ€?- 鐪熷疄鏁版嵁搴撱€佺湡瀹炴寔涔呭寲銆佺湡瀹炵敓浜ч儴缃插拰杩愮淮鐩戞帶寰呭悗缁帴鍏ャ€?- 鐪熷疄 ASR/TTS銆侀害鍏嬮閾捐矾銆侀煶棰戜繚瀛樺拰闅愮绛栫暐寰呭悗缁帴鍏ャ€?- 鐪熷疄 OCR/PDF layout Provider 鍜岃瑙夐〉闈㈡绱㈢敓浜ч棴鐜緟鍚庣画鎺ュ叆銆?- 褰撳墠 eval 涓鸿交閲忚嚜鐮?smoke/mock 璇勬祴锛屼笉浠ｈ〃鐪熷疄妯″瀷璇勬祴鎴栫涓夋柟璇勬祴妗嗘灦楠屾敹銆?- 褰撳墠 trace export 涓烘湰鍦?Markdown 浜х墿锛屼笉浠ｈ〃鐢熶骇 observability collector銆?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵

P0+ 鍒?P9+ 宸插畬鎴愶紱鏈疆鍏ㄨ嚜鍔ㄥ崌绾х洰鏍囧彲浠ョ粨鏉熴€傚悗缁闇€缁х画锛屽簲鍙﹀紑鐪熷疄 Provider / 鐢熶骇鐜鎺ュ叆闃舵銆?
## 闃舵锛氭柟妗?B 鏋舵瀯淇锛圥9+ 鍚庯級

### 瀹屾垚鏃堕棿
2026-07-09 14:05:57 Asia/Shanghai

### 闃舵鐩爣
鎵ц鐢ㄦ埛纭鐨勬柟妗?B锛氱敤缁撴瀯鎬ч噸鏋勪慨澶?Agent 鍐崇瓥鍙褰曚笉鎵ц銆丷AG reviewed chunk 鐩稿叧鎬т笉瓒充粛 confident銆佸畨鍏ㄦ剰鍥句紭鍏堢骇涓嶈冻銆佹绱㈡帓搴忎緷璧栨彃鍏ラ『搴忕瓑閫昏緫闂銆?
### 瀹屾垚鍐呭
- 鏂板 `AgentRuntime`锛宍AppPipeline` 涓嶅啀鍐呭祵鏃х嚎鎬т笟鍔℃祦绋嬶紝鍙鎵?runtime 鎵ц銆?- 鏂板 `AgentActionExecutor`锛岃 `SAFE_RESPONSE`銆乣ASK_CLARIFICATION`銆乣REWRITE_ONLY`銆乣RETRIEVE_MORE`銆乣STOP` 鍏峰鐪熷疄鎵ц璺緞銆?- 鏂板 `safety.policy`锛岀粺涓€妫€娴嬪彲鎵ц鍗遍櫓鎿嶄綔缁嗚妭锛屽苟閬垮厤鎶婃嫆缁濆彞璇垽涓哄嵄闄╂楠ゃ€?- 閲嶆瀯 `GroundedAnswerGenerator` 鐨?operation safety 杈撳嚭锛屽畨鍏ㄦ剰鍥句笉澶嶈堪鍗遍櫓 evidence 鍘熸枃銆?- 鏂板 `EvidenceCandidate`銆乣EvidenceRanker`銆乣EvidenceEligibilityPolicy`锛屽皢 RAG 鏀逛负 candidate -> rank -> eligibility -> evidence package銆?- 鍒犻櫎鏃?`src/knowledge/indexes/hybrid_index.py`锛屼笉鍐嶄繚鐣欐彃鍏ラ『搴忓紡 hybrid 鍚堝苟璺緞銆?- `RetrievalController` 涓嶅啀鐢?`chunks_by_id` 鐩存帴鏀堕泦璇佹嵁锛涘彧鏈夐€氳繃鐩稿叧鎬ц祫鏍煎鏌ョ殑 reviewed chunk 鎵嶈繘鍏?`EvidencePackage.evidence_items`銆?- `QueryObject` 鏂板 `safety_flags`锛宍QueryUnderstandingService` 灏嗙淮淇€佹晠闅滃缃€佹搷浣溿€佹媶鍗搞€佹敼瑁呯瓑瀹夊叏鎰忓浘缃簬閮ㄤ欢鎰忓浘涔嬪墠銆?- 灏嗙己澶辫瘉鎹涔夌粺涓€涓?`no_qualified_evidence`锛屽苟鍦?demo audit 鏂囨。涓悓姝ユ洿鏂般€?
### 淇敼鏂囦欢
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

### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- `src/knowledge/indexes/hybrid_index.py`

### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\input\test_safety_intent_priority.py tests\integration\rag_pipeline\test_evidence_relevance_gate.py tests\unit\knowledge\test_evidence_ranker.py tests\integration\app_loop\test_agent_decision_execution.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\self_check\test_safety_policy.py tests\integration\app_loop\test_agent_decision_execution.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id scheme_b_refactor_acceptance
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
```

### 娴嬭瘯缁撴灉

閫氳繃銆?
- 鏂规 B 鐩爣娴嬭瘯锛歚8 passed`銆?- 瀹夊叏绛栫暐涓?Agent 鍐崇瓥娴嬭瘯锛歚4 passed`銆?- 鍏ㄩ噺 pytest锛歚109 passed`锛岄€€鍑虹爜 0銆?- compileall锛歚COMPILEALL=ok`銆?- smoke eval锛歚case_count=3`锛宍passed_count=3`锛宍pass_rate=1.0`锛宍mock_offline=true`銆?- trace export锛氱敓鎴?`docs/trace_reports/scheme_b_refactor_acceptance.md`锛宍mock_offline=true`銆?- deployment validate锛歚status=ok`锛宍missing_files=[]`锛宍provider_profile=mock`锛宍real_provider_pending=true`銆?
### 鍏抽敭鍥炲綊鍦烘櫙
- reviewed 璧勬枡鍙湁鈥滃彂鍔ㄦ満鎻愪緵鎺ㄥ姏鈥濇椂锛岃闂€滆В閲婁竴涓嬮樆鍔涒€濅笉浼氳繘鍏?confident evidence锛屼篃涓嶄細鍥炵瓟鍙戝姩鏈烘帹鍔涖€?- mock vector store 鍗曠嫭寮卞懡涓笉鑳芥敮鎾戝弬鏁版垨浜嬪疄鍥炵瓟銆?- 鏅€氶棶绛旇妫€绱㈠埌鍗遍櫓鎿嶄綔璧勬枡鏃讹紝`SAFE_RESPONSE` 浼氭浛鎹㈠師绋匡紝涓嶈繑鍥炲嵄闄╂楠ゃ€?- 鍦烘櫙瀵硅薄涓庡洖绛旂洰鏍囦笉涓€鑷存椂锛宍ASK_CLARIFICATION` 杩斿洖婢勬竻 answer锛屼笉杩斿洖鍘熷洖绛斻€?- 瀹夊叏鎷掔粷鍙ヤ笉浼氳瀹夊叏绛栫暐璇垽涓哄嵄闄╂搷浣滄楠ゃ€?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚︼紝鏈妭涓?P9+ 鍚庣敤鎴锋槑纭姹傜殑鏂规 B 淇銆?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁銆?* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁銆?* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁銆?* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁銆?* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁銆?* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚︺€?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚︺€?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁銆?
### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氭槸銆?* 鏄惁浣跨敤 Mock ASR/TTS锛氭槸銆?* 鏄惁浣跨敤 mock embedding锛氭槸锛屼絾 mock vector 鍛戒腑涓嶈兘鍗曠嫭鏀拺 confident evidence銆?* 鏄惁浣跨敤 mock visual adapter锛氭槸銆?* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄€?
### 椋庨櫓涓庡緟纭
- 鐪熷疄 embedding provider銆佺湡瀹炲悜閲忓簱鍜岀嚎涓?RAG 璇勬祴浠嶅緟鍚庣画鎺ュ叆銆?- 鐪熷疄 DeepSeek銆佺湡瀹炴暟鎹簱銆佺湡瀹?ASR/TTS銆佺湡瀹?OCR/PDF layout 鍜岀敓浜х幆澧冧粛寰呭悗缁帴鍏ャ€?- 褰撳墠鏂规 B 淇鐨勬槸 Mock/Offline 鏋舵瀯姝ｇ‘鎬э紝涓嶄唬琛ㄧ湡瀹?Provider 楠屾敹銆?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵

鏂规 B 宸插畬鎴愶紱鍚庣画鍙繘鍏ョ湡瀹?Provider / 鐢熶骇鐜鎺ュ叆闃舵銆?
## 闃舵锛歅8+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
寤虹珛杞婚噺鑷爺璇勬祴浣撶郴銆丒2E 鍦烘櫙銆乼race 瀵煎嚭鍜岄儴缃查厤缃牎楠岋紝浣?Mock/Offline 閾捐矾鍙瘎娴嬨€佸彲瀹¤銆佸彲楠岃瘉銆?
### 瀹屾垚鍐呭
- 鏂板 `configs/evals.yaml`锛屽畾涔?smoke suite銆佹姤鍛婄洰褰曘€侀儴缃叉牎楠屾枃浠跺拰 mock profile 瑙勫垯銆?- 鏂板 `TraceReportExporter`锛屽彲灏?`RunTrace` 瀵煎嚭涓?Markdown銆?- 鏂板 `scripts/run_eval.py --suite smoke`锛岄€氳繃姝ｅ紡 `AppPipeline` 鍜?`MockVoiceLoop` 鎵ц鏂囨湰銆佽瘉鎹笉瓒炽€佽闊?mock 涓夌被 smoke case銆?- 鏂板 `scripts/export_trace_report.py --run-id <run_id>`锛岀敓鎴愮绾?trace report銆?- 鏂板 `scripts/validate_deployment.py`锛屾鏌ュ繀瑕侀厤缃枃浠跺拰 mock provider profile銆?- 鏂板 `docs/eval_plan.md` 鍜?`docs/deployment_checklist.md`銆?- 鏂板 E2E 鍦烘櫙锛氭枃鏈棶绛斻€佸弽棣堟敼鍐欍€佽闊?mock銆乪val/trace/deployment 鑴氭湰銆?- 杩愯 smoke eval銆乼race export 鍜?deployment validate锛屽苟鐢熸垚 `docs/eval_reports/smoke_eval.json`銆乣docs/trace_reports/e2e_trace.md`銆乣docs/trace_reports/p8_trace_smoke.md`銆?
### 淇敼鏂囦欢
- `docs/STATUS.md`

### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\e2e -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id p8_trace_smoke
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
```

### 娴嬭瘯缁撴灉
閫氳繃锛孍2E `7 passed`锛屽叏閲忔祴璇?`99 passed in 0.79s`锛宻moke eval `pass_rate=1.0`锛宼race export 鍜?deployment validate 鍧囬€€鍑虹爜 0銆?
### 澶辫触淇璁板綍
- 棣栨 E2E 鍑虹幇 2 涓け璐ワ細trace export 瀛愯繘绋?stdout 鍦?Windows 涓枃璺緞涓嬫寜 UTF-8 瑙ｇ爜澶辫触锛沠eedback E2E 璋冪敤浜嗕笉瀛樺湪鐨?`EvidenceLock.build()` 鎺ュ彛銆?- 鏍瑰洜鍒嗗埆鏄剼鏈?JSON 杈撳嚭浣跨敤闈?ASCII 璺緞鏂囨湰銆丒2E 娴嬭瘯鏈寜鐪熷疄 feedback 鍑芥暟寮忔帴鍙ｈ皟鐢ㄣ€?- 淇鏂瑰紡锛氳剼鏈?stdout JSON 鏀逛负 ASCII 杞箟骞惰缃瓙杩涚▼ `PYTHONIOENCODING=utf-8`锛沠eedback E2E 鏀圭敤 `build_evidence_lock()`銆乣parse_feedback()`銆乣plan_rewrite()` 鍜?`rewrite_answer()`銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氭槸
* 鏄惁浣跨敤 mock embedding锛氫笉娑夊強
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- 褰撳墠 eval 涓鸿交閲忚嚜鐮?smoke/mock 璇勬祴锛屼笉浠ｈ〃 RAGAS/DeepEval/TruLens 鎴栫湡瀹炴ā鍨嬭瘎娴嬨€?- trace export 鍩轰簬鏈湴 mock run锛屼笉浠ｈ〃鐢熶骇 observability collector銆?- deployment validate 浠呴獙璇佹湰鍦?Mock/Offline 閰嶇疆瀹屾暣鎬э紝鐪熷疄鐢熶骇鐜寰呭悗缁帴鍏ャ€?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅7+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
琛ラ綈 Mock-first 璇煶浜や簰妯″潡锛屽寘鎷闊崇姸鎬佹満銆丮ock ASR/TTS銆乂AD銆乥arge-in銆乿oice metrics銆乿oice query object 鍜屼富 pipeline 鍏ュ彛銆?
### 瀹屾垚鍐呭
- 鏂板 `configs/voice.yaml`锛岄粯璁?ASR/TTS/VAD 鍧囦负 mock锛屽師濮嬮煶棰戦粯璁や笉钀界洏銆?- 鏂板璇煶鐘舵€佹満锛岃鐩?`IDLE`銆乣LISTENING`銆乣TRANSCRIBING`銆乣UNDERSTANDING`銆乣RETRIEVING`銆乣GENERATING`銆乣SPEAKING`銆乣INTERRUPTED`銆乣REWRITE`銆乣CLARIFY`銆?- 鏂板 `MockASRProvider`銆乣MockTTSProvider`銆乣MockVADService`銆乣BargeInController`銆乣VoiceMetricsRecorder` 鍜?mock transport銆?- 鏂板鑸┖鏈绾犻敊锛岃鐩栤€滆埅閬撴瘮 -> 娑甸亾姣斺€濃€滈浮缈?-> 鏈虹考鈥濄€?- 鏂板 `VoiceQueryNormalizer` 鍜?`VoiceQueryObject`锛屼綆缃俊 ASR 鏍囪 clarification锛屼笉杩涘叆姝ｅ紡妫€绱€?- 鏂板 `MockVoiceLoop`锛岄珮缃俊璇煶杈撳叆杩涘叆 `AppPipeline`锛屼笉缁曡繃 RAG/鐢熸垚/鑷閾捐矾銆?- 鏂板 voice 鍗曞厓娴嬭瘯鍜?mock voice loop 闆嗘垚娴嬭瘯銆?
### 淇敼鏂囦欢
- `docs/STATUS.md`

### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\voice tests\integration\voice_loop -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "raw_audio|persist|MockASR|MockTTS|low_confidence|barge|VoiceState|ASRResult|TTSResult|voice_query" src configs tests\unit\voice tests\integration\voice_loop
```

### 娴嬭瘯缁撴灉
閫氳繃锛寁oice 涓撻」娴嬭瘯 `9 passed`锛屽叏閲忔祴璇?`92 passed in 0.47s`銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氭槸
* 鏄惁浣跨敤 mock embedding锛氫笉娑夊強
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- 褰撳墠璇煶閾捐矾涓?Mock ASR/TTS/VAD锛屼笉浠ｈ〃鐪熷疄楹﹀厠椋庛€佺湡瀹?ASR/TTS Provider 鎴栦綆寤惰繜瀹炴椂璇煶鑳藉姏銆?- 鍘熷闊抽榛樿涓嶈惤鐩橈紱鐪熷疄闊抽淇濆瓨绛栫暐闇€鍚庣画浜у搧涓庨殣绉佺‘璁ゃ€?- 鐪熷疄 Provider銆佹祻瑙堝櫒闊抽鍗忚鍜?WebSocket/WebRTC 鎺ュ叆寰呭悗缁樁娈电‘璁ゃ€?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅6+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
灏?Prompt 浠庡唴缃皬鍨?store 鍗囩骇涓哄彲鐗堟湰鍖栥€佸彲蹇収銆佸彲鍥炴粴鐨勮祫浜у簱锛岃鐩栨暀瀛︺€佺煡璇嗚瑙ｃ€佽闊炽€佽嚜妫€鍜屽厹搴曟ā鏉胯竟鐣屻€?
### 瀹屾垚鍐呭
- `configs/prompts.yaml` 鏂板 `asset_dir`銆乣snapshot_dir` 鍜?rollback policy銆?- 鏂板 `assets/prompts/**` 澶栫疆 Prompt 璧勪骇锛岃鐩栨暀瀛﹁В閲娿€佺煡璇嗚瑙ｃ€佽闊冲€欓€夈€佽嚜妫€鍜屽厹搴曟ā鏉裤€?- Prompt 璧勪骇澧炲姞 `risk_boundaries`銆乣snapshot_id`锛孭rompt 鍐呭鐗堟湰澧炲姞 `snapshot_id`銆?- `PromptAssetStore.from_config()` 浼樺厛璇诲彇澶栫疆璧勪骇鐩綍锛岀己澶辨椂淇濈暀鏃ч粯璁ゆā鏉?fallback銆?- `PromptAssetStore` 鏀寔鍚屼竴 template_id 鐨勭増鏈巻鍙层€乣activate_version()` 鍜?`rollback_prompt()`銆?- 鏈鏍哥殑 `aviation_voice_spoken` 淇濇寔 `candidate` 鐘舵€侊紝涓嶈兘杩涘叆 runtime active銆?- 鏂板 Prompt snapshot fixture锛屽苟娴嬭瘯 active asset 涓?snapshot 瀵归綈銆?- 鏂板鐗堟湰鍖栥€乧andidate 闃绘柇銆佸揩鐓у拰 rollback 娴嬭瘯銆?
### 淇敼鏂囦欢
- `configs/prompts.yaml`
- `src/prompts/asset_models.py`
- `src/prompts/asset_store.py`
- `docs/STATUS.md`

### 鏂板鏂囦欢
- `assets/prompts/aviation_basic_safe.yaml`
- `assets/prompts/aviation_explain_active.yaml`
- `assets/prompts/aviation_knowledge_explain.yaml`
- `assets/prompts/aviation_self_check.yaml`
- `assets/prompts/aviation_voice_spoken.yaml`
- `tests/fixtures/prompt_snapshots/aviation_explain_active_v2.json`
- `tests/unit/prompts/test_prompt_versioning.py`

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\prompts -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "status: candidate|status: active|snapshot_id|parent_version|rollback|asset_dir|candidate_not_runtime|PromptAssetStore|activate_version" assets configs src\prompts tests\unit\prompts
```

### 娴嬭瘯缁撴灉
閫氳繃锛孭rompt 涓撻」娴嬭瘯 `12 passed`锛屽叏閲忔祴璇?`83 passed in 0.40s`銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氫笉娑夊強
* 鏄惁浣跨敤 mock embedding锛氫笉娑夊強
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- Prompt 鍐呭浠嶆槸绂荤嚎鍒濆妯℃澘锛岄渶瑕佸悗缁汉宸ヨ瘎瀹″拰鐪熷疄璇勬祴鍙嶉鍚庡啀鎵╁ぇ active 鑼冨洿銆?- `aviation_voice_spoken` 宸插瓨鍦ㄤ絾淇濇寔 candidate锛孭7+ 鍙湪璇煶閾捐矾涓户缁畬鍠勶紝鏈彁鍓嶅惎鐢ㄣ€?- Prompt snapshot 鐩墠涓鸿交閲?fixture锛孭8+ 闇€绾冲叆姝ｅ紡 eval/smoke 鍥炲綊銆?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅5+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
琛ラ綈澶氭ā鎬佸璞°€侀〉闈㈠尯鍩熴€乥box銆乴ayout trace銆佽瑙夎瘉鎹绾﹀拰 mock visual adapter锛屾槑纭己灏?OCR/layout 鏃剁殑 incomplete 鐘舵€併€?
### 瀹屾垚鍐呭
- 鏂板 `ImageInput`銆乣BoundingBox`銆乣LayoutTrace`銆乣PageRegion`銆乣VisualEvidenceItem` 濂戠害銆?- 灏?`VisualAsset.bbox` 浠庤８ tuple 鍗囩骇涓?`BoundingBox`锛屽苟澧炲姞 `layout_trace`銆乣incomplete_reasons` 鍜?metadata銆?- 鏂板 `MockVisualAdapter`锛岄粯璁よ緭鍑?mock region锛屽苟鏍囪 `visual_evidence_incomplete`銆?- `PdfIngestor` 鐨?mock layout trace 鏄庣‘鏍囪 OCR/layout 鏈惎鐢ㄣ€?- `VisualIngestor` 鍙皢 PageRegion 杞负 VisualEvidenceItem锛涙棤 text cross-check 鏃朵笉鍙綔涓烘牳蹇冧簨瀹炶瘉鎹€?- `VisualPageIndex` 鏀寔 PageRegion 鐨勭储寮曚笌鏍囩妫€绱€?- `EvidencePackageBuilder` 鏂板瑙嗚璇佹嵁杞崲鍏ュ彛锛屼繚鐣?bbox銆乴ayout trace銆乼ext cross-check 鍜?incomplete reasons銆?- `configs/rag.yaml` 琛ラ綈 visual_search銆丱CR/layout 寮€鍏炽€乼ext cross-check 瑕佹眰鍜岃瑙夊鐞嗛檺鍒躲€?- 鏂板澶氭ā鎬佸绾︽祴璇曪紝瑕嗙洊 bbox銆乴ayout trace銆乵ock adapter銆乿isual evidence gate銆?
### 淇敼鏂囦欢
- `configs/rag.yaml`
- `src/knowledge/schemas.py`
- `src/knowledge/ingestion/pdf_ingestor.py`
- `src/knowledge/ingestion/visual_ingestor.py`
- `src/knowledge/indexes/visual_page_index.py`
- `src/knowledge/evidence_package.py`
- `tests/integration/rag_pipeline/test_scene_binding_retrieval.py`
- `docs/STATUS.md`

### 鏂板鏂囦欢
- `tests/unit/knowledge/test_multimodal_contracts.py`

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\knowledge tests\integration\rag_pipeline tests\unit\self_check -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "visual_evidence_incomplete|missing_text_cross_check|text_cross_check|usable_as_core_evidence|BoundingBox|LayoutTrace|PageRegion|VisualEvidenceItem|MockVisualAdapter" src tests configs
```

### 娴嬭瘯缁撴灉
閫氳繃锛宬nowledge/RAG/self_check 鐩稿叧娴嬭瘯 `19 passed`锛屽叏閲忔祴璇?`78 passed in 0.48s`銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氫笉娑夊強
* 鏄惁浣跨敤 mock embedding锛氫笉娑夊強
* 鏄惁浣跨敤 mock visual adapter锛氭槸
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- 褰撳墠瑙嗚澶勭悊涓?mock adapter锛屼笉浠ｈ〃鐪熷疄 OCR/PDF layout 鑳藉姏銆?- 鏃?text cross-check 鐨勮瑙夌嚎绱㈠彧鍙綔涓哄€欓€夋垨 incomplete evidence锛屼笉鑳芥敮鎾戞牳蹇冭埅绌轰簨瀹炪€?- 鐪熷疄 OCR/PDF layout Provider 鍜屽ぇ鍥惧鐞嗙瓥鐣ュ緟鍚庣画鎺ュ叆銆?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅4+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
灏?RAG 浠庝粎鏈?token similarity 鐨?MVP 妫€绱㈠崌绾т负鍏峰 `EmbeddingProvider`銆乣VectorStore`銆乵ock embedding 鍜屾槑纭?fallback trace 鐨勫伐绋嬪寲鐭ヨ瘑搴撹竟鐣屻€?
### 瀹屾垚鍐呭
- 鏂板 `EmbeddingProvider` 鍗忚銆乣EmbeddingResult` 鍜?`MockEmbeddingProvider`銆?- 鏂板 `VectorStore` 鍗忚銆乣VectorSearchResult` 鍜?`InMemoryVectorStore`銆?- 涓?`SimpleVectorIndex` 澧炲姞 `simple_token_similarity`銆乣is_embedding_index=false` 鍜?fallback reason 鏍囪瘑銆?- `RetrievalController` 榛樿浣跨敤 `MockEmbeddingProvider + InMemoryVectorStore`锛屽悓鏃朵繚鐣?token similarity fallback銆?- 妫€绱?trace 鏂板 embedding provider銆佹槸鍚?mock銆乿ector store provider銆乫allback index 鍜?fallback reason銆?- `configs/rag.yaml` 琛ラ綈 embedding provider銆乨imension銆乿ector store provider銆乫allback index 鍜?trace 寮€鍏炽€?- 鏂板 VectorStore/EmbeddingProvider 鍗曞厓娴嬭瘯鍜?RAG trace 闆嗘垚娴嬭瘯銆?
### 淇敼鏂囦欢
- `configs/rag.yaml`
- `src/knowledge/indexes/vector_index.py`
- `src/knowledge/retrieval_controller.py`
- `docs/STATUS.md`

### 鏂板鏂囦欢
- `src/services/embedding_provider.py`
- `src/knowledge/indexes/vector_store.py`
- `tests/unit/knowledge/test_vector_store_contract.py`
- `tests/integration/rag_pipeline/test_vector_adapter_trace.py`

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\knowledge tests\integration\rag_pipeline -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "simple_token_similarity|token similarity|fallback|embedding_provider|VectorStore|EmbeddingProvider|SimpleVectorIndex" src configs tests docs\STATUS.md docs\upgrade_audit.md
```

### 娴嬭瘯缁撴灉
閫氳繃锛孯AG/knowledge 鐩稿叧娴嬭瘯 `9 passed`锛屽叏閲忔祴璇?`74 passed in 0.39s`銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氫笉娑夊強
* 鏄惁浣跨敤 mock embedding锛氭槸
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- 褰撳墠 embedding provider 涓?mock锛寁ector store 涓哄唴瀛樺疄鐜帮紝涓嶄唬琛ㄧ湡瀹炲悜閲忓簱鐢熶骇鑳藉姏銆?- Chroma銆丗AISS銆乻entence-transformers 鏈紩鍏ワ紝浠嶅緟 Python 3.13 + Windows 鍏煎鎬ф牳楠屻€?- `SimpleVectorIndex` 淇濈暀涓?token similarity fallback锛屼笉寰楁弿杩颁负宸ヤ笟绾?embedding 妫€绱€?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅3+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
寤虹珛 `deepseek-flash` 缁熶竴妯″瀷璋冪敤灞傘€丮ockModelClient銆丏eepSeek adapter 杈圭晫銆佺粨鏋勫寲杈撳嚭鏍￠獙銆侀噸璇曠瓥鐣ュ拰瑙勫垯鐢熸垚 fallback銆?
### 瀹屾垚鍐呭
- 鏂板 `ModelClient` 鍗忚銆乣ModelMessage`銆乣ModelOptions`銆乣ModelResult` 鍜屾ā鍨嬮敊璇垎绫汇€?- 鏂板 `MockModelClient`锛岄粯璁ゅ彲鍦ㄦ棤瀵嗛挜鐜涓嬭繑鍥炵粨鏋勫寲杈撳嚭銆?- 鏂板 `DeepSeekModelClient` adapter锛屼娇鐢ㄦ爣鍑嗗簱 HTTP 瀹㈡埛绔紝缂哄皯瀵嗛挜鎴?model_id 鏃惰繑鍥炵粨鏋勫寲閿欒锛屼笉璁块棶鐪熷疄缃戠粶銆?- 鏂板 `StructuredOutputValidator` 鍜?`ANSWER_ENVELOPE_SCHEMA`锛屾牎楠屾ā鍨嬭緭鍑哄繀椤讳负 JSON object 涓斿寘鍚牳蹇冨瓧娈点€?- 鏂板杞婚噺 `RetryPolicy`锛岃鐩栧彲閲嶈瘯闄愭祦閿欒銆?- 灏?`GroundedAnswerGenerator` 鏀逛负鍙€夋敞鍏?`ModelClient`锛涙ā鍨嬭緭鍑烘湁鏁堟椂鐢熸垚缁撴瀯鍖?answer锛屾ā鍨嬪け璐ユ垨缁撴瀯鍖栭敊璇椂鍥炶惤瑙勫垯 generator銆?- 琛ラ綈 `configs/providers.yaml` 涓?DeepSeek adapter 鐨?`chat_path`銆乫allback銆佹棩蹇楀拰 rate limit 閰嶇疆椤广€?- 鏂板妯″瀷鏈嶅姟鍜岀敓鎴愬櫒鍥為€€娴嬭瘯銆?- 鎼滅储纭 `urllib`銆丄uthorization銆丏eepSeek adapter 鍙瓨鍦ㄤ簬 `src/services/**`锛屼笟鍔℃ā鍧楁棤鐩磋繛妯″瀷 API銆?
### 淇敼鏂囦欢
- `configs/providers.yaml`
- `src/generation/generator.py`
- `docs/STATUS.md`

### 鏂板鏂囦欢
- `src/services/model_client.py`
- `src/services/deepseek_client.py`
- `src/services/structured_output.py`
- `src/services/retry_policy.py`
- `tests/unit/services/test_model_client.py`
- `tests/unit/generation/test_model_generation.py`

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\services tests\unit\generation -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
rg -n "deepseek|DeepSeek|urllib|httpx|requests|Authorization|Bearer" src tests configs docs -g '!src/services/**'
```

### 娴嬭瘯缁撴灉
閫氳繃锛屾湇鍔?鐢熸垚娴嬭瘯 `12 passed`锛屽叏閲忔祴璇?`71 passed in 0.40s`銆傝竟鐣屾悳绱㈡湭鍙戠幇涓氬姟妯″潡鐩磋繛妯″瀷 API銆?
### 澶辫触淇璁板綍
- 棣栨鏂板妯″瀷娴嬭瘯鍑虹幇 1 涓け璐ワ細缁撴瀯鍖栨牎楠屽皢 `claim_candidates: []` 璇垽涓虹己澶卞瓧娈点€?- 鏍瑰洜鏄?validator 鎶婄┖鍒楄〃绛夊悓浜庣己瀛楁锛屼絾绌?claim 鍒楄〃瀵规緞娓呮垨鏃犱簨瀹炰富寮犲洖绛旀槸鍚堟硶缁撴瀯銆?- 淇鏂瑰紡锛氱己澶卞垽瀹氭敼涓哄瓧娈典笉瀛樺湪銆乣None`銆佺┖瀛楃涓叉垨绌哄璞★紱绌哄垪琛ㄤ繚鐣欎负鍚堟硶鍊笺€?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氭槸
* 鏄惁浣跨敤 Mock ASR/TTS锛氫笉娑夊強
* 鏄惁浣跨敤 mock embedding锛氫笉娑夊強
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- `DeepSeekModelClient` 浠呭疄鐜?adapter 杈圭晫锛岄粯璁や笉鍚敤鐪熷疄璋冪敤锛涚湡瀹?`DEEPSEEK_MODEL`銆乥ase_url銆佸瘑閽ュ拰绾夸笂楠屾敹寰呭悗缁帴鍏ャ€?- P3+ 娌℃湁鎶婅鍒?generator 鎻忚堪涓虹湡瀹?LLM 鑳藉姏锛涜鍒欒矾寰勪粛鏄?fallback銆?- P6+ 浠嶉渶灏?prompt 璧勪骇杩涗竴姝ュ缃拰鐗堟湰鍖栵紝褰撳墠 P3+ 鍙繚鐣欐渶灏忕粨鏋勫寲杈撳嚭鎸囦护銆?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅2+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
寤虹珛 CLI-first 搴旂敤鍏ュ彛銆乻ervice/pipeline facade銆佸仴搴锋鏌ャ€乣run_id`銆佺粺涓€缁撴瀯鍖栭敊璇搷搴斿拰 app loop smoke 娴嬭瘯銆?
### 瀹屾垚鍐呭
- 鏂板 `src/services/app_pipeline.py`锛屼覆鑱旂幇鏈?input銆乺etrieval銆乬eneration銆乻elf_check 妯″潡锛孉PI/CLI 涓嶅鍒朵笟鍔￠€昏緫銆?- 鏂板 `TextQueryRequest`銆乣TextQueryResponse`銆乣HealthStatus` 鍜?`ErrorResponse` 绛夋湰鍦?API 濂戠害銆?- 鏂板 CLI 鍏ュ彛 `src/app/cli.py` 鍜?`src/app/main.py`锛岃緭鍑?UTF-8 JSON銆?- `configs/app.yaml` 琛ラ綈 `entry_mode`銆乭ost銆乸ort 鍜?trace 寮€鍏炽€?- 鏂板 `docs/api_contracts.md`锛岃褰?P2+ 璇锋眰銆佸搷搴斻€侀敊璇拰 CLI 鍛戒护濂戠害銆?- 鏂板 app loop 闆嗘垚娴嬭瘯锛岃鐩?health check銆乸ipeline smoke銆佺粨鏋勫寲閿欒鍜?CLI 瀛愯繘绋?smoke銆?- 鐩存帴杩愯 CLI smoke锛岀‘璁ゆ棤璧勬枡鏃惰繑鍥炵粨鏋勫寲 clarification锛屼笉浼€犺瘉鎹€?
### 淇敼鏂囦欢
- `configs/app.yaml`
- `docs/STATUS.md`

### 鏂板鏂囦欢
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

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\integration\app_loop -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
$env:PYTHONPATH='src'; $env:PYTHONIOENCODING='utf-8'; & "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m app.cli --query "瑙ｉ噴涓€涓嬪崌鍔? --run-id "run_p2_cli_smoke" --no-trace
```

### 娴嬭瘯缁撴灉
閫氳繃锛宎pp loop 娴嬭瘯 `4 passed`锛屽叏閲忔祴璇?`64 passed in 0.38s`锛孋LI smoke 閫€鍑虹爜涓?0銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氫笉娑夊強
* 鏄惁浣跨敤 mock embedding锛氫笉娑夊強
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- HTTP API 浠嶄负 optional锛屾湭寮曞叆 FastAPI/uvicorn锛涘鍚庣画闇€瑕侊紝椤诲厛纭 Python 3.13 + Windows 鍏煎鎬с€?- 榛樿 CLI 涓嶉缃湡瀹炶祫鏂欏簱锛屽洜姝ゆ棤璧勬枡鏃惰繑鍥炰繚瀹?clarification锛涙紨绀鸿祫鏂欎笌 eval 鏁版嵁鍦ㄥ悗缁樁娈垫不鐞嗐€?- P2+ 涓嶆帴鍏ユā鍨嬶紝P3+ 浠嶉渶瀹炵幇缁熶竴 ModelClient 鍜岀粨鏋勫寲鐢熸垚杈圭晫銆?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歅1+

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
瀹屾垚 UTF-8 涓枃缂栫爜鍥炲綊銆丮ock-first provider 閰嶇疆琛ラ綈銆乣.env.example` 鍜?Windows 铏氭嫙鐜鍛戒护鏂囨。鍖栵紝骞朵繚鎸佸叏閲忔祴璇曢€氳繃銆?
### 瀹屾垚鍐呭
- 鏂板 UTF-8 鏂囨湰鏂囦欢鎵弿娴嬭瘯锛岃鐩栭」鐩?Markdown銆丳ython銆乊AML銆乀OML銆乀XT 鍜?`.env.example` 绛夋枃鏈枃浠躲€?- 鏂板涓枃妫€绱㈠埌瑙勫垯鐢熸垚鐨勫洖褰掓祴璇曪紝纭涓枃鑸┖鏈鍙湪鏈湴閾捐矾涓繚鎸?UTF-8銆?- 灏?`configs/providers.yaml` 浠?`pending_confirmation` 璋冩暣涓洪粯璁?`mock` profile锛屽苟淇濈暀 DeepSeek adapter 鐨勭幆澧冨彉閲忚竟鐣屼笌 `model_id: pending_confirmation`銆?- 琛ラ綈 `.env.example` 涓?DeepSeek 鐩稿叧鐜鍙橀噺鍚嶏紝涓嶅啓鍏ョ湡瀹炲瘑閽ユ垨鐪熷疄鏈嶅姟鍦板潃銆?- 鏇存柊 `README.md`锛屾槑纭繀椤讳娇鐢ㄦ寚瀹氳櫄鎷熺幆澧冭В閲婂櫒杩愯 pytest锛屽苟澹版槑 Mock-first / Offline-first 绛栫暐銆?- 鏇存柊宸叉湁閰嶇疆鍔犺浇娴嬭瘯锛屼娇鍏跺尮閰?P1+ 鐨?mock provider 榛樿鍊笺€?
### 淇敼鏂囦欢
- `.env.example`
- `README.md`
- `configs/providers.yaml`
- `tests/unit/core/test_state_machine.py`
- `docs/STATUS.md`

### 鏂板鏂囦欢
- `tests/unit/core/test_encoding_and_config.py`

### 鍒犻櫎鏂囦欢
- 鏃?
### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests\unit\core\test_encoding_and_config.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

### 娴嬭瘯缁撴灉
閫氳繃锛屾柊澧炴祴璇?`3 passed`锛屽叏閲忔祴璇?`60 passed in 0.26s`銆?
### 澶辫触淇璁板綍
- 棣栨杩愯鏂板 UTF-8 鎵弿娴嬭瘯鏃跺嚭鐜?1 涓け璐ワ細娴嬭瘯鏂囦欢鑷韩鍖呭惈瑕佹娴嬬殑涔辩爜鏍囪甯搁噺锛屾壂鎻忓埌鑷韩鍚庤鎶ャ€?- 鏍瑰洜鏄祴璇曞す鍏锋妸鈥滃潖鏍蜂緥鈥濆啓杩涗簡琚壂鎻忚寖鍥达紝涓嶆槸椤圭洰鏂囦欢鍑虹幇涔辩爜銆?- 淇鏂瑰紡锛氬皢涔辩爜鏍囪鏀逛负杩愯鏃舵寜 Unicode code point 鏋勯€狅紝淇濇寔鎵弿鏍囧噯涓嶅彉銆?
### harness_plus.md 杈圭晫妫€鏌?
* 鏄惁璺抽樁娈碉細鍚?* 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵锛氬惁
* 鏄惁姹℃煋鍏ㄥ眬 Python 鐜锛氬惁
* 鏄惁纭紪鐮佸瘑閽ユ垨妯″瀷閰嶇疆锛氬惁
* 鏄惁鍒犻櫎娴嬭瘯瑙勯伩澶辫触锛氬惁
* 鏄惁鎶?MVP 鍗犱綅鎻忚堪涓哄伐涓氱骇鑳藉姏锛氬惁
* 鏄惁鎶?Mock/Offline 楠屾敹鎻忚堪涓虹湡瀹炵敓浜ч獙鏀讹細鍚?* 鏄惁寮曞叆鏈‘璁ゅぇ鍨嬩緷璧栵細鍚?* 鏄惁璁块棶鐪熷疄澶栭儴鏈嶅姟锛氬惁

### Mock / Offline 鐘舵€?
* 鏄惁浣跨敤 MockModelClient锛氫笉娑夊強
* 鏄惁浣跨敤 Mock ASR/TTS锛氫笉娑夊強
* 鏄惁浣跨敤 mock embedding锛氭槸锛岄厤缃粯璁?`providers.embedding.default: mock`
* 鏄惁浣跨敤 mock visual adapter锛氫笉娑夊強
* 鐪熷疄 Provider 鏄惁寰呭悗缁帴鍏ワ細鏄?
### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `writing-plans`
- `systematic-debugging`
- `verification-before-completion`

### 椋庨櫓涓庡緟纭
- 褰撳墠鍙槸閰嶇疆灞?mock profile锛岀湡姝?`MockModelClient` 鍜?DeepSeek adapter 鍦?P3+ 瀹炵幇銆?- `DEEPSEEK_MODEL`銆佺湡瀹?base_url 鍜岀粨鏋勫寲杈撳嚭鑳藉姏浠嶅緟 P3+ 閰嶇疆鏍搁獙銆?- 鍚戦噺妫€绱粛鏈崌绾э紝`simple_token_similarity` 鍙綔涓?P4+ 鍓嶇殑 fallback 鏍囪銆?
### 鏄惁鍙互杩涘叆涓嬩竴闃舵
鍙互銆?
## 闃舵锛歍ask 2 (P1 Prompt Asset Repository Migration)

### 瀹屾垚鏃堕棿
2026-07-09 Asia/Shanghai

### 闃舵鐩爣
鏋勫缓 JSON Prompt Asset Repository锛岃縼绉诲钩閾?YAML Prompt 璧勪骇鍒扮洰褰曞寲 JSON 鐗堟湰璧勪骇锛屽苟绉婚櫎鏃х殑 `PromptAssetStore` / 骞抽摵 YAML 鍔犺浇璺緞銆?
### 宸插畬鎴愬唴瀹?- 鏂板 `PromptAssetRepository`锛屾敮鎸佷粠 `configs/prompts.yaml` 璇诲彇鐩綍鍖?JSON 璧勪骇銆?- 瀹炵幇 `get_asset()`銆乣get_version()`銆乣get_active_version()`銆乣find_for_intent()`銆乣get_evaluation_snapshot()`銆乣list_versions()`銆?- 灏?`PromptRouter` 浠庢棫 `PromptAssetStore` 鍒囨崲鍒版柊 repository锛屽苟鍚屾鏇存柊閰嶇疆閿负 `clarification_template_id`銆乣required_final_fact_variables`銆?- 灏?`PromptAssembler` 璋冩暣涓烘敞鍏ラ€変腑鐗堟湰鐨勭湡瀹?prompt 鍐呭锛岃€屼笉鏄棫妯℃澘 id銆?- 鏂板 `tests/unit/prompts/test_prompt_repository.py`锛屽厛绾㈠悗缁块獙璇?Task 2 鐩爣鎺ュ彛銆?- 灏嗘棫 YAML prompt 璧勪骇杩佺Щ涓?JSON 鐩綍璧勪骇锛歚asset.json` + `versions/v1.json` + `versions/v2.json` + `evaluations/*.json`銆?- 鍒犻櫎鏃у钩閾?YAML 璧勪骇鍜?`src/prompts/asset_store.py`锛屼笉淇濈暀鍏煎 shim銆?
### 淇敼鏂囦欢
- `configs/prompts.yaml`
- `src/prompts/router.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_router.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `docs/STATUS.md`

### 鏂板鏂囦欢
- `src/prompts/repository.py`
- `tests/unit/prompts/test_prompt_repository.py`
- `assets/prompts/aviation_fact_qa/asset.json`
- `assets/prompts/aviation_fact_qa/versions/v1.json`
- `assets/prompts/aviation_fact_qa/versions/v2.json`
- `assets/prompts/aviation_fact_qa/evaluations/aviation_fact_qa_v2.json`
- `assets/prompts/aviation_component_explain/asset.json`
- `assets/prompts/aviation_component_explain/versions/v1.json`
- `assets/prompts/aviation_component_explain/versions/v2.json`
- `assets/prompts/aviation_component_explain/evaluations/aviation_component_explain_v2.json`
- `assets/prompts/aviation_concept_correction/asset.json`
- `assets/prompts/aviation_concept_correction/versions/v1.json`
- `assets/prompts/aviation_concept_correction/versions/v2.json`
- `assets/prompts/aviation_concept_correction/evaluations/aviation_concept_correction_v2.json`
- `assets/prompts/aviation_quiz_reinforcement/asset.json`
- `assets/prompts/aviation_quiz_reinforcement/versions/v1.json`
- `assets/prompts/aviation_quiz_reinforcement/versions/v2.json`
- `assets/prompts/aviation_quiz_reinforcement/evaluations/aviation_quiz_reinforcement_v2.json`
- `assets/prompts/aviation_feedback_rewrite/asset.json`
- `assets/prompts/aviation_feedback_rewrite/versions/v1.json`
- `assets/prompts/aviation_feedback_rewrite/versions/v2.json`
- `assets/prompts/aviation_feedback_rewrite/evaluations/aviation_feedback_rewrite_v2.json`
- `assets/prompts/aviation_self_check/asset.json`
- `assets/prompts/aviation_self_check/versions/v1.json`
- `assets/prompts/aviation_self_check/versions/v2.json`
- `assets/prompts/aviation_self_check/evaluations/aviation_self_check_v2.json`
- `assets/prompts/aviation_basic_safe/asset.json`
- `assets/prompts/aviation_basic_safe/versions/v1.json`
- `assets/prompts/aviation_basic_safe/versions/v2.json`
- `assets/prompts/aviation_basic_safe/evaluations/aviation_basic_safe_v2.json`
- `assets/prompts/asr_correction_prompt/asset.json`
- `assets/prompts/asr_correction_prompt/versions/v1.json`
- `assets/prompts/asr_correction_prompt/versions/v2.json`
- `assets/prompts/asr_correction_prompt/evaluations/asr_correction_prompt_v2.json`
- `assets/prompts/spoken_answer_style_prompt/asset.json`
- `assets/prompts/spoken_answer_style_prompt/versions/v1.json`
- `assets/prompts/spoken_answer_style_prompt/versions/v2.json`
- `assets/prompts/spoken_answer_style_prompt/evaluations/spoken_answer_style_prompt_v2.json`
- `assets/prompts/barge_in_feedback_prompt/asset.json`
- `assets/prompts/barge_in_feedback_prompt/versions/v1.json`
- `assets/prompts/barge_in_feedback_prompt/versions/v2.json`
- `assets/prompts/barge_in_feedback_prompt/evaluations/barge_in_feedback_prompt_v2.json`
- `assets/prompts/experimental_teaching_strategy/asset.json`
- `assets/prompts/experimental_teaching_strategy/versions/v1.json`
- `assets/prompts/experimental_teaching_strategy/versions/v2.json`
- `assets/prompts/experimental_teaching_strategy/evaluations/experimental_teaching_strategy_v2.json`

### 鍒犻櫎鏂囦欢
- `src/prompts/asset_store.py`
- `tests/unit/prompts/test_prompt_versioning.py`
- `assets/prompts/aviation_basic_safe.yaml`
- `assets/prompts/aviation_explain_active.yaml`
- `assets/prompts/aviation_knowledge_explain.yaml`
- `assets/prompts/aviation_self_check.yaml`
- `assets/prompts/aviation_voice_spoken.yaml`

### 娴嬭瘯鍛戒护
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_router.py tests/unit/prompts/test_prompt_assembler.py -q
```

### 娴嬭瘯缁撴灉
- `tests/unit/prompts/test_prompt_repository.py -q`锛歚6 passed`
- `tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py -q`锛歚11 passed`
- `tests/unit/prompts/test_prompt_router.py tests/unit/prompts/test_prompt_assembler.py -q`锛歚7 passed`

### 瀹為檯璋冪敤杩囩殑鍏抽敭 skill
- `using-superpowers`
- `brainstorming`锛堟湰浠诲姟宸叉湁鏄庣‘ brief锛屾寜宸叉壒鍑嗚璁＄洿鎺ュ疄鐜帮級
- `writing-plans`
- `verification-before-completion`

### 鏄惁杩濆弽 harness.md
鍚︺€?
### 鏈畬鎴愪簨椤?- `tests/fixtures/prompt_snapshots/aviation_explain_active_v2.json` 浠嶆槸鏃ф祴璇曞す鍏凤紝褰撳墠 Task 2 楠屾敹涓嶅啀渚濊禆瀹冿紱鍚庣画鏃?prompt 鏍堟竻鐞嗕换鍔″彲涓€骞跺垹闄ゆ垨閲嶅懡鍚嶃€?- 鏃?prompt 璺敱/缁勮涔嬪鐨勬洿娣卞眰璋冪敤杩佺Щ浠嶇暀寰呭悗缁换鍔＄户缁帹杩涖€?
### 涓嬩竴闃舵鏄惁鍙互寮€濮?鍙互銆?
```

## docs/superpowers/plans/_sdd/task-2-report.md

```
# Task 2 Report: Build JSON Prompt Asset Repository and Migrate Assets

## 浠诲姟缁撹

Task 2 宸插畬鎴愩€侾rompt 璧勪骇宸蹭粠骞抽摵 YAML 杩佺Щ涓虹洰褰曞寲 JSON 鐗堟湰璧勪骇锛屾柊鐨?`PromptAssetRepository` 宸叉帴绠￠厤缃姞杞姐€佺増鏈鍙栥€佽繍琛屾€佺瓫閫夊拰璇勪及蹇収鏍￠獙锛涙棫 `PromptAssetStore` 涓庢棫 YAML 璧勪骇宸插垹闄ゃ€?
## 闇€瑕佽皟鐢ㄧ殑 skill

鏈鎵ц瀹為檯浣跨敤椤哄簭锛?
1. `using-superpowers`
   - 鎸夋湰鍦拌鍒欏厛纭蹇呴』鍚敤鐨勬祦绋嬪瀷 skill銆?2. `brainstorming`
   - 浠诲姟宸叉湁鏄庣‘ brief锛屾湰娆″皢 brief 瑙嗕负宸叉壒鍑嗚璁★紝浠呯敤浜庨伒瀹堟祦绋嬪苟纭涓嶅彟寮€璁捐鍒嗘敮銆?3. `writing-plans`
   - 鐢ㄤ簬鎷嗚В Task 2 鐨勬祴璇曘€佸疄鐜般€佽縼绉汇€侀獙璇佸拰鏂囨。鏀跺熬姝ラ銆?4. `verification-before-completion`
   - 鍦ㄨ緭鍑哄畬鎴愮粨璁哄墠閲嶆柊杩愯鐢ㄦ埛鎸囧畾鐨?pytest 鍛戒护骞惰褰曠粨鏋溿€?
## 鍙樻洿鎽樿

### 浠ｇ爜涓庨厤缃?
- 鏂板 [src/prompts/repository.py](D:/APP/Python 3.13/鎸戞垬鏉?src/prompts/repository.py)
  - 瀹炵幇 `PromptAssetRepository`
  - 瀹炵幇 `PromptRepositoryError`
  - 鏀寔锛?    - `from_config()`
    - `get_asset()`
    - `get_version()`
    - `get_active_version()`
    - `find_for_intent()`
    - `get_evaluation_snapshot()`
    - `list_versions()`
  - 瀵硅繍琛屾€?`active` / `experimental` 璧勪骇鎵ц鎵瑰噯蹇収鏍￠獙

- 淇敼 [src/prompts/router.py](D:/APP/Python 3.13/鎸戞垬鏉?src/prompts/router.py)
  - 浠?`PromptAssetStore` 鍒囨崲鍒?`PromptAssetRepository`
  - 閰嶇疆閿敼涓?`clarification_template_id`
  - 閰嶇疆閿敼涓?`required_final_fact_variables`
  - `PromptSelection` 鏀圭敤宸插瓨鍦ㄧ殑 `route_reason` / `is_clarification` / `selected_version`

- 淇敼 [src/prompts/assembler.py](D:/APP/Python 3.13/鎸戞垬鏉?src/prompts/assembler.py)
  - `prompt_asset` 娉ㄥ叆鏀逛负鐪熷疄鐗堟湰鍐呭
  - `MessageBundle.fallback_reason` 鏀逛负 `route_reason`
  - `is_final_fact_prompt` 渚濇嵁 `is_clarification` 鍒ゆ柇

- 淇敼 [configs/prompts.yaml](D:/APP/Python 3.13/鎸戞垬鏉?configs/prompts.yaml)
  - `default_template_id: aviation_fact_qa`
  - `clarification_template_id: aviation_basic_safe`
  - 绉婚櫎鏃?`snapshot_dir`
  - `required_final_fact_variables: [rag_evidence, output_contract]`

### 娴嬭瘯

- 鏂板 [tests/unit/prompts/test_prompt_repository.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_repository.py)
  - 鍏堢孩鍚庣豢楠岃瘉鏂?repository 鎺ュ彛

- 淇敼 [tests/unit/prompts/test_prompt_router.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_router.py)
  - 鍒囨崲鍒?`PromptAssetRepository`
  - 鏍￠獙 clarification fallback 涓庨潪杩愯鎬佽祫浜ф嫤鎴?
- 淇敼 [tests/unit/prompts/test_prompt_assembler.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_assembler.py)
  - 鏍￠獙鐪熷疄 prompt 鍐呭娉ㄥ叆涓?final fact prompt 閫昏緫

- 鍒犻櫎 [tests/unit/prompts/test_prompt_versioning.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_versioning.py)
  - 鏃ф祴璇曚緷璧?`PromptAssetStore` 涓?YAML 璧勪骇锛屽凡鐢?repository 娴嬭瘯鏇夸唬

### 璧勪骇杩佺Щ

鏃ф枃浠跺凡鍒犻櫎锛?
- `assets/prompts/aviation_basic_safe.yaml`
- `assets/prompts/aviation_explain_active.yaml`
- `assets/prompts/aviation_knowledge_explain.yaml`
- `assets/prompts/aviation_self_check.yaml`
- `assets/prompts/aviation_voice_spoken.yaml`

鏂板鐩綍鍖?JSON 璧勪骇锛?
- `assets/prompts/aviation_fact_qa/**`
- `assets/prompts/aviation_component_explain/**`
- `assets/prompts/aviation_concept_correction/**`
- `assets/prompts/aviation_quiz_reinforcement/**`
- `assets/prompts/aviation_feedback_rewrite/**`
- `assets/prompts/aviation_self_check/**`
- `assets/prompts/aviation_basic_safe/**`
- `assets/prompts/asr_correction_prompt/**`
- `assets/prompts/spoken_answer_style_prompt/**`
- `assets/prompts/barge_in_feedback_prompt/**`
- `assets/prompts/experimental_teaching_strategy/**`

姣忎釜鐩綍鍧囧寘鍚細

- `asset.json`
- `versions/v1.json`
- `versions/v2.json`
- `evaluations/*.json`

鍏朵腑锛?
- 杩愯鎬?`active` 璧勪骇鐨?`v2` 閮藉甫鏈?`approve` 蹇収
- `experimental_teaching_strategy` 鏍囪涓?`candidate`
- 鎵€鏈?prompt 鍐呭鍧囦负澶氭鏁欏鎸囦护
- 鎵€鏈?prompt 鍐呭鍙畾涔夋寚浠ゃ€佽瘉鎹竟鐣屽拰杈撳嚭琛屼负锛屼笉鎻愪緵鑸┖浜嬪疄

## 璇︾粏鏂囦欢娓呭崟

### 淇敼鏂囦欢

- [configs/prompts.yaml](D:/APP/Python 3.13/鎸戞垬鏉?configs/prompts.yaml)
- [src/prompts/router.py](D:/APP/Python 3.13/鎸戞垬鏉?src/prompts/router.py)
- [src/prompts/assembler.py](D:/APP/Python 3.13/鎸戞垬鏉?src/prompts/assembler.py)
- [tests/unit/prompts/test_prompt_router.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_router.py)
- [tests/unit/prompts/test_prompt_assembler.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_assembler.py)
- [docs/STATUS.md](D:/APP/Python 3.13/鎸戞垬鏉?docs/STATUS.md)

### 鏂板鏂囦欢

- [src/prompts/repository.py](D:/APP/Python 3.13/鎸戞垬鏉?src/prompts/repository.py)
- [tests/unit/prompts/test_prompt_repository.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_repository.py)
- `assets/prompts/**/asset.json`
- `assets/prompts/**/versions/v1.json`
- `assets/prompts/**/versions/v2.json`
- `assets/prompts/**/evaluations/*.json`

### 鍒犻櫎鏂囦欢

- [src/prompts/asset_store.py](D:/APP/Python 3.13/鎸戞垬鏉?src/prompts/asset_store.py)
- [tests/unit/prompts/test_prompt_versioning.py](D:/APP/Python 3.13/鎸戞垬鏉?tests/unit/prompts/test_prompt_versioning.py)
- [assets/prompts/aviation_basic_safe.yaml](D:/APP/Python 3.13/鎸戞垬鏉?assets/prompts/aviation_basic_safe.yaml)
- [assets/prompts/aviation_explain_active.yaml](D:/APP/Python 3.13/鎸戞垬鏉?assets/prompts/aviation_explain_active.yaml)
- [assets/prompts/aviation_knowledge_explain.yaml](D:/APP/Python 3.13/鎸戞垬鏉?assets/prompts/aviation_knowledge_explain.yaml)
- [assets/prompts/aviation_self_check.yaml](D:/APP/Python 3.13/鎸戞垬鏉?assets/prompts/aviation_self_check.yaml)
- [assets/prompts/aviation_voice_spoken.yaml](D:/APP/Python 3.13/鎸戞垬鏉?assets/prompts/aviation_voice_spoken.yaml)

## 娴嬭瘯鎵ц璁板綍

### 鍏堢孩闃舵

鍛戒护锛?
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py -q
```

杈撳嚭锛?
```text
=================================== ERRORS ====================================
________ ERROR collecting tests/unit/prompts/test_prompt_repository.py ________
ImportError while importing test module 'D:\APP\Python 3.13\鎸戞垬鏉痋tests\unit\prompts\test_prompt_repository.py'.
...
E   ModuleNotFoundError: No module named 'prompts.repository'
=========================== short test summary info ============================
ERROR tests/unit/prompts/test_prompt_repository.py
!!!!!!!!!!!!!!!!!!! Interrupted: 1 error during collection !!!!!!!!!!!!!!!!!!!!
```

璇存槑锛?
- 杩欐槸棰勬湡澶辫触锛岃瘉鏄?repository 娴嬭瘯鍏堜簬瀹炵幇钀藉湴銆?
### 鐢ㄦ埛瑕佹眰鐨勯獙璇佸懡浠?1

鍛戒护锛?
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py -q
```

杈撳嚭锛?
```text
......                                                                   [100%]
```

缁撴灉锛?
- `6 passed`

### 鐢ㄦ埛瑕佹眰鐨勯獙璇佸懡浠?2

鍛戒护锛?
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py tests/unit/prompts/test_prompt_repository.py -q
```

杈撳嚭锛?
```text
...........                                                              [100%]
```

缁撴灉锛?
- `11 passed`

### 棰濆鍥炲綊鍛戒护

鍛戒护锛?
```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_router.py tests/unit/prompts/test_prompt_assembler.py -q
```

杈撳嚭锛?
```text
.......                                                                  [100%]
```

缁撴灉锛?
- `7 passed`

## Harness / 杈圭晫妫€鏌?
- 鏄惁璺抽樁娈碉細鍚?- 鏄惁鎻愬墠瀹炵幇鍚庣画闃舵鍔熻兘锛氬惁
- 鏄惁寮曞叆鏂颁緷璧栵細鍚?- 鏄惁淇濈暀 `PromptAssetStore` / `default_prompt_assets` / 骞抽摵 YAML 鍔犺浇鍏煎 shim锛氬惁
- 鏄惁灏嗚埅绌轰簨瀹炲啓鍏?prompt 璧勪骇锛氬惁
- 鏄惁璁╄繍琛屾€?active/experimental 鐗堟湰缂哄皯鎵瑰噯蹇収锛氬惁
- 鏄惁淇敼 git 鐘舵€侊紙add/commit/reset/checkout 绛夛級锛氬惁

## 椋庨櫓涓庡緟鍚庣画澶勭悊

1. [tests/fixtures/prompt_snapshots/aviation_explain_active_v2.json](D:/APP/Python 3.13/鎸戞垬鏉?tests/fixtures/prompt_snapshots/aviation_explain_active_v2.json) 浠嶆槸鏃у懡鍚嶅す鍏枫€?   - 褰撳墠 Task 2 涓嶅啀渚濊禆瀹冦€?   - 鍚庣画鏃?prompt 鏍堝交搴曟媶闄ゆ椂锛屽缓璁竴骞舵竻鐞嗐€?
2. 鐩墠浠呭鏈换鍔＄洿鎺ュ奖鍝嶇殑 prompt 璺敱涓庣粍瑁呴摼璺仛浜?repository 杩佺Щ銆?   - 鍚庣画浠诲姟濡傛灉缁х画鏇挎崲鏃?prompt stack锛屾洿娣卞眰璋冪敤鐐逛粛搴旂户缁敹鏁涘埌 repository銆?
## 缁撹

Task 2 宸叉寜 brief 瀹屾垚锛屾寚瀹氭祴璇曢€氳繃锛岃祫浜х洰褰曠粨鏋勪笌杩愯鎬佹不鐞嗙害鏉熷凡钀藉湴銆?
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
