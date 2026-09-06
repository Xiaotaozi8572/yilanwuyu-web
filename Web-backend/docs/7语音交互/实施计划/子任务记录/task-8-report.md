# Task 8 实施记录：指标、Trace、隐私和故障降级

## 实施结果

- 将 `VoiceMetricRecord` 扩展为唯一 canonical 指标契约，覆盖 Provider、状态、单调延迟、ASR 置信度、转写/口播/chunk 长度、实体抽样、VAD false-cut、澄清、barge-in、取消、answer/evidence/event ID、完整状态转换和隐私不变量。
- `VoiceMetricsRecorder` 以 session/turn 隔离的单调时钟记录真实测点；同步 writer 通过 worker thread 执行，异步 writer 直接 await，写入任务跨 session 并发、有界、可超时、可 drain/release，失败只产生受控审计。
- orchestrator 在首帧、voice start、ASR partial/final、正式 pipeline start/end、TTS 首 chunk、barge-in cancel、状态转换和 turn finish 采集指标；Provider 名来自实际实例，而非配置推测。
- transport/ASR/TTS/setup/stream/session timeout 均安全降级。TTS/transport 失败保留可信 answer/evidence；超时后通过有界 closed-session/closed-turn tombstone 拒绝迟到 frame 和 final ASR，正式 RAG 调用次数保持不变。
- 播放 task 成为完成屏障；自然完成、取消和失败均在 task 返回前收敛状态、引用和指标，避免 callback 时序竞态。TTS 首 chunk 在 Provider yield 后、transport send 前记录。
- `voice_trace_summary` 采用新对象白名单输出。严格生成格式 ID 可追踪；历史或调用方自由 ID 映射为稳定 SHA-256 摘要。Provider、状态、动作、错误、VAD、澄清和转换原因采用封闭 allowlist，未知自由文本被丢弃或摘要化。
- `VoiceSettings.__post_init__` 强制 raw audio 与 raw transcript 隐私不变量，配置文件加载和 `dataclasses.replace` 使用相同安全边界。
- 全局 metric audit 改为有界 deque；session 已删除时 pending error 立即清理，长期失败不会造成 `_pending_metric_errors` 无界增长。

## TDD 与三轮独立评审证据

1. 第一轮完整审查红测：`42 failed, 20 passed`，退出码 `1`。失败覆盖 TTS setup 异常泄漏、playback callback race、session timeout 缺失、writer 阻塞/无界、trace 自由字段泄漏、raw transcript 配置缺失、真实测点不准确和转换明细不足。
2. 第一轮修复后，T8+eval 为 `23 passed`，相关回归 `335 passed`，全量 `491 passed`。随后复核要求继续加强 timeout 墓碑、全字段 trace 与构造级隐私不变量。
3. 第二轮新增红测：`8 failed, 69 passed`，退出码 `1`。精确证明 `dataclasses.replace` 可绕过四项隐私约束、timeout 删除 session 后迟到 final ASR 可令 RAG calls 从 1 增至 2、墓碑与审计容量缺失、合法前缀加自由文本可穿透 trace。
4. 第二轮同时补齐三项缺失回归：VAD false-cut 计数为 1 且 RAG 为 0；metrics queue-full 受控 drop 且 drain 后 pending 为 0；普通 feedback 重新取证后 TTS setup 异常安全降级。
5. 第一轮独立评审共发现 `8 Important`；第二轮共发现 `4 Important` 并要求补齐上述 3 项回归；第三轮最终评审 PASS，Critical `0`、Important `0`。
6. 最终目标套件为 `82 passed`，0 failed，0 skipped，退出码 `0`；全量为 `503 passed`，0 failed，0 skipped，退出码 `0`。

## 最终验证

```powershell
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -o addopts='' -q tests/integration/voice_loop/test_voice_trace_privacy.py tests/unit/voice/test_voice_settings.py tests/e2e/scenarios/test_eval_and_deployment_scripts.py
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -o addopts='' -q tests/unit/voice tests/unit/feedback tests/integration/voice_loop tests/integration/app_loop tests/integration/feedback_loop tests/e2e/scenarios/test_feedback_flow.py tests/e2e/scenarios/test_eval_and_deployment_scripts.py
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -o addopts='' -q
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m compileall -q src scripts
git diff --check
```

- 目标测试：`82 passed`，0 failed，0 skipped，退出码 `0`。
- 相关 voice/feedback/app/eval 回归：`352 passed`，0 failed，0 skipped，退出码 `0`。
- 全量 pytest：`503 passed`，0 failed，0 skipped，退出码 `0`。
- compileall：退出码 `0`。
- `git diff --check`：退出码 `0`。
- 30 轮 playback 完成屏障、barge-in/finish 并发、writer queue-full/timeout/failure、session timeout 迟到事件和 trace 全字段动态注入均通过；无测试悬挂 task 或端口占用。
- `smoke_eval.json` 与 `e2e_trace.md` 的评审运行生成变更已精确恢复，不纳入 T8 提交。

### 测试生成物恢复审计

- 评审冻结阶段曾执行一次受控双文件恢复：
  `git restore --worktree -- 'docs/评测与验收/评测报告/smoke_eval.json' 'docs/评测与验收/追踪报告/e2e_trace.md'`。
- 收尾首次 apply_patch 恢复后，为修正 `smoke_eval.json` 相对 HEAD 仅剩的 EOF newline 字节，曾执行一次单文件命令：
  `git restore --worktree -- 'docs/评测与验收/评测报告/smoke_eval.json'`。
- 主代理指出后未再使用 checkout/reset/restore。最终新鲜测试后的动态字段全部通过 `apply_patch` 恢复；由于本环境 `apply_patch` 会为 JSON 自动补末尾换行，最后仅使用 Python 3.13 对已由 apply_patch 恢复的文件删除一个 EOF `LF` 字节。随后 `git diff --exit-code` 同时核验两个生成物与 HEAD 完全一致。

## 文件变更

### 新增

- `tests/integration/voice_loop/test_voice_trace_privacy.py`
- `docs/7语音交互/实施计划/子任务记录/task-8-brief.md`
- `docs/7语音交互/实施计划/子任务记录/task-8-report.md`

### 修改

- `configs/voice.yaml`
- `src/observability/trace_exporter.py`
- `src/voice/contracts.py`
- `src/voice/errors.py`
- `src/voice/metrics.py`
- `src/voice/orchestrator.py`
- `src/voice/session_state.py`
- `src/voice/settings.py`
- `tests/e2e/scenarios/test_eval_and_deployment_scripts.py`
- `tests/unit/voice/test_voice_settings.py`
- `docs/项目总控/STATUS.md`

### 删除

- 无。

## 隐私、状态、取消、配置和 Trace 验证

- 原始音频、base64 音频、完整私人 transcript、完整 Prompt、prompt messages、原始私人记忆和密钥均不进入指标、异常、审计或 trace。
- 每个 session 的状态和指标写入通过 session lock 与 turn-local lock 串行化；超时墓碑有容量上限和显式 clear 生命周期。
- TTS cancel/transport failure/session timeout 均停止旧发送路径；失败后不增加旧 chunk，不重复正式 RAG。
- Voice 隐私配置只有 `configs/voice.yaml` 一处来源，构造级校验拒绝不安全替换。
- Trace ID 保持稳定关联但不暴露自由文本；原因、Provider 和错误字段不接受 prefix-only 自由值。

## Harness 与 dead-code 检查

- 修改均在 T0 明确授权的 `src/voice/**`、`src/observability/**`、`configs/voice.yaml`、语音/评测测试和总控文档范围内。
- 未新增依赖，未读取真实 API Key，未连接真实 ASR/TTS/模型、生产数据库或生产服务；语音事实仍只来自 `AppPipeline` 最终可信答案。
- `VoiceMetricRecord` 无重复定义；metrics、trace 和 session timeout 均由唯一 orchestrator 主链消费。
- 旧 `MockVoiceLoop`、兼容 `VoiceMetricsRecorder.record()` 和 callback finalizer 辅助方法仍按计划保留，必须在 T10 调用方迁移后由 T11 做零引用删除，不在 T8 提前破坏旧契约。
- 未违反 `harness.md`。

## 已知限制与后续范围

- Python/asyncio 无法强制终止已经进入 `asyncio.to_thread` 的任意同步 writer。当前隔离保证用户路径不等待它，写入有超时、有界队列和有界审计；未来生产 writer 应提供自身超时或协作式取消。
- Provider trace allowlist 是封闭安全集合；未来接入新 Provider 时必须同步更新 allowlist 和隐私测试，否则新 Provider 名将被安全丢弃。
- T9 WebSocket、T10 调用方迁移和 T11 旧链/兼容路径清理尚未在本任务实施。真实 ASR、真实 TTS、真实模型和生产部署仍为后续范围。

## 结论

T8 第三轮最终评审 PASS。指标真实测点、故障降级、超时防重入、隐私配置、Trace 白名单和后台写入隔离均满足任务要求，可以进入 T9。
