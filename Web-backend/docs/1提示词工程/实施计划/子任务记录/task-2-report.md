# Task 2 Report: Review Fixes

## Summary

This update closes the remaining Task 2 documentation audit finding without restoring `PromptAssetStore`, flat YAML prompt assets, or compatibility shims.

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

- Added a clean `P1 Prompt Refactor Update` section to `docs/spec.md`.
- Replaced the stale `src/prompts/asset_store.py` references in `docs/spec.md` with `src/prompts/repository.py`.
- Replaced the stale `MessageBundle` return contract in `docs/spec.md` with `PromptMessageBundle`.
- Clarified in `docs/spec.md` that prompt assets live under `assets/prompts/<template_id>/asset.json`, `versions/*.json`, and `evaluations/*.json`.
- Clarified in `docs/spec.md` that `PromptAssetRepository` is the asset/version/snapshot loader, that runtime `active`/`experimental` versions require approved matching snapshot ids, that trace output does not store full prompt text, and that Prompt assets do not provide aviation facts.
- Added a clean `P1 Prompt Refactor Update` section near the top of `docs/harness.md` so the P1 prompt safety boundary is auditable even though older text near the top still contains mojibake.
- Restated in `docs/harness.md` that `PromptAssembler` returns `PromptMessageBundle`, bundle messages are `ModelMessage` with only `system` and `user` roles, and old flat YAML assets / `PromptAssetStore` / `default_prompt_assets` / `role="context"` are prohibited.
- Updated the Task 2 entry in `docs/STATUS.md` to note the documentation audit follow-up.

## Files changed

- `src/prompts/repository.py`
- `tests/unit/prompts/test_prompt_repository.py`
- `src/prompts/assembler.py`
- `tests/unit/prompts/test_prompt_assembler.py`
- `docs/STATUS.md`
- `docs/superpowers/plans/_sdd/task-2-report.md`

## Validation record

### Command 1

```powershell
rg -n "asset_store.py|PromptAssetStore|default_prompt_assets|MessageBundle|role=\"context\"|role: context" docs/spec.md docs/harness.md src tests scripts
```

Output:

```text
docs/spec.md:174:- `src/prompts/repository.py` 已替代删除的 `src/prompts/asset_store.py`。
docs/spec.md:177:- `PromptAssembler` 返回 canonical `PromptMessageBundle`，不存在独立的 `MessageBundle` 运行时契约。
docs/spec.md:178:- `PromptMessageBundle.messages` 只能包含 `services.model_client.ModelMessage`，角色只允许 `system` 和 `user`。
docs/spec.md:181:- 旧平铺 YAML Prompt 资产、`PromptAssetStore`、`default_prompt_assets`、以及 `role="context"` / `role: context` 消息角色都是禁止项。
docs/spec.md:315:assemble_messages(prompt_selection, evidence_package, memory_context, output_contract) -> PromptMessageBundle
docs/harness.md:23:- `src/prompts/repository.py` 替代已删除的 `src/prompts/asset_store.py`。
docs/harness.md:26:- `PromptAssembler` 必须输出 canonical `PromptMessageBundle`，不得再定义或返回独立 `MessageBundle`。
docs/harness.md:30:- 旧平铺 YAML 资产、`PromptAssetStore`、`default_prompt_assets` 和任何 `role="context"` / `role: context` 组装方式均为禁止项。
tests\unit\prompts\test_prompt_models.py:7:    PromptMessageBundle,
tests\unit\prompts\test_prompt_models.py:19:    assert asset_models.PromptMessageBundle.__module__ == "prompts.asset_models"
tests\unit\prompts\test_prompt_models.py:37:    bundle = PromptMessageBundle(
src\prompts\assembler.py:5:from prompts.asset_models import PromptMessageBundle, PromptSelection
src\prompts\assembler.py:22:    ) -> PromptMessageBundle:
src\prompts\assembler.py:48:        return PromptMessageBundle(
src\prompts\assembler.py:70:def assemble_messages(prompt_selection: PromptSelection, **kwargs: Any) -> PromptMessageBundle:
src\prompts\asset_models.py:76:class PromptMessageBundle(BaseContract):
```

### Command 2

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest tests/unit/prompts -q
```

Output:

```text
...................                                                      [100%]
```

## Result

- The doc audit strings now appear only in current-code names or in explicit prohibition/clarification text.
- `tests/unit/prompts -q` passed on the final verification run.
