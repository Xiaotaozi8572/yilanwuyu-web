# P0+ MVP 设计一致性审计与升级基线

## 审计结论

当前项目已完成原始 `P0` 到 `P6` 的 MVP 模块化闭环，具备核心契约、输入理解、Prompt 路由、记忆治理、RAG 证据包、规则生成、自检和反馈改写能力。当前状态仍属于可测试 MVP，不是完整工业级生产系统。

本轮 plus 升级采用 `Mock-first / Offline-first` 策略：真实 API Key、真实数据库、真实语音服务、真实 OCR/PDF layout Provider、真实生产服务均作为后续真实 Provider 接入事项，不阻塞 P0+ 到 P9+ 的离线闭环建设。

## 审计范围

- 文档：`docs/升级规划/task_plus.md`、`docs/升级规划/spec_plus.md`、`docs/升级规划/harness_plus.md`、`docs/项目总控/task.md`、`docs/项目总控/spec.md`、`docs/项目总控/harness.md`、`docs/项目总控/AUTO_DEV.md`、`docs/项目总控/STATUS.md`、`AGENTS.md`
- 目录：`src/`、`tests/`、`configs/`、`scripts/`、`data/`、`docs/`
- 配置：`configs/app.yaml`、`configs/providers.yaml`、`configs/prompts.yaml`、`configs/memory.yaml`、`configs/rag.yaml`、`.env.example`
- 脚本：`scripts/ingest_sources.py`
- 测试：`tests/unit/**`、`tests/integration/**`

## 扫描与验证命令

```powershell
rg --files -g '!**/__pycache__/**' -g '!**/.pytest_cache/**'
rg -n "deepseek-flash 2|deepseek-flash|DeepSeek|pending_confirmation|SimpleVectorIndex|GroundedAnswerGenerator|VoiceTurnEvent|ModelClient|LLMClient"
& "D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest
```

## Baseline 测试结果

- 命令：`"D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe" -m pytest`
- 结果：通过，`57 passed in 0.31s`
- 说明：P0+ 未修改 `src/**`、`tests/**` 或配置文件，baseline 仅作为后续阶段准入依据。

## 真实目录结构摘要

| 区域 | 当前状态 | P0+ 结论 |
| --- | --- | --- |
| `src/core/**` | 动作枚举、状态机、契约、配置、trace 已存在 | 已符合原始 MVP 基线，后续需接入服务级 run trace |
| `src/input/**` | 文本 query object、场景绑定、输入理解已存在 | 部分符合，尚无语音 query normalizer |
| `src/prompts/**` | Prompt 资产模型、路由、组装器已存在 | 部分符合，资产仍内置且缺完整版本化目录 |
| `src/memory/**` | 事件日志、记忆候选、治理、内存 store 已存在 | 部分符合，生产持久化待后续接入 |
| `src/knowledge/**` | source registry、scene registry、RAG controller、evidence gate 已存在 | MVP 可用，`SimpleVectorIndex` 仍是 token similarity fallback |
| `src/generation/**` | `GroundedAnswerGenerator` 规则化生成已存在 | 规则实现，不是 LLM 生成；P3+ 需统一 ModelClient |
| `src/self_check/**` | claim、证据、场景、多模态、边界、安全检查已存在 | MVP 可用，评测治理与 trace report 待补齐 |
| `src/feedback/**` | checkpoint、证据锁、反馈解析、受控改写已存在 | MVP 可用，后续需通过正式 pipeline/E2E 验证 |
| `src/app/**` | 不存在 | P2+ 必须补齐 CLI/API/service facade |
| `src/services/**` | 不存在 | P2+/P3+/P4+ 必须补齐 pipeline、ModelClient、EmbeddingProvider 等边界 |
| `src/voice/**` | 不存在 | P7+ 必须补齐 Mock ASR/TTS、状态机和 metrics |
| `src/observability/**` | 不存在 | P8+ 可新增 trace export / metrics |
| `tests/e2e/**` | 不存在 | P8+ 必须补齐 E2E 场景 |
| `scripts/run_eval.py` | 不存在 | P8+ 必须新增 smoke/mock eval |
| `scripts/export_trace_report.py` | 不存在 | P8+ 必须新增 trace export |
| `scripts/validate_deployment.py` | 不存在 | P8+ 必须新增部署配置校验 |
| `configs/voice.yaml` | 不存在 | P7+ 必须新增 |
| `configs/evals.yaml` | 不存在 | P8+ 必须新增 |

## 设计一致性矩阵

| 模块 | 分类 | MVP 状态 | Plus 差距 | 后续阶段 |
| --- | --- | --- | --- | --- |
| core | 已符合设计文档 | 统一动作、状态、基础 contract 与 trace 已有 | 缺服务入口 trace 汇总和部署级观测 | P2+、P8+ |
| input | 部分符合 | 文本输入和场景绑定可测 | 语音输入标准化缺失 | P7+ |
| prompts | 部分符合 | 路由和注入顺序已可测 | 缺外置资产目录、版本、快照、回滚和语音 Prompt | P6+ |
| memory | 部分符合 | 内存 store 与治理规则已可测 | 持久化、脱敏报告和运行期治理仍待补齐 | P8+、P9+ |
| knowledge/RAG | MVP/fallback | 多索引结构已存在 | token Jaccard 不能称为工业级 embedding；缺 VectorStore/EmbeddingProvider | P4+ |
| multimodal | 接口预留 | visual ingestor/index 占位存在 | 缺 bbox、layout trace、视觉证据 contract 和 mock visual adapter | P5+ |
| generation | 规则实现 | 规则 generator 可生成 answer envelope | 无 ModelClient、结构化模型输出、重试、限流、fallback 分类 | P3+ |
| self_check | 部分符合 | 规则自检和动作分流可测 | 缺 eval runner、trace report、质量指标 | P8+ |
| feedback | 部分符合 | 证据锁和受控改写可测 | 缺正式 app pipeline E2E 覆盖 | P2+、P8+ |
| voice | 尚未实现 | 仅有 `VoiceTurnEvent` 契约 | 无语音模块、Mock ASR/TTS、barge-in、metrics | P7+ |
| app/API | 尚未实现 | 目前是模块库和测试入口 | 无 CLI/API、health check、统一错误响应、run_id 服务闭环 | P2+ |
| eval/deploy | 尚未实现 | 仅单元和集成测试 | 无 smoke eval、E2E、trace export、deployment validate | P8+、P9+ |

## 命名一致性检查

- 未发现 `deepseek-flash 2`。
- plus 文档已统一目标业务模型名称为 `deepseek-flash`。
- `configs/providers.yaml` 中 `providers.llm.default` 与 `providers.embedding.default` 仍为 `pending_confirmation`，属于 P1+/P3+/P4+ 的配置补齐范围。
- P0+ 不接入真实 DeepSeek API，不要求真实密钥，不修改业务代码。

## MVP 能力边界声明

- `SimpleVectorIndex` 当前必须标注为 token similarity fallback，不得描述为工业级 embedding 向量检索。
- `GroundedAnswerGenerator` 当前必须标注为规则化生成 fallback，不得描述为 LLM 智能生成。
- `VoiceTurnEvent` 当前只是契约，不得描述为完整语音系统。
- 当前无真实 DeepSeek API Key、真实数据库、真实 ASR/TTS、真实 OCR/PDF layout、真实生产部署验收。

## 风险与待确认

- `deepseek-flash` 对应真实 DeepSeek API `model_id`、base_url、结构化输出能力待 P3+ 配置核验。
- API 首选 CLI 还是 HTTP API 仍待产品侧确认；P2+ 可按文档采用 CLI-first、HTTP optional。
- 向量库、OCR/PDF layout、语音 Provider、RAG 评测框架均存在 Python 3.13 + Windows 兼容性风险。
- 当前父级 Git 仓库状态包含大量工作区外变更，P0+ 不处理与本项目无关的 Git 状态。

## P0+ 阶段结论

P0+ 审计已建立后续升级基线。当前项目可以进入 P1+，但 P1+ 必须继续遵守 `harness_plus.md`：优先处理 UTF-8 中文编码、配置体系、`.env.example`、providers 配置和中文回归测试，不得重写 RAG、生成、自检或反馈业务逻辑。
