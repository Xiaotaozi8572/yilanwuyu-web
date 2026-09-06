# T3 实施报告：Transport、VAD 与 EndpointDetector

## 任务结论

T3 已把连续 `AudioFrame` 传输、统一边界校验、每会话 VAD 状态和 endpoint 四门条件实现为独立基础设施。InMemory 与 WebSocket adapter 共用同一 contract 和 validator，Provider Registry 创建真实对应实现；模块没有 RAG、生成、反馈或记忆逻辑，连接关闭不会伪造 ASR final。

## 失败优先证据

- 首次聚焦 pytest：3 个 collection error，退出码 `1`。
- 根因分别为缺少 `voice.endpoint`、`VADSessionState`、`InMemoryAudioTransport` 和 `WebSocketAudioTransport`，准确验证了计划中的缺失能力。
- 实现后聚焦测试：`54 passed`，0 failed，0 skipped，退出码 `0`。
- 一次 encoding 回归失败定位为 worktree 内临时 `.venv` 被项目文本扫描收集；删除本代理创建的临时环境和 editable egg-info、改用批准的外部 Python 3.13 环境后，未修改测试即通过。
- 强制评审随后识别 4 个 Important 生命周期缺口；评审红测精确复现为 `8 failed, 30 passed`、退出码 `1`，未通过调整 timeout、放宽断言或跳过测试处理。
- 最终复审又识别 1 个 accepted-frame 生命周期缺口：关闭后才开始消费会丢弃已入队 frame。恢复 Provider 队列断言并新增并发 red tests 后精确复现为 `3 failed, 32 passed`、退出码 `1`。

## 实现摘要

- `AudioFrameValidator` 原子校验 transport session/turn/stream 绑定、严格递增序列、采样率、声道和 1–64 KiB payload；失败帧不会更新序列状态。
- `InMemoryAudioTransport` 使用异步队列持续接收真实 frame，以显式输入关闭结束 iterator，并记录发送的 canonical `TTSChunk`。关闭时按 active receiver 数逐一唤醒；队列无 accepted frame 时，关闭后的新 iterator 立即空返回。
- 对关闭后才创建的 iterator，transport 使用非阻塞 FIFO drain：先交付所有关闭前已接受 frame，再立即 EOF；多个 post-close receiver 共享消费但不会重复 frame 或悬挂。关闭仍严格拒绝任何新 frame。
- `WebSocketAudioTransport` 只将已解码 frame 放入同一输入队列、将 TTS bytes 交给 socket `send()` 并处理关闭；T9 才负责 wire JSON 和 server/client。
- `VADSessionState` 保存 active、silence duration、last sequence 和 voice frame count。Provider 不持有 `_active` 或全局 session map。
- `MockVADService` 区分 `VOICE_START`、`VOICE_ACTIVE`、`VOICE_PAUSE`、`VOICE_END` 和 `SILENCE`。短 pause 保持 active；达到 settings/显式注入的 end silence 后返回 `VOICE_END` 并将 state 置为 inactive。
- `EndpointDetector.push()` 只有在 ASR final、语义完整、非空 transcript、静音阈值四项同时满足时返回 `speech_final`；断连返回稳定 `transport_closed` 原因和非 final 结果。任何 active 帧都先把累计静音原子清零，不能被客户端 elapsed 值覆盖。
- Provider Protocol/Registry 同步收紧，`websocket` 与 `in_memory` 不再映射同一个旧 Mock transport。

## 验证证据

- endpoint/VAD/transport/registry/settings：`96 passed`，退出码 `0`。
- 全部 `tests/unit/voice`：`150 passed`，退出码 `0`。
- `tests/integration/voice_loop` 与现有语音 E2E：`6 passed`，退出码 `0`。
- `tests/unit/core`（含 UTF-8/contract/config）：`13 passed`，退出码 `0`。
- `python -m compileall -q src scripts`：退出码 `0`。
- `git diff --check`：退出码 `0`。
- 本地重复 AudioFrame/VAD contract、Provider `_active`、transport/endpoint 业务导入、`MockAudioTransport`：全部零匹配。
- registry 对 WebSocket/InMemory 的统一实现映射：各精确命中一次。
- 双并发 receiver、第二个 post-close iterator 和 `asyncio.all_tasks()` 检查通过，无关闭悬挂任务。
- 关闭前 accepted frame 的 post-close FIFO drain、双 post-close receiver 去重和 Provider Registry 真实队列语义均通过。

## 自检结论

- contract 唯一；transport/VAD/endpoint 职责单一。
- session VAD 状态由调用方持有，Provider 无跨 session 可变状态。
- pause 和 endpoint 分离，断连不会进入检索。
- 未引入依赖、真实服务、密钥、生产资源或原始音频落盘。
- T4 以后能力未提前实现。
