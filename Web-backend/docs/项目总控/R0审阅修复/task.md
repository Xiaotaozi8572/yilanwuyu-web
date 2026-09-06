# task.md — 翼览无余修复任务分解（R0 审阅修复专项实施计划）

> 本文件是 R0 审阅修复专项的详细实施任务清单，由 `docs/项目总控/task.md` 的“R0 审阅修复专项：用户授权”段引用。
> 治理层级：本专项受 `docs/项目总控/harness.md` 的“R0 审阅修复专项门禁”段 + 各任务触及模块对应的原 P harness 不变量双重约束；本目录 `harness.md` 给出逐任务文件白名单与验证检查项。
> 来源：《最终修复计划.md》（2026-08-16，基于 deepseekpro/GLM5.3/gptsol 三份独立审阅）。
> 配套：`spec.md`（执行步骤）、`harness.md`（约束规则）。三文档共用任务 ID。
> 优先级：P0（阻断）> P1（高）> P2（中）> P3（低/文档）
> 任务状态约定：PENDING / IN-PROGRESS / BLOCKED / VERIFIED / CLOSED

---

## 任务总览（按阶段）

| 阶段 | 任务区间 | 优先级 | 说明 |
|---|---|---|---|
| 阶段零 事实核查 | T0-1 ~ T0-8 | P0 | 只读验证，不改代码；结论决定后续任务是否成立 |
| 阶段一 阻断修复 | T1-1 ~ T1-10 | P0 | 提交/演示直接翻车项 |
| 阶段二 高优修复 | T2-1 ~ T2-20 | P1 | 稳定性与文档一致 |
| 阶段三 中优修复 | T3-1 ~ T3-10 | P2 | 答辩前完成 |
| 阶段四 低优/文档 | T4-1 ~ T4-5 | P3 | 整洁度与表述 |
| 阶段五 放行门禁 | T5-1 | P0 | 最终验收闸 |

---

## 阶段零 · 事实核查（只读）

| 任务 | 目标 | 对应计划项 | 依赖 | 验收标准 |
|---|---|---|---|---|
| T0-1 | 核实记忆语义权重 double counting | V-1 / B-07 | — | 给出 `memory/retrieval.py:101-108` 证实或证伪结论；若证实，输出期望修复点行号 |
| T0-2 | 核实多候选指代不澄清 | V-2 / H-02 | — | 对照 `context_resolution.py:76-91` 与 `task.md`(原 P1 验收)原文，输出冲突判定 |
| T0-3 | 核实语义校验=子串匹配 | V-3 / B-06 | — | 构造一条释义型 claim 跑自检，输出判定结果与 SUPPORTED/UNSUPPORTED 落点 |
| T0-4 | 核实主 Prompt 未注入 query | V-4 / B-05 | — | 打印一次最终 messages，确认是否含用户原问题 |
| T0-5 | 核实 evidence sketch 字段错位 | V-5 / B-09 | — | grep `retrieval_score` 读写点，输出写/读位置对照 |
| T0-6 | 核实反馈改写证据锁+契约断裂 | V-6 / B-08 | — | grep `validate_rewrite` 调用；读四分支 claims/bindings 处理 |
| T0-7 | 核实 cryptography==49.0.0 是否存在 | V-7 / T5-1 | — | 干净 venv 执行 `uv sync --frozen --group dev`，输出安装结果 |
| T0-8 | 核实 CrossEncoder 是否进生产路径 | V-8 / H-15 | — | 读 `retrieval_controller.py:464-469`，确认是否传入 cross_encoder |

> T0-9（GLM 文件确认）已关闭，不计入。

---

## 阶段一 · 阻断修复（P0）

| 任务 | 目标 | 对应计划项 | 依赖 | 验收标准 |
|---|---|---|---|---|
| T1-1 | 修复 `generate_terms_graph_data.py` 语法错误 | B-01 | — | `python -m compileall -q src scripts` 退出码 0 |
| T1-2 | 隔离 DeepSeek 单测环境 | B-02 | T1-1 | `pytest tests -q` 在无宿主密钥机器上 0 failed；无子进程编码警告 |
| T1-3 | 提供可用的全 mock 配置并修正文档 | B-03 | — | `validate_deployment.py --profile mock` 干净环境退出码 0；README/速览只保留实测通过命令 |
| T1-4 | 禁用 Prompt 实例缓存（止损） | B-04 | — | 关闭 assembler 实例级缓存；补隔离回归测试占位（T1-5 重建） |
| T1-5 | 重建 Prompt 缓存键 + 注入 user_query | B-04/B-05 | T0-4, T1-4 | 最终 messages 含本轮 query；SHA-256 键覆盖 output_contract/query/weak_points/版本；不同 query/会话不复用 bundle |
| T1-6 | 语义校验接入 embedding judge + Jaccard 兜底 | B-06 | T0-3 | 释义型正确回答不再判 UNSUPPORTED；mock 路径行为不变 |
| T1-7 | 删除记忆重复权重键 | B-07 | T0-1 | 语义权重=配置值 0.30；补权重求和单测通过 |
| T1-8 | 修复反馈改写四分支契约断裂 | B-08 | T0-6 | （T0-6 证实 `validate_rewrite` 已在 app_pipeline.py:351/orchestrator.py:495 调用，F-2 证伪移除）四分支 FACT_CHALLENGE/SCENE_REBIND/STOP/CLARIFY 清空 claims/bindings；去 "Simple:" 前缀；`_to_table` 表头中文化；改写后通过自检 |
| T1-9 | 修复 evidence sketch 字段读取 | B-09 | T0-5 | sketch 排序统一读 `item.retrieval_score`；补 builder→sketch 集成测试 |
| T1-10 | edge-tts 加超时+响应 cancel | B-10 | — | 单段流读 10s 超时；每次迭代检查 cancel_event；barge-in 实测即时生效 |

---

## 阶段二 · 高优修复（P1）

| 任务 | 目标 | 对应计划项 | 依赖 | 验收标准 |
|---|---|---|---|---|
| T2-1 | eval/mock 强制 mock embedding + 延迟门禁 | H-01 | T1-3 | text_smoke 断网可跑；超 5s 判失败；报告区分离线/本地模型评测 |
| T2-2 | 多候选指代转 CLARIFY | H-02 | T0-2 | 多候选标记 reference_ambiguous 转 CLARIFY；单候选才静默绑定；集成测试通过 |
| T2-3 | RAG prefetch 加超时 | H-03 | — | 新增 `rag_prefetch_timeout_ms` 配置；超时回退同步 plan_retrieval 路径 |
| T2-4 | DeepSeek 同步桥改造 | H-04 | — | 任意上下文调用 complete 不崩；不再 asyncio.run |
| T2-5 | 流式接口错误处理 | H-05 | T2-4 | raise_for_status + httpx 异常转 ModelErrorCode；SSE 规范解析 |
| T2-6 | 重试策略治理 | H-06 | T2-4 | 5xx/TransportError/超时纳入可重试；指数退避+jitter+deadline；配置统一消费；structured_output_invalid 重试一次 |
| T2-7 | 对齐缺密钥降级行为与 README | H-07 | T1-3 | 实现 missing_key_strategy:mock 或改文档；文档声明可实测复现 |
| T2-8 | 检索通道降级策略 | H-08 | — | 非核心通道失败保留成功候选+可审计降级证据包；补 dense 失败/keyword 成功用例 |
| T2-9 | component_scene dense fallback | H-09 | T2-8 | scene 不可用自动补 dense；主/回退通道配置化 |
| T2-10 | 反馈入口+checkpoint 持久化 | H-10 | T1-8 | 新增 feedback CLI 命令；checkpoint 落独立 SQLite 表带 TTL；结构化失效响应 |
| T2-11 | 语音资源上限 | H-11 | — | 服务端校验帧字节+自算 RMS；max_utterance_ms/max_frames/max_audio_bytes；超限测试通过 |
| T2-12 | resume 熔断生效 | H-12 | — | 恢复路径原子递增 resume_attempt；超限隔离 checkpoint 删除断点 |
| T2-13 | 终态清理改告警不覆盖成功 | H-13 | T2-12 | 已成功响应不被清理失败覆盖；错误分支仍清 checkpoint |
| T2-14 | CrossEncoder 处置 | H-15 | T0-8 | 不可用置 None 跳过混合；模型 ID 入 rag.yaml；文档称"特征重排" |
| T2-15 | BGE_M3 运行时降级可观测 | H-16 | — | 降级更新 is_mock=True+抛 EmbeddingProviderError；_ensure_model 加锁 |
| T2-16 | SAFETY_MARKERS 收窄+删 legacy | H-17 | — | "操作"等过宽词收窄；拒答窗口扩整句+英文词；删 legacy 安全路径 |
| T2-17 | generate() 保留 plan/outline | H-18 | — | 调用方用 generate_outcome；RETRIEVE_MORE 可构造指令；契约测试同步 |
| T2-18 | 自检 PASS 按 severity 过滤 | H-19 | — | 仅 critical/high 阻塞；medium 不再误转人工 |
| T2-19 | 文档/环境口径统一 | H-20 | T1-3 | 九阶段口径；Python 版本/uv sync 命令/相对路径统一；验收报告重生成 |
| T2-20 | chunk None 防御+embedding 审计 metadata | M-08/M-09 | — | parent 缺失不崩；trace 审计真实显示 embedding 来源 |

---

## 阶段三 · 中优修复（P2）

| 任务 | 目标 | 对应计划项 | 依赖 | 验收标准 |
|---|---|---|---|---|
| T3-1 | 输入防护包 | M-20 | — | 查询长度上限+注入标记；evidence/memory"数据非指令"边界声明；canary 测试 |
| T3-2 | ingest 缓存失效+增量 embedding | M-12 | — | ingest 成功清缓存；按内容 hash 仅嵌新增 chunk；"检索→入库→再检索"用例通过 |
| T3-3 | Agent 健壮性小包 | M-04/M-05/M-06/M-07 | T2-12/T2-13 | _run_lock setdefault；锁清理；审计 try/except；异常分支清 checkpoint |
| T3-4 | 对外契约语义 | M-01/M-02/M-03 | — | check_report_id 唯一化；终态动作码规范；错误路径 0 分 ScoreCard |
| T3-5 | 硬编码配置化+load_settings 收敛+死代码清理 | M-15/M-16/M-17 | — | 别名/白名单入配置；显式注入 settings；删除 HyDE/RetryPolicy/normalize_scores/EmbeddingCache/ArtifactRehydrator/_add_run_id/legacy dict |
| T3-6 | RAG 一致性 | M-10/M-11/M-13/M-14 | T2-8 | review_status 统一；ingest_text 委托结构感知；top-k 口径对齐；分块约束校验补全 |
| T3-7 | 引用与 claim 口径 | M-18/M-19 | T1-6 | 引用绑定加语义交集校验；claim 匹配口径统一归一化 span |
| T3-8 | 语音并发结构 | M-21/M-22 | T1-10/T2-11 | session lock 仅取帧+迁状态；whisper 锁实例级；websocket binding 失效/帧串行/barge_in/相对路径修复 |
| T3-9 | 记忆工程 | M-23 | — | SQLite WAL+busy_timeout；submit_candidate 事务；cutover 日志；governance 死分支/temporal 容错；YAML 解析统一 |
| T3-10 | 健康检查失真 | M-24 | T2-7 | 区分 liveness/readiness；语音健康如实；server 关闭对称 close |

---

## 阶段四 · 低优/文档（P3）

| 任务 | 目标 | 对应计划项 | 依赖 | 验收标准 |
|---|---|---|---|---|
| T4-1 | 工程整洁必做 | L-13/L-14 | T2-19 | tmp 清理；.env.example 补全；pyproject 元数据+pytest-asyncio 移 dev；离线 CI；5 分钟复现章节 |
| T4-2 | 文档表述校准 | D-15 | T2-19 | "受控状态图工作流""特征重排""document recall""mock 闭环"表述统一 |
| T4-3 | RAG 工程杂项 | L-01~L-12 | T3-6 | 缓存 TTL 配置化；FTS5 min_should_match；SceneIndex top；ANN except 日志；pack_sentences overlap 等 |
| T4-4 | 杂项修复 | L-15~L-18 | — | EvidenceLock tuple；safe_alternative_prompts 空保护；missing_behavior 枚举；asset.json 文件锁；safety 测试；evidence_gate 副作用；PDF OCR 边界标注 |
| T4-5 | 可选调参（回归后定） | D-14/L-04 | T4-3 | evidence_policy 0.72→0.65（过 bench 回归后定）；FTS5 min(1, n*0.3) |

---

## 阶段五 · 放行门禁（P0）

| 任务 | 目标 | 对应计划项 | 依赖 | 验收标准 |
|---|---|---|---|---|
| T5-1 | 最终放行门禁 | 阶段五 | T0-7, 全部 P0/P1 完成 | 干净环境 6 条命令全绿：uv sync / compileall / pytest / run_eval / validate_deployment / check_secrets |

---

## 依赖关系图（关键路径）

```
T0-1 → T1-7
T0-2 → T2-2
T0-3 → T1-6 → T3-7
T0-4 → T1-5（依赖 T1-4 止损）
T0-5 → T1-9
T0-6 → T1-8 → T2-10
T0-7 → T5-1（若装失败，升级阻断修复）
T0-8 → T2-14

T1-1 → T1-2 → T5-1（compileall 与 pytest 链）
T1-3 → T2-1 / T2-7 / T2-19
T1-4 → T1-5
T2-4 → T2-5 → T2-6（deepseek_client.py 串行修改）
T2-8 → T2-9 → T3-6
T2-11 → T3-8
T2-12 → T2-13 → T3-3
T2-19 → T4-1 / T4-2
T3-6 → T4-3 → T4-5
```

---

## 执行约束（与 harness.md 联动）

1. 每个任务开始前：读 `harness.md` 对应"任务文件白名单"与"验证检查项"。
2. 每个任务执行中：按 `spec.md` 对应步骤逐条落地，每步后跑该任务的验证检查。
3. 每个任务完成后：更新 `STATUS.md`（阶段编号/任务 ID/修改文件/新增文件/删除文件/测试命令/结果/harness 合规性/未完成事项/下一任务可否开始），符合 AGENTS.md 要求。
4. 任一验证检查失败：停止当前任务，修复后重跑；不带着失败进下一任务。
5. 越权改动（超出 harness 白名单）：立即回滚，记录违规。
