# Pico 4 语音后端联调专项 · 执行规格（spec.md）

> 与 `task.md` 任务编号一一对应。所有命令均在 PowerShell（Windows）下于项目根目录 `D:\APP\Python 3.13\挑战杯` 执行。
>
> 统一测试入口：`.venv\Scripts\python.exe -m pytest`（使用项目既有虚拟环境，全程不创建新环境、不对 .venv 安装/卸载任何包）。
>
> 行号以 2026-08-17 仓库状态为准，执行时若行号漂移，以"锚点代码"定位。

---

## V0 确认项目 .venv 测试环境并固化测试基线

### 步骤

1. 确认 git 基线干净：
   ```powershell
   git status --short; git log --oneline -3
   ```
   预期：工作区无未提交变更（若有，停止并报告，不得覆盖）。

2. 确认项目既有 `.venv` 存在且可用：
   ```powershell
   Test-Path .venv\Scripts\python.exe    # 预期 True
   .venv\Scripts\python.exe -V           # 预期 Python 3.13.x
   ```
   若 `.venv` 不存在或不可用：立即停止并向用户报告，禁止创建任何新虚拟环境。

3. 确认关键依赖在场（只读检查，禁止 pip install）：
   ```powershell
   .venv\Scripts\python.exe -m pip list | Select-String -Pattern "websockets|pytest|httpx"
   ```
   预期：`websockets 15.0.1`、`pytest`（版本以实际为准并记录）、`httpx` 在列。任一缺失：停止并报告。

4. 运行全量测试并记录基线：
   ```powershell
   .venv\Scripts\python.exe -m pytest
   ```
   预期：全部通过。记录输出末尾的 `N passed` 为基线通过数 `B`，连同 pytest/websockets 版本一并记入 STATUS.md。

### 改动前后行为对比

- 改动前：仓库无本专项的固化基线，回归无对照数字。
- 改动后：基线通过数 `B` 与环境版本信息固化于 STATUS.md，成为 V1–V6 的回归判据；`.venv` 零改动。

### 验证方式

- 步骤 2/3 的版本与依赖输出；
- 步骤 4 的 `B passed, 0 failed`；
- `git status --short` 仅显示 STATUS.md（本任务唯一允许变更文件）；
- `Test-Path .venv_pico_fix` 为 False（证明未创建新环境）。

提交信息：`V0 确认项目测试环境并记录测试基线`

---

## V1 固定端口与非回环监听支持

### 步骤

1. 修改 `src/voice/websocket_server.py` 的 `serve_voice()`（锚点：`async def serve_voice(`，约 L951）：
   - 签名追加 keyword-only 参数：`*, allow_non_loopback: bool = False`（放在 `tts_config` 之后）；
   - 替换 host 校验（锚点 L982-983）：
     ```python
     # 改动前
     if host not in {"127.0.0.1", "localhost", "::1"}:
         raise ValueError("voice mock server must bind to a loopback host")
     # 改动后
     if (
         host not in {"127.0.0.1", "localhost", "::1"}
         and not allow_non_loopback
     ):
         raise ValueError(
             "voice mock server must bind to a loopback host unless "
             "allow_non_loopback is explicitly set"
         )
     if isinstance(allow_non_loopback, bool) is False:
         raise ValueError("allow_non_loopback must be a boolean")
     ```

2. 修改 `scripts/run_voice.py`：
   - 新增 `import argparse`；
   - 将 `HOST = "127.0.0.1"` 常量删除，改为 `main()` 内 argparse：
     ```python
     parser = argparse.ArgumentParser(description="翼览无余 语音 WebSocket 服务")
     parser.add_argument("--host", default="127.0.0.1")
     parser.add_argument("--port", type=int, default=8765)
     args = parser.parse_args()
     ```
   - `serve_voice(...)` 调用处传入 `host=args.host, port=args.port`，当 `args.host` 不在 `{"127.0.0.1", "localhost", "::1"}` 时传 `allow_non_loopback=True` 并打印一行警告：`[警告] 已允许非回环监听（{host}），请确认网络边界与防火墙`；
   - 启动打印块（锚点 `print(f"listening on ws://{HOST}:{port}")`）改为多行输出，必须包含：最终 `ws://<host>:<port>`、`配置快照 ID: {settings.snapshot_id}`。

3. 新增 `tests/unit/voice/test_voice_websocket_server.py`，至少包含 3 个用例：
   - `test_serve_voice_rejects_non_loopback_by_default`：`pytest.raises(ValueError)` 断言 `serve_voice("0.0.0.0", 0)` 抛错（用最小 settings 构造 orchestrator，参照 tests/e2e/test_voice_flow.py 中 orchestrator 的构造方式）；
   - `test_serve_voice_allows_non_loopback_when_opted_in`：`serve_voice("0.0.0.0", 0, orchestrator, allow_non_loopback=True)` 返回 server 后立即 `server.close(); await server.wait_closed()`，不断言异常；
   - `test_serve_voice_rejects_invalid_allow_non_loopback_type`：`allow_non_loopback="yes"` 时抛 `ValueError`。

### 改动前后行为对比

| 场景 | 改动前 | 改动后 |
|---|---|---|
| `serve_voice("0.0.0.0", 0)` | `ValueError` | `ValueError`（不变） |
| `serve_voice("0.0.0.0", 0, allow_non_loopback=True)` | 不存在该参数 | 成功绑定 |
| `python scripts/run_voice.py` | 随机端口，仅打印一行 | 默认 `127.0.0.1:8765`，打印 URL+快照 ID |

### 验证方式

```powershell
.venv\Scripts\python.exe -m pytest tests/unit/voice/test_voice_websocket_server.py -v
.venv\Scripts\python.exe -m pytest tests/e2e/test_voice_flow.py tests/e2e/scenarios/test_voice_flow.py tests/integration/voice_loop/test_disconnect_reconnect.py
# 手动冒烟（启动 5 秒后 Ctrl+C 终止，检查输出含 ws://127.0.0.1:8765 与 配置快照 ID）：
.venv\Scripts\python.exe scripts\run_voice.py
.venv\Scripts\python.exe -m pytest   # 全量回归
```

提交信息：`V1 语音服务支持固定端口与显式非回环监听`

---

## V2 WebSocket 同端口 HTTP /health 健康检查

### 步骤

1. `src/voice/websocket_server.py`：将 `VoiceWebSocketServer.health()`（锚点 L157-194）的方法体抽取为模块级函数：
   ```python
   def _build_health_payload(orchestrator: VoiceSessionOrchestrator) -> dict[str, Any]:
       # 原 health() 体内逻辑原样迁入，返回 dict
   ```
   `health()` 改为 `return _build_health_payload(self._orchestrator)`。逻辑零变化。

2. `serve_voice()` 内（锚点：`server = await serve(` 之前）定义并挂接 `process_request`：
   ```python
   async def process_request(connection, request):
       if request.path == "/health":
           payload = _build_health_payload(orchestrator)
           response = connection.respond(200, json.dumps(payload, ensure_ascii=False))
           response.headers["Content-Type"] = "application/json"
           return response
       return None

   server = await serve(
       handler, host, port,
       max_size=MAX_WIRE_MESSAGE_BYTES,
       compression=None,
       process_request=process_request,
   )
   ```
   说明：websockets 15 的 asyncio `serve` 对普通 HTTP GET 先调用 `process_request`，返回非 None 即短路升级流程；`connection.respond(status, text)` 是该版本 `ServerConnection` 的标准辅助方法。

3. `scripts/run_voice.py` 启动打印块追加一行：`健康检查: http://<host>:<port>/health`。

4. `tests/unit/voice/test_voice_websocket_server.py` 追加用例 `test_health_endpoint_serves_payload`：
   - 启动 `serve_voice("127.0.0.1", 0, orchestrator)`；
   - 用项目已有依赖 httpx：`async with httpx.AsyncClient() as client: resp = await client.get(f"http://127.0.0.1:{port}/health")`；
   - 断言 `resp.status_code == 200`、`resp.headers["content-type"].startswith("application/json")`、body JSON 含 `status/providers/capabilities/voice_config_snapshot_id` 四个键；
   - `finally` 中关闭 server。

### 改动前后行为对比

| 请求 | 改动前 | 改动后 |
|---|---|---|
| `GET /health`（HTTP） | 被当作非法 WebSocket 升级，连接失败 | 200 + health JSON |
| WebSocket 升级（任意路径） | 正常握手 | 正常握手（process_request 返回 None 放行） |
| 进程内 `server.health()` | 返回 dict | 逐字段一致（仅实现位置重构） |

### 验证方式

```powershell
.venv\Scripts\python.exe -m pytest tests/unit/voice/test_voice_websocket_server.py -v
.venv\Scripts\python.exe -m pytest tests/unit/voice tests/e2e tests/integration/voice_loop
# 手动冒烟：启动 run_voice.py 后另开终端：
Invoke-RestMethod http://127.0.0.1:8765/health
.venv\Scripts\python.exe -m pytest   # 全量回归
```

提交信息：`V2 语音服务同端口提供 HTTP /health 健康检查`

---

## V3 TTS 音频帧元数据协议（tts.frame）

### 步骤

1. `src/voice/contracts.py` 的 `TTSChunk`（锚点 L576-591）：新增字段 `codec: str = ""`（置于 `is_final` 之后），`__post_init__` 追加校验：
   ```python
   if not isinstance(self.codec, str) or len(self.codec) > 32:
       raise VoiceContractError("codec", "must be a string of at most 32 characters")
   ```
   语义：空串 = 未声明编码（旧路径），非空 = wire 上可用的编码标识。

2. `src/voice/tts.py`：
   - `PlaybackHandle.__init__`（锚点 L38-46）新增 keyword-only 参数 `audio_codec: str = ""`，校验为长度 ≤32 的字符串后存为 `self.audio_codec`（空串 = 未声明编码，向后兼容既有不传该参数的调用方）；
   - `chunks()` 中 `TTSChunk(...)` 构造处（锚点 L236-244）追加 `codec=self.audio_codec`；
   - `MockTTSProvider.synthesize_stream` 的 `PlaybackHandle(...)`（锚点 L391-397）追加 `audio_codec="utf8_text"`（mock 载荷为 `segment.encode("utf-8")` 文本占位，见 `_encode_segment` L399-411）。

3. `src/voice/edge_tts.py`：`synthesize_stream` 的 `PlaybackHandle(...)`（锚点 L107-113）追加 `audio_codec="mp3"`（每段为完整 MP3 blob）。

4. `src/voice/transport.py`：
   - `_QueuedAudioTransport.__init__`（锚点 L185-218）在构造 validator 的同时保存 `self.bound_session_id = session_id`、`self.bound_turn_id = turn_id`；
   - `WebSocketAudioTransport.send_audio`（锚点 L322-328）在发送 payload 前，若 `chunk.codec` 非空，先发送 JSON 头：
     ```python
     if chunk.codec:
         header = json.dumps(
             {
                 "type": "tts.frame",
                 "session_id": self.bound_session_id or "",
                 "turn_id": self.bound_turn_id or "",
                 "playback_id": chunk.playback_id,
                 "sequence": chunk.sequence_no,
                 "codec": chunk.codec,
                 "duration_ms": chunk.duration_ms,
                 "is_final": chunk.is_final,
             },
             ensure_ascii=False,
             separators=(",", ":"),
         )
         await self._call_socket(self._socket.send, header)
     await self._call_socket(self._socket.send, chunk.payload)
     ```
     文件顶部追加 `import json`。字段不含 sample_rate/channels：MP3 容器自带采样率信息，禁止硬编码未经验证的数值。
   - **JSON 头以 `str` 文本帧发送（不 `.encode("utf-8")`）**：websockets 协议中 `str`→text frame、`bytes`→binary frame。浏览器 `onmessage` 中 text frame 返回 `string`、binary frame 返回 `Blob`。若 JSON 头按 bytes 发送，客户端无法将其与 MP3 binary 区分（两者都是 binary/Blob）。发送 `str` 使客户端可通过消息类型区分 JSON 元数据与音频 binary。
   - 注意：JSON 头与 binary 均经 `_ConnectionSocketFacade.send`（websocket_server.py L117-121）串行化，顺序天然保证；打断暂停门（pause_audio）只作用于 bytes，JSON 头不被门控，与现有打断语义兼容。

5. `tests/unit/voice/test_voice_transport.py` 追加用例（不修改既有用例）：
   - `test_websocket_transport_emits_tts_frame_header`：用记录型 fake socket（`send` 把入参 append 到 list），构造 `TTSChunk(..., codec="utf8_text", ...)` 经 `WebSocketAudioTransport(socket=fake, session_id=..., turn_id=...).send_audio(chunk)`，断言 fake 收到恰好 2 条：第 1 条为 `str`（text frame）且 `json.loads` 后字段齐全（含 `sequence == chunk.sequence_no`、`is_final` 布尔），第 2 条为原始 payload（bytes/binary）；
   - `test_websocket_transport_skips_header_without_codec`：`codec=""`（缺省）时 fake 只收到 1 条 payload——证明既有路径不受影响。

6. `tests/e2e/scenarios/test_voice_websocket_flow.py`：先执行
   ```powershell
   git grep -n "binary\|bytes\|recv" -- tests/e2e/scenarios/test_voice_websocket_flow.py
   ```
   定位服务端下行音频断言；将"连续 N 条 binary"的预期更新为"`tts.frame` JSON 与 binary 交替、JSON `codec == "utf8_text"`、末块 `is_final == True`"。若该文件不断言下行 binary，则在 `tests/unit/voice/test_voice_websocket_server.py` 中补 `test_e2e_style_playback_emits_frames`（复用其 client fixture 模式）。

7. `assets/voice/index.html`：检查 `handleJson(event)` 分发逻辑（锚点 L291-301 `socket.onmessage`）：
   - 若未知 `type` 落入静默 default 分支：不改文件，仅在提交说明中记录验证结论；
   - 若 default 分支抛错/弹错：在分发处新增 `case "tts.frame": break;`（或等价忽略分支），不改播放逻辑（binary 仍按整段 MP3 入队，锚点 L296-299、L586-603）。

### 改动前后行为对比

| 场景 | 改动前 | 改动后 |
|---|---|---|
| WebSocket 下行（mock TTS） | 仅 binary | `tts.frame` JSON + binary 交替 |
| WebSocket 下行（edge TTS） | 仅 binary（MP3） | JSON（codec=mp3）+ binary 交替 |
| TTSChunk 缺省构造/InMemory transport | 存 chunk | 行为不变（codec="" 不触发 JSON） |
| 浏览器前端 | 播放 binary | 不变（未知 JSON 忽略或显式忽略） |

### 验证方式

```powershell
.venv\Scripts\python.exe -m pytest tests/unit/voice/test_voice_transport.py -v
.venv\Scripts\python.exe -m pytest tests/e2e/scenarios/test_voice_websocket_flow.py -v
.venv\Scripts\python.exe -m pytest   # 全量回归，通过数 >= 基线 B
```

提交信息：`V3 TTS 下行增加 tts.frame 元数据帧实现方案B协议`

---

## V4 协议版本字段（protocol_version）

### 步骤

1. `src/voice/websocket_server.py`：模块常量区（锚点 L24-30 附近）新增 `PROTOCOL_VERSION = "voice-ws-v1"`。

2. `_send_voice_state`（锚点 L694-707）追加可选参数并在事件 dict 中合并：
   ```python
   async def _send_voice_state(
       self, binding: _Binding, status: str, *, extra: dict[str, Any] | None = None
   ) -> None:
       ...
       event = {...}
       if extra:
           event.update(extra)
       await self._send_event(event)
   ```

3. `_start_session` 末尾（锚点 L380）改为：
   ```python
   await self._send_voice_state(
       binding, "session.started", extra={"protocol_version": PROTOCOL_VERSION}
   )
   ```
   其余 `_send_voice_state` 调用点（scene.updated/frame_accepted/playback.* 等）不传 extra，事件不含该字段。

4. `tests/unit/voice/test_voice_websocket_server.py` 追加：
   - `test_session_started_carries_protocol_version`：连接后发送 `session.start`，收到的首条 `voice.state` 事件 `status=="session.started"` 且 `protocol_version=="voice-ws-v1"`；
   - `test_other_voice_states_do_not_carry_protocol_version`：发送 `scene.update` 后收到的 `scene.updated` 事件不含 `protocol_version` 键。

### 改动前后行为对比

- 改动前：`session.started` 仅有 `type/session_id/turn_id/state/status`。
- 改动后：仅该事件额外携带 `protocol_version: "voice-ws-v1"`，其他事件字段集不变。

### 验证方式

```powershell
.venv\Scripts\python.exe -m pytest tests/unit/voice/test_voice_websocket_server.py -v
.venv\Scripts\python.exe -m pytest   # 全量回归
```

提交信息：`V4 session.started 事件携带协议版本 voice-ws-v1`

---

## V5 接口文档同步

### 步骤

1. 定位 `api_contracts.md` 中的语音 WS 契约小节：
   ```powershell
   git grep -n "serve_voice\|voice.state\|audio.frame\|tts" -- docs/接口与部署/api_contracts.md
   ```
   若存在语音 WS 小节：同步三处——启动地址（默认 8765 可配置）、`tts.frame` 帧格式（字段同 V3 步骤 4 的 JSON）、`session.started` 的 `protocol_version` 字段。若不存在：仅记录"无语音 WS 小节，无需同步"于提交说明。

2. 修改 `docs/接口与部署/pico4_unity_voice_integration.md`：
   - §1（锚点"当前后端可供客户端对接的入口"）：`run_voice.py` 行改为"默认 `ws://127.0.0.1:8765`，`--host/--port` 可配置"；删除"使用随机端口"表述；
   - §4.1（锚点"注意：当前 `serve_voice()` 在代码层面校验 host"）："属于后端待办"改为"已支持 `allow_non_loopback` 显式放开（V1），run_voice.py 以 `--host` 触发"；
   - §4.2：三种就绪证明选项更新为"推荐直接使用 `GET /health`（V2）"，附字段列表 `status/providers/registry_ready/lexicons/voice_config_snapshot_id/capabilities`；
   - §7.1：`voice.state` 示例后补一句"`session.started` 事件额外携带 `protocol_version: "voice-ws-v1"`（V4）"；
   - §8：标题段后新增小节"当前实现（V3）：tts.frame 已上线"，给出实际 JSON 字段表（type/session_id/turn_id/playback_id/sequence/codec/duration_ms/is_final），说明 codec 取值 `mp3`（edge）/`utf8_text`（mock 占位），并说明不含 sample_rate/channels 的原因；原"方案 A/方案 B 二选一"表述改为"方案 B 已实现，方案 A 不再适用"；
   - §12：待办 1/2/3/4/5 逐条标注"已完成（V1/V1/V2/V3/V4）"；仅保留第 6/7 条为持续事项；
   - §13：参考文件追加 `tests/unit/voice/test_voice_websocket_server.py`。

3. 清理过时表述（对整个文件执行，预期均无输出）：
   ```powershell
   git grep -n "随机端口\|随机分配\|没有远程 HTTP health\|缺少 codec/sequence" -- docs/接口与部署/pico4_unity_voice_integration.md docs/接口与部署/api_contracts.md
   ```

### 改动前后行为对比

- 改动前：文档描述 5 项能力为"待办"，与代码不一致。
- 改动后：文档与 V1–V4 代码行为一致，每项均标注对应任务编号，可追溯。

### 验证方式

- 步骤 3 的 grep 无匹配；
- 人工复核 §8 新增小节字段名与 V3 步骤 4 的 JSON 键完全一致；
- `git diff --stat` 仅含两个文档文件。

提交信息：`V5 接口文档同步V1至V4行为变化`

---

## V6 STATUS.md 专项记录与全量回归

### 步骤

1. 在 `docs/项目总控/STATUS.md` 文件末尾追加小节 `## 语音后端联调专项（V0–V7）`，按 AGENTS.md 完成标准逐项填写：
   - 阶段编号：语音后端联调专项 V0–V7；
   - 已完成任务：V0–V6 概要（一行一个）；
   - 修改文件列表：`src/voice/websocket_server.py`、`src/voice/transport.py`、`src/voice/contracts.py`、`src/voice/tts.py`、`src/voice/edge_tts.py`、`scripts/run_voice.py`、`assets/voice/index.html`（若 V3 步骤 7 判定需改）、`docs/接口与部署/pico4_unity_voice_integration.md`、`docs/接口与部署/api_contracts.md`（若含语音小节）、`docs/项目总控/STATUS.md`；
   - 新增文件列表：`tests/unit/voice/test_voice_websocket_server.py`；
   - 删除文件列表：无（V7 执行后补记实际删除的临时产物，若有）；
   - 测试命令：`.venv\Scripts\python.exe -m pytest`；
   - 测试结果：最终全量通过数与基线 B 的对比；
   - 是否违反 harness.md：逐任务声明"否"或列出偏离；
   - 未完成事项：如实填写（如"Pico 真机联调未执行，属客户端侧工作"）；
   - 下一阶段是否可以开始：写明"Unity 侧可依据更新后文档开始真机联调"。

2. 执行最终全量回归：
   ```powershell
   .venv\Scripts\python.exe -m pytest
   ```

3. 确认历史记录未被改动：
   ```powershell
   git diff -- docs/项目总控/STATUS.md | Select-String -Pattern "^-[^-]"
   ```
   预期：无删除行输出（纯追加）。

### 改动前后行为对比

- 改动前：STATUS.md 无本专项任何记录。
- 改动后：新增完整阶段记录，历史 P0–P8/G0–G8 内容零改动。

### 验证方式

- 步骤 2 全量通过（数字回填步骤 1 后再提交）；
- 步骤 3 无删除行。

提交信息：`V6 STATUS记录语音后端联调专项并完成全量回归`

---

## V7 清理收尾与环境确认

### 步骤

1. 死代码与临时标记检查（对 V1–V6 全部改动文件执行，预期无输出）：
   ```powershell
   git diff --name-only HEAD~6..HEAD | ForEach-Object { git grep -n -E "TODO|FIXME|XXX" -- $_ }
   ```
   同时人工复核 diff 中不存在被注释掉的旧代码块与未使用的导入（重点：`websocket_server.py` 的 `json` 已在 V2 前导入则 V3 不得重复导入；`run_voice.py` 删除 `HOST` 常量后无残留引用）。

2. 若步骤 1 发现问题：仅允许在对应任务的 harness 允许清单内修复，修复后重跑该任务验证命令与全量测试，新增修复提交（信息如 `V7 修复V3遗留的未使用导入`）。

3. 临时产物清理与环境确认（不删除 `.venv`，本项目测试环境保留）：
   ```powershell
   Test-Path .venv_pico_fix      # 预期 False（全程未创建新虚拟环境）
   Test-Path .venv               # 预期 True（保留）
   Get-ChildItem -Force | Where-Object { $_.Name -match "^\.venv" }   # 仅 .venv 一项
   ```
   检查项目根目录与 docs 下无执行期间产生的临时文件（.bak、.orig、.tmp、副本文件等），发现即删除并在 STATUS.md"删除文件列表"补记。

4. 最终状态确认与收尾提交：
   ```powershell
   git status --short        # 预期干净
   git log --oneline -8      # 预期 V0..V6 + 收尾提交，全部中文
   ```

### 改动前后行为对比

- 改动前：工作区可能有零散变更或临时产物。
- 改动后：git 历史为每任务一个中文提交，工作区干净，无任何临时产物；`.venv` 原样保留且全程零包变更。

### 验证方式

- `Test-Path .venv_pico_fix` 返回 `False`、`Test-Path .venv` 返回 `True`；
- `git status --short` 无输出；
- `git log` 近 8 条提交信息全为中文且与任务一一对应。

提交信息：`V7 清理临时产物并收尾语音后端联调专项`
