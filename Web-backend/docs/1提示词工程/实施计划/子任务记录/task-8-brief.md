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
