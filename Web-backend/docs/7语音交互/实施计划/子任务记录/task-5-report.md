# Task 5 实施记录：持久会话状态机与 VoiceSessionOrchestrator

## 实施结果

- 新增唯一 `VoiceSessionOrchestrator`，所有正式语音查询只通过 `AppPipeline.run_text_query()` 进入主链；voice 目录未导入 retrieval、generator、memory repository 或 evidence store。
- 新增 `VoiceSessionStore`/`VoiceSessionContext`。每个 session 独立持有状态机、turn、`asyncio.Lock`、VAD state、endpoint detector、ASR partial、预检索 token、playback handle、可信 pipeline response 与完整 transition audit。
- partial 只形成 `PreRetrievalResult` 候选 ID；final 到达时旧 token 被取消并清除。预检索对象从不转换为 EvidencePackage，也不作为最终回答来源。
- 只有 final、非空、高置信、场景明确且 normalizer 标记 eligible 的结果才构造一次 `TextQueryRequest`；重复 final 返回首个已完成 outcome，不重复检索。
- 正式请求透传 `user_id/session_id/turn_id/source="voice"/scene_state`。文本请求新增字段均为可选，默认仍为 `source="text"`。
- `AgentRuntime` 的 query audit 使用请求 source，并记录 turn ID；既有文本默认路径保持不变。
- pipeline 抛错、错误状态或缺少 answer 时进入安全澄清，不产生可信语音回答，不向调用方转发不可信 pipeline response 或私有异常正文。

## TDD 证据

首次运行目标命令在测试收集阶段失败，退出码 `1`：

- `tests/unit/voice/test_voice_orchestrator.py`：`ModuleNotFoundError: No module named 'voice.orchestrator'`
- `tests/integration/voice_loop/test_streaming_voice_loop.py`：同一缺失公共入口错误

该红测准确证明 T5 前不存在持久 session store 和权威 orchestrator。自检后又以 permissive normalizer 复现 orchestrator 未独立执行置信度门控：`1 failed`、退出码 `1`，实际错误为低置信 final 被错误标记 `answered`；补齐编排器防御性阈值后转绿。

独立评审追加 11 项失败行为证据：目标命令 `11 failed, 25 passed`、退出码 `1`，分别覆盖 completed outcome 跨身份/场景/转写误复用、finish 后 turn ID 重用、normalizer raw/confidence/source 绕过、Answer/P5/evidence provenance 畸形被信任、连续音频未进入 registry ASR/endpoint，以及旧第五位置 `run_id` 被破坏。修复后又以空身份绑定污染测试复现 `1 failed`、退出码 `1`，证明被拒绝重传仍修改 session；改为无副作用 completed binding 校验后转绿。最后以 `short_answer=123` 复现畸形 AnswerEnvelope 导致未捕获 `AttributeError`：`1 failed`、退出码 `1`；补齐所有 AnswerEnvelope 字段类型和嵌套敏感键校验后转绿。复审再以 camelCase `promptMessages` 和非字符串 `action_decision=[]` 得到 `2 failed`、退出码 `1`；统一 key token 规范化并在 set membership 前验证字符串后转绿。最终目标测试 `42 passed`，0 failed，0 skipped，退出码 `0`。

## 状态与并发验证

- 正常状态序列严格为 `IDLE -> LISTENING -> TRANSCRIBING -> UNDERSTANDING -> RETRIEVING -> GENERATING -> SPEAKING`。
- 每条 transition audit 包含 session ID、turn ID、固定原因和毫秒时间戳，不包含 transcript 或音频 payload。
- 两个 session 的 awaitable pipeline 调用真实并行（最大并发 2）；同一 session 的重复 final 受同一 lock 串行化（最大并发 1）且只产生一次正式请求。
- 非法 session/frame/turn 绑定在状态修改前拒绝；`finish_turn` 非活动 turn 不改变上下文。
- `finish_turn` 只清理当前 turn 的临时状态并复位 VAD/endpoint；session transition audit 和已完成结果证据继续保留。

## 测试结果

```powershell
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest tests/unit/voice/test_voice_orchestrator.py tests/integration/voice_loop/test_streaming_voice_loop.py tests/integration/app_loop -q
```

- `42 passed`，0 failed，0 skipped，退出码 `0`。

```powershell
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest tests/unit/voice -q
```

- 最终回归为 `212 passed`，0 failed，0 skipped，退出码 `0`。

```powershell
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest tests/integration/voice_loop tests/integration/app_loop tests/integration/feedback_loop -q
```

- `27 passed`，0 failed，0 skipped，退出码 `0`。

```powershell
& "D:\APP\Python 3.13\.venvs\challenge-cup-voice-realtime\Scripts\python.exe" -m pytest -q
```

- 最终全量回归为 `402 passed`，0 failed，0 skipped，退出码 `0`。

## 独立评审修复

- completed outcome 现在绑定 session 当前 user、scene 和完整 final ASR 输入的 SHA-256 指纹；只有活动 turn 的完全一致重传幂等返回。身份/场景校验无副作用，`finish_turn()` 写入 tombstone 后永久拒绝同 session 重用该 turn ID。
- orchestrator 在新 turn 内通过 `ProviderRegistry.create_asr()` 创建 session-scoped ASR provider。连续 `AudioFrame` 在 session lock 内经 VAD、EndpointDetector、buffered ASR、partial/final normalizer 和 formal gate 进入同一链；测试证明 32 帧全部被 Mock ASR 消费、final endpoint 生效、raw frame buffer 清空且只产生一个 pipeline 请求。
- formal gate 独立验证原始 transcript 非空、query/raw 和 confidence 与 canonical ASR 完全一致、plan source 为 `final_transcript`、pre-retrieval-only 为 false，并在 frame 链要求 endpoint `speech_final`，故障 normalizer 不能绕过。
- `TextQueryResponse` 以尾部可选字段公开最小 provenance：`final_answer_id/evidence_package_id/check_report_id/self_check_completed`；未暴露完整 check report。`AgentRuntime` 从真实 final answer、EvidencePackage 和 P5 CheckReport 填充这些 ID。
- orchestrator 拒绝未知或敏感 AnswerEnvelope 字段、缺失/不安全 ID、P5 未完成、非终态 decision、缺少可展示文本或 answer ID 不匹配，并只保存 typed `AnswerEnvelope` 与 evidence package ID 供 T6 使用。
- `TextQueryRequest.run_id` 保留旧第五位置，新 `turn_id/source` 追加在后；新增五位置参数回归确认文本调用兼容。
- 复审修复将 camelCase、snake_case、kebab-case、空格及混合标点 key 统一规约为只含字母数字的 casefold token，`promptMessages/apiKey/rawAudio/fullPrompt` 等变体均进入同一 denylist；非字符串/不可哈希 action decision 安全降级为 clarification，不再抛出类型异常。

## 边界与后续

- T5 不实现 spoken answer 压缩、TTS 流、barge-in、P6/P2 分流、WebSocket server 或 trace exporter；依次留给 T6-T9。
- 旧 `MockVoiceLoop` 未删除，调用方迁移与零引用清理由 T10/T11 完成。
- 真实 ASR、TTS、模型和生产服务未接入。
