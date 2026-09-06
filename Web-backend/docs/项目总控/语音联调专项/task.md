# Pico 4 语音后端联调专项 · 任务拆解（task.md）

> 项目根目录：`D:\APP\Python 3.13\挑战杯`
>
> 本三文档保存于 `docs/项目总控/语音联调专项/`（task.md / spec.md / harness.md）。
>
> 背景：`docs/接口与部署/pico4_unity_voice_integration.md` 复核确认，语音 WebSocket 服务存在 5 项阻碍 Pico 4 真机联调的后端缺口（loopback 强制、随机端口、无远程健康检查、TTS 裸 binary 无元数据、无协议版本字段）。本计划将其拆解为 V0–V7 共 8 个任务。
>
> 测试环境约定：直接使用项目既有虚拟环境 `.venv`，全程不创建任何新虚拟环境、不对 `.venv` 安装或卸载任何包（只读使用）。
>
> 配套文档：执行规格见 `spec.md`，约束规范见 `harness.md`，三者按任务编号（V0–V7）交叉引用。
>
> 任务编号前缀 V 取自 voice，与历史 P0–P8、G0–G8、W0–W7 阶段互不冲突，不创建新 P 阶段，不覆盖任何历史记录。

## 任务总表与执行顺序

| 编号 | 任务 | 优先级 | 前置依赖 |
|---|---|---|---|
| V0 | 确认项目 .venv 测试环境并固化测试基线 | 高 | 无 |
| V1 | 固定端口与非回环监听支持 | 高 | V0 |
| V2 | WebSocket 同端口 HTTP /health 健康检查 | 高 | V1 |
| V3 | TTS 音频帧元数据协议（tts.frame） | 高 | V2 |
| V4 | 协议版本字段（protocol_version） | 中 | V3 |
| V5 | 接口文档同步 | 中 | V4 |
| V6 | STATUS.md 专项记录与全量回归 | 中 | V5 |
| V7 | 清理收尾与环境确认 | 高 | V6 |

执行顺序为严格串行 V0 → V1 → … → V7。V1–V4 均修改 `src/voice/websocket_server.py` 或其紧密关联文件，禁止并行实施；多 agent 并行仅允许用于"任务复核"（见执行提示词），不允许用于并行改代码。

---

## V0 确认项目 .venv 测试环境并固化测试基线

- **目标**：确认项目既有 `.venv` 可用且依赖齐全，运行全量测试并记录基线结果，作为后续每个任务的回归对照。不创建任何新虚拟环境。
- **优先级**：高。
- **预计改动范围**：不修改任何源码文件；仅在 `docs/项目总控/STATUS.md` 追加基线记录。
- **验收标准**：
  1. `.venv\Scripts\python.exe -V` 输出 3.13.x；`pip list` 含 `websockets` 15.0.1 与 `pytest`（版本以 .venv 实际为准并记录；若任一依赖缺失，立即停止并报告，禁止 pip install 补装）；
  2. 全量 `.venv\Scripts\python.exe -m pytest` 通过（无失败，通过数记为基线值 B）；
  3. 基线摘要（通过/跳过数量、pytest 与 websockets 版本）已写入 `docs/项目总控/STATUS.md` 的 V0 小节；
  4. `git status` 显示除 STATUS.md 外无其他变更；确认未创建任何新虚拟环境目录（如 `.venv_pico_fix` 不存在）。
- **依赖**：无。

## V1 固定端口与非回环监听支持

- **目标**：`serve_voice()` 支持（显式 opt-in 的）非回环绑定；`scripts/run_voice.py` 提供 `--host`/`--port` 参数，端口默认固定为 8765，并在启动时打印最终 WS URL 与配置快照 ID。Pico 4 真机联调的前提条件之一。
- **优先级**：高。
- **预计改动范围**：`src/voice/websocket_server.py`（serve_voice 签名与 host 校验，约 5 行）、`scripts/run_voice.py`（argparse 与打印，约 25 行）、新增 `tests/unit/voice/test_voice_websocket_server.py`。
- **验收标准**：
  1. `serve_voice(host="0.0.0.0", port=0)` 不带新参数仍抛 `ValueError`（默认安全行为不变）；
  2. `serve_voice(host="0.0.0.0", port=0, allow_non_loopback=True)` 可成功绑定并可关闭；
  3. 现有三个 `serve_voice("127.0.0.1", 0, ...)` 调用方（tests/e2e/test_voice_flow.py:189、tests/e2e/scenarios/test_voice_flow.py:173、tests/integration/voice_loop/test_disconnect_reconnect.py:228）不改一行仍通过；
  4. `.venv\Scripts\python.exe scripts/run_voice.py` 不带参数启动后输出 `ws://127.0.0.1:8765`；
  5. 新增单元测试全部通过，全量回归无失败。
- **依赖**：V0。

## V2 WebSocket 同端口 HTTP /health 健康检查

- **目标**：利用 websockets 15 的 `process_request` 钩子，在同一监听端口上提供 `GET /health`，返回与进程内 `health()` 完全一致的 JSON，使 Unity/Pico 可远程做就绪检查。不新增端口、不新增依赖。
- **优先级**：高。
- **预计改动范围**：`src/voice/websocket_server.py`（health() 逻辑抽取为模块级函数 + process_request 闭包，约 35 行）、`scripts/run_voice.py`（启动打印追加 health URL 行，约 2 行）、`tests/unit/voice/test_voice_websocket_server.py`（追加用例）。
- **验收标准**：
  1. 服务运行时 `GET http://127.0.0.1:<port>/health` 返回 200、`Content-Type: application/json`，body 含 `status`、`providers`、`capabilities`、`voice_config_snapshot_id` 字段；
  2. `VoiceWebSocketServer.health()` 返回值结构与改动前逐字段一致（现有测试不破坏）；
  3. WebSocket 升级请求路径不受影响（既有 e2e/integration 测试全通过）；
  4. 新增 /health 单元测试通过。
- **依赖**：V1（同文件串行，且打印行依赖固定端口）。

## V3 TTS 音频帧元数据协议（tts.frame）

- **目标**：落地 pico4 文档第 8 节"方案 B"——每个 TTS binary 之前先发送 `tts.frame` JSON 元数据（playback_id/sequence/codec/duration_ms/is_final 及绑定 ID），使 Unity 能区分音频代际、顺序与编码格式。`codec` 由 TTS Provider 声明（edge→`mp3`，mock→`utf8_text`），不在传输层硬编码。
- **优先级**：高。
- **预计改动范围**：`src/voice/contracts.py`（TTSChunk 增 `codec` 字段，约 4 行）、`src/voice/tts.py`（PlaybackHandle 增 `audio_codec` 参数并写入 chunk，约 8 行；MockTTSProvider 传 `utf8_text`）、`src/voice/edge_tts.py`（EdgeTTSProvider 传 `mp3`，1 行）、`src/voice/transport.py`（WebSocketAudioTransport.send_audio 先发 JSON 头，约 20 行）、`tests/unit/voice/test_voice_transport.py`（追加用例）、`tests/e2e/scenarios/test_voice_websocket_flow.py`（更新服务端下行断言）、`assets/voice/index.html`（确认未知 JSON 类型被忽略，若 default 分支抛错则加忽略分支）。
- **验收标准**：
  1. WebSocket 客户端在 mock provider 下收到的下行序列为：`tts.frame` JSON 与 binary 严格交替，JSON 含 `type/session_id/turn_id/playback_id/sequence/codec/duration_ms/is_final`，`codec="utf8_text"`，最后一块 `is_final=true`；
  2. `TTSChunk` 缺省构造（不带 codec）时传输层不发送 JSON 头——既有 InMemory/单元测试路径行为不变；
  3. `tests/unit/voice/test_voice_transport.py` 既有用例不修改断言仍通过（用缺省 codec 构造的用例），新增 tts.frame 用例通过；
  4. e2e 语音流测试更新后通过；
  5. `assets/voice/index.html` 在收到 `tts.frame` 时不报错、MP3 播放路径不变；
  6. 全量回归无失败。
- **依赖**：V2（同文件串行）。

## V4 协议版本字段（protocol_version）

- **目标**：`session.started` 事件携带 `"protocol_version": "voice-ws-v1"`，为客户端提供可配置的版本协商依据（pico4 文档 §12 待办第 5 条）。
- **优先级**：中。
- **预计改动范围**：`src/voice/websocket_server.py`（模块常量 + `_send_voice_state` 可选 extra 参数 + `_start_session` 调用处，约 8 行）、`tests/unit/voice/test_voice_websocket_server.py`（追加断言）。
- **验收标准**：
  1. `session.start` 的响应事件 JSON 中 `voice.state` 项含 `status=session.started` 且 `protocol_version=="voice-ws-v1"`；
  2. 其余所有 `voice.state` 事件不含该字段（仅 session.started 携带）；
  3. 新增断言通过，全量回归无失败。
- **依赖**：V3（同文件串行）。

## V5 接口文档同步

- **目标**：将 V1–V4 的行为变化同步到接口文档，消除"文档先行声明、代码未落地"的悬空表述。
- **优先级**：中。
- **预计改动范围**（仅文档，不改代码）：`docs/接口与部署/pico4_unity_voice_integration.md`、`docs/接口与部署/api_contracts.md`（若含语音 WS 契约小节）。
- **验收标准**：
  1. pico4 文档：§1/§4.1 更新为"默认 127.0.0.1:8765，`--host`/`--port` 可配置"；§4.2 增补 `GET /health` 用法与字段说明；§7.1 的 session.started 示例含 `protocol_version`；§8 标注方案 B 已实现并给出 `tts.frame` 实际字段表（说明不含 sample_rate/channels 的原因：MP3 容器自带，避免硬编码未经验证数值）；§12 待办 1/2/3/4/5 标记已完成并注明对应任务编号；§13 补充测试文件；
  2. 文档内不再存在"当前端口由操作系统随机分配""没有远程 HTTP health endpoint""wire 层缺少 codec/sequence/is_final/duration 元数据"三类过时表述（以 grep 验证）；
  3. `api_contracts.md` 中语音 WS 相关小节（以 grep 定位）同步 `tts.frame` 与 `protocol_version`；
  4. 文档为纯文本修改，无代码文件变更。
- **依赖**：V4（文档必须描述最终代码行为）。

## V6 STATUS.md 专项记录与全量回归

- **目标**：按 AGENTS.md 阶段完成标准，在 `docs/项目总控/STATUS.md` 追加"语音后端联调专项（V0–V7）"完整记录，并执行最终全量回归。
- **优先级**：中。
- **预计改动范围**：仅 `docs/项目总控/STATUS.md`（追加，不修改历史 P0–P8/G0–G8 记录）。
- **验收标准**：
  1. STATUS.md 新增小节包含 AGENTS.md 要求的全部字段：阶段编号（语音后端联调专项 V0–V7）、已完成任务、修改文件列表、新增文件列表、删除文件列表、测试命令、测试结果、是否违反 harness.md、未完成事项、下一阶段是否可以开始；
  2. 最终全量 `pytest`（经 `.venv`）通过，结果数字写入记录；
  3. 历史 P0–P8、G0–G8 记录未被改动（git diff 可证）。
- **依赖**：V5。

## V7 清理收尾与环境确认

- **目标**：检查并清除执行期间产生的一切临时产物与死代码，确认 `.venv` 未被改动、未创建任何新虚拟环境，工作区干净并完成收尾提交。本计划不删除 `.venv`。
- **优先级**：高。
- **预计改动范围**：删除执行期间产生的临时文件（若有）；`docs/项目总控/STATUS.md` 补记；仅当发现前序任务遗留问题时才允许触碰源码（触碰范围不得超出对应任务的 harness 允许清单）。
- **验收标准**：
  1. 对全部改动文件执行 `grep -n "TODO\|FIXME\|XXX" <改动文件>` 无新增标记；无被注释掉的旧代码块、无未使用导入；
  2. 确认全程未创建任何新虚拟环境目录（`Test-Path .venv_pico_fix` 等均为 False）；`.venv` 未发生任何包变更（全程零 pip install/uninstall）；
  3. `git status` 干净（所有变更已按任务分别提交）；
  4. 收尾提交（中文信息）已完成，未推送到远程。
- **依赖**：V6。
