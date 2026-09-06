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

