# T0 实施报告：治理门禁、worktree 与基线

## 任务结论

P7 T0–T11 的用户授权、受控跨模块范围、依赖白名单和安全边界已写入项目总控文档。T0 不改变业务行为，因此没有新增行为红测；本任务以新鲜基线命令作为验收证据。

## 隔离环境

- Branch：`codex/voice-realtime-refactor`
- Worktree：`D:\APP\Python 3.13\挑战杯-voice-realtime`
- Python：`D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe`，3.13.13
- websockets：15.0.1
- pytest：9.0.3

最初在 worktree 内创建的 `.venv` 会被仓库编码扫描测试递归遍历，导致第三方 pip vendor 文件触发 1 个误报。通过将隔离环境迁到 `D:\APP\Python 3.13\.venvs` 后，未修改测试即可使相同全量命令通过。

## 基线结果

- 语音专项：13 passed，0 failed，0 skipped，退出码 0。
- 初次全量 pytest：192 passed，1 failed，0 skipped，退出码 1；唯一失败是编码扫描测试未排除 worktree 内 `.venv`，误扫第三方 pip vendor 文件。
- 外部隔离环境全量 pytest 复跑：193 passed，0 failed，0 skipped，退出码 0。
- compileall：退出码 0。
- deployment validate：`status=ok`，退出码 0。
- smoke eval：3/3 passed，`pass_rate=1.0`，退出码 0。
- 旧语音符号扫描：29 行命中，退出码 0；作为后续迁移和 T11 清理基线。

## 安全与提交边界

测试生成的 `smoke_eval.json` 与 `e2e_trace.md` 变动已恢复到 HEAD。未修改业务代码、测试或接口，未访问真实密钥、真实 Provider、生产数据库或生产服务，也未执行 push、merge 或 reset。

## 提交前后检查

- `git diff --check`：退出码 0。
- T0 治理提交及评审修复提交完成后，`git status --short` 输出为空，专项 worktree 干净。
