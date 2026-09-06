# Task 1 Report: Replace Prompt Domain Models

## Current Stage

- Stage: Task 1
- Status: completed

## Completed Work

- Replaced the prompt domain model definitions in `src/prompts/asset_models.py`.
- Removed the duplicate `PromptAsset` contract from `src/core/contracts.py`.
- Added focused unit tests for the new prompt model shapes in `tests/unit/prompts/test_prompt_models.py`.
- Added a regression check that `prompt_asset` is absent from `core.contracts.CONTRACT_TYPES` and that the prompt domain models are defined under `prompts.asset_models`.
- Verified the new tests fail before implementation and pass after the model replacement.

## Modified Files

- `src/prompts/asset_models.py`
- `src/core/contracts.py`
- `tests/unit/prompts/test_prompt_models.py`

## Added Files

- `tests/unit/prompts/test_prompt_models.py`
- `docs/superpowers/plans/_sdd/task-1-report.md`

## Deleted Files

- None

## Test Command

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py -q
```

## Test Result

- First run: failed during collection because the new prompt model symbols did not exist yet.
- Final run: `5 passed`.

## Review Evidence

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts/test_prompt_models.py -q
```

```text
.....                                                                    [100%]
```

## Harness Review

- No harness.md violations introduced for this task.
- The change stayed inside the task-owned files.
- No online provider validation was added.
- No heavy prompt platform was introduced.

## Unfinished Items

- The broader prompt stack still uses the older prompt asset shapes and was not migrated in this task.
- Only the focused Task 1 test target was run.

## Next Stage

- P1 can start after the remaining prompt callers are migrated in their own task.
