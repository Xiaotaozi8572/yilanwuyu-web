# T0 子任务简报：治理门禁、worktree 与基线

工作目录：`D:\APP\Python 3.13\挑战杯-voice-realtime`

## 目标

正式写入用户对 P7 语音交互与实时会话重构的授权，记录隔离环境和新鲜基线，并只提交当前任务范围内的总控文档。

## 允许修改

- `docs/项目总控/task.md`
- `docs/项目总控/spec.md`
- `docs/项目总控/harness.md`
- `docs/项目总控/STATUS.md`
- 本任务记录文件

不得修改业务代码、测试或生成报告。运行测试/脚本产生的非确定性报告变化必须在提交前恢复，不能纳入 T0 commit。

## 必须写入的授权

- P7 按 T0–T11 执行，允许修改 `src/voice/**`、语音测试、配置和文档。
- 允许受控修改用户提示中列出的 core、pipeline、API schema、agent runtime、feedback、memory 公共接线、observability、normalizer、scripts、pyproject 与部署/总控文档。
- 唯一允许新增的运行时依赖为 `websockets==15.0.1`。
- 禁止 voice 直接访问 retrieval index、generator、memory repository、EvidenceStore；只能通过现有公共接口。
- 禁止真实密钥、生产数据库、真实 ASR/TTS/模型/生产服务；允许 Mock、本地随机端口、内存 transport、离线 fixture。
- 允许专项 branch/worktree 和逐任务 commit；禁止 push、合并、强制 reset 和覆盖来源工作区用户修改。
- 保持文本 API/CLI/AppPipeline/P2/P3/P5/P6 公共契约向后兼容。

## 环境证据

- Branch：`codex/voice-realtime-refactor`
- Worktree：`D:\APP\Python 3.13\挑战杯-voice-realtime`
- Python：`D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe` 3.13.13（虚拟环境位于仓库外，避免项目文本扫描误扫第三方包）
- websockets：15.0.1
- pytest：9.0.3（仅测试环境）

## 执行要求

1. 使用上述仓库外隔离 Python 3.13 环境运行：
   - `python -m pytest tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py -q`
   - `python -m pytest -q`
   - `python -m compileall -q src scripts`
   - `python scripts/validate_deployment.py`
   - `python scripts/run_eval.py --suite smoke`
2. 运行旧符号扫描：
   - `rg -n "MockVoiceLoop|VoiceTurnEvent|MockAudioTransport|MockVADService|MockASRProvider|MockTTSProvider|BargeInController" src tests scripts`
3. 将每条命令的退出码、通过/失败/跳过数量和旧符号分类写入 `STATUS.md`。
4. 运行 `git diff --check`；只提交本任务文件，commit 信息：`docs: authorize P7 realtime voice refactor`。
5. 生成 `docs/7语音交互/实施计划/子任务记录/task-0-report.md`，记录红测说明（T0 为治理/基线任务，无行为红测时必须明确说明）、命令和结果。

## 完成标准

- 总控文档不再称 P7 未开始/未授权。
- 修改范围、依赖白名单、安全边界与用户授权一致。
- 基线命令有新鲜证据，所有意外生成报告变化未进入提交。
- `git status --short` 只允许保留后续任务明确需要的未提交记录；T0 commit 存在。
