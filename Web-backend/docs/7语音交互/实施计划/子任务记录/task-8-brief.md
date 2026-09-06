# Task 8 实施简报：指标、Trace、隐私和故障降级

## 目标

在唯一 `VoiceSessionOrchestrator` 主链上建立可审计、隐私安全的语音可观测性与故障降级能力。指标必须来自真实执行点，Trace 必须采用字段白名单，Provider、状态和原因必须使用封闭值域；任何观测写入失败都不得破坏用户主流程或重复正式 RAG。

## 实施范围

- 扩展 canonical `VoiceMetricRecord`，实现 `VoiceMetricsRecorder.start_turn/mark/finish`。
- 在 orchestrator 的首帧、VAD、ASR、正式 pipeline、TTS、barge-in、状态转换、取消与超时处采集真实指标。
- 为 transport、ASR、TTS、session timeout 和 metrics writer 建立安全降级。
- 在 `TraceReportExporter` 中输出脱敏 `voice_trace_summary`，禁止 raw audio、完整 transcript、完整 Prompt、私人记忆和密钥进入报告。
- 将 raw transcript 隐私三元组与 raw audio 禁止项固化为 `VoiceSettings` 构造级不变量。
- 建立有界 session/turn 关闭墓碑及有界 metrics audit，阻止超时后的迟到事件重启 RAG。
- 新增专项集成测试，并按隐私策略调整 trace E2E 断言。

## 必须保持的不变量

1. `VoiceMetricRecord` 只有 `src/voice/contracts.py` 一处权威定义。
2. 所有计时使用 `perf_counter_ns()`，服务端耗时统一输出非负整数毫秒。
3. partial ASR、空转写、低置信度和不明确场景不得进入正式 RAG。
4. TTS 或 transport 失败保留可信 display answer，不重跑已经完成的 RAG。
5. session timeout 后，同 session/turn 的迟到 frame/final ASR 必须稳定返回 `VOICE_SESSION_TIMEOUT`。
6. `raw_audio_persist_enabled=false`、`raw_transcript_logging_enabled=false`、retention 为 `0`、redaction 为 `full`，且 `dataclasses.replace` 不得绕过。
7. Trace ID 只原样保留严格生成格式或 SHA-256 snapshot；其他 ID 使用稳定不可逆摘要。Provider、状态、动作、错误码与原因使用封闭 allowlist。
8. metrics writer 的阻塞、异常、超时和队列满不得阻塞跨 session 主流程；所有后台 task 必须可 drain。

## 验收命令

```powershell
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -o addopts='' -q tests/integration/voice_loop/test_voice_trace_privacy.py tests/unit/voice/test_voice_settings.py tests/e2e/scenarios/test_eval_and_deployment_scripts.py
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -o addopts='' -q tests/unit/voice tests/unit/feedback tests/integration/voice_loop tests/integration/app_loop tests/integration/feedback_loop tests/e2e/scenarios/test_feedback_flow.py tests/e2e/scenarios/test_eval_and_deployment_scripts.py
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m compileall -q src scripts
git diff --check
```

完成标准：目标测试 `82 passed`，相关回归全部通过，compileall 与 diff check 退出码均为 `0`，第三轮独立评审 Critical `0`、Important `0`，测试生成的非确定性报告不进入 T8 提交。

## 延后事项

- `asyncio.to_thread` 中已经开始执行的任意同步 writer 无法被 asyncio 强制杀死；当前通过非阻塞调度、超时、有界队列、受控审计和 drain 隔离影响。生产 writer 应自身支持超时或协作取消。
- Provider trace allowlist 当前只包含已经注册和验证的 `mock`、`in_memory`、`websocket`；未来新增 Provider 时必须同步扩展封闭值域并增加隐私测试。
- T7 兼容测试仍引用旧 callback finalizer 辅助路径；待 T10 调用方迁移后在 T11 与旧 Mock 主链一起零引用删除。
