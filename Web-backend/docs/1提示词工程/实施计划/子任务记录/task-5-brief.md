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

