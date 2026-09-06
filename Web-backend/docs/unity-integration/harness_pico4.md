# Pico 4 接入约束规范

> 对应任务：`task_pico4.md`、`spec_pico4.md` 的 PICO-01 至 PICO-13。此规范优先于计划中的便利性建议。

## 全局硬约束

1. 禁止修改 `pyproject.toml`、`uv.lock`、`requirements*`、`Packages/manifest.json`、`Packages/packages-lock.json`、`ProjectSettings/**`；禁止安装、下载或引入 FastAPI、Uvicorn、JWT、Caddy、NativeWebSocket、Newtonsoft.Json、第三方 MP3 decoder。
2. 不改变 `AppPipeline`、`VoiceSessionOrchestrator`、CLI、现有 v1 文本/语音公开契约；若新增外层适配入口超出项目总控授权，立即停止。
3. 每任务前运行 `git status --short`；白名单文件有非本任务改动即停止。执行 `git diff --binary -- <白名单>` 保存到 `C:\Users\SONGQI\Desktop\Pico4_变更备份\PICO-XX\before.patch`；未跟踪且已存在的文件先复制到同目录。只可用该备份或 `git restore --source=HEAD -- <白名单>` 回滚，禁止 reset --hard/checkout --/删除用户文件。
4. 每步后运行 `git diff --check`、本任务指定验证、`git diff --name-only`；出现白名单外文件立刻还原本任务改动并停止。
5. 后端测试仅使用 `.venv-pico4`；它不入 git，不复用全局环境，PICO-13 后删除。每个提交必须中文、原子、仅当前任务，格式 `PICO-XX：中文动作`；不 push/merge。
6. 禁止未调用公开接口、复制粘贴实现、注释掉的代码、任何待办占位标记、调试日志、token/证书/PCM/完整 transcript/Prompt；新增源文件必须在同任务被调用且有验证，否则删除。

| 任务 | 允许修改文件（另允许 `docs/项目总控/STATUS.md`） | 禁止与门禁 | 每步必过检查 / 提交边界 |
|---|---|---|---|
| PICO-01 | `docs/接口与部署/pico4_零依赖可行性记录.md`；`tests/unit/app/api/test_pico4_contract_examples.py`。 | 不改源代码、依赖、Unity。WSS/MP3 任一 FAIL 即停止相关分支。 | venv 最小回归、fixture 测试、能力报告；`PICO-01：完成零依赖可行性核查`。 |
| PICO-02 | `docs/接口与部署/pico4_gateway_contract.md`；`docs/接口与部署/adr/ADR-pico4-零依赖接入.md`；`docs/unity-integration/后端接入文档.md`；契约测试。 | 不改 runtime 源码。禁止许诺未存在依赖。 | 正反例 fixture、v1 契约 diff；`PICO-02：冻结文本语音契约`。 |
| PICO-03 | `configs/pico4_gateway.yaml`；`src/app/api/pico4_settings.py`、`pico4_auth.py`、`pico4_http_server.py`；`scripts/run_pico4_text_api.py`；对应 unit tests。 | 禁止依赖文件；总控未授权 HTTP 入口时只写 STATUS。 | settings/auth 测试、compileall、无 token=401、日志脱敏；`PICO-03：建立文本安全基础`。 |
| PICO-04 | `http_models.py`、`text_http_handler.py`、`idempotency.py`、`pico4_http_server.py`、`tests/integration/pico4/test_text_http_api.py`。 | 禁止直接调 RAG/memory/model 私有接口。 | 200/400/413/401/409/503、幂等单调用、无 trace；`PICO-04：接入文本问答适配器`。 |
| PICO-05 | `src/voice/websocket_server.py`；`scripts/run_pico4_voice.py`；指定 voice tests。 | 禁止改变 `serve_voice()` 默认 loopback 和 v1 wire。 | Pico 选项测试、全 voice_loop 回归；`PICO-05：增加Pico语音监听配置`。 |
| PICO-06 | `websocket_server.py`、`transport.py`；`test_voice_v2_protocol.py`、`test_voice_v2_framed_tts.py`。 | 禁止改变 v1 裸 binary；不新建未接入 transport。 | v2 顺序/取消/版本反例及 v1 回归；`PICO-06：增加语音v2帧协议`。 |
| PICO-07 | `websocket_server.py`、`pico4_auth.py`、`pico4_rate_limit.py`、两项 voice integration tests。 | 禁止 URL token、accept 后才认证、全局连接清理。 | 未认证拒绝、设备不符拒绝、4408 隔离重连；`PICO-07：增加语音握手认证`。 |
| PICO-08 | 两 Pico 启动脚本；`pico4_gateway_runbook.md`、`pico4_gateway_回滚手册.md`、TLS test。 | 禁止 Caddy、真实证书/token、防火墙或外网操作。 | 缺证书拒启、测试 TLS smoke、文档静态审查；`PICO-08：补充TLS部署手册`。 |
| PICO-09 | 三个 Unity C# 与各自 `.meta`；能力报告。 | 禁止 Packages/ProjectSettings/PlayerPrefs；能力 FAIL 时不写伪客户端。 | Unity 编译、Pico WSS/MP3 probe；`PICO-09：建立Unity零依赖能力探针`。 |
| PICO-10 | `TextQueryClient.cs`、`AgentSessionStore.cs`、`AgentStatusPresenter.cs` 及 `.meta`。 | 禁止解析 trace、保存 token、修改场景。 | Unity 编译与 Pico 文本/取消/错误手测；`PICO-10：接入Unity文本问答`。 |
| PICO-11 | 五个语音 C# 及各自 `.meta`。 | 禁止新 package；PICO-09 无原生 PASS 时停止。 | 640 B/FF7F、buffer/cancel/错配 MP3 Pico 验证；`PICO-11：接入Unity实时语音`。 |
| PICO-12 | `AgentGatewayController.cs`、`AgentSceneBridge.cs`、各 `.meta`、`Assets/公用文件/02-机型选择页面共用.unity`。 | 禁止 XR、Pico 包、其他场景/UI/模型资源。 | 退避、旧事件隔离、失焦释放手测；`PICO-12：完善Unity会话状态管理`。 |
| PICO-13 | `tests/e2e/test_pico4_gateway_smoke.py`；`pico4_unity_acceptance.md`、`pico4_unity_handover.md`。 | 禁止提交证书/token/音频；未全绿不得删除 venv 或标完成。 | 全指定测试、compileall、diff check、真机证据、确认 venv 不存在；`PICO-13：完成Pico接入验收交付`。 |

任何任务跨后端与 Unity 两个仓库时，最多各一个同编号中文原子提交；暂存区不得包含其他任务文件。三个文档中任何 PICO 编号缺失、文件白名单不一致或验证命令缺失，均视为计划不合格，先修正文档再实施。
