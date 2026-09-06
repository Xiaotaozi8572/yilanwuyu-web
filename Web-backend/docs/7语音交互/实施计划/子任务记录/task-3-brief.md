# T3 子任务简报：Transport、VAD 与 EndpointDetector

工作目录：`D:\APP\Python 3.13\挑战杯-voice-realtime`

基线提交：`ab18949`

## 目标

把连续 `AudioFrame` 的接收/发送、帧边界校验、每会话 VAD 状态和 endpoint 判定做成独立可替换基础设施。transport/VAD/endpoint 不得调用 RAG、生成、反馈或记忆；断连不得伪造 final transcript。

## 允许修改

- 修改 `src/voice/transport.py`
- 修改 `src/voice/vad.py`
- 新建 `src/voice/endpoint.py`
- 必要时修改 `src/voice/providers.py`，仅将 registry transport factory 指向统一实现并收紧协议
- 必要时修改 `src/voice/contracts.py`，仅补齐 T3 明确需要的安全状态/关闭事件字段，不得加入业务编排
- 新建 `tests/unit/voice/test_endpoint_detector.py`
- 修改/增强 `tests/unit/voice/test_voice_components.py`
- 可新增聚焦 transport 测试文件
- 更新 STATUS、task-3-report 和外部 ledger

## Transport

- 使用 T1 `AudioFrame`/`TTSChunk`，删除 `transport.py` 本地重复 `AudioFrame`。
- 公共 `AudioFrameValidator`（或等价单一校验点）校验：
  - session/turn/audio_stream 与 transport 绑定一致；
  - 每个 `(session_id, audio_stream_id)` 的 `sequence_no` 严格递增，重复/倒退拒绝；
  - sample rate/channel 与 `VoiceSettings` 一致；
  - payload 最大 64 KiB；
  - 关闭后不再接收/发送。
- `InMemoryAudioTransport` 通过异步队列接收 frame、记录发送的 TTS chunk，支持明确 `push_frame`/关闭事件；测试不得用 transcript 字符串代替 frame。
- `WebSocketAudioTransport` 是轻量 I/O 适配器：仅接收已解码的 `AudioFrame` 或通过显式安全 codec 转换，调用 socket `send` 输出 chunk/event，处理关闭；不得包含 VAD/ASR/RAG/反馈业务。T9 再实现 JSON 协议/server/client。
- registry 中 `in_memory` 必须创建 `InMemoryAudioTransport`，`websocket` 必须创建 `WebSocketAudioTransport`；二者都实际符合 `AudioTransport` Protocol。构造时由 settings 注入 sample/channel/frame duration，不硬编码竞争默认。
- transport close 只能发出 `transport_closed`/结束 async iterator，不能创建 `ASRResult(is_final=True)`。

## VAD

- `VADSessionState` 保存 active、silence duration、last sequence、voice frame count 等单会话字段；禁止 Provider 内部 `_active` 或全局 session map。
- `MockVADService.classify(frame, state)` 只修改传入 state；双 session 并发互不影响。
- 使用 canonical `VADDecision`/`VADDecisionType`，支持 `VOICE_START`、`VOICE_ACTIVE`、`VOICE_PAUSE`、`SILENCE`；turn 结束由调用方显式 reset，避免一次短暂停顿直接终结。
- 阈值/帧长来自 settings 或显式注入；10/20/30ms 测试均可累计正确 silence。
- sequence 倒退由 transport 拒绝，VAD 仍防御性拒绝重复/倒退 frame。

## EndpointDetector

- `EndpointDetector(silence_ms, frame_duration_ms)` 参数严格验证。
- `push(vad, asr_is_final, semantic_complete, elapsed_silence_ms=None, transcript_nonempty=True)` 返回 canonical `EndpointDecision`。
- 唯一正式终结条件：`asr_is_final and semantic_complete and transcript_nonempty and elapsed_silence_ms >= silence_ms`。
- 短 pause、ASR 非 final、语义不完整、空 transcript、断连均不得 `speech_final`。
- 结果包含稳定 reason/累计静音，方便 T5 审计；提供 reset 但不持有跨 session 全局状态（每 session 各自实例或显式 state）。

## TDD 与验证

1. 先写红测，覆盖：短停顿、长停顿四门条件、空转写、断连、10/20/30ms、双 session VAD、sequence 重复/倒退、采样率/声道/payload、关闭后发送、WebSocket 适配器无业务调用。
2. 红测：
   `python -m pytest tests/unit/voice/test_endpoint_detector.py tests/unit/voice/test_voice_components.py <transport-test> -q`
3. 实现后重跑目标测试。
4. 回归：
   `python -m pytest tests/unit/voice tests/integration/voice_loop tests/e2e/scenarios/test_voice_flow.py -q`
5. 配置/registry 回归、contract/core/encoding、compileall。
6. 静态检查：
   - `transport.py`/`vad.py` 不再定义 `AudioFrame`/重复 VAD contract；
   - `MockVADService` 无 `_active`；
   - transport/endpoint 无 `AppPipeline`、retrieval、generator、feedback、memory 导入；
   - registry 映射创建真实统一 transport。
7. 更新 STATUS/report/ledger，`git diff --check`，提交 `refactor: add streaming voice transport and endpoint`。

## 完成标准

- 连续 frame 基础设施可离线异步运行。
- 会话状态完全隔离。
- pause 与 endpoint 分离，只有四条件同时满足才终结。
- transport 关闭/错误不会伪造查询或 final transcript。
- 无重复契约、全局 VAD 状态或 WebSocket 业务逻辑。
