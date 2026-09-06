# Prompt Engineering Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild prompt engineering as the single runtime and governance path for prompt assets, message assembly, trace audit, rollback, and voice prompt use.

**Architecture:** Replace the current side-car prompt store with a JSON-backed `PromptAssetRepository`, a single `PromptRuntime` for routing and message assembly, and a `PromptGovernanceService` for activation, deprecation, evaluation records, and rollback. Generation and voice flows consume prompt bundles instead of constructing prompt text independently.

**Tech Stack:** Python 3.13, dataclasses, stdlib `json`, existing `ModelMessage`, existing dataclass contracts, pytest. No new package dependency.

## Global Constraints

- Do not introduce a heavy external prompt platform.
- Do not let prompt assets supply aviation facts.
- Do not keep compatibility shims for old prompt APIs once callers are migrated.
- Do not keep one-line prompt assets as production assets.
- Do not implement true online provider validation in this refactor.
- Use `D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe` for test commands.
- Keep local mock/offline tests runnable without real API keys.
- Prompt full text must not be stored in normal trace output.

---

## File Structure

Create or replace these prompt files:

- Modify: `src/prompts/asset_models.py` as the single domain model module.
- Create: `src/prompts/repository.py` for JSON asset loading and version lookup.
- Modify: `src/prompts/router.py` so it only selects assets and versions.
- Modify: `src/prompts/assembler.py` so it only renders validated variables into model messages.
- Create: `src/prompts/runtime.py` as the only runtime entry point.
- Create: `src/prompts/governance.py` for promotion, deprecation, evaluation records, and rollback.
- Modify: `src/prompts/__init__.py` to export the new public API.
- Delete: `src/prompts/asset_store.py` after repository migration.

Convert prompt assets:

- Delete old files under `assets/prompts/*.yaml`.
- Create JSON directories under `assets/prompts/<template_id>/`.
- Move evaluation snapshots under each asset's `evaluations/` directory.

Update callers:

- Modify: `src/agent/runtime.py`
- Modify: `src/generation/generator.py`
- Modify: `src/core/contracts.py`
- Modify: `src/observability/trace_exporter.py`
- Modify: `scripts/export_trace_report.py`
- Modify: `src/voice/terminology.py`
- Modify: `src/input/voice_query_normalizer.py`
- Modify: `src/voice/tts.py`
- Modify: `src/voice/barge_in.py`
- Modify: `src/voice/voice_loop.py`
- Modify: `scripts/run_eval.py`

Replace tests:

- Replace: `tests/unit/prompts/test_prompt_versioning.py`
- Replace: `tests/unit/prompts/test_prompt_router.py`
- Replace: `tests/unit/prompts/test_prompt_assembler.py`
- Create: `tests/unit/prompts/test_prompt_repository.py`
- Create: `tests/unit/prompts/test_prompt_runtime.py`
- Create: `tests/unit/prompts/test_prompt_governance.py`
- Modify: `tests/integration/app_loop/test_cli_pipeline.py`
- Modify: `tests/integration/voice_loop/test_mock_voice_loop.py`
- Modify: `tests/e2e/scenarios/test_text_qa_flow.py`
- Modify: `tests/e2e/scenarios/test_voice_flow.py`

---

### Task 1: Replace Prompt Domain Models

**Files:**
- Modify: `src/prompts/asset_models.py`
- Modify: `src/core/contracts.py`
- Test: `tests/unit/prompts/test_prompt_models.py`

**Interfaces:**
- Produces: `PromptStatus`, `PromptVariableSpec`, `PromptContentVersion`, `TeachingPromptAsset`, `PromptSelection`, `PromptMessageBundle`, `PromptEvaluationSnapshot`, `PromptGovernanceResult`.
- Consumes: `BaseContract`, `ModelMessage`.

- [ ] **Step 1: Write failing model tests**

```python
# tests/unit/prompts/test_prompt_models.py
import pytest

from prompts.asset_models import (
    PromptContentVersion,
    PromptEvaluationSnapshot,
    PromptMessageBundle,
    PromptStatus,
    PromptVariableSpec,
    TeachingPromptAsset,
)
from services.model_client import ModelMessage


def test_prompt_variable_spec_requires_name_source_and_missing_behavior():
    spec = PromptVariableSpec(
        name="rag_evidence",
        source="evidence_package",
        value_type="evidence_summary",
        required=True,
        missing_behavior="clarification",
        description="Reviewed evidence used as the only fact source.",
    )

    assert spec.name == "rag_evidence"
    assert spec.required is True


def test_prompt_message_bundle_uses_model_messages_only():
    bundle = PromptMessageBundle(
        messages=[ModelMessage(role="system", content="facts from evidence only")],
        injection_order=["system_boundary"],
        template_id="aviation_fact_qa",
        version="v1",
        snapshot_id="aviation_fact_qa_v1",
        injection_summary=[{"section": "system_boundary", "chars": 24}],
    )

    assert bundle.messages[0].role == "system"
    assert bundle.template_id == "aviation_fact_qa"


def test_active_asset_points_to_existing_active_version_shape():
    version = PromptContentVersion(
        template_id="aviation_fact_qa",
        version="v1",
        content="Use {{rag_evidence}} only.",
        review_status=PromptStatus.ACTIVE,
        snapshot_id="aviation_fact_qa_v1",
        variables=[
            PromptVariableSpec(
                name="rag_evidence",
                source="evidence_package",
                value_type="evidence_summary",
                required=True,
                missing_behavior="clarification",
                description="Reviewed evidence.",
            )
        ],
    )
    asset = TeachingPromptAsset(
        template_id="aviation_fact_qa",
        title="Aviation factual answer",
        task_type="concept_explanation",
        status=PromptStatus.ACTIVE,
        active_version="v1",
        activation_scope=["concept_explanation"],
        constraints=["facts_from_evidence_only"],
        risk_boundaries=["no_parameter_without_source"],
    )

    assert asset.active_version == version.version
    assert asset.status == PromptStatus.ACTIVE


def test_evaluation_snapshot_has_final_decision():
    snapshot = PromptEvaluationSnapshot(
        snapshot_id="aviation_fact_qa_v1",
        template_id="aviation_fact_qa",
        version="v1",
        test_cases=["basic_concept"],
        dimension_scores={"faithfulness": 1.0},
        violations=[],
        improvements=["clearer evidence wording"],
        final_decision="approve",
    )

    assert snapshot.final_decision == "approve"
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py -q
```

Expected: fails because the new dataclasses do not exist.

- [ ] **Step 3: Replace `src/prompts/asset_models.py`**

Use this implementation shape:

```python
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from core.contracts import BaseContract
from services.model_client import ModelMessage


class PromptStatus(StrEnum):
    DRAFT = "draft"
    CANDIDATE = "candidate"
    ACTIVE = "active"
    EXPERIMENTAL = "experimental"
    DEPRECATED = "deprecated"


@dataclass
class PromptVariableSpec(BaseContract):
    required_fields = ("name", "source", "value_type", "missing_behavior")

    name: str = ""
    source: str = ""
    value_type: str = "text"
    required: bool = False
    missing_behavior: str = "omit"
    description: str = ""


@dataclass
class PromptContentVersion(BaseContract):
    required_fields = ("template_id", "version", "content", "review_status")

    template_id: str = ""
    version: str = ""
    content: str = ""
    parent_version: str | None = None
    change_reason: str = ""
    created_at: str = ""
    review_status: PromptStatus = PromptStatus.DRAFT
    snapshot_id: str | None = None
    variables: list[PromptVariableSpec] = field(default_factory=list)


@dataclass
class TeachingPromptAsset(BaseContract):
    required_fields = ("template_id", "title", "task_type", "status", "active_version")

    template_id: str = ""
    title: str = ""
    task_type: str = ""
    concept_scope: list[str] = field(default_factory=list)
    scene_scope: list[str] = field(default_factory=list)
    status: PromptStatus = PromptStatus.DRAFT
    active_version: str = ""
    activation_scope: list[str] = field(default_factory=list)
    constraints: list[str] = field(default_factory=list)
    risk_boundaries: list[str] = field(default_factory=list)


@dataclass
class PromptSelection(BaseContract):
    template_id: str = ""
    version: str = ""
    task_type: str = ""
    status: PromptStatus = PromptStatus.ACTIVE
    route_reason: str = ""
    missing_variables: list[str] = field(default_factory=list)
    selected_asset: TeachingPromptAsset | None = None
    selected_version: PromptContentVersion | None = None
    is_clarification: bool = False


@dataclass
class PromptMessageBundle(BaseContract):
    messages: list[ModelMessage] = field(default_factory=list)
    injection_order: list[str] = field(default_factory=list)
    template_id: str = ""
    version: str = ""
    snapshot_id: str | None = None
    injection_summary: list[dict[str, Any]] = field(default_factory=list)
    missing_variables: list[str] = field(default_factory=list)
    is_final_fact_prompt: bool = True


@dataclass
class PromptOptimizationRecord(BaseContract):
    input_version: str = ""
    output_draft_version: str = ""
    model: str = ""
    instruction: str = ""
    trigger_event: str = ""
    created_at: str = ""


@dataclass
class PromptExample(BaseContract):
    variables: dict[str, Any] = field(default_factory=dict)
    rag_requirement: str = ""
    expected_focus: list[str] = field(default_factory=list)
    failure_modes: list[str] = field(default_factory=list)
    real_output: str = ""
    score: dict[str, float] = field(default_factory=dict)


@dataclass
class PromptEvaluationSnapshot(BaseContract):
    required_fields = ("snapshot_id", "template_id", "version", "final_decision")

    snapshot_id: str = ""
    template_id: str = ""
    version: str = ""
    test_cases: list[str] = field(default_factory=list)
    dimension_scores: dict[str, float] = field(default_factory=dict)
    violations: list[str] = field(default_factory=list)
    improvements: list[str] = field(default_factory=list)
    final_decision: str = ""


@dataclass
class CompareStopSignals(BaseContract):
    target_vs_baseline: str = ""
    reference_gap: str = ""
    overfit_risk: str = "unknown"
    stop_recommendation: str = ""


@dataclass
class PromptGovernanceResult(BaseContract):
    template_id: str = ""
    version: str = ""
    action: str = ""
    ok: bool = False
    reason: str = ""
```

- [ ] **Step 4: Remove the duplicate core prompt contract**

In `src/core/contracts.py`, delete the `PromptAsset` dataclass and remove `"prompt_asset": PromptAsset` from `CONTRACT_TYPES`.

- [ ] **Step 5: Run model tests**

Run:

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py -q
```

Expected: `4 passed`.

- [ ] **Step 6: Commit**

```powershell
git add src/prompts/asset_models.py src/core/contracts.py tests/unit/prompts/test_prompt_models.py
git commit -m "refactor: define prompt domain models"
```

---

### Task 2: Build JSON Prompt Asset Repository and Migrate Assets

**Files:**
- Create: `src/prompts/repository.py`
- Delete: `src/prompts/asset_store.py`
- Modify: `configs/prompts.yaml`
- Delete: `assets/prompts/*.yaml`
- Create: `assets/prompts/**/asset.json`
- Create: `assets/prompts/**/versions/*.json`
- Create: `assets/prompts/**/evaluations/*.json`
- Test: `tests/unit/prompts/test_prompt_repository.py`

**Interfaces:**
- Consumes: prompt domain models from Task 1.
- Produces: `PromptAssetRepository.from_config()`, `get_asset()`, `get_version()`, `get_active_version()`, `find_for_intent()`, `get_evaluation_snapshot()`, `list_versions()`.

- [ ] **Step 1: Write failing repository tests**

```python
# tests/unit/prompts/test_prompt_repository.py
import pytest

from prompts.asset_models import PromptStatus
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
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py -q
```

Expected: import failure for `prompts.repository`.

- [ ] **Step 3: Create repository implementation**

```python
# src/prompts/repository.py
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
    def __init__(self, asset_dir: str | Path, allowed_runtime_statuses: list[str] | None = None) -> None:
        self.asset_dir = Path(asset_dir)
        self.allowed_runtime_statuses = set(allowed_runtime_statuses or ["active", "experimental"])
        self._assets: dict[str, TeachingPromptAsset] = {}
        self._versions: dict[str, dict[str, PromptContentVersion]] = {}
        self._snapshots: dict[tuple[str, str], PromptEvaluationSnapshot] = {}
        self._load()

    @classmethod
    def from_config(cls, path: str | Path = "configs/prompts.yaml") -> "PromptAssetRepository":
        data = parse_simple_yaml(Path(path).read_text(encoding="utf-8"))
        prompts = data.get("prompts", {})
        asset_dir = prompts.get("asset_dir")
        if not asset_dir:
            raise PromptRepositoryError("prompts.asset_dir is required")
        return cls(asset_dir, prompts.get("allowed_runtime_statuses", ["active", "experimental"]))

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

    def list_versions(self, template_id: str) -> list[str]:
        return sorted(self._versions.get(template_id, {}))

    def find_for_intent(self, intent_type: str) -> TeachingPromptAsset | None:
        for asset in self._assets.values():
            if asset.status.value not in self.allowed_runtime_statuses:
                continue
            if intent_type in asset.activation_scope:
                return asset
        return None

    def runtime_assets(self) -> list[TeachingPromptAsset]:
        return [
            asset
            for asset in self._assets.values()
            if asset.status.value in self.allowed_runtime_statuses
        ]

    def _load(self) -> None:
        if not self.asset_dir.exists():
            raise PromptRepositoryError(f"prompt asset directory does not exist: {self.asset_dir}")
        for root in sorted(path for path in self.asset_dir.iterdir() if path.is_dir()):
            asset = self._load_asset(root / "asset.json")
            versions = self._load_versions(root / "versions")
            snapshots = self._load_snapshots(root / "evaluations")
            if asset.active_version not in versions:
                raise PromptRepositoryError(f"active version missing: {asset.template_id}@{asset.active_version}")
            if asset.status in {PromptStatus.ACTIVE, PromptStatus.EXPERIMENTAL}:
                active = versions[asset.active_version]
                if active.snapshot_id is None or (asset.template_id, active.version) not in snapshots:
                    raise PromptRepositoryError(f"approved snapshot missing: {asset.template_id}@{active.version}")
                if snapshots[(asset.template_id, active.version)].final_decision != "approve":
                    raise PromptRepositoryError(f"active snapshot not approved: {asset.template_id}@{active.version}")
            self._assets[asset.template_id] = asset
            self._versions[asset.template_id] = versions
            self._snapshots.update(snapshots)

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
                variables=[self._variable_spec(item) for item in data.get("variables", [])],
            )
            versions[version.version] = version
        return versions

    def _load_snapshots(self, root: Path) -> dict[tuple[str, str], PromptEvaluationSnapshot]:
        snapshots: dict[tuple[str, str], PromptEvaluationSnapshot] = {}
        if not root.exists():
            return snapshots
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

    def _variable_spec(self, data: dict[str, Any]) -> PromptVariableSpec:
        return PromptVariableSpec(
            name=data["name"],
            source=data["source"],
            value_type=data.get("value_type", "text"),
            required=bool(data.get("required", False)),
            missing_behavior=data.get("missing_behavior", "omit"),
            description=data.get("description", ""),
        )

    def _read_json(self, path: Path) -> dict[str, Any]:
        if not path.exists():
            raise PromptRepositoryError(f"prompt repository file missing: {path}")
        return json.loads(path.read_text(encoding="utf-8"))
```

- [ ] **Step 4: Update prompt config**

Use this `configs/prompts.yaml` shape:

```yaml
prompts:
  default_template_id: aviation_fact_qa
  clarification_template_id: aviation_basic_safe
  asset_dir: assets/prompts
  allowed_runtime_statuses: [active, experimental]
  required_final_fact_variables: [rag_evidence, output_contract]
  injection_order: [system_boundary, task_type, scene_state, rag_evidence, memory_context, weak_points, prompt_asset, output_contract]
```

- [ ] **Step 5: Convert one asset first**

Create `assets/prompts/aviation_fact_qa/asset.json`:

```json
{
  "template_id": "aviation_fact_qa",
  "title": "Aviation factual answer",
  "task_type": "concept_explanation",
  "status": "active",
  "active_version": "v2",
  "activation_scope": ["concept_explanation", "knowledge_explanation", "comparison", "parameter_fact"],
  "concept_scope": ["aviation_concept", "aircraft_component"],
  "scene_scope": ["text", "scene"],
  "constraints": ["facts_from_evidence_only", "cite_reviewed_sources", "state_uncertainty_when_missing"],
  "risk_boundaries": ["no_parameter_without_source", "no_operation_steps"]
}
```

Create `assets/prompts/aviation_fact_qa/versions/v1.json` and `v2.json`. `v2.json` must include a real template:

```json
{
  "template_id": "aviation_fact_qa",
  "version": "v2",
  "parent_version": "v1",
  "change_reason": "Add explicit evidence boundary and teaching order.",
  "created_at": "2026-07-09T00:00:00Z",
  "review_status": "active",
  "snapshot_id": "aviation_fact_qa_v2",
  "variables": [
    {"name": "rag_evidence", "source": "evidence_package", "value_type": "evidence_summary", "required": true, "missing_behavior": "clarification", "description": "Reviewed local evidence used as the only fact source."},
    {"name": "output_contract", "source": "runtime", "value_type": "json_schema_summary", "required": true, "missing_behavior": "error", "description": "Answer envelope output requirements."},
    {"name": "scene_state", "source": "scene_state", "value_type": "scene_summary", "required": false, "missing_behavior": "omit", "description": "Current 3D or visual scene summary."}
  ],
  "content": "You are an aviation science tutor. Explain only facts supported by {{rag_evidence}}. If evidence is insufficient, say what is missing and ask a clarification question. Use {{scene_state}} only to ground references to the current object. Follow {{output_contract}} exactly."
}
```

Create `assets/prompts/aviation_fact_qa/evaluations/aviation_fact_qa_v2.json`:

```json
{
  "snapshot_id": "aviation_fact_qa_v2",
  "template_id": "aviation_fact_qa",
  "version": "v2",
  "test_cases": ["basic_concept", "parameter_without_source", "scene_component"],
  "dimension_scores": {"faithfulness": 1.0, "clarity": 0.92, "scene_alignment": 0.9},
  "violations": [],
  "improvements": ["Evidence boundary is explicit."],
  "final_decision": "approve"
}
```

- [ ] **Step 6: Convert remaining assets**

Create versioned JSON directories for:

```text
aviation_component_explain
aviation_concept_correction
aviation_quiz_reinforcement
aviation_feedback_rewrite
aviation_self_check
aviation_basic_safe
asr_correction_prompt
spoken_answer_style_prompt
barge_in_feedback_prompt
experimental_teaching_strategy
```

Mark `experimental_teaching_strategy` as `candidate` and do not include it in runtime selection.

- [ ] **Step 7: Delete old flat YAML prompt assets and old store**

Delete:

```text
assets/prompts/aviation_basic_safe.yaml
assets/prompts/aviation_explain_active.yaml
assets/prompts/aviation_knowledge_explain.yaml
assets/prompts/aviation_self_check.yaml
assets/prompts/aviation_voice_spoken.yaml
src/prompts/asset_store.py
```

- [ ] **Step 8: Run repository tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_repository.py -q
```

Expected: repository tests pass.

- [ ] **Step 9: Commit**

```powershell
git add src/prompts/repository.py src/prompts/asset_models.py configs/prompts.yaml assets/prompts tests/unit/prompts/test_prompt_repository.py
git rm src/prompts/asset_store.py assets/prompts/*.yaml
git commit -m "refactor: add versioned prompt asset repository"
```

---

### Task 3: Refactor Router, Assembler, and Prompt Runtime

**Files:**
- Modify: `src/prompts/router.py`
- Modify: `src/prompts/assembler.py`
- Create: `src/prompts/runtime.py`
- Modify: `src/prompts/__init__.py`
- Test: `tests/unit/prompts/test_prompt_router.py`
- Test: `tests/unit/prompts/test_prompt_assembler.py`
- Test: `tests/unit/prompts/test_prompt_runtime.py`

**Interfaces:**
- Consumes: `PromptAssetRepository`, `PromptSelection`, `PromptContentVersion`, `PromptVariableSpec`.
- Produces: `PromptRuntime.prepare_route()`, `PromptRuntime.assemble_bundle()`, `PromptMessageBundle`.

- [ ] **Step 1: Replace router tests**

```python
# tests/unit/prompts/test_prompt_router.py
import pytest

from core.contracts import EvidenceItem, EvidencePackage, SceneState
from input.query_object import QueryObject
from prompts.repository import PromptAssetRepository
from prompts.router import PromptRouter, PromptStatusError


def _evidence():
    return EvidencePackage(evidence_items=[EvidenceItem(evidence_id="ev1", source_id="src1", content="reviewed fact")])


def test_router_selects_active_asset_for_intent():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="Explain lift", normalized_query="Explain lift", intent_type="concept_explanation")

    selection = router.select_prompt(query, evidence_package=_evidence(), output_contract={"type": "answer_envelope"})

    assert selection.template_id == "aviation_fact_qa"
    assert selection.version == "v2"
    assert selection.route_reason == "intent_scope_match"


def test_missing_rag_evidence_routes_to_clarification_prompt():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="Explain lift", normalized_query="Explain lift", intent_type="concept_explanation")

    selection = router.select_prompt(query, scene_state=SceneState(), output_contract={"type": "answer_envelope"})

    assert selection.template_id == "aviation_basic_safe"
    assert selection.is_clarification is True
    assert "rag_evidence" in selection.missing_variables


def test_candidate_assets_are_not_runtime_routable():
    router = PromptRouter(PromptAssetRepository.from_config("configs/prompts.yaml"))
    query = QueryObject(raw_query="q", normalized_query="q", intent_type="experimental_teaching_strategy")

    with pytest.raises(PromptStatusError):
        router.select_prompt(query, evidence_package=_evidence(), output_contract={"type": "answer_envelope"})
```

- [ ] **Step 2: Implement narrowed router**

```python
# src/prompts/router.py
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from core.settings import parse_simple_yaml
from input.query_object import QueryObject
from prompts.asset_models import PromptSelection
from prompts.repository import PromptAssetRepository


class PromptStatusError(Exception):
    pass


@dataclass
class PromptRouterConfig:
    default_template_id: str = "aviation_fact_qa"
    clarification_template_id: str = "aviation_basic_safe"
    required_final_fact_variables: list[str] = field(default_factory=lambda: ["rag_evidence", "output_contract"])
    injection_order: list[str] = field(default_factory=lambda: ["system_boundary", "task_type", "scene_state", "rag_evidence", "memory_context", "weak_points", "prompt_asset", "output_contract"])

    @classmethod
    def from_file(cls, path: str | Path = "configs/prompts.yaml") -> "PromptRouterConfig":
        prompts = parse_simple_yaml(Path(path).read_text(encoding="utf-8")).get("prompts", {})
        return cls(
            default_template_id=prompts.get("default_template_id", "aviation_fact_qa"),
            clarification_template_id=prompts.get("clarification_template_id", "aviation_basic_safe"),
            required_final_fact_variables=prompts.get("required_final_fact_variables", ["rag_evidence", "output_contract"]),
            injection_order=prompts.get("injection_order", cls().injection_order),
        )


class PromptRouter:
    def __init__(self, repository: PromptAssetRepository | None = None, config: PromptRouterConfig | None = None) -> None:
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

        asset = self.repository.find_for_intent(query_object.intent_type)
        if asset is None:
            asset = self.repository.get_asset(self.config.default_template_id)
            if asset.status.value not in self.repository.allowed_runtime_statuses:
                raise PromptStatusError(f"default prompt is not runtime eligible: {asset.template_id}")
        version = self.repository.get_active_version(asset.template_id)
        return PromptSelection(
            template_id=asset.template_id,
            version=version.version,
            task_type=asset.task_type,
            status=asset.status,
            route_reason="intent_scope_match",
            selected_asset=asset,
            selected_version=version,
        )

    def _clarification(self, missing: list[str]) -> PromptSelection:
        asset = self.repository.get_asset(self.config.clarification_template_id)
        if asset.status.value not in self.repository.allowed_runtime_statuses:
            raise PromptStatusError(f"clarification prompt is not runtime eligible: {asset.template_id}")
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

    def _available_variables(self, scene_state: Any | None, memory_context: Any | None, evidence_package: Any | None, output_contract: Any | None) -> set[str]:
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
```

- [ ] **Step 3: Replace assembler tests**

```python
# tests/unit/prompts/test_prompt_assembler.py
from core.contracts import EvidenceItem, EvidencePackage, MemoryContext, SceneState
from input.query_object import QueryObject
from prompts.runtime import PromptRuntime


def test_prompt_runtime_injects_rag_before_prompt_asset_and_uses_model_messages():
    runtime = PromptRuntime.from_config("configs/prompts.yaml")
    query = QueryObject(raw_query="Explain lift", normalized_query="Explain lift", intent_type="concept_explanation")
    evidence = EvidencePackage(evidence_items=[EvidenceItem(evidence_id="ev_lift", source_id="src_lift", content="Lift depends on airflow evidence.")])

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
    assert "Lift depends on airflow evidence." in "\n".join(message.content for message in bundle.messages)
    assert "aviation science tutor" in "\n".join(message.content for message in bundle.messages)
```

- [ ] **Step 4: Implement assembler and runtime**

Implement `PromptAssembler` with these key methods:

```python
class PromptAssembler:
    def assemble_messages(self, selection: PromptSelection, variables: dict[str, str], injection_order: list[str]) -> PromptMessageBundle:
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
        system_content = "\n\n".join([sections["system_boundary"], sections["output_contract"]])
        user_content = "\n\n".join(f"[{name}]\n{sections[name]}" for name in injection_order if name not in {"system_boundary", "output_contract"})
        messages = [ModelMessage(role="system", content=system_content), ModelMessage(role="user", content=user_content)]
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
```

Implement `PromptRuntime` as the only public runtime API:

```python
class PromptRuntime:
    def __init__(self, repository: PromptAssetRepository, router: PromptRouter, assembler: PromptAssembler, config: PromptRouterConfig) -> None:
        self.repository = repository
        self.router = router
        self.assembler = assembler
        self.config = config

    @classmethod
    def from_config(cls, path: str = "configs/prompts.yaml") -> "PromptRuntime":
        repository = PromptAssetRepository.from_config(path)
        config = PromptRouterConfig.from_file(path)
        return cls(repository, PromptRouter(repository, config), PromptAssembler(), config)

    def prepare_route(self, query_object, scene_state=None):
        return self.router.select_prompt(query_object, scene_state=scene_state, output_contract={"type": "answer_envelope"})

    def assemble_bundle(self, query_object, scene_state, memory_context, evidence_package, output_contract, weak_points=None):
        selection = self.router.select_prompt(query_object, scene_state, memory_context, evidence_package, output_contract)
        variables = self._bind_variables(query_object, scene_state, memory_context, evidence_package, output_contract, weak_points or [], selection)
        return self.assembler.assemble_messages(selection, variables, self.config.injection_order)
```

- [ ] **Step 5: Update exports**

`src/prompts/__init__.py` must export `PromptRuntime`, `PromptAssetRepository`, `PromptRouter`, `PromptAssembler`, and the domain models.

- [ ] **Step 6: Run prompt runtime tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Expected: all prompt unit tests pass.

- [ ] **Step 7: Commit**

```powershell
git add src/prompts tests/unit/prompts
git commit -m "refactor: route and assemble prompt runtime bundles"
```

---

### Task 4: Integrate Prompt Runtime into Agent and Generation

**Files:**
- Modify: `src/core/contracts.py`
- Modify: `src/agent/runtime.py`
- Modify: `src/generation/generator.py`
- Modify: `src/services/model_client.py` only if type imports require adjustment.
- Test: `tests/integration/app_loop/test_cli_pipeline.py`
- Test: `tests/unit/generation/test_model_generation.py`

**Interfaces:**
- Consumes: `PromptRuntime`, `PromptMessageBundle`.
- Produces: runtime traces with prompt template id, version, snapshot id, route reason, missing variables, and injection summary.

- [ ] **Step 1: Write integration test for prompt trace**

```python
# tests/integration/app_loop/test_cli_pipeline.py
from app.api.schemas import TextQueryRequest
from services.app_pipeline import AppPipeline


def test_text_query_records_prompt_trace_fields():
    response = AppPipeline().run_text_query(TextQueryRequest(query="Explain lift", run_id="prompt_trace_test"))

    assert response.status == "ok"
    assert response.trace["prompt_template_id"]
    assert response.trace["prompt_version"]
    assert isinstance(response.trace["prompt_injection_summary"], list)
    assert response.answer["generation_trace"]["template_id"] == response.trace["prompt_template_id"]
```

- [ ] **Step 2: Extend RunTrace**

Add fields to `RunTrace`:

```python
prompt_version: str | None = None
prompt_snapshot_id: str | None = None
prompt_route_reason: str | None = None
prompt_missing_variables: list[str] = field(default_factory=list)
prompt_injection_summary: list[dict[str, Any]] = field(default_factory=list)
```

- [ ] **Step 3: Inject PromptRuntime in AgentRuntime**

Add constructor dependency:

```python
from prompts.runtime import PromptRuntime

class AgentRuntime:
    def __init__(..., prompt_runtime: PromptRuntime | None = None, ...):
        self.prompt_runtime = prompt_runtime or PromptRuntime.from_config()
```

After evidence retrieval and before generation:

```python
prompt_bundle = self.prompt_runtime.assemble_bundle(
    query_object=query_object,
    scene_state=scene_state,
    memory_context=memory_context,
    evidence_package=evidence_package,
    output_contract={"type": "answer_envelope"},
)
trace.prompt_template_id = prompt_bundle.template_id
trace.prompt_version = prompt_bundle.version
trace.prompt_snapshot_id = prompt_bundle.snapshot_id
trace.prompt_missing_variables = list(prompt_bundle.missing_variables)
trace.prompt_injection_summary = list(prompt_bundle.injection_summary)
```

Pass `prompt_bundle=prompt_bundle` into `generate_answer()`.

- [ ] **Step 4: Remove generator-owned model message construction**

Change `GroundedAnswerGenerator.generate_answer()` signature to include `prompt_bundle: PromptMessageBundle`.

Replace:

```python
self._build_model_messages(plan, evidence_sketch)
```

with:

```python
prompt_bundle.messages
```

Delete `_build_model_messages()`.

Set trace values:

```python
"template_id": prompt_bundle.template_id,
"template_version": prompt_bundle.version,
"prompt_snapshot_id": prompt_bundle.snapshot_id,
```

- [ ] **Step 5: Update generation tests**

Add this assertion to model generation tests:

```python
assert answer.generation_trace["template_id"] == "aviation_fact_qa"
assert answer.generation_trace["template_version"] == "v2"
```

Use `PromptRuntime.from_config().assemble_bundle(...)` to create the prompt bundle in the test.

- [ ] **Step 6: Run integration and generation tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/generation tests/integration/app_loop -q
```

Expected: tests pass and no generation test imports `_build_model_messages`.

- [ ] **Step 7: Commit**

```powershell
git add src/core/contracts.py src/agent/runtime.py src/generation/generator.py tests/unit/generation tests/integration/app_loop
git commit -m "refactor: use prompt runtime in generation chain"
```

---

### Task 5: Add Prompt Governance and Real Rollback

**Files:**
- Create: `src/prompts/governance.py`
- Test: `tests/unit/prompts/test_prompt_governance.py`
- Modify: `src/prompts/repository.py` if write helpers are needed.

**Interfaces:**
- Consumes: `PromptAssetRepository`, `PromptEvaluationSnapshot`.
- Produces: `PromptGovernanceService.promote_version()`, `deprecate_version()`, `rollback_prompt()`, `record_evaluation()`.

- [ ] **Step 1: Write governance tests**

```python
# tests/unit/prompts/test_prompt_governance.py
import pytest

from prompts.governance import PromptGovernanceService, PromptGovernanceError
from prompts.repository import PromptAssetRepository


def test_rollback_uses_real_existing_version_file():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")
    service = PromptGovernanceService(repo)

    result = service.rollback_prompt("aviation_fact_qa", "v1", "quality regression in v2")

    assert result.ok is True
    assert result.action == "rollback"
    assert result.version == "v1"


def test_rollback_rejects_missing_target_version():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")
    service = PromptGovernanceService(repo)

    with pytest.raises(PromptGovernanceError, match="prompt version not found"):
        service.rollback_prompt("aviation_fact_qa", "v404", "bad target")


def test_candidate_cannot_promote_without_approved_snapshot():
    repo = PromptAssetRepository.from_config("configs/prompts.yaml")
    service = PromptGovernanceService(repo)

    with pytest.raises(PromptGovernanceError, match="approved evaluation snapshot required"):
        service.promote_version("experimental_teaching_strategy", "v1", "missing_snapshot", "manual promotion attempt")
```

- [ ] **Step 2: Implement governance service**

```python
# src/prompts/governance.py
from __future__ import annotations

from prompts.asset_models import PromptGovernanceResult
from prompts.repository import PromptAssetRepository, PromptRepositoryError


class PromptGovernanceError(Exception):
    pass


class PromptGovernanceService:
    def __init__(self, repository: PromptAssetRepository) -> None:
        self.repository = repository

    def promote_version(self, template_id: str, version: str, snapshot_id: str, reason: str) -> PromptGovernanceResult:
        prompt_version = self.repository.get_version(template_id, version)
        snapshot = self.repository.get_evaluation_snapshot(template_id, version)
        if prompt_version.snapshot_id != snapshot_id or snapshot.snapshot_id != snapshot_id or snapshot.final_decision != "approve":
            raise PromptGovernanceError("approved evaluation snapshot required")
        return PromptGovernanceResult(template_id=template_id, version=version, action="promote", ok=True, reason=reason)

    def deprecate_version(self, template_id: str, version: str, reason: str) -> PromptGovernanceResult:
        self.repository.get_version(template_id, version)
        return PromptGovernanceResult(template_id=template_id, version=version, action="deprecate", ok=True, reason=reason)

    def rollback_prompt(self, template_id: str, target_version: str, reason: str) -> PromptGovernanceResult:
        try:
            prompt_version = self.repository.get_version(template_id, target_version)
            snapshot = self.repository.get_evaluation_snapshot(template_id, target_version)
        except PromptRepositoryError as exc:
            raise PromptGovernanceError(str(exc)) from exc
        if prompt_version.snapshot_id != snapshot.snapshot_id or snapshot.final_decision != "approve":
            raise PromptGovernanceError("approved evaluation snapshot required")
        return PromptGovernanceResult(template_id=template_id, version=target_version, action="rollback", ok=True, reason=reason)
```

The first implementation may return a governance result without mutating files. Add file mutation only through an explicit later step in the same task after tests cover it.

- [ ] **Step 3: Add active pointer mutation test**

Add:

```python
def test_rollback_changes_active_pointer_in_repository(tmp_path):
    # Copy one asset directory into tmp_path, load repository from that temp config, run rollback,
    # reload repository, and assert active_version is v1.
```

Use `shutil.copytree()` and write a temp `prompts.yaml` containing `asset_dir: <tmp_path>`.

- [ ] **Step 4: Implement active pointer mutation**

Add a repository method:

```python
def set_active_version(self, template_id: str, version: str) -> None:
    asset = self.get_asset(template_id)
    self.get_version(template_id, version)
    path = self.asset_dir / template_id / "asset.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["active_version"] = version
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    asset.active_version = version
```

Call this from `rollback_prompt()`.

- [ ] **Step 5: Run governance tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_governance.py -q
```

Expected: governance tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/prompts/governance.py src/prompts/repository.py tests/unit/prompts/test_prompt_governance.py
git commit -m "feat: add prompt governance and rollback"
```

---

### Task 6: Integrate Dedicated Voice Prompt Assets

**Files:**
- Modify: `src/voice/terminology.py`
- Modify: `src/input/voice_query_normalizer.py`
- Modify: `src/voice/tts.py`
- Modify: `src/voice/barge_in.py`
- Modify: `src/voice/voice_loop.py`
- Test: `tests/integration/voice_loop/test_mock_voice_loop.py`
- Test: `tests/e2e/scenarios/test_voice_flow.py`

**Interfaces:**
- Consumes: `PromptRuntime`.
- Produces: voice prompt usage in ASR correction, spoken answer conversion, and barge-in feedback parsing.

- [ ] **Step 1: Write voice prompt tests**

```python
# tests/integration/voice_loop/test_mock_voice_loop.py
from voice.voice_loop import MockVoiceLoop


def test_voice_loop_records_spoken_prompt_asset():
    result = MockVoiceLoop().run_turn("voice_prompt", "Explain wing", confidence=0.95)

    assert result.pipeline_response is not None
    assert result.tts_result is not None
    assert result.tts_result.metadata["prompt_template_id"] == "spoken_answer_style_prompt"


def test_asr_correction_uses_prompt_asset_metadata():
    result = MockVoiceLoop().run_turn("voice_asr", "C nine one nine wing", confidence=0.95)

    assert result.voice_query.metadata["asr_prompt_template_id"] == "asr_correction_prompt"
```

- [ ] **Step 2: Extend voice result models minimally**

Add `metadata: dict[str, str]` to `VoiceQueryObject` and `TTSResult`.

- [ ] **Step 3: Use prompt runtime in term correction**

`AviationTermCorrector` should accept `prompt_runtime: PromptRuntime | None`. In mock mode it still uses deterministic corrections, but records `asr_correction_prompt` as the prompt asset used.

- [ ] **Step 4: Use spoken answer prompt before TTS**

In `MockVoiceLoop`, assemble a prompt bundle for `spoken_answer_style_prompt` after the text answer passes the main pipeline. Use deterministic conversion in mock mode, but attach `prompt_template_id` and `prompt_version` to `TTSResult.metadata`.

- [ ] **Step 5: Use barge-in prompt metadata**

`BargeInController.handle_barge_in()` should classify feedback deterministically and attach `barge_in_feedback_prompt` to the produced `FeedbackEvent` metadata. If `FeedbackEvent` lacks metadata, add it as `metadata: dict[str, str]`.

- [ ] **Step 6: Run voice tests**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py -q
```

Expected: voice tests pass and voice prompt ids are visible in metadata.

- [ ] **Step 7: Commit**

```powershell
git add src/voice src/input/voice_query_normalizer.py tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py
git commit -m "feat: integrate voice prompt assets"
```

---

### Task 7: Trace Export, Eval Assertions, and Legacy Cleanup

**Files:**
- Modify: `src/observability/trace_exporter.py`
- Modify: `scripts/export_trace_report.py`
- Modify: `scripts/run_eval.py`
- Modify: `tests/e2e/scenarios/test_text_qa_flow.py`
- Modify: `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`
- Modify: `tests/unit/core/test_contracts.py`

**Interfaces:**
- Consumes: extended `RunTrace`.
- Produces: trace reports and eval outputs that include prompt audit summaries without prompt full text.

- [ ] **Step 1: Add trace export assertion**

```python
# tests/e2e/scenarios/test_eval_and_deployment_scripts.py
from pathlib import Path


def test_trace_report_includes_prompt_audit_without_prompt_body():
    report = Path("docs/trace_reports/p9_trace_acceptance.md")
    if report.exists():
        text = report.read_text(encoding="utf-8")
        assert "prompt_template_id" in text
        assert "You are an aviation science tutor" not in text
```

- [ ] **Step 2: Update trace exporter**

Add prompt fields to the markdown report:

```python
lines.extend([
    f"- prompt_template_id: {trace.get('prompt_template_id')}",
    f"- prompt_version: {trace.get('prompt_version')}",
    f"- prompt_snapshot_id: {trace.get('prompt_snapshot_id')}",
    f"- prompt_route_reason: {trace.get('prompt_route_reason')}",
    f"- prompt_missing_variables: {trace.get('prompt_missing_variables')}",
    f"- prompt_injection_summary: {trace.get('prompt_injection_summary')}",
])
```

Do not include `message.content` or rendered prompt content.

- [ ] **Step 3: Update smoke eval**

In `scripts/run_eval.py`, assert each text or voice case that reaches the main pipeline has `trace.prompt_template_id`. For evidence-missing cases, assert `prompt_template_id == "aviation_basic_safe"` and `is_final_fact_prompt` appears false in the prompt injection summary.

- [ ] **Step 4: Delete legacy imports and tests**

Run:

```powershell
rg -n "PromptAssetStore|default_prompt_assets|_build_model_messages|role=\"context\"|core.contracts.PromptAsset" src tests scripts
```

Remove every production-code match. Test matches are only allowed when asserting the strings are absent.

- [ ] **Step 5: Run cleanup verification**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts tests/integration/app_loop tests/e2e/scenarios -q
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
```

Expected: all commands exit 0.

- [ ] **Step 6: Commit**

```powershell
git add src scripts tests docs/trace_reports
git commit -m "test: enforce prompt trace and remove legacy paths"
```

---

### Task 8: Final Full Verification

**Files:**
- Modify: `docs/STATUS.md`
- Modify: `docs/release_acceptance.md`
- Modify: `docs/demo_audit_report.md`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: documented verification evidence.

- [ ] **Step 1: Run full test suite**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

Expected: all tests pass.

- [ ] **Step 2: Run compileall**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m compileall -q src scripts
```

Expected: exit code 0.

- [ ] **Step 3: Run smoke eval**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\run_eval.py --suite smoke
```

Expected: pass rate 1.0 and prompt trace fields present in each case.

- [ ] **Step 4: Run trace export**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id prompt_refactor_acceptance
```

Expected: report exists and includes prompt audit fields without prompt full text.

- [ ] **Step 5: Run deployment validation**

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" scripts\validate_deployment.py
```

Expected: `status=ok`.

- [ ] **Step 6: Update status docs**

Append a prompt refactor acceptance entry to `docs/STATUS.md` with:

- phase label: prompt runtime and governance refactor
- modified files
- deleted files
- test commands
- test results
- harness violations: no
- remaining items: real online provider validation remains out of scope
- next phase can start: yes

- [ ] **Step 7: Commit**

```powershell
git add docs/STATUS.md docs/release_acceptance.md docs/demo_audit_report.md docs/trace_reports
git commit -m "docs: record prompt refactor acceptance"
```

---

## Self Review

- Spec coverage: every requirement from `2026-07-09-prompt-engineering-refactor-design.md` maps to at least one task.
- No unused compatibility shim remains in the planned final state.
- Type names are consistent across tasks: `PromptAssetRepository`, `PromptRuntime`, `PromptMessageBundle`, `PromptGovernanceService`.
- The plan uses JSON assets to avoid a new YAML dependency while supporting full variable contracts.
- The main runtime path, voice path, trace path, and governance path are all covered by tests.
