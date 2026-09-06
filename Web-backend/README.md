# 翼览无余 AI 智能导师系统

面向航空科普场景的证据驱动智能导师系统：围绕航空器、部件、原理、发展背景和应用价值，提供有证据、可追溯、适合用户理解的讲解，并支持真实语音交互（本地 faster-whisper ASR + edge-tts TTS）与多轮记忆化问答。

本项目按 `docs/项目总控/task.md`、`docs/项目总控/spec.md`、`docs/项目总控/harness.md` 和 `docs/项目总控/AUTO_DEV.md` 分阶段开发。

## 阶段与维护口径

阶段编号统一遵循 `docs/项目总控/task.md`：

- **P0–P8**：历史主线九阶段（工程骨架 → 输入理解 → 记忆 → RAG 与多模态证据 → 回答生成 → 自检与回退 → 反馈改写 → 语音交互 → 评测与部署治理），均有验收记录。
- **G0–G8**：回答生成专项维护工作包，位于历史 P8 之后，不创建新的 P 阶段。
- 其余专项（LangGraph 迁移、M0–M5 记忆服务重构、真实语音 Provider 专项）编号与边界同样以 `task.md` 为准。

历史遗留文档中的 "P9+" 提法已不再使用，验收口径以 `docs/评测与验收/release_acceptance.md` 与 `docs/项目总控/STATUS.md` 为准。

## 当前状态

- **Mock-first / Offline-first**：缺少真实 DeepSeek API Key、真实数据库或真实 OCR/PDF layout Provider 时，不阻塞本地测试与离线闭环验收。
- **记忆后端现状（T32 口径）**：记忆系统当前运行后端为本地 SQLite（`configs/memory.yaml` `backend: local`）；Java memory-service（PostgreSQL+pgvector，含加密/遗忘/图投影）已完成 M0–M5 开发与契约测试，定位为权威化演进设计，通过 AuthorityMode 状态机灰度切换，当前处于 LOCAL 阶段，未切流。
- **真实 ASR/TTS 已接入**：`configs/voice.yaml` 中 `asr_provider: whisper`（faster-whisper 本地推理，无需密钥）、`tts_provider: edge`（edge-tts 公共端点），可运行真实语音链路（`scripts\run_voice.py` + `scripts\run_frontend.py`）。
- LLM 与 Embedding 走真实 Provider 配置（`configs/providers.yaml`：`llm.default: deepseek`、`embedding.default: bge_m3`），密钥缺失时自动降级 mock，不伪装为真实调用。
- 本轮验收代表 Mock/Offline 工程闭环与真实语音链路就绪；真实 DeepSeek 线上验收、真实数据库、真实 OCR/PDF layout 与生产部署环境仍属后续 real-provider 范围。

## 本地验证

Python 版本统一为 **3.11**（项目 `.venv` 实测 3.11.9）。所有 Python 命令必须使用项目指定虚拟环境解释器，避免污染全局环境：

```powershell
& "D:\APP\Python 3.13\挑战杯\.venv\Scripts\python.exe" -m pytest
```

依赖同步使用 `uv sync --frozen --group dev`；测试与门禁统一使用项目 `.venv`（Python 3.11.9），禁止使用全局 Python。

## 5 分钟从零复现

在干净机器上（无宿主密钥、可断网）复现 Mock/Offline 闭环：

```powershell
# 1. 安装依赖（uv 自动创建隔离环境）
uv sync --frozen --group dev

# 2. 准备 mock 配置 —— configs/mock/ 已内置离线覆盖，无需任何真实密钥

# 3. 跑离线 text_smoke（mock 闭环，秒级）
uv run python scripts/run_eval.py --suite text_smoke

# 4. mock 部署校验
uv run python scripts/validate_deployment.py --profile mock
```

预期：`text_smoke` 与 `validate_deployment --profile mock` 退出码均 0，全程离线、无真实 API 调用。真实 DeepSeek 线上验收属后续 real-provider 范围。

## 密钥与真实 Provider

- 真实 DeepSeek 接入只通过环境变量和 `configs/providers.yaml` 的 adapter 边界提供：

```powershell
$env:DEEPSEEK_API_KEY=""
$env:DEEPSEEK_BASE_URL=""
$env:DEEPSEEK_MODEL=""
```

- `.env.example` 不会被自动加载，变量必须手动 set/export 进运行进程才会被读取。
- 环境预检清单见 `docs/评委速览.md`。

## 验收与演示审计

- 最终验收报告：`docs/评测与验收/release_acceptance.md`
- 演示审计报告：`docs/评测与验收/demo_audit_report.md`
- 评委速览（量化指标 / 演示路径 / 预检清单）：`docs/评委速览.md`

核心验收命令：

```powershell
& "D:\APP\Python 3.13\挑战杯\.venv\Scripts\python.exe" -m pytest
& "D:\APP\Python 3.13\挑战杯\.venv\Scripts\python.exe" -m compileall -q src scripts
& "D:\APP\Python 3.13\挑战杯\.venv\Scripts\python.exe" scripts\run_eval.py --suite text_smoke
& "D:\APP\Python 3.13\挑战杯\.venv\Scripts\python.exe" scripts\export_trace_report.py --run-id <run_id>
& "D:\APP\Python 3.13\挑战杯\.venv\Scripts\python.exe" scripts\validate_deployment.py --profile mock
& "D:\APP\Python 3.13\挑战杯\.venv\Scripts\python.exe" scripts\validate_deployment.py --profile real
```
