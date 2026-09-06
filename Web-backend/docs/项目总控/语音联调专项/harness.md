# Pico 4 语音后端联调专项 · 约束规范（harness.md）

> 与 `task.md` / `spec.md` 按任务编号（V0–V7）一一对应。任何任务违反本文件即视为该任务失败，必须回滚后重做。

## 全局强约束（适用于所有任务）

1. **git 提交信息一律使用中文**，格式为 `V<编号> <动宾摘要>`（例：`V1 语音服务支持固定端口与显式非回环监听`）。禁止使用英文提交信息，禁止 `git push`，禁止 `git rebase`/`reset --hard` 等改写历史命令；回滚仅允许 `git restore <file>`（未提交变更）或 `git revert <commit>`（已提交变更）。
2. **改动前备份**：每个任务开始前 `git status --short` 必须为空；以"每任务一个提交"作为回滚边界。任务中途放弃时必须 `git restore` 回到任务起点。
3. **禁止新增第三方依赖**：只允许使用 `pyproject.toml` 已声明依赖（websockets 15.0.1、httpx 等）与标准库。全程禁止执行任何 `pip install`/`pip uninstall`（包括向 `.venv` 补装包）。
4. **禁止改变现有架构模式**：不新增顶层目录；不把 Agent 调度/RAG/记忆/语音混写入同一文件；不绕过 `src/voice/` 既有接口直接调用底层模块；不硬编码核心业务规则（端口默认值、协议版本常量、codec 映射必须写在明确的常量/Provider 声明处，且逐处可测试）。
5. **不得留下冗余代码、无用接口或注释掉的死代码**：删除即彻底删除；禁止保留 `# 旧实现如下` 之类注释块；禁止新增未使用的导入、未使用的函数参数。
6. **测试环境约束**：所有测试一律使用项目既有虚拟环境 `.venv` 执行（`.venv\Scripts\python.exe -m pytest`）。`.venv` 为只读环境——禁止在其中安装、卸载、升级任何包；**禁止创建任何新虚拟环境**（含 `.venv_pico_fix` 等）；计划结束后 `.venv` 原样保留，不删除。
7. **历史记录保护**：禁止修改 `docs/项目总控/STATUS.md` 中 P0–P8、G0–G8 的任何历史内容；禁止覆盖 `docs/接口与部署/pico4_unity_voice_integration.md` 的修订记录头（只允许追加）；`docs/项目总控/语音联调专项/` 三文档为计划依据，执行期间只读，不得修改。
8. **单次提交边界**：一次提交只包含当前任务的变更；提交前必须通过本任务全部验证检查项；跨任务文件不得搭车提交。
9. **失败处理**：任一验证检查项失败时，先修复当前任务；同一任务连续 3 次修复仍失败，停止执行并向用户报告，不得带病进入下一任务（对应 AGENTS.md 自动开发停止条件 1）。

## V0 环境确认与基线

- **允许修改/新增的文件清单**：
  - 追加：`docs/项目总控/STATUS.md`（仅 V0 基线小节）
- **禁止改动**：一切源码、配置、测试文件；`.venv/`（任何包变更、配置变更均禁止）；禁止创建任何新虚拟环境目录。
- **备份要求**：无代码改动，无需备份；STATUS.md 追加前确认 `git status` 干净。
- **每步验证检查项**：
  1. `Test-Path .venv\Scripts\python.exe` 为 True 且 `-V` 输出 3.13.x（否则停止报告，禁止新建环境）；
  2. `pip list` 含 `websockets 15.0.1`、`pytest`、`httpx`（版本以实际为准记入 STATUS.md；任一缺失即停止报告，禁止补装）；
  3. 全量 `pytest` 通过，通过数记为 B 并写入 STATUS.md；
  4. `git status --short` 仅显示 STATUS.md；`Test-Path .venv_pico_fix` 为 False。
- **提交边界**：仅提交 STATUS.md 的 V0 基线小节。提交信息：`V0 确认项目测试环境并记录测试基线`。

## V1 固定端口与非回环监听

- **允许修改的文件清单**：
  1. `src/voice/websocket_server.py`（仅 `serve_voice` 签名与 host 校验块）
  2. `scripts/run_voice.py`
  3. 新增 `tests/unit/voice/test_voice_websocket_server.py`
- **禁止改动**：`src/voice/` 其他文件、`src/voice/transport.py`、既有测试文件（含 tests/e2e/test_voice_flow.py 等 3 处 serve_voice 调用方）、`configs/voice.yaml`、`.venv/`。
- **备份要求**：改前 `git status --short` 为空；失败时 `git restore src/voice/websocket_server.py scripts/run_voice.py` 并删除新增测试文件。
- **每步验证检查项**：
  1. host 校验用例：默认拒绝 `0.0.0.0`、opt-in 放行、非法类型拒绝，3 用例通过；
  2. 3 个既有 serve_voice 调用方测试文件不改一行全部通过；
  3. `run_voice.py` 默认启动输出含 `ws://127.0.0.1:8765` 与配置快照 ID；
  4. 全量 `pytest` 通过数 ≥ B。
- **禁止事项**：不得把 8765 写死在 `websocket_server.py`（默认值只存在于 `run_voice.py` 的 argparse）；不得移除原 ValueError 报错语义（仅放宽为显式 opt-in）。
- **提交边界**：上述 3 个文件一次提交。提交信息：`V1 语音服务支持固定端口与显式非回环监听`。

## V2 HTTP /health 健康检查

- **允许修改的文件清单**：
  1. `src/voice/websocket_server.py`（health 抽取 + process_request + serve 调用）
  2. `scripts/run_voice.py`（打印追加一行）
  3. `tests/unit/voice/test_voice_websocket_server.py`（追加用例）
- **禁止改动**：`src/voice/orchestrator.py`、`src/voice/transport.py`、既有测试断言、`.venv/`。
- **备份要求**：同全局 2；回滚命令 `git restore src/voice/websocket_server.py scripts/run_voice.py`。
- **每步验证检查项**：
  1. `/health` 用例：200、JSON content-type、四字段齐全；
  2. `health()` 既有行为用例（若 tests 中存在 health 断言）不改断言通过；`git diff` 确认 health() 仅是等价重构；
  3. 既有 e2e/integration 语音测试全部通过（升级握手不受影响）；
  4. 全量 `pytest` 通过数 ≥ B。
- **禁止事项**：不得新开监听端口；不得引入 aiohttp/fastapi/uvicorn 等新依赖；不得在 /health 中暴露 transcript、音频数据、密钥等私有载荷（payload 仅限 health() 既有字段）。
- **提交边界**：上述 3 个文件一次提交。提交信息：`V2 语音服务同端口提供 HTTP /health 健康检查`。

## V3 tts.frame 元数据协议

- **允许修改的文件清单**：
  1. `src/voice/contracts.py`（仅 TTSChunk）
  2. `src/voice/tts.py`（仅 PlaybackHandle 与 MockTTSProvider）
  3. `src/voice/edge_tts.py`（仅 PlaybackHandle 构造处）
  4. `src/voice/transport.py`（仅 _QueuedAudioTransport.__init__ 与 WebSocketAudioTransport.send_audio）
  5. `tests/unit/voice/test_voice_transport.py`（仅追加）
  6. `tests/e2e/scenarios/test_voice_websocket_flow.py`（仅下行断言更新；若 V3 步骤 6 判定无需改，则改为在 `tests/unit/voice/test_voice_websocket_server.py` 追加用例）
  7. `assets/voice/index.html`（仅当 handleJson 对未知类型非静默时追加忽略分支）
- **禁止改动**：`src/voice/orchestrator.py`（send_audio 调用点 L1612 保持不动）、`src/voice/websocket_server.py`、`scripts/run_frontend.py`、pcm-processor.js、`.venv/`。
- **备份要求**：同全局 2；该任务涉及 7 个文件，回滚时逐一 `git restore` 并移除测试追加。
- **每步验证检查项**：
  1. `test_voice_transport.py` 既有用例零修改通过 + 2 个新用例通过；
  2. TTSChunk 缺省 codec 路径不发送 JSON 头（显式用例断言）；
  3. mock 下行序列 JSON/binary 交替、`codec=="utf8_text"`、末块 `is_final==True`；
  4. `assets/voice/index.html` 收到 tts.frame 无控制台错误（代码走查 handleJson default 分支或手动冒烟）；
  5. 全量 `pytest` 通过数 ≥ B（允许 e2e 断言更新导致的用例改写，但不得删除既有用例）。
- **禁止事项**：不得在 transport 层硬编码 provider→codec 映射（codec 必须由 Provider 经 PlaybackHandle.audio_codec 声明）；不得在 tts.frame 中加入 sample_rate/channels 等未经验证的数值字段；不得改动打断/暂停语义（JSON 头不经过 pause 门是既定设计，不得"顺手"调整）。
- **提交边界**：涉及文件一次提交（含按判定可能不改的 2 个文件说明，写入提交正文）。提交信息：`V3 TTS 下行增加 tts.frame 元数据帧实现方案B协议`。

## V4 protocol_version

- **允许修改的文件清单**：
  1. `src/voice/websocket_server.py`（常量 + _send_voice_state extra + _start_session 调用）
  2. `tests/unit/voice/test_voice_websocket_server.py`（追加 2 用例）
- **禁止改动**：其他任何 _send_voice_state 调用点的事件结构；transport/contracts/orchestrator；`.venv/`。
- **备份要求**：同全局 2。
- **每步验证检查项**：
  1. session.started 含 `protocol_version=="voice-ws-v1"` 用例通过；
  2. 其他 voice.state 事件不含该键的用例通过；
  3. 全量 `pytest` 通过数 ≥ B。
- **禁止事项**：版本号不得散落多处（全仓库仅 `PROTOCOL_VERSION` 常量一处定义）；不得把版本字段加到所有事件。
- **提交边界**：2 个文件一次提交。提交信息：`V4 session.started 事件携带协议版本 voice-ws-v1`。

## V5 接口文档同步

- **允许修改的文件清单**：
  1. `docs/接口与部署/pico4_unity_voice_integration.md`
  2. `docs/接口与部署/api_contracts.md`（仅当 grep 证实存在语音 WS 契约小节）
- **禁止改动**：任何代码/测试/配置文件；`docs/项目总控/语音联调专项/` 三文档；pico4 文档头部的"修订记录"只追加一行（`2026-08-XX V1–V4 行为同步`），不得改写已有修订记录。
- **备份要求**：同全局 2。
- **每步验证检查项**：
  1. 过时表述 grep（spec V5 步骤 3 命令）无匹配；
  2. §8 字段表与 V3 实际 JSON 键逐字一致（人工比对）；
  3. `git diff --stat` 仅含允许清单内文件；
  4. Markdown 无断链、无残留"待办已完成"自相矛盾表述（§12 与正文交叉一致）。
- **禁止事项**：不得顺手修改与 V1–V4 无关的文档段落；不得在文档中承诺未实现功能（如 sample_rate 字段、鉴权、wss/TLS）。
- **提交边界**：1–2 个文档文件一次提交。提交信息：`V5 接口文档同步V1至V4行为变化`。

## V6 STATUS 专项记录与全量回归

- **允许修改的文件清单**：
  1. `docs/项目总控/STATUS.md`（仅文件末尾追加小节）
- **禁止改动**：一切代码与测试；STATUS.md 既有内容（含 P0–P8、G0–G8 及 V0 小节本身——V0 小节在 V6 中仅可引用不可改写；若需补记测试数字，以追加子行方式）；`docs/项目总控/语音联调专项/` 三文档；`.venv/`。
- **备份要求**：同全局 2。
- **每步验证检查项**：
  1. 最终全量 `pytest`（经 `.venv`）通过（数字回填记录）；
  2. `git diff` 对 STATUS.md 无删除行（纯追加）；
  3. 记录包含 AGENTS.md 全部 10 项完成标准字段。
- **禁止事项**：不得在记录中虚报测试结果；不得把本专项写成新的 P 阶段或 G 工作包。
- **提交边界**：1 个文件一次提交。提交信息：`V6 STATUS记录语音后端联调专项并完成全量回归`。

## V7 清理收尾与环境确认

- **允许修改的文件清单**：
  1. 删除：执行期间产生的临时文件（.bak/.orig/.tmp/副本等，若有关逐一记录）
  2. 追加：`docs/项目总控/STATUS.md`（"删除文件列表"补记）
  3. 例外修复：仅当 V7 步骤 1 检出问题时，允许触碰问题所属任务的允许清单文件。
- **禁止改动**：`.venv/`（禁止删除、禁止任何包变更——项目测试环境原样保留）；禁止创建任何新虚拟环境；禁止删除 `docs/`、`tests/` 任何既有内容；禁止删除 `docs/项目总控/语音联调专项/` 三文档。
- **备份要求**：收尾提交前确认 V6 已提交且全量通过。
- **每步验证检查项**：
  1. TODO/FIXME/XXX grep 对 V1–V6 改动文件无新增；
  2. `git grep` 与人工走查无注释掉的死代码、无未使用导入；
  3. `Test-Path .venv_pico_fix` 为 False（未创建新环境）、`Test-Path .venv` 为 True（保留）；
  4. `git status --short` 为空；`git log --oneline` 显示 V0–V7 中文提交序列。
- **提交边界**：收尾一次提交。提交信息：`V7 清理临时产物并收尾语音后端联调专项`。此为整个计划最后一次提交。
