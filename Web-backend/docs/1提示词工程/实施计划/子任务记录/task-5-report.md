# Task 5 Report: Prompt Governance and Real Rollback

## Scope

Implemented prompt governance through repository-owned persistence APIs. The worker did not modify `src/agent/actions.py`, app loop tests, or real prompt asset active pointers.

## Modified Files

- `src/prompts/repository.py`
- `src/prompts/__init__.py`

## Added Files

- `src/prompts/governance.py`
- `tests/unit/prompts/test_prompt_governance.py`
- `docs/superpowers/plans/_sdd/task-5-report.md`

## Deleted Files

- None

## Implementation Notes

- Added `PromptGovernanceService` with:
  - `promote_version(template_id, version, snapshot_id, reason)`
  - `deprecate_version(template_id, version, reason)`
  - `rollback_prompt(template_id, target_version, reason)`
  - `record_evaluation(snapshot)`
- Promotion and rollback both require:
  - target prompt version exists;
  - evaluation snapshot exists for that template/version;
  - version `snapshot_id` matches the evaluation snapshot;
  - snapshot `final_decision == "approve"`.
- Promotion and rollback update `asset.json` through `PromptAssetRepository.set_active_version(...)`, then update the in-memory `TeachingPromptAsset.active_version`.
- Evaluation snapshots are written only through `PromptAssetRepository.record_evaluation_snapshot(...)`.
- `record_evaluation_snapshot(...)` refuses to overwrite the evaluation result for the current active runtime version with a non-approved decision, preserving the repository invariant that active runtime prompts always reload with an approved snapshot.
- Version deprecation is written only through `PromptAssetRepository.set_version_review_status(...)`, and it refuses to deprecate the currently active version.
- Repository JSON writes go through a temporary file followed by `Path.replace(...)`, so active pointers, version files, and evaluation snapshots are not left half-written on normal local filesystem failures.
- Tests use `tmp_path` copies of prompt asset directories and verify reload behavior after real file mutation.

## Test Commands and Results

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_governance.py tests/unit/prompts/test_prompt_repository.py -q
```

Result: passed, `16 passed`.

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Result: passed, `34 passed`.

## Harness Check

- Git operations: not executed.
- Real prompt assets mutated: no.
- Disallowed files touched: no.
- New dependencies added: no.
- JSON write logic outside repository: no.

## Remaining Risks

- Governance audit history is limited to persisted asset/version/evaluation JSON state; there is no separate append-only governance event log yet.
