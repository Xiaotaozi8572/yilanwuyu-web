# Pico 4 Unity 接入文本问答与实时语音服务实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development`（建议）或按任务串行执行本计划；每项用复选框记录状态。
>
> 文档版本：v1.0（2026-08-16）  
> 适用代码库：后端 `D:\APP\Python 3.13\挑战杯`；Unity/Pico 4 客户端 `C:\Users\SONGQI\Desktop\pico 4`。  
> 本文是实施计划，不代表任何后端接口已经新增或 Pico 已完成接入。

**目标：** 在不绕过现有 `AppPipeline` 与 `VoiceSessionOrchestrator` 的前提下，为 Pico 4 Unity 客户端提供一个受 TLS 和鉴权保护的统一入口：文本问答走 REST，实时语音走 WebSocket，并以可测试、可回滚的方式完成端到端联调。

**架构：** 新增一个 ASGI 网关。`POST /api/v1/text-query` 将受控地把 `TextQueryRequest` 交给既有 `AppPipeline.run_text_query()`；`wss://<host>/ws/voice` 以 ASGI WebSocket 适配器复用既有 `VoiceSessionOrchestrator`、VAD、ASR、RAG、回答生成与 TTS。公网/局域网 TLS、反向代理、访问令牌和限流位于网关边界，核心 Agent、RAG、记忆和语音业务规则不移动、不重写。

**技术栈：** Python 3.13、FastAPI + Uvicorn（新增、需锁定版本）、现有 `websockets==15.0.1`、现有 `faster-whisper`/`edge-tts`、Caddy（TLS 终止与反向代理）、Unity 2022.3.62f1、PICO XR、NativeWebSocket、Newtonsoft.Json、UnityWebRequest、Unity Microphone/AudioSource。

## 全局约束

- 现有后端 `scripts/run_voice.py` 只绑定 `127.0.0.1` 且端口随机；它不能直接作为 Pico Wi-Fi 端入口。
- 现有后端没有 HTTP 文本问答服务，只有 CLI `app.cli`；不得让 Unity 启动 Python CLI、读取数据库、调用模型、RAG 或记忆底层模块。
- 既有语音 wire 契约的上行音频固定为 **16 kHz、单声道、signed PCM16 little-endian、20 ms/640 B**；帧序号从 0 开始严格递增。
- 新网关必须保持既有 Python Mock 客户端及现有 WebSocket 测试的兼容性；`/ws/voice` 的 v2 能力使用明确版本协商，不能悄悄改变旧 `serve_voice()` 语义。
- 设备可见入口必须使用 HTTPS/WSS；开发环境可使用内部 CA 或受 Pico 信任的开发证书，不能在正式演示中跳过证书校验。
- 所有用户请求均经过认证和请求大小限制；日志不得记录原始 PCM、访问令牌、完整私人转写、Prompt、密钥或后端堆栈。
- 语音 TTS 下行必须升级为“JSON 元数据帧紧随一条 binary”的 v2 契约，不能继续让 Unity 猜测裸 binary 的编码、归属或结束边界。
- 任何新增依赖、公开路由、配置、Caddy 部署文件及 Unity 第三方包须按项目 `harness.md` 的允许范围取得批准后实施。

---

## 1. 已核实的现状与实施边界

| 项目 | 已有事实 | 对计划的影响 |
| --- | --- | --- |
| 文本 Agent | `src/services/app_pipeline.py::AppPipeline.run_text_query()` 接受 `TextQueryRequest` 并返回 `TextQueryResponse` | HTTP 适配器只能调用此公共入口 |
| 文本 schema | `src/app/api/schemas.py` 已定义 `TextQueryRequest`、`TextQueryResponse` | 网络 schema 由它们映射而来，不直接暴露内部 trace |
| 语音 Agent | `src/voice/websocket_server.py` 的 `_VoiceConnection` 已支持 session、场景、PCM、ASR、回答、TTS 与打断 | 应抽取可复用连接处理器，再接入 ASGI 路由 |
| 当前语音协议 | 仅 loopback `ws://`、随机端口；5 种上行 JSON；TTS 是裸 MP3 binary | 不能直接交付 Pico；需 v2 网关与部署方案 |
| Unity 工程 | Unity 2022.3.62f1，含 PICO XR/场景/资源；没有 `.cs` 或 `.asmdef` | 网络、音频、状态、UI 绑定全部需新增，不能假定已有客户端实现 |

## 2. 目标对外契约（先冻结，再编码）

### 2.1 URL 与版本

| 服务 | 生产 URL | 开发 URL | 版本 |
| --- | --- | --- | --- |
| 就绪探针 | `GET https://agent.example.com/healthz` | `GET https://<LAN-IP>:8443/healthz` | `gateway-v1` |
| 文本问答 | `POST https://agent.example.com/api/v1/text-query` | 同左 | `text-v1` |
| 语音 | `wss://agent.example.com/ws/voice` | `wss://<LAN-IP>:8443/ws/voice` | `voice-ws-v2` |

客户端通过 `Authorization: Bearer <短期访问令牌>` 认证。HTTP 令牌放在请求头；WebSocket 令牌放在升级请求的同一 header。服务器不接受 URL query token，不在 URL、日志或 telemetry 中写令牌。每个 Pico 安装实例拥有独立 `device_id`，令牌的 subject 与该 ID 绑定。

### 2.2 文本问答：请求、成功与失败

请求 Content-Type 必须为 `application/json`，body 不超过 32 KiB；`query` 去掉空白后长度为 1–1000 字符。

```json
{
  "query": "C919 的发动机有什么作用？",
  "scene_state": {
    "scene_state_id": "scene-c919-engine",
    "aircraft_id": "c919",
    "component_id": "engine",
    "selected_object_id": "engine-left-01",
    "scene_confidence": 1.0,
    "visual_refs": [],
    "candidate_object_ids": []
  },
  "session_id": "pico-session-9f4c",
  "turn_id": "text-turn-0001",
  "source": "text"
}
```

成功返回 `200`，body 是 **公共** `TextQueryResponse`；仅允许 Unity 使用 `answer`、`action_decision`、`final_answer_id`、`evidence_package_id`、`check_report_id` 与 `self_check_completed`。响应中不得包含 `trace` 原文；统一以 `{ "run_id", "status", "answer", "action_decision", "error", "final_answer_id", "evidence_package_id", "check_report_id", "self_check_completed", "feedback_checkpoint_id" }` 返回，`trace` 字段删除。

| HTTP 状态 | 稳定 code | Unity 行为 |
| --- | --- | --- |
| 200 | `ok` | 渲染 `answer.short_answer`、`main_answer`、`display_blocks` |
| 400 | `invalid_request` | 提示“问题或场景数据不符合要求”，不重试 |
| 401 / 403 | `unauthorized` / `forbidden` | 刷新令牌一次；仍失败则退出会话 |
| 408 / 504 | `request_timeout` | 显示超时，可由用户重新提交；不得盲重放 |
| 429 | `rate_limited` | 显示冷却倒计时，按 `Retry-After` 后才允许重试 |
| 500 / 503 | `pipeline_execution_error` / `service_unavailable` | 保留用户输入，允许用户手动重试；记录脱敏 code 与 run_id |

### 2.3 实时语音 v2：上行与下行

语音连接成功后，首个应用消息必须为下列 `session.start`；它以 `protocol_version` 区分 v2，避免把 v2 字段送入旧 v1 server：

```json
{
  "type": "session.start",
  "protocol_version": "voice-ws-v2",
  "session_id": "pico-session-9f4c",
  "turn_id": "voice-turn-0001",
  "audio_stream_id": "audio-0001",
  "user_id": "learner-opaque-id",
  "device_id": "pico4-opaque-id"
}
```

保留现有 `scene.update`、`audio.frame`、`audio.end`、`session.cancel` 的字段与严格顺序；每个 `audio.frame` JSON header 后必须紧跟一条 PCM binary。v2 下行保留 `voice.state`、`asr.partial`、`asr.final`、`clarification.required`、`answer.display`、`barge_in.accepted`、`voice.error`，并把裸 TTS binary 升级为：

```json
{
  "type": "tts.frame",
  "protocol_version": "voice-ws-v2",
  "session_id": "pico-session-9f4c",
  "turn_id": "voice-turn-0001",
  "playback_id": "playback-opaque-id",
  "sequence": 0,
  "codec": "mp3",
  "sample_rate": 24000,
  "channels": 1,
  "duration_ms": 1260,
  "is_final": false
}
```

每条 `tts.frame` 后紧跟对应完整 MP3 binary。`is_final: true` 后仍以 `voice.state(status="playback.completed")` 作为服务端完成确认。客户端只播放当前 `(session_id, turn_id, playback_id)` 的严格连续 sequence；任何旧 generation、跳号、非 MP3 或无 header binary 都丢弃并写入本地脱敏诊断。

### 2.4 状态与恢复规则

Unity 可显示 `IDLE → LISTENING → TRANSCRIBING → UNDERSTANDING → RETRIEVING → GENERATING → SPEAKING`，并处理 `CLARIFY`、`INTERRUPTED`、`REWRITE`。传输状态单独维护 `Disconnected / Connecting / Ready / Reconnecting / AuthenticationFailed`；不得把 WebSocket 连接状态和 Agent 语义状态混成一个枚举。

- 单条 WebSocket 连接只占用一个 `session_id`；同连接所有帧的 `session_id`、`turn_id`、`audio_stream_id` 必须精确匹配。
- 现有服务端 30 秒没有**应用层消息**会 `4408`；v2 新增 `{"type":"session.ping","sent_at_ms":...}` / `session.pong`，仅在 `Ready` 且没有录音或播报时每 15 秒发送。未在 5 秒收到 pong 即断开重连。
- `1000` 正常收尾；`4400` 停止当前 turn 并提示客户端协议错误；`4401` 生成新 ID 并重连；`4408` 以同一 session、新 turn、新 audio stream 重连；`1009` 缩小帧并停止自动高频重试；网络/`1011` 采用 1、2、4、8、10 秒退避，最多 5 次。
- 重连不重传已上传 PCM，不重复执行未确认文本请求；文本请求使用 `Idempotency-Key: <turn_id>`，服务端以 `(subject, key)` 保存 10 分钟结果，保证用户手动重试不会重复写入记忆候选。

---

## 3. 文件结构与职责（实现后应当如此）

### 后端

| 文件 | 操作 | 单一职责 |
| --- | --- | --- |
| `pyproject.toml` | 修改 | 锁定 FastAPI、Uvicorn、JWT/令牌校验所需依赖 |
| `configs/gateway.yaml` | 新建 | host、内部端口、受信 issuer/audience、请求上限、限流、CORS、允许的 PICO device 配置 |
| `src/app/gateway.py` | 新建 | `create_gateway_app()` 应用工厂与 lifespan；组装公共入口，不含业务规则 |
| `src/app/api/http_models.py` | 新建 | Pydantic HTTP 入/出站模型及 `TextQueryRequest` 映射 |
| `src/app/api/text_router.py` | 新建 | `POST /api/v1/text-query`，仅调用 `AppPipeline.run_text_query()` |
| `src/app/api/health_router.py` | 新建 | `GET /healthz` 和受保护 `GET /readyz` |
| `src/app/api/auth.py` | 新建 | Bearer 令牌验证、subject/device 绑定、角色与 scope 校验 |
| `src/app/api/problem.py` | 新建 | 统一、脱敏的 HTTP Problem JSON 与异常映射 |
| `src/app/api/idempotency.py` | 新建 | 文本请求幂等键缓存接口（不能写入长期记忆） |
| `src/voice/asgi_socket.py` | 新建 | 把 Starlette `WebSocket` 适配为既有 voice socket 的 `recv/send/close` 形状 |
| `src/voice/websocket_server.py` | 修改 | 抽出可复用 `handle_voice_connection()`；保留旧 `serve_voice()` v1 行为 |
| `src/voice/v2_protocol.py` | 新建 | v2 schema、版本协商、`session.ping/pong` 与 `tts.frame` 元数据校验/序列化 |
| `src/voice/v2_transport.py` | 新建 | 发送 `tts.frame` 后才发送 binary，并确认 `TTSChunk.playback_id/sequence_no/duration_ms/is_final` |
| `src/app/api/voice_router.py` | 新建 | `GET WebSocket /ws/voice` 的认证、accept、v2 调度和关闭码映射 |
| `scripts/run_gateway.py` | 新建 | 只加载配置并启动 `gateway:create_gateway_app`；不在脚本复制路由逻辑 |
| `deploy/Caddyfile` | 新建 | 公网/LAN TLS 终止、HTTP+WS 反代、header 与请求上限 |
| `tests/api/*`、`tests/integration/gateway/*` | 新建 | HTTP、WS、鉴权、回归和真实应用边界测试 |

### Unity

在 `C:\Users\SONGQI\Desktop\pico 4\Assets\翼揽无余\Scripts\AgentGateway\` 新建下列 C# 文件；每个文件只处理一类职责。

| 文件 | 职责 |
| --- | --- |
| `GatewayConfig.cs` | ScriptableObject：Base URL、WS URL、超时、开发证书策略开关（发布版强制关闭绕过） |
| `GatewayContracts.cs` | DTO：文本请求/响应、所有 WS JSON 事件、`SceneStateDto`、错误对象；使用 Newtonsoft.Json |
| `AccessTokenProvider.cs` | 从安全存储读取、刷新并过期检查短期令牌；不写日志 |
| `TextQueryClient.cs` | UnityWebRequest POST、Authorization、Idempotency-Key、取消、HTTP 错误映射 |
| `VoiceSocketClient.cs` | NativeWebSocket 建连、header、文本/binary 收发、连接 generation、退避重连 |
| `Pcm16FrameEncoder.cs` | Unity float 样本 → 单声道 16 kHz PCM16 LE、20 ms 分帧与 RMS energy |
| `MicrophoneCapture.cs` | 权限、麦克风循环读取、重采样、开始/停止和资源释放 |
| `TtsFrameBuffer.cs` | 依据 `tts.frame` 关联 MP3 binary、连续序号、旧 generation 丢弃与内存上限 |
| `Mp3Playback.cs` | 选定解码器的封装、AudioSource 排队播放、停止/释放；不含 WS 协议 |
| `AgentSessionStore.cs` | 每轮 ID、文本/语音状态、当前回答、取消令牌；没有 UI 代码 |
| `AgentGatewayController.cs` | 编排 UI 意图到 TextQueryClient/VoiceSocketClient；只订阅状态，不解析 wire |
| `AgentStatusPresenter.cs` | 将状态映射到 Pico UI 字幕、转圈、文字回答、澄清和错误提示 |

---

## 4. 实施任务与验收

### Task 1：冻结 ADR、公开契约与安全基线

**文件：**
- 新建：`docs/接口与部署/gateway_v1_contract.md`
- 新建：`docs/接口与部署/adr/ADR-001-unified-pico-gateway.md`
- 修改：`docs/接口与部署/pico4_unity_voice_integration.md`
- 测试：`tests/api/test_contract_examples.py`

**产出接口：** URL、版本、认证 header、HTTP problem schema、WS v2 文本/二进制顺序、状态/关闭码、TTS codec 固定为 MP3。

- [ ] 写入 ADR：网关仅做认证、传输校验、路由与观测；`AppPipeline` 和 `VoiceSessionOrchestrator` 仍是唯一业务入口。
- [ ] 将第 2 节的 JSON 样例作为契约测试 fixture；对每个样例校验必填字段、额外字段拒绝与字节帧顺序。
- [ ] 明确令牌使用 `Authorization` header、Pico 设备独立 subject、TLS 必需、URL 中禁止 token。
- [ ] 运行：`pytest tests/api/test_contract_examples.py -q`。预期：所有 JSON 样例通过，缺 `protocol_version`、无 TTS header 的 binary、额外字段均被拒绝。
- [ ] 评审后提交：`docs(gateway): freeze Pico HTTP and voice v2 contract`。

### Task 2：建立网关配置、应用工厂与安全依赖

**文件：**
- 修改：`pyproject.toml`
- 新建：`configs/gateway.yaml`
- 新建：`src/app/gateway.py`
- 新建：`src/app/api/auth.py`
- 新建：`src/app/api/problem.py`
- 测试：`tests/api/test_gateway_factory.py`、`tests/api/test_auth.py`

**产生接口：**

```python
def create_gateway_app(settings_path: str = "configs/gateway.yaml") -> FastAPI: ...
async def require_subject(authorization: str) -> AuthenticatedSubject: ...
def problem(status: int, code: str, request_id: str) -> JSONResponse: ...
```

- [ ] 在 `pyproject.toml` 中以精确版本加入经过安全审核的 `fastapi`、`uvicorn` 和令牌验证库；同步 lock/requirements 规则，不升级无关依赖。
- [ ] 在 `gateway.yaml` 写明：内部 bind `127.0.0.1:8088`、HTTP 32 KiB、WS 控制帧 32 KiB、PCM frame 64 KiB、WS idle 30 秒、ping 15 秒、pong 5 秒、每 device 的文本/语音限流值。
- [ ] 应用 factory 只注册 router、异常处理、request-id middleware 和 lifespan；lifecycle 启动时构造一个共享的 `AppPipeline` 与一个共享 voice provider registry，不为每请求重复加载 Whisper 模型。
- [ ] `require_subject` 验证签名、`exp`、issuer、audience、scope=`agent:use`、device claim；任何失败一律返回稳定 `401 unauthorized`，日志仅含 request_id 与失败类别。
- [ ] 运行：`pytest tests/api/test_gateway_factory.py tests/api/test_auth.py -q`。预期：无 token=401；过期/错误 audience=401；合法 token 可访问受保护测试路由。
- [ ] 提交：`feat(gateway): add authenticated ASGI application factory`。

### Task 3：实现文本问答 REST 适配器与幂等保护

**文件：**
- 新建：`src/app/api/http_models.py`
- 新建：`src/app/api/text_router.py`
- 新建：`src/app/api/idempotency.py`
- 测试：`tests/api/test_text_router.py`、`tests/api/test_text_idempotency.py`

**产生接口：**

```python
POST /api/v1/text-query
Headers: Authorization, Idempotency-Key
Body: TextQueryHttpRequest
Response: PublicTextQueryResponse
```

- [ ] 以 Pydantic 模型验证 `query`、`scene_state`、opaque ID、body 上限；把合法请求显式映射为现有 `TextQueryRequest(source="text")`。
- [ ] 在受控线程/async 边界调用 `AppPipeline.run_text_query(request)`；不得直接调用 RAG、memory、LLM client 或私有 pipeline 方法。
- [ ] 将结果映射成第 2.2 节公共 response，删除 `trace`；错误统一为 `{ "code", "message", "request_id", "run_id" }`，不输出异常文本。
- [ ] 对同 subject + `Idempotency-Key` 保存终态 response 10 分钟；进行中的重复请求返回同一结果或 `409 request_in_progress`，绝不重复触发 pipeline。
- [ ] 运行：`pytest tests/api/test_text_router.py tests/api/test_text_idempotency.py -q`。预期：有效问题=200；空 query=400；第二次同 key 不增加 mock pipeline 调用次数；pipeline 异常=503 且无 trace。
- [ ] 提交：`feat(api): expose guarded text-query endpoint`。

### Task 4：增加健康、就绪、限流与审计边界

**文件：**
- 新建：`src/app/api/health_router.py`
- 新建：`src/app/api/rate_limit.py`
- 修改：`src/app/gateway.py`
- 测试：`tests/api/test_health.py`、`tests/api/test_rate_limit.py`

**产生接口：** `GET /healthz` 返回存活；受认证 `GET /readyz` 返回 `pipeline_ready`、`voice_registry_ready`、`asr_ready`、`tts_configured`、`config_snapshot_id`，但不返回密钥、完整配置或 provider 内部异常。

- [ ] `/healthz` 不依赖数据库、ASR 或 TTS，供 Caddy/进程管理器探测；未就绪的 runtime 也应返回进程存活。
- [ ] `/readyz` 仅在 pipeline、provider registry、词典与配置均加载成功时返回 200；否则返回 503 与机器码。
- [ ] 限流 key 为认证 subject + route；对文本和 WebSocket 连接建立分别计数，429 附带 `Retry-After`。
- [ ] 给所有 HTTP/WS 事件写 `request_id/session_id/turn_id/error_code/latency`；加入测试断言日志里不存在 Authorization、PCM、transcript、Prompt 或 exception stack。
- [ ] 运行：`pytest tests/api/test_health.py tests/api/test_rate_limit.py -q`。预期：liveness 不泄露配置；ready 只在全部依赖就绪时 200；超限=429。
- [ ] 提交：`feat(gateway): add readiness limits and safe diagnostics`。

### Task 5：将既有语音连接处理器抽为可复用核心

**文件：**
- 修改：`src/voice/websocket_server.py`
- 新建：`src/voice/asgi_socket.py`
- 测试：`tests/unit/voice/test_asgi_socket.py`、现有 `tests/integration/voice_loop/*`

**产生接口：**

```python
async def handle_voice_connection(
    socket: VoiceSocket,
    orchestrator: VoiceSessionOrchestrator,
    *, idle_timeout_seconds: float,
    protocol: VoiceProtocol,
) -> None: ...
```

- [ ] 把 `_VoiceConnection` 的 run/cleanup 生命周期抽为上述公共函数；`serve_voice()` 仍创建原有 websocket server 并调用 v1 handler，现有 host loopback 限制不变。
- [ ] `AsgiVoiceSocket` 将 `receive()` 的 text/bytes/disconnect 规范为旧处理器所需 `recv()`，将 `send()` 区分 text/binary，并将 close code/reason 安全映射到 Starlette WebSocket。
- [ ] 将连接所有权、session cleanup、正常 1000、4400、4401、4408 的现有断言原样保留。
- [ ] 运行：`pytest tests/unit/voice/test_asgi_socket.py tests/integration/voice_loop -q`。预期：旧 Python Mock 客户端、断线重连、澄清继续、barge-in、TTS 失败均回归通过。
- [ ] 提交：`refactor(voice): make connection lifecycle transport-host independent`。

### Task 6：实现 `voice-ws-v2`、心跳与带元数据的 TTS

**文件：**
- 新建：`src/voice/v2_protocol.py`
- 新建：`src/voice/v2_transport.py`
- 修改：`src/voice/websocket_server.py`
- 修改：`src/voice/transport.py`（仅注册 v2 transport）
- 测试：`tests/unit/voice/test_v2_protocol.py`、`tests/integration/gateway/test_voice_v2.py`

**产生接口：** `VoiceProtocolV2.parse_client_event()`、`VoiceV2Transport.send_audio(TTSChunk)`、`serialize_tts_frame(TTSChunk)`。

- [ ] v2 严格要求 `session.start.protocol_version == "voice-ws-v2"`、`device_id` 与 token claim 一致；版本不匹配返回 `voice.error(code="VOICE_PROTOCOL_VERSION_UNSUPPORTED", field="protocol_version")` 后以 4400 关闭。
- [ ] 在 v2 扩展允许的客户端事件为 `session.ping`，服务端回复 `{ "type":"session.pong", "sent_at_ms":<原值> }`；它只刷新连接 idle 计时，不创建 turn、不调用 pipeline。
- [ ] `VoiceV2Transport.send_audio()` 必须先按 `TTSChunk` 输出 `tts.frame`，再发送其 payload，并在成功 binary send 后确认播放 chunk；`codec="mp3"`、采样率/声道由 Edge TTS 配置的冻结值提供。
- [ ] v2 对每条 TTS binary 的 header/sequence/playback_id 建立一对一约束；取消后不再发旧 playback 的 header 或 binary。
- [ ] 运行：`pytest tests/unit/voice/test_v2_protocol.py tests/integration/gateway/test_voice_v2.py -q`。预期：v2 PCM 可得到 answer.display + `tts.frame` + MP3；binary 无 header、错误 version、重复 sequence、取消后旧 audio 都失败或被阻断。
- [ ] 提交：`feat(voice): add authenticated v2 protocol and framed TTS`。

### Task 7：装配受认证的 ASGI WebSocket 路由

**文件：**
- 新建：`src/app/api/voice_router.py`
- 修改：`src/app/gateway.py`
- 测试：`tests/integration/gateway/test_voice_auth.py`、`tests/integration/gateway/test_voice_reconnect.py`

**产生接口：** `wss://host/ws/voice`；连接建立前验证 Authorization，建立后仅运行 v2 voice handler。

- [ ] 在 accept 前验证 Bearer token、device binding、连接限流和 Origin 允许列表；失败以 WebSocket 1008 policy violation 关闭，不能先 accept 再处理匿名消息。
- [ ] accept 后创建 `AsgiVoiceSocket` 和 `VoiceProtocolV2`，调用 Task 5 公共连接函数；阻止 v1 事件进入 `/ws/voice`。
- [ ] 用每连接 task 隔离取消和异常；单个 Pico 断开不得关闭全局 Whisper/TTS/其他连接。
- [ ] 运行：`pytest tests/integration/gateway/test_voice_auth.py tests/integration/gateway/test_voice_reconnect.py -q`。预期：无 token/错误 device=1008；合法连接 session.started；4408 后换 turn 重连成功；旧连接事件不污染新连接。
- [ ] 提交：`feat(api): mount secure voice websocket endpoint`。

### Task 8：部署 TLS、反向代理和 Pico 可达性

**文件：**
- 新建：`deploy/Caddyfile`
- 新建：`deploy/run_agent_gateway.ps1`
- 新建：`docs/接口与部署/pico_gateway_runbook.md`
- 修改：`scripts/validate_deployment.py`
- 测试：`tests/integration/gateway/test_reverse_proxy_contract.py`

- [ ] Caddy 监听 `:443` 并将 `/api/*` 和 `/ws/voice` 反向代理到 `127.0.0.1:8088`；保留 WebSocket upgrade，不缓冲流式 WS，不把 Authorization 写入 access log。
- [ ] 开发 LAN 采用受 Pico 信任的证书链；部署说明包含证书导入/信任、Windows 防火墙入站规则、固定 DNS 或固定 LAN IP、后端与 Pico 同网段验证步骤。
- [ ] Uvicorn 仅 bind `127.0.0.1:8088`，禁止把未经 TLS 的内部端口暴露给 Wi-Fi；Caddy 是唯一设备可见入口。
- [ ] 在 CI/部署预检验证 `https://.../healthz`、认证 `/readyz`、WSS TLS peer、/ws/voice upgrade 和无 token 拒绝。
- [ ] 运行：`python scripts/validate_deployment.py` 和部署 runbook 的 health/WSS smoke command。预期：公网/LAN 仅暴露 443；内部 8088 无外网监听；Pico 可验证证书。
- [ ] 提交：`ops(gateway): publish TLS reverse-proxy deployment`。

### Task 9：Unity 工程基础、配置与安全令牌

**文件：**
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/GatewayConfig.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/AccessTokenProvider.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/GatewayContracts.cs`
- 修改：`Packages/manifest.json`（仅加入已批准的 NativeWebSocket、Newtonsoft.Json）
- 测试：Unity EditMode `Assets/翼揽无余/Tests/EditMode/GatewayContractsTests.cs`

- [ ] `GatewayConfig` 使用 ScriptableObject；发布配置只写 `https://...` 和 `wss://...`，不写 token、私钥或跳过证书校验开关。
- [ ] `AccessTokenProvider` 仅从 Android/Pico 安全存储读取短期 token；token 失效时触发受控刷新或安全登出，不通过 PlayerPrefs 明文保存。
- [ ] `GatewayContracts` 实现第 2 节 DTO；未知 `type` 进入可观测的 `UnsupportedEvent`，不会导致主线程异常。
- [ ] 运行 Unity EditMode 测试。预期：JSON 序列化与契约 fixture 一致；序列化输出不含 token。
- [ ] 提交：`feat(unity): add gateway configuration and contracts`。

### Task 10：Unity 文本问答客户端与展示状态

**文件：**
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/TextQueryClient.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/AgentSessionStore.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/AgentStatusPresenter.cs`
- 新建：`Assets/翼揽无余/Tests/EditMode/TextQueryClientTests.cs`
- 新建：`Assets/翼揽无余/Tests/PlayMode/TextAnswerPresentationTests.cs`

- [ ] `TextQueryClient.SendAsync(TextQueryRequestDto, CancellationToken)` 设置 Content-Type、Authorization、Idempotency-Key、30 秒取消超时并返回 `Result<PublicTextAnswer, GatewayError>`。
- [ ] 只渲染 `short_answer`、`main_answer`、`display_blocks`、`follow_up_questions`、`safety_notes`；将 `visual_refs`/`component_id` 发布成事件，供场景高亮系统选择性订阅。
- [ ] Store 对每次按钮提交生成 `turn_id`，在 response 回来前禁用同一按钮；取消时 abort UnityWebRequest，不把已取消 response 更新 UI。
- [ ] HTTP 400/401/403/408/429/5xx 映射为第 2.2 节用户提示；详细 `code/request_id/run_id` 只进入本地脱敏日志。
- [ ] 运行 EditMode + PlayMode 测试。预期：重复点击只发一个 idempotency key；过期 token 不显示后端异常；慢 response 不覆盖新 turn。
- [ ] 提交：`feat(unity): integrate text question-answering client`。

### Task 11：Unity 实时语音采集、WebSocket 和 MP3 播放

**文件：**
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/VoiceSocketClient.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/MicrophoneCapture.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/Pcm16FrameEncoder.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/TtsFrameBuffer.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/Mp3Playback.cs`
- 测试：`Assets/翼揽无余/Tests/EditMode/Pcm16FrameEncoderTests.cs`、`TtsFrameBufferTests.cs`；PlayMode `VoiceSocketStateTests.cs`

- [ ] 申请/检查 Pico 麦克风权限；取得 samples 后下混为单声道、重采样至 16 kHz、裁成 320 sample、编码为 640 B PCM16 LE，并算 0–1 RMS energy。
- [ ] VoiceSocketClient 用 WSS 和 Authorization header 建连；按顺序发 v2 session.start、可选 scene.update、audio.frame+binary、audio.end；接收循环与采集/播放循环必须分离。
- [ ] 每个 voice turn 使用新 `turn_id/audio_stream_id`，而 session 可复用；接收事件必须先匹配当前 session+turn 与 connection generation，才投递 UI。
- [ ] `TtsFrameBuffer` 仅接受 header 后紧邻的 binary，检查 MP3 codec、playback_id、sequence 和 `is_final`；缓冲超过 3 个完整 blob 或 8 MiB 时暂停接收/报受控错误，不能无限增长。
- [ ] `Mp3Playback` 使用经过 Pico Android 真机验证的 MP3 decoder；逐 blob 排队、`session.cancel` 时立即 Stop/clear，`playback.failed` 时保留文本回答并提示语音播放失败。
- [ ] 运行 EditMode/PlayMode 测试与模拟 WS fixture。预期：float 1.0 编码为 `0xFF 0x7F`；20 ms frame=640 B；header/binary 错配不播放；cancel 后没有旧音频。
- [ ] 提交：`feat(unity): stream Pico microphone and framed TTS`。

### Task 12：Unity 断线恢复、状态管理与交互整合

**文件：**
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/AgentGatewayController.cs`
- 新建：`Assets/翼揽无余/Scripts/AgentGateway/AgentSceneBridge.cs`
- 修改：`Assets/公用文件/02-机型选择页面共用.unity`（新增唯一的 `AgentGatewayBootstrap` GameObject，并挂载 `AgentGatewayController`、`AgentSceneBridge`、`AudioSource` 与 `GatewayConfig`）
- 测试：`Assets/翼揽无余/Tests/PlayMode/GatewayRecoveryTests.cs`

- [ ] Controller 维护传输状态与 Agent 状态两个独立 state machine；仅在 `Ready` 时允许录音，`SPEAKING` 时显示停止按钮，`CLARIFY` 显示重新提问/补充对象。
- [ ] 处理 1/2/4/8/10 秒、最多 5 次退避；4400 不重连；4401/4408 生成新 turn IDs；所有重连成功后仅重新发送当前 scene.update，不重传 PCM。
- [ ] 识别场景热点时构造 `scene_state`；无场景选择允许文本与语音提问，但含“这个/它”且后端澄清时必须提示用户选中部件。
- [ ] 应用暂停、退出、头显失焦时停止采集、关闭 WS 1000、取消 HTTP、清空 TTS buffer、停止 AudioSource；恢复时不自动提交旧问题。
- [ ] 运行 PlayMode 测试。预期：模拟 4408 可恢复；旧 socket 回包不更新 UI；失焦后无麦克风/音频/连接泄漏。
- [ ] 提交：`feat(unity): add resilient Agent interaction controller`。

### Task 13：端到端验收、性能基线和交付

**文件：**
- 新建：`tests/e2e/test_pico_gateway_smoke.py`
- 新建：`docs/接口与部署/pico_unity_acceptance.md`
- 新建：`docs/接口与部署/pico_unity_handover.md`
- 修改：`docs/项目总控/STATUS.md`（在得到对应阶段/工作包授权后记录）

- [ ] 后端 CI：HTTP 成功/参数错误/鉴权/限流/幂等；v1 voice 回归；v2 WSS session、连续 PCM、answer.display、tts.frame、cancel、澄清、断线/4408、TLS 失败均覆盖。
- [ ] Unity CI：contracts、PCM 字节序、文本错误映射、TTS 事件顺序、generation filter、重连退避、失焦释放覆盖。
- [ ] Pico 真机最小验收：同一 Wi-Fi，验证证书，healthz=200，readyz=200，文本提问，选中发动机后的语音提问，ASR partial/final，answer.display，MP3 播放，点击停止，断网重连，低置信澄清。
- [ ] 性能记录 p50/p95：文本首答、语音首 ASR partial、audio.end 至 answer.display、首个 TTS frame、barge-in 停止；设定演示可接受阈值并写入验收文档，不能只记录“感觉流畅”。
- [ ] 交付 Unity：固定 WSS/HTTPS URL、证书链/安装说明、测试设备 token 发放流程、契约版本、OpenAPI、WS schema、错误/关闭码表、Pico demo 脚本、已知限制及回滚步骤。
- [ ] 提交：`docs(pico): publish acceptance evidence and Unity handover`。

---

## 5. 实施顺序、依赖与停止条件

```text
Task 1 → Task 2 → Task 3 → Task 4
                      └→ Task 5 → Task 6 → Task 7 → Task 8
Task 9 ────────────────────────────────────────────────┐
Task 3 + Task 9 → Task 10                               ├→ Task 13
Task 6 + Task 7 + Task 9 → Task 11 → Task 12 ──────────┘
```

1. Task 1 是接口冻结门；没有 ADR、token 策略、TLS 拓扑和 v2 TTS 契约，不写 Unity 业务代码。
2. Task 3（文本）与 Task 5–8（语音服务）可在 Task 2 后并行；Task 9（Unity 基础）可与后端并行。
3. Unity 的 Task 10 依赖真实 HTTP 契约；Task 11 依赖真实 `voice-ws-v2` 及 MP3 解码方案；Task 12 最后进行。
4. 任一安全、TLS、鉴权、协议兼容或主链回归失败，停止进入下一任务，先修复当前任务。
5. 若 FastAPI/Uvicorn、反向代理、JWT 方案或 MP3 解码器不被项目 harness 允许，立即记录“待确认”，不以临时裸 `ws://`、关闭证书验证或把 token 写入 Unity 资源文件替代。

## 6. 明确不做的事项

- 不让 Unity 直接连接 Python `run_voice.py` 的 loopback 随机端口。
- 不将文本/语音请求改为 Unity 直连 LLM、向量库、SQLite、Java memory service 或外部 ASR/TTS。
- 不在日志、Crash report、PlayerPrefs、ScriptableObject、URL query 中保存 token、PCM、私密 transcript 或 Prompt。
- 不把 TTS MP3 假定为 PCM，不接收缺失 `tts.frame` header 的 binary。
- 不将 `GET /healthz` 设计成泄露 Provider、模型路径、密钥、知识库内容或用户会话的调试接口。
- 不在未完成 Pico 真机 TLS/麦克风/MP3/断网验收前宣称“已稳定接入”。

## 7. 完成定义（Definition of Done）

- `https://.../healthz`、认证 `readyz`、认证 `POST /api/v1/text-query`、认证 `wss://.../ws/voice` 均通过自动化与 Pico 真机验证。
- 文本回答保持现有 Agent 的 `AppPipeline`、证据绑定、自检和结构化 `answer` 语义；语音继续走既有 `VoiceSessionOrchestrator`。
- 旧 voice v1 `serve_voice()` 测试全部通过；v2 有独立严格 schema、带 metadata 的 MP3 帧、heartbeats、关闭码与错误码测试。
- Unity 同时具备可取消文本请求、16 kHz PCM 输入、字幕、结构化回答、MP3 播放、打断、澄清、断线恢复和脱敏日志。
- 验收记录包括版本、commit、配置 snapshot、Pico 型号/系统版本、网络拓扑、测试命令与结果、p50/p95 指标和已知风险。

## 8. 给 Unity 负责人的最终交付包

后端负责人在 Task 13 完成后一次性交付：

1. 固定的 HTTPS/WSS 基础 URL、协议版本和生效日期；
2. Pico 可安装且受信任的证书链与网络接入说明；
3. 短期测试 token 的安全发放/吊销流程，绝不通过聊天明文长期分发生产 token；
4. OpenAPI 文档、文本 JSON examples、完整 voice-ws-v2 event 表和 PCM/TTS binary 顺序说明；
5. 状态机、稳定错误码、HTTP status、WebSocket close code、重试矩阵；
6. `scene_state` 可接受字段及 Unity 场景对象 ID 对照表；
7. 一套可重复的 smoke 场景和可接受输出；
8. 端到端验收报告、性能基线、已知限制、问题反馈渠道及回滚版本。

## 参考依据

- 后端现有协议与限制：`D:\APP\Python 3.13\挑战杯\docs\接口与部署\pico4_unity_voice_integration.md`
- 后端—Unity 交付清单：`D:\APP\Python 3.13\挑战杯\docs\unity-integration\后端接入文档.md`
- 语音实现：`src/voice/websocket_server.py`、`src/voice/transport.py`、`src/voice/tts.py`、`src/voice/edge_tts.py`
- 文本公共契约：`src/app/api/schemas.py`、`src/services/app_pipeline.py`
- FastAPI 官方 WebSocket 文档：[WebSockets](https://fastapi.tiangolo.com/advanced/websockets/)
