# Task 9 实施记录：WebSocket 协议与本地 Mock Client

## 实施结果

- 新增 `serve_voice()` loopback WebSocket server，使用本地随机端口并复用已注册的 `websocket` transport；配置或 registry 不满足时启动阶段失败。
- 新增 `MockVoiceClient`，输入使用真正的二进制 `AudioFrame` 消息，输出将 Mock TTS opaque binary 归一为客户端 `tts.chunk` 事件。
- 协议支持 session 启动、场景更新、连续音频帧、端点结束和 session 取消；服务端返回 canonical voice state、ASR partial/final、澄清、可信 display answer、barge-in、稳定错误和 TTS 二进制。
- 增加原子 session binding registry。重复连接收到 `4401`，且不会释放或取消原连接；不同 session 可并发运行。
- 增加 32 KiB JSON、128 KiB wire、64 KiB logical audio、嵌套深度、ID、场景数量/长度和连续帧序号校验。
- WebSocket 事件状态取自 orchestrator 的 session lock 内 canonical `VoiceState`，不再由适配层维护竞争状态。
- TTS planner/stream 失败保留可信 display answer，返回 `VOICE_TTS_ERROR` 并进入 `CLARIFY`，不重复正式 RAG。
- rewrite 后的新 `PlaybackHandle` 被当前连接跟踪，输出新 chunk 并等待完成；socket facade 用同一 send lock 串行化 JSON 与 binary，取消后 audio gate 阻止旧 chunk。
- 指标清理改为 session/turn 定向 drain；单个连接关闭不会全局等待其他连接的慢 writer，server 关闭才进行全局 drain。

## TDD 与评审证据

1. 初始收集红测：`1 error`，退出码 `1`，原因是 `voice.mock_client` 尚不存在。
2. 第一轮实现红测：`3 failed, 3 passed`，退出码 `1`；定位为可信答案 fixture 没有 planner 支持的 spoken claim，修正 fixture 后验证真实公共链。
3. 异常断连红测：`1 failed, 12 passed`，退出码 `1`；定位为 `ConnectionClosedOK` 的 `1001` 被错误归为正常完成，修复关闭语义。
4. 第一轮独立评审发现 Critical `0`、Important `7`：合法单 claim TTS 降级、重复 session 连接、wire size、跨连接 metrics drain、transport 配置门禁、非 canonical state、rewrite 新播放未跟踪。逐项增加 E2E 红测后完成根因修复。
5. 评审 Minor 修复：JSON/binary 共用发送锁；仅将 JSON 序列化错误转换为安全协议错误；API 文档逐项列出服务端事件字段，并明确 transcript 只发往当前连接且不记录。
6. 第二轮独立评审 PASS：Critical `0`、Important `0`。

## 最终验证

```powershell
python -m pytest -o addopts='' -q tests/e2e/scenarios/test_voice_websocket_flow.py
python -m pytest -o addopts='' -q tests/e2e/scenarios/test_voice_websocket_flow.py tests/integration/voice_loop/test_voice_trace_privacy.py tests/integration/voice_loop/test_barge_in_feedback.py tests/unit/voice tests/integration/app_loop tests/integration/feedback_loop
python -m pytest -o addopts='' -q
python -m compileall -q src scripts
git diff --check
```

- 目标 E2E：`19 passed`，0 failed，0 skipped，退出码 `0`。
- 相关 voice/app/feedback 回归：`351 passed`，0 failed，0 skipped，退出码 `0`。
- 全量 pytest：`522 passed`，0 failed，0 skipped，退出码 `0`。
- compileall：退出码 `0`；`git diff --check`：退出码 `0`。
- 正常关闭、端口释放、重复 session 保护、不同 session 并发、乱序/超限帧、澄清、TTS 降级、barge-in 和 rewrite 后播放均通过；无悬挂 asyncio task。

## 文件变更

### 新增

- `src/voice/websocket_server.py`
- `src/voice/mock_client.py`
- `tests/e2e/scenarios/test_voice_websocket_flow.py`
- `docs/7语音交互/实施计划/子任务记录/task-9-brief.md`
- `docs/7语音交互/实施计划/子任务记录/task-9-report.md`

### 修改

- `src/voice/orchestrator.py`
- `src/voice/metrics.py`
- `docs/接口与部署/api_contracts.md`
- `docs/接口与部署/deployment_checklist.md`
- `docs/项目总控/STATUS.md`

### 删除

- 无。旧 `MockVoiceLoop` 及调用方按计划留到 T10 迁移、T11 零引用清理。

## 状态、取消、隐私与 Harness

- 状态查询由 session lock 串行化；duplicate binding、invalid ID/sequence/schema 和 idle timeout 使用稳定关闭码，不会创建第二套业务状态机。
- barge-in 的旧播放先停止，socket audio gate 防止取消后的旧 chunk；rewrite 新播放绑定当前连接并完整收敛。
- raw audio 仅在内存中作为 transport 输入，不落盘、不写日志；ASR transcript 只作为当前 WebSocket 连接事件返回，不进入日志、异常、metrics 或 trace。
- WebSocket 层没有 `run_text_query`、RetrievalController、Generator、MemoryRepository、EvidenceStore、FeedbackParser 或 VoiceQueryNormalizer 的直接调用。
- 未读取真实密钥，未连接真实 ASR/TTS/模型、生产数据库或生产服务；未引入 FastAPI/Flask/Django/Sanic 等框架。
- Mock TTS 输出是测试用 opaque binary，不是生产 PCM；真实语音 Provider、鉴权、TLS、限流、媒体协商和集群部署属于后续范围。
- 未违反 `harness.md`，无待确认阻塞项。

## 结论

T9 第二轮评审 PASS，WebSocket 适配层、Mock client、并发隔离、协议边界、可信答案降级和隐私不变量均满足任务要求，可以进入 T10 调用方迁移与部署验证。
