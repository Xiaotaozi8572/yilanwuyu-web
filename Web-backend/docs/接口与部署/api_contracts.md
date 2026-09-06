# P2+ 应用入口与 API 契约

## 范围

P2+ 采用 CLI-first 策略，不把 FastAPI/uvicorn 作为当前阶段的必要依赖。CLI 与后续可选 HTTP 入口共用 `src/app/api/schemas.py` 中的请求、响应和错误结构。

## TextQueryRequest

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `query` | string | 是 | 用户问题，不能为空字符串。 |
| `scene_state` | object/null | 否 | 可选场景状态，字段语义与 `core.contracts.SceneState` 对齐。 |
| `user_id` | string/null | 否 | 用户级记忆作用域标识。提供时，主链路可检索和治理该用户的长期记忆；缺失时，不得把其他用户记忆带入当前请求。 |
| `session_id` | string/null | 否 | 会话级记忆作用域标识。提供时，允许命中同一会话记忆并优先于同调词的全局记忆；缺失时，只允许使用无会话绑定的记忆。 |
| `run_id` | string/null | 否 | 可选调用方 run id；为空时由系统生成。 |

## TextQueryResponse

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `run_id` | string | 本次运行 id。 |
| `status` | string | `ok` 或 `error`。 |
| `answer` | object/null | `AnswerEnvelope` 字典；错误时为空。 |
| `action_decision` | string/null | 自检动作，如 `PASS`、`ASK_CLARIFICATION`、`STOP`。 |
| `trace` | object | `RunTrace` 字典，可通过 CLI `--no-trace` 关闭。 |
| `error` | object/null | 结构化错误响应。 |

## RunTrace 与记忆脱敏约束

- 运行时 trace 会记录真实的 `memory_context_ids`，用于标识本次主链路实际构建出的 `MemoryContext`。
- trace 中与提示词相关的 `prompt_injection_summary` 只输出脱敏摘要字段，例如 `section`、`chars`、`present`，不输出完整 prompt 正文。
- memory hits 只允许以 audit/summary 形式出现，例如 `memory_id`、`use_class`、`reason`、`score` 等脱敏元数据；不得输出 raw private memory value。
- 导出的 markdown trace report 遵循同一脱敏规则：可以显示 `memory_context_ids` 与 `memory_context` 段是否存在，但不得展开 `learner_profile`、`dialogue_context`、`recent_feedback` 或任何 raw private memory value。

## ErrorResponse

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `error_code` | string | 稳定错误码，例如 `invalid_request`。 |
| `message` | string | 用户可读错误消息，不包含堆栈。 |
| `run_id` | string | 关联运行 id。 |

## CLI 命令

```powershell
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m app.cli --query "解释一下升力"
```

CLI 只做输入输出适配，实际问答链路由 `services.app_pipeline.AppPipeline` 串联现有 input、retrieval、generation、self_check 与 memory controller 模块。

## P7 WebSocket 语音 Mock 协议

T9 提供一个本地、离线的 WebSocket 原型。服务端入口为
`voice.websocket_server.serve_voice(host, port, orchestrator)`，且所有合法消息都进入同一个
`VoiceSessionOrchestrator`。该适配层不实现 VAD、ASR 纠错、RAG、feedback 或 memory 规则。
`scripts/run_voice.py` 默认以 `127.0.0.1:8765` 启动（`--host`/`--port` 可配置，V1）；
`serve_voice()` 默认只允许 loopback host，非回环绑定需显式传 `allow_non_loopback=True`。

### 连接与绑定

- 每个连接只绑定一个 `session_id` / `turn_id` / `audio_stream_id`。同一 server 内的 `session_id` 必须由单一连接独占；重复绑定以 `4401` 拒绝，且不影响已有 owner。ID 必须为 1–128 字符的安全标识符，只允许字母、数字、`.`、`_`、`:` 和 `-`。
- 首条控制帧必须是 `session.start`。之后任何 ID 不匹配都以 `4401` 关闭，不会进入业务链。
- 客户端正常完成后使用关闭码 `1000`。协议/schema 错误使用 `4400`，绑定错误使用 `4401`，idle/session timeout 使用 `4408`。

### 客户端 JSON 控制帧

schema 为严格模式：未知字段、缺失字段或错误类型都会被拒绝。

| `type` | 必填字段 | 可选字段 | 语义 |
| --- | --- | --- | --- |
| `session.start` | `session_id`, `turn_id`, `audio_stream_id` | `user_id` | 绑定连接与 orchestrator session，不伪造音频或 ASR。 |
| `scene.update` | 三个 ID、`scene_state` | 无 | 将严格 `SceneState` 传给 orchestrator，用于指代绑定。 |
| `audio.frame` | 三个 ID、`sequence`、`sample_rate`、`channels`、`timestamp_ms`、`energy` | 无 | 声明紧随其后的一条 PCM binary 帧元数据。 |
| `audio.end` | 三个 ID | 无 | 关闭客户端 PCM 输入；不伪造 VAD endpoint 或 ASR final，也不自行触发 RAG。 |
| `session.cancel` | 三个 ID | `feedback` | 调用 orchestrator barge-in 公共路径；`feedback` 默认为“停止”，最长 256 字符。 |

`audio.frame` 控制帧后必须恰好跟随一条非空 PCM binary 帧，上限为 64 KiB。第一帧
`sequence=0`，之后必须连续加一；重复、倒退、跳号、无 header binary、双 header 和双 binary
均以 `4400` 拒绝。Mock client 发送真实 `AudioFrame.payload` PCM 字节，不在协议中注入 transcript 字符串。
控制 JSON 上限为 32 KiB、嵌套深度上限为 8；wire 层允许适配器接收到 128 KiB，以便 70–128 KiB 的超限 PCM 能统一返回 `voice.error` + `4400`。超过 128 KiB 的极端 wire 消息由 `websockets` 在传输层以 `1009` 拒绝。

### 服务端事件

JSON 事件为 `voice.state`、`asr.partial`、`asr.final`、`clarification.required`、
`answer.display`、`barge_in.accepted` 和 `voice.error`。TTS 音频使用 binary 帧；
`MockVoiceClient` 将收到的 binary 帧规范化为 `{ "type": "tts.chunk", "sequence": n, "size": n }`，
不在事件或日志中保留 payload。

| 服务端事件 | 字段 | 说明 |
| --- | --- | --- |
| `voice.state` | `session_id`, `turn_id`, `state`, `status` | `state` 只能是 orchestrator 当前 canonical `VoiceState`；`audio.input_ended` 等适配层信号仅放在 `status`。`session.started` 额外携带 `protocol_version: "voice-ws-v1"`（V4），其余事件不含该字段。 |
| `asr.partial` / `asr.final` | `session_id`, `turn_id`, `transcript`, `confidence`, `provider` | 仅回发当前绑定连接的转写结果。`partial` 不是正式 RAG 事实来源。 |
| `clarification.required` | `session_id`, `turn_id`, `reason` | 低置信、指代不明或其他安全门控拒绝时发送。 |
| `answer.display` | `session_id`, `turn_id`, `answer_id`, `evidence_package_id`, `answer` | 只来自可信最终 `TextQueryResponse`，不包含 pipeline trace 或 Prompt。 |
| `tts.frame` + TTS binary | `tts.frame` 为 JSON 文本帧：`type`, `session_id`, `turn_id`, `playback_id`, `sequence`, `codec`, `duration_ms`, `is_final`；随后紧接一条 binary | V3 起每个 TTS binary 前先发 `tts.frame` 文本帧，JSON 与 binary 严格交替；`codec` 由 Provider 声明（edge→`mp3`，mock→`utf8_text`），不含 `sample_rate`/`channels`（MP3 容器自带）。客户端视图 `tts.chunk` 含 `sequence`, `size`。 |
| `barge_in.accepted` | `session_id`, `turn_id`, `status`, `intent` | 确认旧播放已按 P6 公共路径受理。改写意图后的新代音频在该事件之后开始。 |
| `voice.error` | `code`, `field` | 仅稳定机器码和字段；不返回异常原文或输入 payload。 |

ASR `transcript` 属于当前用户连接的实时回显，默认不记录、不持久化、不写入 trace 或报告；
server 不会把它广播到其他 session。JSON 与 TTS binary 共用同一个 connection send serializer，
以保证 `answer.display`、`barge_in.accepted` 与相应音频代际的可观测顺序。

`answer.display` 只会在 orchestrator 返回已完成 P5 自检的可信 `TextQueryResponse` 后发送，
包含 `answer_id`、`evidence_package_id` 与用于展示的 `answer`。TTS setup/stream 失败不会重跑
RAG；客户端保留 `answer.display`，并收到仅含稳定 code/field 的 `voice.error`。错误事件不回显
PCM、完整私人 transcript、Prompt 或异常原文。

### 健康与能力边界

`VoiceWebSocketServer.health()` 只读检查当前 transport、Provider registry、两个词典和 voice config
snapshot；自 V2 起同一监听端口提供 HTTP `GET /health`，返回与 `health()` 完全一致的 JSON（字段含
`status` / `providers` / `registry_ready` / `lexicons` / `voice_config_snapshot_id` / `capabilities`），
可作为远程就绪检查，不新增端口、不引入额外依赖。当前能力明确为 `websocket_mock=true`、`offline=true`、`real_asr=false`、
`real_tts=false`、`production_media_server=false`。健康检查不读取密钥，不调用真实外部服务。
`serve_voice` 要求 `VoiceSettings.transport=websocket` 且 registry 已注册 `websocket`；不满足时在启动阶段 fail-fast，不会返回虚假 `status=ok`。单连接关闭只 drain 自己 session/turn 的 metrics，server 整体关闭才执行全局 drain，避免一个慢 writer 阻塞其他连接清理。
## 知识库运维 CLI（P3 Task 11）

### 真实资料入库

`scripts/ingest_sources.py` 必须显式提供以下参数，不使用演示数据或隐式默认知识库：

```powershell
python scripts/ingest_sources.py `
  --input <文件路径> `
  --source-id <稳定来源ID> `
  --title <来源标题> `
  --source-type <text或pdf> `
  --authority-level <权威等级> `
  --review-status <draft|candidate|reviewed|deprecated> `
  --aircraft <机型> `
  --component <部件> `
  --concept <概念> `
  --config <rag配置路径> `
  [--project-maintainer]
```

- `--review-status reviewed` 只有在显式提供 `--project-maintainer` 时才允许；缺少授权时命令返回非零，且不会注册来源。
- `source_id` 是不可覆盖的稳定标识；已存在时返回 `duplicate_source`，原 source、content hash、父子节点、视觉页、物理索引和 active version 均保持不变。本接口不提供 replace/upsert 语义。
- 入库必须经 `RetrievalController.ingest_source()` 完成来源注册、父子切分、BM25/向量索引和适用的 PDF 页面持久化，脚本不得直接写底层表。
- UTF-8 decode、PDF parse、父子/视觉契约构造和 embedding 预计算必须在首次知识业务持久化前完成。source、parents/chunks、visual pages、FTS、vector、index active 与 completed audit 随后在同一 SQLite 事务中提交；任一阶段失败时全部业务与物理索引回滚，failed audit 持久保留，已创建的候选 index version 可标记为 `failed`。
- 每次已开始的入库尝试都写入 `ingestion_jobs` 审计，包含维护者授权、完整 CLI 参数、输入 SHA-256、成功结果或结构化失败结果。
- 成功时 stdout 只包含单个 JSON 对象，字段顺序和语义稳定：

```json
{"status":"ok","source_id":"src_lift","parent_count":2,"chunk_count":5,"index_version":"index_...","warnings":[]}
```

- 参数、解析、仓库、FTS5、embedding 或验证失败时退出码非零；stdout 不混入日志，stderr 只包含 `{"status":"error","error":{"code":"...","message":"..."}}`。

### 索引原子重建

```powershell
python scripts/rebuild_knowledge_indexes.py --config <rag配置路径>
```

命令在同一知识库中创建新的 `index_versions` 候选，先在临时 staging 表验证 reviewed chunk、FTS 和 vector 数量以及 embedding provider/dimension，再在单个 SQLite 事务中替换当前索引并标记新版本为 `active`。候选构建、验证或交换失败时，新版本标记为 `failed`，旧 active 版本和旧物理索引保持不变。成功 stdout 为结构化 JSON，包含 `index_version`、三类计数和 embedding 元数据。

### 知识库只读验证

```powershell
python scripts/validate_knowledge_base.py --config <rag配置路径>
```

validation 使用 SQLite read-only 模式，不创建数据库、不建表、不迁移、不补索引。JSON 的 `checks` 固定包含：`sqlite_integrity`、`foreign_keys`、`reviewed_sources_have_chunks`、`chunks_have_parents`、`embedding_dimensions`、`graph_edges_have_sources`、`visual_assets_have_layout_trace`、`active_index_version`。全部通过时 `status=ok` 且退出码 0；任一检查失败时 `status=error` 且退出码非零。正式库缺失或尚未初始化时必须如实报告，禁止 validation 静默修复。

### AppPipeline 默认知识库所有权

未注入 retrieval controller 时，`AppPipeline(rag_config_path=...)` 通过同一 `RetrievalController.from_config()` 创建 repository-backed controller，并拥有其关闭责任；context manager 或 `close()` 只关闭该自建 controller。显式注入的 controller 仍由调用方拥有，`AppPipeline` 不关闭它。测试和离线 fixture 必须传临时 RAG/Memory 配置，不得写正式 `knowledge.sqlite3` 或 `memory.sqlite3`。

## Prompt-facing Memory Sanitization (2026-07-09)

## P7 最终配置与反馈契约（2026-07-13）

- `voice_terminology.yaml` 必须同时提供严格字符串映射 `terms` 与 `simplifications`。ASR 只消费 `terms`，P6 `SIMPLIFY` 只消费 `simplifications`；任一分区非法时 `VoiceSettings.from_file()` 启动失败。
- 两个分区分别产生 `terminology_lexicon_digest` 与 `simplification_lexicon_digest`，并影响 `snapshot_id`；safe summary 不输出词典路径或内容。
- `CheckpointStore` 默认消费 `feedback.checkpoint.max_items`，按 FIFO 显式淘汰，淘汰审计同样有界且仅包含安全 ID/容量/策略。模块级 `save_checkpoint(...)` 仅委托受治理 store，不复制 P6 逻辑。
- 真实 ASR/TTS/模型、生产鉴权、TLS、媒体服务与生产部署不在当前契约的已上线能力中。

- Runtime prompt assembly may include only sanitized memory context cues.
- Allowed prompt-visible memory cues are limited to safe preference summaries, learner-level summaries, misconception labels, and hit metadata such as `memory_id`, `use_class`, `reason`, and `score`.
- Raw private values, raw recent feedback text, raw learner profile payloads, raw temporal notes, and other free-text private memory content must not be serialized into `PromptMessageBundle.messages`.
- `memory_context_ids` in trace remain the stable linkage for audit; prompt/runtime safety must be enforced before prompt serialization rather than only in trace export.

## LangGraph 文本主链迁移契约

- 文本问答仍只经 `AppPipeline` 公共边界进入运行时；`TextQueryRequest`、`TextQueryResponse`、CLI 参数及公开的 answer/action/trace 字段保持不变。语音实时层继续消费这一文本公共入口，不接入图编排。
- LangGraph checkpoint 使用 `langgraph.checkpoint_path` 指定的本地 SQLite 文件，必须与 RAG 的 `knowledge.sqlite3` 及记忆的 `memory.sqlite3` 分离。checkpoint 仅保存恢复所需的最小脱敏状态；终态完成后删除对应图线程的 checkpoint。
- checkpoint 不持久化原始音频、完整 Prompt、私有记忆正文、来源全文、证据正文或密钥。部署校验的 LangGraph 相关字段只输出包版本和路径分类，不输出配置路径、查询、Prompt、记忆、证据或秘密内容。
- `scripts/validate_deployment.py` 仅做只读部署核验：读取配置、导入 `StateGraph` 与 `SqliteSaver` 并比较配置路径；不得创建 checkpoint 数据库，也不得以写模式打开知识库或记忆库。
- 真实模型 Provider、真实语音 Provider、生产数据库连接和生产部署不属于本迁移范围。
