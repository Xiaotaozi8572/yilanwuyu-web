# Pico 4 Unity 语音 Agent 接入说明

> 文档用途：提供给 Unity/Pico 4 客户端负责人，说明后端当前已经存在的语音 WebSocket 协议、客户端必须实现的行为，以及后端在跨设备接入前必须提供或确认的部署条件。
>
> 文档范围：只覆盖“Pico 麦克风 → 后端 ASR/Agent → Unity 播放 TTS”的实时语音链路。不涉及 Unity 场景美术、手势交互、模型加载或 Pico SDK 的具体 UI 实现。
>
> 依据代码：`src/voice/websocket_server.py`、`src/voice/transport.py`、`src/voice/session_state.py`、`src/voice/orchestrator.py`、`src/voice/edge_tts.py`、`configs/voice.yaml`、`scripts/run_voice.py`、`scripts/run_frontend.py`。
>
> 修订记录：2026-08-17 依据 R0 加固（T1-10 / T2-11 / T3-8 / T3-10）与 T9 浏览器前端接入后的代码状态复核更新。
> 修订记录：2026-08-18 V1–V4 行为同步（固定端口、非回环监听、/health、tts.frame、protocol_version）。

## 1. 先给 Unity 负责人的结论

后端不是 HTTP JSON 问答接口，而是一个“单条 WebSocket 连接绑定一个语音会话”的实时协议：

```text
Pico 麦克风
  → 16 kHz / 单声道 / signed PCM16 little-endian / 20 ms 二进制音频帧
  → WebSocket：audio.frame JSON 头 + 紧随其后的 binary
  → 后端 VAD / ASR / 术语标准化 / AppPipeline / RAG / 自检
  → answer.display JSON
  → WebSocket binary TTS 音频
  → Unity AudioSource 播放
```

当前后端可供客户端对接的入口是：

```text
scripts/run_voice.py        # 语音 WebSocket 服务（默认 127.0.0.1:8765，--host/--port 可配置）
scripts/run_frontend.py     # 本机浏览器语音前端（127.0.0.1:8000，静态页 + Web Audio 采集/播放）
```

但当前入口默认只监听 `127.0.0.1`（固定端口 `8765`）。因此默认命令只能用于后端所在电脑本机或本机浏览器测试；若需 Pico 4 通过 Wi‑Fi 访问，必须用 `--host` 显式绑定非回环地址（见第 4.1 节）。跨设备接入前，后端负责人必须提供一个“Pico 可达的固定 WebSocket 地址”，见第 4 节。

## 2. 当前后端运行配置

当前 `configs/voice.yaml` 的关键值如下：

| 配置项 | 当前值 | 对 Unity 的影响 |
|---|---:|---|
| `transport` | `websocket` | 客户端使用 WebSocket，不是 HTTP 长轮询 |
| `sample_rate` | `16000` | 麦克风上传必须为 16 kHz |
| `channel_count` | `1` | 必须单声道 |
| `frame_duration_ms` | `20` | 推荐每帧 20 ms |
| `asr_provider` | `whisper` | 后端接收 PCM16 并在本地 ASR |
| `tts_provider` | `edge` | 当前 TTS 实现生成音频 binary；编码格式必须按第 8 节确认 |
| `endpoint_silence_ms` | `600` | 端点静音由后端判断，Unity 不要自行伪造 ASR final |
| `max_utterance_ms` | `15000` | 单轮话语时长上限（与下两项共同生效） |
| `max_frames` | `750` | 单轮音频帧数上限，超限触发 `clarification.required`（见 7.3） |
| `max_audio_bytes` | `480000` | 单轮音频字节上限（约 15 秒 PCM16），超限同上 |
| `barge_in_enabled` | `true` | 支持 `session.cancel` 打断当前播报 |
| `max_spoken_answer_seconds` | `20` | 单次播报有时长上限 |
| `max_spoken_answer_chars` | `180` | 单次播报有字符上限 |

后端仍通过既有 `AppPipeline` 进入回答生成、证据检索和自检；Unity 不直接调用 RAG、模型或数据库。

## 3. 连接生命周期

### 3.1 一条连接只服务一个绑定

每条 WebSocket 连接绑定以下三个 ID：

- `session_id`：会话 ID，可跨多个 turn 复用，但同一时刻只能由一条连接占用。
- `turn_id`：本轮语音交互 ID。
- `audio_stream_id`：本轮音频流 ID。

`session.start` 还可携带可选的 `user_id` 字段（受同一正则约束），用于后端会话归属与记忆关联；不发送也可正常工作。

ID 必须匹配正则：

```text
[A-Za-z0-9][A-Za-z0-9._:-]{0,127}
```

建议 Unity 使用 UUID 去掉连字符以外的非法字符，或使用类似：

```text
session_id: pico-session-<device-id>
turn_id: turn-<timestamp>-<counter>
audio_stream_id: audio-<timestamp>-<counter>
```

同一条连接上的所有后续消息必须携带相同的三个 ID。

### 3.2 正常顺序

```text
WebSocket connect
  → session.start
  ← voice.state(status=session.started)
  → 可选 scene.update
  ← voice.state(status=scene.updated)
  → 重复发送 audio.frame JSON + binary
  → audio.end
  ← ASR/状态/回答/TTS 事件
  → 正常关闭码 1000
```

Unity 必须分离“发送循环”和“接收循环”。接收循环不能等待 UI、音频播放或下一帧录音，否则会阻塞服务端事件和 TTS binary 的接收。

## 4. 后端必须提供给 Unity 负责人的部署信息

以下信息没有提供齐全前，Unity 负责人只能做协议模拟，不能完成 Pico 真机联调。

### 4.1 WebSocket 地址

后端负责人必须提供：

```text
ws://<后端电脑局域网 IP>:<固定端口>
```

例如：

```text
ws://192.168.1.20:8765
```

当前代码默认打印的是类似：

```text
ws://127.0.0.1:8765
```

该地址不能直接填入 Pico 4 客户端。后端需要在开发部署方案中明确：

1. 监听地址是后端电脑的局域网 IP 或明确的开发绑定地址；
2. 端口固定（默认 `8765`），避免每次启动后手工修改 Unity 配置；
3. Windows 防火墙允许该端口的局域网入站连接；
4. 电脑和 Pico 4 处于同一局域网，且网络允许设备互通；
5. 当前只支持明文 `ws://`，若要求 `wss://`，需要后端另行提供 TLS 终止方案；
6. 服务启动后能打印最终可复制的 WS URL、监听地址和配置快照 ID。

注意：`serve_voice()` 默认仍只允许 loopback host（`127.0.0.1` / `localhost` / `::1`），但已支持显式放开：`allow_non_loopback=True`（V1），`scripts/run_voice.py` 以 `--host <局域网 IP>` 自动触发并在启动时打印警告。提供局域网监听仍需评估暴露面，且必须配合 Windows 防火墙放行；不建议默认开启。

如果不允许放开局域网监听，则只能采用 USB `adb reverse`，由 Pico 访问设备侧 `127.0.0.1` 转发到电脑，不能作为无线演示方案。

### 4.2 服务就绪证明

服务在同一个监听端口上提供 HTTP `GET /health`（V2），返回 JSON，可直接作为远程就绪检查：

```text
GET http://<host>:8765/health
```

返回字段（与进程内 `health()` 完全一致）：

- `status`：`ok`（transport/provider 注册齐全且两个词典文件存在）或 `error`；
- `providers`：`transport` / `vad` / `asr` / `tts` 四个当前配置的 Provider 名；
- `registry_ready`：布尔，四个 Provider 是否都在注册表内；
- `lexicons`：`terminology` / `pronunciation` 两个词典文件是否存在；
- `voice_config_snapshot_id`：当前 `voice.yaml` 配置快照 ID；
- `capabilities`：`websocket_mock` / `offline` / `real_asr` / `real_tts` / `production_media_server` 能力标记。

该 endpoint 与 WebSocket 升级共用同一端口，不新增监听端口、不依赖额外服务。

Unity 不应依赖 `health()` 中的 `real_asr` / `real_tts` 字段判断真机能力：`health()` 仍是进程内方法，不是远程契约。不过当前实现的能力标记已改为直接从 `configs/voice.yaml` 的实际 Provider 配置派生（`asr_provider=whisper` → `real_asr=true`，`tts_provider=edge` → `real_tts=true`），不再是硬编码快照；但它只反映配置状态，不证明 ASR 模型已下载或 TTS 网络可达。

### 4.3 版本信息

后端负责人必须随地址一起提供：

- 协议版本，例如 `voice-ws-v1`；
- 后端 commit 或发布包版本；
- `configs/voice.yaml` 的关键音频配置；
- ASR Provider 是否已下载模型及模型语言；
- TTS Provider 是否需要外网访问；
- 当前支持的错误码和 WebSocket close code；
- 一条可重复的最小联调问题，例如“飞机的发动机是做什么的”。

## 5. 音频协议

### 5.1 Unity → 后端：输入音频

后端期望的音频是：

```text
编码：signed PCM 16-bit little-endian
采样率：16000 Hz
声道：1（mono）
帧时长：20 ms
每帧采样数：320
每帧 payload：640 bytes
```

Unity 麦克风通常得到 `float` 样本，必须转换为 PCM16：

```text
sample = clamp(floatSample, -1.0, 1.0)
pcm16 = round(sample * 32767)
按 little-endian 写入两个字节
```

建议每个 binary payload 正好为 640 bytes。服务端允许单帧不超过 64 KiB，但大帧会增加延迟，不应利用该上限发送整段录音。

### 5.2 `audio.frame` 必须二步成帧

每个音频帧必须先发送 JSON 控制帧，再发送恰好一条 binary：

```json
{
  "type": "audio.frame",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "audio_stream_id": "audio-001",
  "sequence": 0,
  "sample_rate": 16000,
  "channels": 1,
  "timestamp_ms": 0,
  "energy": 0.31
}
```

紧随其后的 binary 是该帧 PCM16 payload。下一条消息才能继续发送下一个 JSON header。

约束：

- 第一帧 `sequence` 必须为 `0`；
- 后续每帧必须严格递增 `1`；
- `timestamp_ms` 为非负整数，建议从本轮录音开始计时；
- `energy` 必须是 `0.0` 到 `1.0` 的有限数值（服务端只做格式校验；实际 VAD 使用的能量由服务端根据 PCM 重算，客户端无法伪造、也无需精确计算）；
- binary 不能为空；
- 不能发送没有 header 的 binary；
- 不能连续发送两个 `audio.frame` header；
- 不能跳号、重复或倒退发送 sequence；
- 控制 JSON 不要增加未定义字段；
- 控制 JSON 不超过 32 KiB，嵌套深度不超过 8。

### 5.3 结束输入

录音停止后发送：

```json
{
  "type": "audio.end",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "audio_stream_id": "audio-001"
}
```

`audio.end` 只表示客户端不再发送 PCM。它不等于“客户端已经得到回答”，Unity 仍必须继续接收服务端事件和 TTS binary，直到收到 `playback.completed`、`playback.failed`、`clarification.required` 或连接异常。

例外：如果已因超过单轮话语上限收到 `clarification.required(reason=utterance_too_long)`（见 7.3），服务端已将该轮音频输入标记为结束，此时不要再发送 `audio.end`，也不要再发送 `audio.frame`——两者都会触发 4400 协议错误并断连。

## 6. 可选场景状态

如果 Unity 当前选中了飞机、部件或热点，建议在发送音频前发送 `scene.update`，使后端能处理“这个部件”“它有什么作用”等指代问题。

```json
{
  "type": "scene.update",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "audio_stream_id": "audio-001",
  "scene_state": {
    "scene_state_id": "scene-001",
    "aircraft_id": "c919",
    "component_id": "engine",
    "hotspot_label": "发动机",
    "camera_view": "left-side",
    "visual_refs": [],
    "scene_confidence": 1.0,
    "selected_object_id": "engine-01",
    "candidate_object_ids": []
  }
}
```

`scene_state` 允许的字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `scene_state_id` | string | 可选，安全 ID |
| `aircraft_id` | string/null | 机型，如 `c919` |
| `component_id` | string/null | 部件标识，如 `engine` |
| `hotspot_label` | string/null | 展示给用户的热点名称 |
| `camera_view` | string/null | 当前观察视角 |
| `visual_refs` | string[] | 视觉资源标识列表 |
| `scene_confidence` | number | `0.0`–`1.0` |
| `selected_object_id` | string/null | 当前选中的 Unity 对象 ID |
| `candidate_object_ids` | string[] | 候选对象 ID 列表 |

未知字段会触发协议错误。没有场景状态时可以不发 `scene.update`，但涉及指代的问题可能收到 `clarification.required`。

## 7. 后端 → Unity：服务端事件

### 7.1 `voice.state`

```json
{
  "type": "voice.state",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "state": "LISTENING",
  "status": "frame_accepted"
}
```

`state` 可能是：

```text
IDLE
LISTENING
TRANSCRIBING
UNDERSTANDING
RETRIEVING
GENERATING
SPEAKING
INTERRUPTED
REWRITE
CLARIFY
```

`status` 是更细的事件状态，典型值包括：

```text
session.started
scene.updated
frame_accepted
partial
pre_retrieval
clarification
answered
audio.input_ended
playback.completed
playback.failed
barge_in.<status>
```

`session.started` 事件额外携带 `protocol_version: "voice-ws-v1"`（V4），客户端可据此做版本协商；其余所有 `voice.state` 事件不含该字段。

Unity UI 可将 `state` 映射为“聆听中、识别中、理解中、检索中、生成中、播报中、需要澄清、已停止”等显示状态，但不要仅凭 `audio.input_ended` 判断整轮结束。

### 7.2 ASR 事件

```json
{
  "type": "asr.partial",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "transcript": "飞机的发动机",
  "confidence": 0.82,
  "provider": "whisper"
}
```

`asr.final` 与此结构相同，只是 `type` 为 `asr.final`。客户端可显示转写文本，但：

- `asr.partial` 只能作为实时字幕，不能当作最终问题；
- 低置信或指代不清时，后端可能不进入正式 RAG，而是发送 `clarification.required`；
- Unity 不要自行把 `asr.partial` 拼装后再次调用另一套问答接口。

### 7.3 `clarification.required`

```json
{
  "type": "clarification.required",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "reason": "voice_input_unclear"
}
```

`reason` 是机器可读原因，不应直接当作自然语言播报。实际取值以后端实现为准，除 `voice_input_unclear` 外还包括 `scene_object_ambiguous`、`feedback_intent_unclear`、`session_timeout`、`pipeline_processing_failed`、`asr_final_missing` 等 20 余种；Unity 不应枚举穷举，统一按“需要澄清/重新提问”处理即可。

收到该事件后，Unity 应停止等待 TTS，提示用户重新提问或补充场景对象。具体澄清文案由 Unity UI 负责展示。

#### 特殊路径：`utterance_too_long`

当单轮录音超过上限（约 15 秒 / 750 帧 / 480000 字节，见第 2 节）时，服务端会发送：

```json
{
  "type": "clarification.required",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "reason": "utterance_too_long"
}
```

此时服务端已将该轮音频输入标记为结束，但 WebSocket 连接保持打开。Unity 必须：

1. 立即停止发送新的 `audio.frame`；
2. 不发送 `audio.end`（服务端已视为输入结束，再发送会触发 4400 断连）；
3. 提示用户缩短提问；
4. 由于三个绑定 ID 在连接生命周期内固定，开始新一轮需要正常关闭当前连接（close code `1000`），再用新的 `turn_id` / `audio_stream_id` 重新建立连接（`session_id` 可复用）。

### 7.4 `answer.display`

```json
{
  "type": "answer.display",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "answer_id": "answer_...",
  "evidence_package_id": "evidence_pkg_...",
  "answer": {
    "short_answer": "发动机为飞机提供推力。",
    "main_answer": "……",
    "source_binding": [],
    "display_blocks": [],
    "follow_up_questions": [],
    "safety_notes": []
  }
}
```

`answer` 是结构化对象，Unity 负责人不要假设只有一个固定字段。最小展示策略是：

1. 优先展示 `short_answer`；
2. 其次展示 `main_answer`；
3. `display_blocks` 存在时按其顺序展示；
4. `source_binding` 可用于“查看来源”入口，但不要求在语音播报中读出；
5. `answer_id` 和 `evidence_package_id` 作为日志关联 ID 保存，不要改写或拼接。

该事件只会在后端可信回答链路完成后发送。它不是模型原始输出，也不包含 Prompt、完整 trace 或后端异常堆栈。

### 7.5 `voice.error`

```json
{
  "type": "voice.error",
  "code": "VOICE_PROTOCOL_INVALID",
  "field": "sequence"
}
```

错误事件只保证机器码和字段，不保证有可直接面向用户的自然语言。Unity 应将错误映射为用户友好提示，并把原始 `code`/`field` 写入本地调试日志。

### 7.6 `barge_in.accepted`

```json
{
  "type": "barge_in.accepted",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "status": "stopped",
  "intent": "stop"
}
```

`status` 是打断处理结果，实际取值为 `stopped`（已停止播报）、`clarification`（取消未确认或意图不清，需澄清）、`rewritten`（已按反馈改写并生成替代播报）、`preference_recorded`（已记录临时偏好）；不存在 `accepted` 取值。`intent` 是解析出的用户意图：`stop`、`clarify`、`simplify`、`shorten`。后续 `voice.state` 的 `status` 为 `barge_in.<上述 status>`。

用户在 TTS 播放时点击“停止”或检测到新的打断意图时：

1. Unity 先立即停止本地 AudioSource；
2. 再发送 `session.cancel`；
3. 等待 `barge_in.accepted` 和后续 `voice.state`；
4. 旧一代 TTS binary 不应继续播放；
5. 如果后端产生替代回答，只播放打断事件之后的新一代音频。

发送格式：

```json
{
  "type": "session.cancel",
  "session_id": "pico-session-001",
  "turn_id": "turn-001",
  "audio_stream_id": "audio-001",
  "feedback": "停止"
}
```

`feedback` 可选，默认是“停止”，最长 256 个字符。

## 8. TTS binary 的当前限制与必须确认项

这是当前协议最需要后端负责人补充说明的部分。

当前服务端向 WebSocket 发送 TTS 时，发送的是裸 binary，没有伴随的 JSON 元数据。现有 wire 协议没有明确传递以下字段：

- 编码格式（MP3、PCM、WAV 或其他）；
- 采样率、声道数；
- `playback_id`；
- chunk sequence；
- 是否为最后一块；
- chunk 的播放时长；
- 当前音频属于哪一代播报。

`edge` Provider 的代码路径当前生成 MP3 音频片段。当前实现中每个 binary 是一段完整合成的 MP3 blob（每个语音段独立编码、整段发送），方案 A 的第 1 条前提已由实现满足；但“当前实现观察到的格式”仍不等于对 Unity 的稳定协议承诺。**方案 B 已实现（V3），下文以方案 B 为准，方案 A 不再适用。**

### 当前实现（V3）：tts.frame 已上线

每个 TTS binary 之前，服务端先发送一条 `tts.frame` JSON 文本帧，然后紧随一条 binary。实际字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | `string` | 固定 `"tts.frame"` |
| `session_id` | `string` | 当前绑定会话 ID |
| `turn_id` | `string` | 当前绑定轮次 ID |
| `playback_id` | `string` | 本次播报代次 ID |
| `sequence` | `int` | chunk 序号，从 0 递增 |
| `codec` | `string` | 音频编码：`mp3`（edge Provider）、`utf8_text`（mock 占位） |
| `duration_ms` | `int` | 该 chunk 预估播放时长 |
| `is_final` | `bool` | 是否为最后一块 |

JSON 与 binary 严格交替：`tts.frame` → binary → `tts.frame` → binary → …，最后一块的 `is_final=true`。

**不含 `sample_rate` / `channels` 的原因**：MP3 容器自带采样率与声道信息，且 mock 的 `utf8_text` 载荷没有可验证的采样率数值；为避免在 wire 层硬编码未经验证的数值，这两个字段不进入 `tts.frame`。Unity 对 `codec=mp3` 的 chunk 直接用系统 MP3 解码器，从容器读取采样率即可。

`codec` 由 TTS Provider 声明（edge→`mp3`、mock→`utf8_text`），传输层不做 provider→codec 硬编码映射。

### 方案 A：维持当前裸 binary 协议

> 已不适用：V3 起服务端始终按方案 B 发送 `tts.frame` + binary。本节保留仅为说明历史取舍。

### 方案 B：提供带元数据的 v2 音频帧协议

> 已实现（V3），见上文「当前实现（V3）：tts.frame 已上线」。早期的示例草稿含 `sample_rate` / `channels` 字段，正式实现按上文字段表为准（不含这两个字段）。

另外，当前实现为每个合成段设置了 10 秒流读取超时（edge-tts 为公共无凭据端点，防止网络抖动时整段音频锁死在会话内）。超时不会挂起连接，而是走 `voice.error(VOICE_TTS_ERROR)` + `voice.state(playback.failed)` 降级路径，Unity 不会收到该段音频，应按播放失败处理并保留文字回答。

Unity 收到 `tts.frame(codec=mp3)` 后按系统 MP3 解码器处理 binary；不要把 TTS binary 当作 PCM16 播放，否则会出现无法解码、噪声或播放中断。

## 9. WebSocket close code 与客户端处理

| close code | 含义 | Unity 行为 |
|---:|---|---|
| `1000` | 正常关闭 | 结束本轮，不提示网络错误 |
| `1009` | wire 消息过大 | 记录协议错误，缩小音频帧，不自动高频重试 |
| `4400` | 协议/schema 错误 | 停止当前发送，修正客户端字段后再重建会话 |
| `4401` | session/turn/audio stream 绑定不一致或重复占用 | 丢弃当前连接，重新生成绑定 ID |
| `4408` | 会话空闲/超时（默认 30 秒无入站消息） | 提示用户重新开始，重新建立连接 |
| 其他 | 网络或服务异常 | 进入断线重连状态，使用退避策略 |

### 推荐重连策略

- 只在网络错误、连接被动关闭或服务不可达时重连；
- 退避间隔建议 `1s → 2s → 4s → 8s`，上限 `10s`；
- 重连后不要复用上一次未完成的 `turn_id` 和 `audio_stream_id`；
- 如果旧连接仍可能存活，先等待或关闭旧连接，避免同一 `session_id` 触发 `4401`；
- 重连成功后发送新的 `session.start`，必要时重新发送最新 `scene.update`；
- 不自动重传已经发送过的 PCM，避免重复提问；
- WebSocket 未连接时，录音按钮应不可用或明确显示“服务未连接”。

## 10. Unity 负责人需要实现的客户端职责

### 10.1 连接层

- 可配置 WS URL，不把 IP 和端口写死在业务脚本；
- 独立接收循环，区分 JSON 文本和 binary；
- 连接超时、空闲超时和断线重连；
- 连接代次标识，防止旧连接事件更新新 UI；
- 关闭连接时发送 close code `1000`，并释放麦克风与 AudioSource。

### 10.2 录音层

- 请求并检查麦克风权限；
- 采集 16 kHz、mono；
- 转换为 PCM16 little-endian；
- 20 ms 分帧；
- 维护从 0 开始连续递增的 sequence；
- `energy` 字段填 `0.0`–`1.0` 内的有限值即可（服务端只做格式校验，实际能量由服务端根据 PCM 重算）；
- 录音停止后发送 `audio.end`；
- 不在本地持久化原始音频。

### 10.3 事件与状态层

- 维护 `IDLE/LISTENING/TRANSCRIBING/UNDERSTANDING/RETRIEVING/GENERATING/SPEAKING/INTERRUPTED/REWRITE/CLARIFY`；
- 将 `asr.partial` 作为临时字幕，将 `asr.final` 作为最终转写展示；
- 只用 `answer.display` 更新最终回答；
- 处理 `clarification.required`，含 `utterance_too_long` 特殊路径（停止发送音频、不发 `audio.end`，关闭连接后以新 `turn_id` 重连，见 7.3）；
- 处理 `voice.error`，不能把后端异常原文展示给用户；
- 以 `session_id + turn_id` 严格过滤事件。

### 10.4 播放层

- 按后端确认的 TTS codec 播放；
- 丢弃旧 playback generation 的音频；
- 收到 `barge_in.accepted` 前后正确切换播放代次；
- `playback.failed` 时保留文字回答并提示“语音播放失败”；
- 不把服务端 TTS binary 当作输入 PCM 重新解释。

### 10.5 日志层

建议只记录以下脱敏信息：

```text
连接时间、WS URL 的 host/port、session_id、turn_id、state/status、
ASR confidence、answer_id、evidence_package_id、错误 code/field、
音频帧数量、重连次数、首个 answer.display 延迟、首个 TTS binary 延迟。
```

不要记录：

- 原始 PCM；
- 完整私人 transcript；
- Prompt、密钥、后端异常堆栈；
- 未经授权的用户长期记忆内容。

## 11. 联调验收清单

### A. 后端负责人提供

- [ ] 固定且可达的 `ws://IP:PORT`；
- [ ] Pico 与后端电脑在同一网络的说明；
- [ ] 防火墙放行端口；
- [ ] 协议版本和后端版本；
- [ ] `sample_rate/channel_count/frame_duration_ms` 配置确认；
- [ ] TTS codec、采样率、声道和 binary 分片语义书面确认；
- [ ] 服务启动日志和最小联调命令；
- [ ] 真实 ASR 模型已安装并可加载；
- [ ] TTS Provider 的网络可用性确认；
- [ ] 服务端错误码和 close code 表；
- [ ] 一条可重复的航空问题和预期可接受结果。

### B. Unity 负责人验证

- [ ] Pico 能连接 `session.start` 并收到 `session.started`；
- [ ] 发送 10 帧连续 PCM 后，服务端返回 `frame_accepted`；
- [ ] `audio.end` 后仍能持续接收事件；
- [ ] 能显示 `asr.partial/asr.final`；
- [ ] 正常问题得到 `answer.display`；
- [ ] 能播放后端确认格式的 TTS；
- [ ] 低置信问题得到 `clarification.required`；
- [ ] 单轮录音超过 15 秒上限时收到 `utterance_too_long`，连接不断开，重连后可继续提问；
- [ ] 缺少字段时能显示客户端协议错误并停止当前 turn；
- [ ] Wi‑Fi 短暂断开后可退避重连；
- [ ] 播报中发送 `session.cancel` 后旧音频停止；
- [ ] TTS 失败时文字回答仍保留；
- [ ] 连续完成 10 轮问答无 session/turn 绑定冲突；
- [ ] 应用退出时麦克风、WebSocket、AudioSource 都能释放。

## 12. 当前明确的后端待办

以下事项不是 Unity 可以自行解决的客户端细节，应由后端负责人在交付联调地址前确认：

1. ~~跨设备监听地址~~：已完成（V1）——`serve_voice(..., allow_non_loopback=True)` 显式放开，`scripts/run_voice.py` 以 `--host` 触发；
2. ~~固定端口~~：已完成（V1）——默认 `127.0.0.1:8765`，`--port` 可配置；
3. ~~服务健康检查~~：已完成（V2）——同端口 HTTP `GET /health`，见 4.2；
4. ~~TTS binary contract~~：已完成（V3）——`tts.frame` + binary 交替，见 8；
5. ~~协议版本~~：已完成（V4）——`session.started` 携带 `protocol_version: "voice-ws-v1"`，见 7.1；
6. 生产/演示边界：当前语音服务仍是本地原型部署，不应直接宣称为生产服务；
7. 外网依赖：`edge-tts` 需要后端运行环境具备可用网络，Pico 不需要直接访问 TTS Provider。

在这些事项完成前，Unity 可先用本机浏览器前端（`scripts/run_frontend.py` 启动静态页，配合 `scripts/run_voice.py`）或模拟 WebSocket 做协议开发。浏览器前端已实现完整的 PCM 采集分帧、二步成帧和事件处理，可直接作为客户端协议参考实现，但不能宣称 Pico 4 真机端到端接入已经完成。

## 13. 参考文件

- 后端协议基线：`docs/接口与部署/api_contracts.md`
- 后端部署检查：`docs/接口与部署/deployment_checklist.md`
- WebSocket 服务：`src/voice/websocket_server.py`
- 音频传输：`src/voice/transport.py`
- 语音状态机：`src/voice/session_state.py`
- 语音配置：`configs/voice.yaml`
- 本地启动入口：`scripts/run_voice.py`
- 浏览器前端静态服务：`scripts/run_frontend.py`
- 浏览器协议参考实现：`assets/voice/index.html`、`assets/voice/pcm-processor.js`
- 语音服务单元测试：`tests/unit/voice/test_voice_websocket_server.py`（V1/V2/V4 绑定、健康检查与协议版本用例）、`tests/unit/voice/test_voice_transport.py`（V3 tts.frame 传输用例）
