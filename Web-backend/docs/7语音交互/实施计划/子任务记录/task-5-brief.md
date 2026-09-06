# Task 5 实施简报：持久会话状态机与主编排器

## 目标

新增 `VoiceSessionStore` 与唯一业务入口 `VoiceSessionOrchestrator`，使每个 session 的状态、turn、VAD/ASR/预检索/TTS 句柄和转换审计独立持久，并确保只有 final、非空、高置信、场景明确的标准化转写能够构造 `TextQueryRequest` 并进入 `AppPipeline`。

## 允许修改范围

- `src/voice/session_state.py`
- 新增 `src/voice/orchestrator.py`
- `src/app/api/schemas.py`
- `src/agent/runtime.py`
- 必要的 `src/voice/__init__.py`
- 新增/修改 `tests/unit/voice/test_voice_orchestrator.py`
- 新增/修改 `tests/integration/voice_loop/test_streaming_voice_loop.py`
- 与文本兼容直接相关的现有 app/runtime 测试
- `docs/项目总控/STATUS.md`
- 本文件及 Task 5 证据记录

不得修改检索、生成、MemoryRepository、P6/P2 业务实现，不得建立测试专用第二条语音链。

## 必须先建立的失败测试

1. partial、空文本、低置信、未完成、场景指代不明、非法 session/turn 均不调用 `AppPipeline`。
2. 一个 turn 的首个合格 final 只产生一个正式 `TextQueryRequest`；重复 final 不重复检索。
3. 正式请求完整透传 `user_id/session_id/turn_id/source="voice"/scene_state`。
4. partial 仅产生 `PreRetrievalResult` 候选 ID；final 到达时旧 token 被取消/清除，且预检索对象不能成为 EvidencePackage/最终事实来源。
5. 两个 session 可并行且状态、partial、turn、审计互不污染；同一 session 的状态写入受同一 `asyncio.Lock` 串行化。
6. 主状态序列覆盖 `IDLE -> LISTENING -> TRANSCRIBING -> UNDERSTANDING -> RETRIEVING -> GENERATING -> SPEAKING`；澄清路径不构造正式请求。
7. 文本调用不提供新字段时仍保持 `source="text"`，现有 API/CLI 和 `AppPipeline.run_text_query()` 行为不变；AgentRuntime 审计使用请求 source/turn_id。

## 实现约束

- 所有 Mock/InMemory/WebSocket 后续都必须复用本 orchestrator；本任务不实现 WebSocket 适配。
- 所有 session 可变状态只能在所属 session lock 下修改。Provider 回调不得直接改全局状态。
- 编排器只调用 `AppPipeline` 公共入口，禁止直接调用 retrieval/generator/memory repository/evidence store。
- 仅把 `AppPipeline` 的可信最终响应保存为后续 spoken answer 来源；不得用 partial、Prompt 或模型常识生成事实回答。
- 同步 `AppPipeline` 保持兼容；若支持 awaitable 测试替身，不得通过跨线程调用破坏 SQLite/thread-affinity。
- 错误信息不得泄漏原始音频、完整私人 transcript 或 Prompt。
- 不删除旧 `MockVoiceLoop`；调用方迁移和删除留到 T10/T11。

## 目标测试

```powershell
python -m pytest tests/unit/voice/test_voice_orchestrator.py tests/integration/voice_loop/test_streaming_voice_loop.py tests/integration/app_loop -q
```

完成后还需运行相关 voice unit/integration、文本 pipeline 回归、`compileall`、`git diff --check`，更新 `STATUS.md` 并独立提交。Task 5 后必须接受独立代码审查，所有 Critical/Important 问题修复后才可进入 T6。
