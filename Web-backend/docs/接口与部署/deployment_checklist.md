# P8+ Deployment Validation Checklist

- Config files exist for app, providers, RAG, prompts, voice, and evals.
- Default provider profile is `mock`.
- Real provider credentials are not required for smoke validation.
- Trace and eval reports are written under `docs/评测与验收/追踪报告` and `docs/评测与验收/评测报告`.
- Real Provider validation remains pending until API keys, services, databases, and deployment targets are provided.

## LangGraph 文本主链部署核验

- [ ] 仅使用 `D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe`（Python 3.11.9）运行核验；安装版本为 `langgraph==1.2.9` 和 `langgraph-checkpoint-sqlite==3.1.0`。
- [ ] `configs/app.yaml` 的 `langgraph.checkpoint_path` 是本地 SQLite 路径，且与 `configs/rag.yaml` 的 `knowledge.sqlite3` 和 `configs/memory.yaml` 的 `memory.sqlite3` 不同。
- [ ] 运行 `scripts/validate_deployment.py` 返回 `langgraph_runtime="ok"`、`langgraph_checkpointer="sqlite"` 与 `langgraph_checkpoint_path_is_separate=true`；冲突路径必须以非零退出码和 `langgraph_checkpoint_path_conflict` 报告。
- [ ] 部署校验仅读取配置并报告包版本/路径分类；不得创建 checkpoint 数据库、写入或迁移知识库/记忆库，也不得输出查询、Prompt、记忆、证据或密钥。
- [ ] 图线程在终态完成后删除 checkpoint；文本外部响应与语音公共边界保持既有契约。
- [ ] 真实 Provider、生产数据库连接与生产部署仍不在本工作包范围内。

## P7 WebSocket Mock 验证清单

- [ ] Python 解释器为 3.13，且隔离环境中 `websockets==15.0.1`。
- [ ] `configs/voice.yaml`、`configs/voice_terminology.yaml` 和 `configs/voice_pronunciation.yaml` 存在且可用 UTF-8 解析。
- [ ] `VoiceWebSocketServer.health()` 返回 `status=ok`、`transport=websocket`、`registry_ready=true`，两个 lexicon 状态均为 `true`。
- [ ] Provider registry 已注册配置指定的 transport/VAD/ASR/TTS；未知 Provider 在启动或配置加载阶段失败。
- [ ] 仅绑定 `127.0.0.1` 和测试随机端口；关闭后端口可立即重新 bind。
- [ ] 正常、低置信澄清、TTS 失败文字降级、播放中 barge-in 四条 E2E 通过，且使用同一 `VoiceSessionOrchestrator`。
- [ ] session/turn/stream ID、帧连续序号、header/binary 边界、64 KiB 上限、4400/4401/4408 关闭码均有测试。
- [ ] 70 KiB 和 128 KiB PCM 均由适配器以 `voice.error` + `4400` 拒绝；控制 JSON 有 32 KiB/深度 8 边界，极端大于 128 KiB wire 消息允许传输层 `1009`。
- [ ] 同 session 并发绑定的第二连接以 `4401` 拒绝，owner 仍可继续；不同 session 可并发完成且无交叉状态。
- [ ] `VoiceSettings.transport` 非 `websocket` 或 registry 缺失该 factory 时，server 启动 fail-fast，不得假报 health ready。
- [ ] TTS 输出不阻塞 WebSocket recv；barge-in 必须在 `tts.chunk` 后受理，受理后旧流无新 chunk。
- [ ] 正常关闭码为 `1000`；idle/session timeout 调用 orchestrator timeout 公共入口并以 `4408` 关闭。
- [ ] server/connection/playback/watcher/metrics task 无悬挂，断连会停止播放、收敛 watcher 并 drain metrics。
- [ ] 连接清理只等待本 session/turn metrics；一个被阻塞 writer 不会延迟另一连接释放，server 关闭时再全局收口。
- [ ] `voice.error` 仅包含稳定 code/field；日志、trace 和错误不含 PCM payload、base64、完整私人 transcript、Prompt、记忆原文或 API Key。
- [ ] 能力报告仍标注 `websocket_mock/offline=true`、真实 ASR/TTS 和生产级媒体服务器未接入。

## P7 最终验收结果（2026-07-13）

- [x] Python `3.13.13`、`websockets==15.0.1`、compileall 和 diff check 均通过。
- [x] 单元 `254 passed`，相关集成 `79 passed`，语音 E2E `21 passed`，全量 `552 passed`，均为 0 failed/0 skipped。
- [x] deployment `status=ok`、`failed_checks=[]`；smoke `3/3`、`pass_rate=1.0`、close `1000`、port released=`true`、pending task=`0`。
- [x] trace export 成功；固定扫描不含 payload、raw audio、秘密音频、完整 Prompt 或 prompt messages。
- [x] `MockVoiceLoop`、旧 `VoiceTurnEvent`、legacy voice 配置键、callback finalizer 和伪 barge-in Prompt 路径零引用。
- [ ] 真实 ASR/TTS/模型、生产鉴权/TLS/限流、媒体服务和生产部署是后续范围。
