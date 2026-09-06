# AUTO_DEV.md

本文件用于控制 Codex 的全自动阶段开发流程。

## 一、开发目标

基于 docs/项目总控/task.md、docs/项目总控/spec.md、docs/项目总控/harness.md，自动完成系统从工程骨架到可运行版本的阶段化开发。

## 二、开发顺序

历史主线按以下顺序执行，P0 至 P8 当前均已有验收记录：

1. P0：工程骨架、统一状态机与数据契约
2. P1：输入理解、场景状态与 Prompt 路由
3. P2：记忆系统与时间有效性治理
4. P3：本地航空知识库、RAG 与多模态证据包
5. P4：回答生成、来源绑定与结构化输出
6. P5：自我检查、动作分流与回退闭环
7. P6：反馈改写、多轮 checkpoint 与记忆候选
8. P7：语音交互与实时会话
9. P8：日志评测、演示审计与部署治理

具体阶段名称以 docs/项目总控/task.md 为准。

回答生成专项是历史 P8 之后的维护工作，必须按 `G0 → G1 → G2 → G3 → G4 → G5 → G6 → G7 → G8` 执行。G 工作包不伪造新的 P 阶段，也不改写历史 P0 至 P8 验收记录；执行每个 G 时，先读取已批准实施计划的 Task Files，再同时套用 `harness.md` 中对应原 P 边界和 G0–G8 专项门禁。

## 三、每阶段执行流程

每个阶段必须执行以下流程：

1. 阅读 task.md 中当前阶段内容。
2. 阅读 spec.md 中当前阶段内容。
3. 阅读 harness.md 中当前阶段内容。
4. 提取当前阶段：
   - 阶段目标
   - 允许修改范围
   - 禁止修改范围
   - 需要新增或修改的文件
   - 测试要求
   - 验收标准
5. 执行代码开发。
6. 运行测试、lint 或基础验证命令。
7. 如果验证失败，修复本阶段问题。
8. 将阶段结果写入 docs/项目总控/STATUS.md。
9. 确认当前阶段通过后，再进入下一阶段。

上述流程同样适用于 G 工作包；“当前阶段”在专项执行期间指当前 G。G 的目标测试、前序回归、停止条件和回滚提交以 `harness.md` 的专项门禁表为准。

## 四、禁止行为

1. 禁止一次性重构整个系统。
2. 禁止跳过阶段验收。
3. 禁止为了通过测试而删除测试。
4. 禁止绕过接口边界。
5. 禁止把未来阶段的功能提前塞进当前阶段。
6. 禁止引入没有在 spec.md 或 harness.md 中允许的复杂依赖。
7. 禁止将临时代码、mock 逻辑、测试脚本混入正式业务模块。
8. 禁止连接真实生产服务。
9. 禁止读取或写入真实密钥。
10. 禁止删除重要文档。

## 五、阶段验收模板

每完成一个阶段，必须向 docs/项目总控/STATUS.md 追加：

```markdown
## 阶段：P?

### 完成时间
自动填写

### 阶段目标
来自 task.md

### 已完成内容
- ...

### 修改文件
- ...

### 新增文件
- ...

### 删除文件
- ...

### 测试命令
```bash
...
```

### 测试结果
- ...

### 是否违反 harness.md
否 / 是（说明原因）

### 未完成事项
- ...

### 下一阶段或工作包是否可以开始
是 / 否（说明原因）
```

## LangGraph 文本主链迁移：用户授权执行规则

本节为 P0–P8 和 G0–G8 历史验收后的新增授权，不改变历史阶段或工作包结论。文本问答主链获准直接迁移为 LangGraph 监督编排，依赖精确固定为 `langgraph==1.2.9` 与 `langgraph-checkpoint-sqlite==3.1.0`；必须在 `D:\APP\Python 3.13\Internet\.venv\Scripts\python.exe` 的 Python 3.11.9 环境中完成锁定、依赖核验和测试，项目最低 Python 版本保持 `>=3.11`。`AppPipeline` 必须继续作为文本和语音的唯一公共边界，语音实时层及其既有边界不进入 LangGraph 图。

执行中不得改变 `TextQueryRequest`、`TextQueryResponse`、`TextQueryResponse.to_dict()`、CLI 参数或公开 answer/action/trace 字段。图 checkpoint 必须落在独立于 `knowledge.sqlite3`、`memory.sqlite3` 的本地 SQLite 文件，只保存最小脱敏恢复状态；不得保存原始音频、完整 Prompt、私有记忆正文、来源全文、证据正文或密钥。直接回答只限无事实对话，所有航空事实必须经 `RetrievalController` 的已审核证据；不安全输入必须先于记忆、检索和生成拒答。

在工作包验收前，必须运行依赖契约和 CLI 公共响应契约、四路路由和同会话指代消解、图恢复/终态清理/SQLite 隐私扫描，以及文本、RAG、记忆、反馈、语音和全量 pytest 回归。若指定 Python 3.11.9 环境依赖不兼容、SQLite 需要存储禁存内容、恢复需要改公开契约、必须绕过既有 controller、语音边界不变量受损，或同类测试连续三次失败仍无法定位，立即停止并将“待确认”写入 `STATUS.md`。不得引入 LangChain、LangSmith、Agent Server、云服务、真实模型 provider、向量数据库或生产数据库连接。

## 记忆服务重构专项 M0–M5 自动执行规则

本专项不得覆盖 P0 至 P8 或 G0 至 G8 的任务、日志或验收结论。仅在已批准计划与 `harness.md` 的 allowlist 内，按 `M0 → M1 → M2 → M3 → M4 → M5` 自动执行：M0 治理与稳定契约；M1 Java PostgreSQL/pgvector 权威核心；M2 Python port/adapter、150 ms 空长期记忆降级与 durable outbox；M3 Redis Streams、无状态 worker、Java 二次治理、Neo4j/缓存投影；M4 同意、隐私、管理、加密与遗忘；M5 迁移、shadow、单一权威、评测、负载和故障验收。

每个任务固定执行：读取当前计划和门禁；核对精确文件清单；写失败测试并确认 RED；做最小实现；运行任务测试和上游契约/公开回答回归；只读检查修改范围、接口、隐私、线程安全、幂等和降级；按审查证据修正；运行当前阶段完整验证并读取退出码；使用 `scripts/check_memory_workspace_cleanliness.py --stage <m0..m5> --check`；向 `STATUS.md` 追加创建/修改/删除文件、命令、结果、harness 判定、风险和下一任务资格。任何测试失败时先处理当前任务，不带失败推进。

运行 Python 前设置 `PYTHONDONTWRITEBYTECODE=1`，pytest 一律增加 `-p no:cacheprovider`。Java 初始 wrapper 生成后只使用 `services/memory-service/mvnw.cmd`，每任务结束执行 Maven Wrapper `clean`。专项不得执行 Git、分支、worktree、提交、推送、合并或 PR，不得部署服务、读取真实密钥或连接真实生产数据。

以下情况自动停止并记录“待确认”：JDK 21 不可用或 Maven Wrapper 3.9.16 生成失败；批准文档/计划真实冲突；需要未授权依赖、目录、接口、权限或 allowlist 外文件；公开接口或跨语言契约将破坏；需要真实身份/密钥/生产服务；出现跨用户读取、撤回后读取、memory-as-fact、forget 数据复活；需要改变核心架构；同一问题连续三次失败且无法定位。停止时不进入下一任务，并报告当前任务、完成内容、阻塞证据、诊断、未修改范围和最小待确认问题。

## 真实语音 Provider 专项：用户授权执行规则（2026-08-13）

本节为 P0–P8、G0–G8、LangGraph 迁移与 M0–M5 历史验收后的新增授权，不改变历史阶段或工作包结论。专项按 `T0 → T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10` 顺序执行：T0 授权专节由用户手动入档；T1 治理文档更新；T2 依赖声明与环境核验；T3 配置扩展；T4 ASR Provider（whisper_asr.py）；T5 TTS Provider（edge_tts.py）；T6 Provider 注册与接线；T7 测试与标定；T8 语音入口（run_voice.py）；T9 前端页面（run_frontend.py + index.html）；T10 全量回归与交付。

允许的新增运行时依赖仅为 `faster-whisper<2` 与 `edge-tts<7`（onnxruntime、ctranslate2 为传递依赖，不单独声明）；两者均无密钥：faster-whisper 本地推理、转录文本不出本机，edge-tts 使用无凭据公共 TTS 端点。仍严禁需密钥的云 ASR/TTS、生产数据库、真实模型服务与真实密钥；隐私硬约束不变（raw_audio_persist_enabled=False、raw_transcript_logging_enabled=False、retention=0、redaction_mode=full）。不得改变 `ASRProvider`/`TTSProvider` Protocol、`ProviderRegistry` 工厂、`PlaybackHandle`、`AppPipeline.run_text_query()`、`TextQueryRequest`/`TextQueryResponse.to_dict()`/CLI 参数；voice 模块不得绕过 AppPipeline 直接回答航空事实；低置信 ASR 文本不得送入 RAG 正式检索。edge-tts 失败降级文本回答已由现有 orchestrator/websocket_server 代码成立，本专项不得新增降级实现（T7 验收既有链路）。

执行每个任务时，先读取 task.md/spec.md/harness.md 对应 §T 编号段落与 T0 授权专节，核对允许文件清单；完成后运行对应验证（provider 单测、settings 快照、grep 残留检查、全量 pytest 回归），并将修改/新增/删除文件、测试命令与结果、harness 判定、待确认写入 STATUS.md。任务失败先修复当前任务，不带失败进入下一任务；allowlist 外文件一律不得修改。

以下情况自动停止并在 STATUS.md 记录“待确认”：faster-whisper/edge-tts 在指定 Python 3.11.9 核验环境无法安装或解析冲突；需要密钥、生产数据库或需付费云服务；公开契约（TextQueryRequest/TextQueryResponse.to_dict()/CLI/answer/action/trace）将被破坏；voice 模块必须绕过 AppPipeline；同一测试连续三次失败且无法定位。停止时不进入下一任务，报告当前任务、完成内容、阻塞证据与最小待确认问题。

回滚：配置级改回 `configs/voice.yaml` 的 mock provider；依赖级移除 pyproject.toml 新增的 faster-whisper/edge-tts 依赖并重新 `uv lock`；文件级删除新增的 whisper_asr.py/edge_tts.py/run_voice.py/run_frontend.py/index.html 及对应测试。本专项不执行任何 Git 操作，每个任务完成后由用户手动提交形成回滚点。

## R0 审阅修复专项自动执行规则（2026-08-16）

本专项不得覆盖 P0–P8、G0–G8、LangGraph 迁移、M0–M5 或真实语音 Provider 专项的任务、日志或验收结论。仅在 `docs/项目总控/R0审阅修复/{task,spec,harness}.md` 与本文件 R0 段、`harness.md` R0 门禁段允许的范围内，按阶段零→一→二→三→四→五顺序自动执行，不跳阶段、不跨阶段并行。

每个任务固定执行流程：
1. 读 `R0审阅修复/task.md` 对应任务目标与依赖、`spec.md` 对应执行步骤、`harness.md`（R0 子文档）对应逐任务白名单与验证检查项。
2. 阶段零任务为只读验证，仅可写 `tmp/verify_*.py` 或 `tmp/verify_venv/`；证实/证伪结论写入 `STATUS.md` 后，对应修复任务方可执行（证伪则从计划移除并记录）。
3. 改动前确认工作区干净或已 stash；改动严格限定在白名单内。
4. 每步改动后立即跑该任务“验证检查项”；失败先修复，不带错推进。
5. 任务完成后向 `STATUS.md` 追加 R0 任务记录（任务 ID/已完成内容/修改文件/新增文件/删除文件/测试命令/测试结果/是否违反 harness/未完成事项/下一任务可否开始）。
6. 每阶段全部任务完成且验证通过后，提交一个回滚点（commit message 含 `[R0-<阶段>]` 标签），汇报后继续下一阶段。

运行 Python 前设置 `PYTHONDONTWRITEBYTECODE=1`，pytest 一律增加 `-p no:cacheprovider`；测试优先使用项目 `.venv`（Python 3.11.9 验证环境）。预授权架构决策（T2-7/T2-14/T2-17）到达时按 task.md R0 段“用户预授权”执行，不暂停。

自动停止并记录“待确认”：R0 子文档与项目既有总控文档真实冲突；需要未授权依赖/目录/接口/权限或白名单外文件；公开契约将被破坏（T2-17 除外，已预授权且须同步全部调用方）；需要核心架构选择（T2-7/T2-14/T2-17 除外，已预授权）；需要密钥或真实生产服务；同一问题连续三次失败且无法定位。停止时不进入下一任务，报告当前任务、完成内容、阻塞证据与最小待确认问题。
