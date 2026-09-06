# Task 9 实施简报：WebSocket 协议与本地 Mock Client

## 目标

在唯一 `VoiceSessionOrchestrator` 主链外增加轻量异步 WebSocket 适配层，使真实二进制音频帧能够经协议校验、session 绑定后进入既有 Transport/VAD/ASR/RAG/TTS 链路。适配层不得实现 VAD、术语纠错、RAG、反馈或记忆业务规则。

## 实施范围

- 新增 loopback-only WebSocket server 与本地 `MockVoiceClient`。
- 定义 `session.start`、`scene.update`、`audio.frame`、`audio.end`、`session.cancel` 客户端事件。
- 定义状态、ASR、澄清、display answer、barge-in、错误与 TTS 二进制服务端事件。
- 强制 session ID、turn ID、音频帧序号、帧大小、JSON 大小、嵌套深度和场景字段边界。
- 建立同一 session 的原子连接占有关系，拒绝重复连接且不影响原连接。
- 连接级清理只 drain 当前 turn 的指标任务；server 关闭时再执行全局 drain。
- 将 WebSocket transport 的启动条件绑定到 `VoiceSettings` 和 Provider registry，未知或错误 transport 启动即失败。
- 补齐正常流程、澄清、TTS 降级、barge-in、重复绑定、帧乱序、超限消息、并发连接、正常关闭、端口释放与无悬挂 task 的 E2E。

## 架构不变量

1. WebSocket server 只做协议解析、schema 校验、session 绑定、orchestrator 调用、事件输出和关闭码处理。
2. 所有客户端均进入同一个 `VoiceSessionOrchestrator`；不得在适配层直接调用 `AppPipeline`、检索器、生成器、P6 或 P2。
3. partial transcript 仅作为当前连接的实时事件返回，不进入正式 RAG；正式事实答案只来自 final transcript 对应的可信 `AnswerEnvelope`。
4. TTS 失败保留可信 display answer 并返回稳定错误；Mock TTS 二进制是 opaque Mock audio，不宣称为真实 PCM。
5. 播放期间的新语音遵守 `cancel -> parse`；改写后新播放必须被当前连接跟踪并继续输出。
6. 原始音频、完整私人 transcript、Prompt、memory payload 和密钥不得进入日志、异常、指标或文档报告。
7. 仅使用已批准的 `websockets==15.0.1`，不引入媒体服务器或 Web 框架。

## 协议边界

- 逻辑音频帧上限：64 KiB；采用 JSON header 后紧随一条 binary message。
- WebSocket wire message 上限：128 KiB；超过该边界由协议栈使用 `1009` 关闭。
- JSON control message 上限：32 KiB；schema、ID、scene 列表和嵌套深度均显式受限。
- 关闭码：`1000` 正常关闭，`4400` 协议/输入错误，`4401` session 绑定冲突，`4408` 空闲超时。
- 仅允许 loopback host 和本地随机端口；生产鉴权、TLS、限流和横向扩展不在本任务范围。

## 验收命令

```powershell
python -m pytest -o addopts='' -q tests/e2e/scenarios/test_voice_websocket_flow.py
python -m pytest -o addopts='' -q tests/e2e/scenarios/test_voice_websocket_flow.py tests/integration/voice_loop/test_voice_trace_privacy.py tests/integration/voice_loop/test_barge_in_feedback.py tests/unit/voice tests/integration/app_loop tests/integration/feedback_loop
python -m pytest -o addopts='' -q
python -m compileall -q src scripts
git diff --check
```

完成标准：目标测试 `19 passed`，相关回归 `351 passed`，全量 `522 passed`，退出码均为 `0`；compileall 和 diff check 退出码为 `0`；最终独立评审 Critical `0`、Important `0`；旧主链清理仍留待 T10/T11。
