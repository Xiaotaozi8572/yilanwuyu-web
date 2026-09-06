# task.md — 翼览无余系统优化修复任务分解

> 本文档是优化修复计划的任务清单，与 `spec.md`（执行步骤）、`harness.md`（约束规范）共同构成修复计划三件套。
> 三者引用关系：`task.md` 每个任务 `T##` 在 `spec.md` 有对应 `§T##` 执行步骤，在 `harness.md` 有对应 `§T##` 文件约束。
> 本计划独立于 `docs/项目总控/` 原有 P0–P8 / G0–G8 主线，不覆盖原有总控文档，仅作为优化修复专项的工作包。
> 任务来源：《架构与RAG优化建议报告.md》中的 19 条优化建议（P0-1 … P3-4）。

---

## 一、任务总览

| 任务 | 标题 | 阶段 | 优先级 | 对应建议 | 依赖 |
|------|------|------|--------|----------|------|
| T01 | LLM 客户端异步化 | 一·P0 | P0 | P0-1 | — |
| T02 | 拆分全局锁为细粒度锁 | 一·P0 | P0 | P0-2 | T01 |
| T03 | 引入 ANN 向量索引 | 一·P0 | P0 | P0-3 | T04 |
| T04 | 激活 BGE-M3 真实 embedding | 一·P0 | P0 | P0-4 | — |
| T05 | 接入结构化日志体系 | 二·P1 | P1 | P1-5 | — |
| T06 | 引入多级缓存层 | 二·P1 | P1 | P1-4 | T01, T04 |
| T07 | 引入 cross-encoder 二阶段重排 | 二·P1 | P1 | P1-3 | T03, T04 |
| T08 | 启用 BGE-M3 稀疏/多向量 | 二·P1 | P1 | P1-6 | T04 |
| T09 | 拆分 LangGraphAgentRuntime God Object | 三·治理 | P1 | P1-1 | T01, T02 |
| T10 | 建立工具抽象与节点注册表 | 三·治理 | P1 | P1-2 | T09 |
| T11 | 分块/分词/术语外置化 | 三·治理 | P2 | P2-1 | — |
| T12 | 上下文压缩/去重/token 预算 | 三·治理 | P2 | P2-2 | T07 |
| T13 | SQLite 连接池 + WAL | 三·治理 | P2 | P2-3 | — |
| T14 | 图边批量写入消除 N+1 | 三·治理 | P2 | P2-4 | — |
| T15 | 补充生产路径测试覆盖 | 三·治理 | P2 | P2-5 | T01–T04 |
| T16 | LLM 驱动查询改写（HyDE/多查询） | 四·演进 | P3 | P3-1 | T01 |
| T17 | 统一 KeywordIndex | 四·演进 | P3 | P3-2 | T08 |
| T18 | 多 Agent 协作可行性评估 | 四·演进 | P3 | P3-3 | T09, T10 |
| T19 | 简化双轨制恢复 | 四·演进 | P3 | P3-4 | T09 |

**执行顺序建议**（拓扑序）：
```
阶段一：T04 → T03 ；T01 → T02 （两条独立链可并行）
阶段二：T05（基础设施先行）；T06（待T01/T04）；T07（待T03/T04）；T08（待T04）
阶段三：T09（待T01/T02）→ T10 ；T11/T13/T14 可并行；T12（待T07）；T15（待T01–T04）
阶段四：T16（待T01）；T17（待T08）；T18（待T09/T10）；T19（待T09）
```

---

## 二、阶段一 · P0 解锁生产可用

### T01 — LLM 客户端异步化
- **优先级**：P0 ｜ **对应建议**：P0-1 ｜ **依赖**：无
- **目标**：将 `DeepSeekModelClient.complete` 由同步 `urllib` 改为异步 `httpx.AsyncClient`，支持流式响应与连接复用；在 sync 入口提供 `asyncio.to_thread` 桥接保持向后兼容。
- **验收标准**：
  - [ ] `complete_async` 方法存在且为 `async def`，底层使用 `httpx`
  - [ ] 原 sync `complete` 保留且行为兼容（内部桥接 async 实现）
  - [ ] 支持 SSE 流式响应，首字延迟 < 3s（mock provider 下可验证回调链路）
  - [ ] 超时/重试/429 处理逻辑保留且有日志
  - [ ] 现有调用方（`generation/pipeline.py` 等）无需改动即仍可工作
  - [ ] `pytest tests/unit/services/` 全绿
- **预估改动文件**：`src/services/deepseek_client.py`、`src/services/model_factory.py`、`configs/providers.yaml`
- **对应**：spec §T01 ｜ harness §T01

### T02 — 拆分全局锁为细粒度锁
- **优先级**：P0 ｜ **对应建议**：P0-2 ｜ **依赖**：T01
- **目标**：移除 `AppPipeline._run_lock` 全局 RLock，按资源类型拆分：只读资源（知识库索引、Prompt 资产）无锁；写资源（checkpoint、trace）以 `run_id` 为粒度的 per-run 锁。
- **验收标准**：
  - [ ] `_run_lock` 全局锁移除，替换为针对 checkpoint/trace 写入的细粒度锁
  - [ ] 并发两个 `run_text_query` 可同时进入检索阶段（只读路径无互斥）
  - [ ] checkpoint 写入仍线程安全（无 `database is locked`）
  - [ ] `TextQueryRequest`/`TextQueryResponse`/`run_text_query` 公共契约不变
  - [ ] `pytest tests/integration/` 全绿
- **预估改动文件**：`src/services/app_pipeline.py`、`src/agent/langgraph_checkpointer.py`
- **对应**：spec §T02 ｜ harness §T02

### T03 — 引入 ANN 向量索引
- **优先级**：P0 ｜ **对应建议**：P0-3 ｜ **依赖**：T04
- **目标**：为 `SQLiteVectorStore` 引入 `sqlite-vec` 作为 ANN 索引（本地 SQLite 扩展，非独立向量数据库），消除全表纯 Python cosine 扫描；查询期只读、摄入期建索引。
- **验收标准**：
  - [ ] `vector_embeddings` 表新增 `vec0` 虚拟表 ANN 索引
  - [ ] `_search_vector` 走 ANN 查询，不再全表 SELECT + Python cosine
  - [ ] 检索延迟对 N 不再线性增长（1000 条样本下 P95 < 50ms）
  - [ ] 召回结果与原 brute-force 在 top-10 上 IoU ≥ 0.9（ ANN 精度校验）
  - [ ] 摄入流程可重建 ANN 索引
  - [ ] 不引入独立向量数据库服务（符合原 harness）
- **预估改动文件**：`src/knowledge/indexes/vector_store.py`、`src/knowledge/ingestion/text_ingestor.py`、`configs/rag.yaml`
- **对应**：spec §T03 ｜ harness §T03

### T04 — 激活 BGE-M3 真实 embedding
- **优先级**：P0 ｜ **对应建议**：P0-4 ｜ **依赖**：无
- **目标**：将默认 embedding provider 由 `mock`（8 维字符 hash）切换为已实现的 `bge_m3`（1024 维）；重新校准 `non_mock` 相似度阈值；摄入期批量预计算并持久化 embedding。
- **验收标准**：
  - [ ] `configs/rag.yaml` 默认 `provider: bge_m3, dimension: 1024`
  - [ ] `non_mock_vector_min_score` 按 BGE-M3 cosine 分布重新校准（有校准依据记录）
  - [ ] 摄入期 embedding 预计算并写入 `vector_embeddings` 表，查询期不重复编码
  - [ ] `MockEmbeddingProvider` 保留用于离线测试，可通过配置切换
  - [ ] 语义相关性测试：相近概念召回率显著高于 mock（有对比数据）
- **预估改动文件**：`configs/rag.yaml`、`src/services/embedding_provider.py`、`src/knowledge/ingestion/text_ingestor.py`、`src/knowledge/indexes/vector_store.py`
- **对应**：spec §T04 ｜ harness §T04

---

## 三、阶段二 · P1 精度与可观测

### T05 — 接入结构化日志体系
- **优先级**：P1 ｜ **对应建议**：P1-5 ｜ **依赖**：无（基础设施，建议阶段二最先执行）
- **目标**：全仓接入 `structlog` 结构化日志；将 32 处 `except Exception:` 替换为具体异常类型并 `logger.exception()`；关键路径（检索/生成/自查）记 INFO，异常记 ERROR。
- **验收标准**：
  - [ ] `src/` 下所有 core 模块接入 logger（不再有零 logger 的核心文件）
  - [ ] 32 处 `except Exception:` 收敛为具体异常类型，吞异常处必须有显式恢复语义注释
  - [ ] `langgraph_runtime.py:204` 的 `discard` 替换为 `logger.exception()` + 显式处理
  - [ ] 日志含 run_id/节点名等结构化字段，可按 run_id 串联全链路
  - [ ] 日志不泄露隐私（checkpoint 脱敏原则不变）
- **预估改动文件**：`src/observability/`（新增 logger 配置）、`src/agent/langgraph_runtime.py`、`src/services/app_pipeline.py`、`src/voice/orchestrator.py` 等含 `except Exception` 的文件
- **对应**：spec §T05 ｜ harness §T05

### T06 — 引入多级缓存层
- **优先级**：P1 ｜ **对应建议**：P1-4 ｜ **依赖**：T01, T04
- **目标**：为 Embedding 结果、Top-K 检索结果、Prompt 装配结果引入缓存（`functools.lru_cache` + TTL）；LLM 响应在 temperature=0 时可选缓存。
- **验收标准**：
  - [ ] Embedding 按文本 hash 缓存，重复文本不重复编码
  - [ ] Top-K 检索结果按查询指纹 + TTL 缓存，知识库变更时失效
  - [ ] Prompt 装配按输入指纹缓存
  - [ ] 缓存命中率可观测（有 metric/log）
  - [ ] 缓存失效逻辑正确（知识库重建后检索缓存失效）
- **预估改动文件**：`src/services/embedding_provider.py`、`src/knowledge/retrieval_controller.py`、`src/prompts/assembler.py`
- **对应**：spec §T06 ｜ harness §T06

### T07 — 引入 cross-encoder 二阶段重排
- **优先级**：P1 ｜ **对应建议**：P1-3 ｜ **依赖**：T03, T04
- **目标**：在 `FeatureReranker` 之后引入轻量 cross-encoder（`bge-reranker-base`，本地模型）作为二阶段重排；统一各通道分数归一化。
- **验收标准**：
  - [ ] 二阶段重排器存在，使用本地 cross-encoder 模型
  - [ ] `FeatureReranker` 降级为特征抽取器，cross-encoder 输出最终分数
  - [ ] 各通道 `normalized_score` 统一归一化（z-score 或 min-max）后再加权
  - [ ] 精度对比：top-5 语义相关性优于纯特征重排（有对比数据）
  - [ ] 不引入云服务 reranker API
- **预估改动文件**：`src/knowledge/reranking.py`、`configs/rag.yaml`、`src/knowledge/fusion.py`（归一化）
- **对应**：spec §T07 ｜ harness §T07

### T08 — 启用 BGE-M3 稀疏/多向量
- **优先级**：P1 ｜ **对应建议**：P1-6 ｜ **依赖**：T04
- **目标**：在已激活的 BGE-M3 dense 基础上，启用 `return_sparse=True` 与 ColBERT 多向量输出，用 sparse 增强关键词检索通路。
- **验收标准**：
  - [ ] `bge_m3.py` 启用 sparse 输出
  - [ ] sparse 向量用于补充/增强 keyword 通道检索
  - [ ] ColBERT 多向量可用于精排阶段（至少有接口暴露）
  - [ ] dense + sparse 混合召回率优于纯 dense（有对比数据）
- **预估改动文件**：`src/memory_worker/providers/bge_m3.py`、`src/knowledge/indexes/keyword_index.py`、`configs/rag.yaml`
- **对应**：spec §T08 ｜ harness §T08

---

## 四、阶段三 · 架构治理（P1/P2）

### T09 — 拆分 LangGraphAgentRuntime God Object
- **优先级**：P1 ｜ **对应建议**：P1-1 ｜ **依赖**：T01, T02
- **目标**：将 1398 行的 `LangGraphAgentRuntime` 拆分为 `NodeHandler` 协议 + 独立节点类（`ContextResolutionNode`、`RetrieveEvidenceNode`、`GenerateDraftNode`、`SelfCheckNode` 等）；runtime 仅保留图编译、状态机绑定、checkpoint 协调；rehydrate 系列归并到 `ArtifactRehydrator`。
- **验收标准**：
  - [ ] `langgraph_runtime.py` 行数 ≤ 400（仅图编译 + 协调）
  - [ ] 每个节点为独立类，实现 `NodeHandler` 协议
  - [ ] `ArtifactRehydrator` 独立类，承担所有 `_rehydrate_*` 逻辑
  - [ ] 图行为不变（状态机转换、checkpoint、trace 全等价）
  - [ ] 现有测试全绿，无公共契约破坏
- **预估改动文件**：`src/agent/langgraph_runtime.py`、`src/agent/nodes/`（新增）、`src/agent/artifact_rehydrator.py`（新增）
- **对应**：spec §T09 ｜ harness §T09

### T10 — 建立工具抽象与节点注册表
- **优先级**：P1 ｜ **对应建议**：P1-2 ｜ **依赖**：T09
- **目标**：定义 `Tool`/`NodeHandler` 协议，建立节点注册表；`_compile_graph` 从注册表动态装配；保留固定 DAG 作为默认策略。
- **验收标准**：
  - [ ] `Tool` 协议定义（`name`/`description`/`invoke(state)->state`）
  - [ ] 节点注册表存在，`_compile_graph` 从注册表装配
  - [ ] 新增节点可通过注册表注册，无需修改 `_compile_graph`
  - [ ] 默认 DAG 行为不变
- **预估改动文件**：`src/agent/tool_protocol.py`（新增）、`src/agent/node_registry.py`（新增）、`src/agent/langgraph_runtime.py`
- **对应**：spec §T10 ｜ harness §T10

### T11 — 分块/分词/术语外置化
- **优先级**：P2 ｜ **对应建议**：P2-1 ｜ **依赖**：无
- **目标**：引入 `jieba` 中文分词替代 CJK 单字切分；硬编码航空术语外置为 `configs/terminology.yaml`；句子边界正则补充英文标点。
- **验收标准**：
  - [ ] `keyword_index.py` 的 `tokenize` 使用 jieba，无硬编码术语
  - [ ] `configs/terminology.yaml` 存在，术语可配置
  - [ ] 句子边界识别 `[。！？；.!?]` 及换行
  - [ ] 关键词召回测试通过且召回碎片化降低（有对比）
- **预估改动文件**：`src/knowledge/indexes/keyword_index.py`、`src/knowledge/ingestion/text_ingestor.py`、`configs/terminology.yaml`（新增）
- **对应**：spec §T11 ｜ harness §T11

### T12 — 上下文压缩/去重/token 预算
- **优先级**：P2 ｜ **对应建议**：P2-2 ｜ **依赖**：T07
- **目标**：`PromptAssembler` 引入 token 计数与硬上限；检索结果按相关度截断 + 语义去重（embedding 相似度阈值）。
- **验收标准**：
  - [ ] Prompt 装配有 token 计数，硬上限保护（不超模型窗口）
  - [ ] 检索结果按相关度截断 + 语义去重
  - [ ] 超限时优先保留高相关证据
  - [ ] 装配结果可观测（token 用量日志）
- **预估改动文件**：`src/prompts/assembler.py`、`configs/rag.yaml`
- **对应**：spec §T12 ｜ harness §T12

### T13 — SQLite 连接池 + WAL
- **优先级**：P2 ｜ **对应建议**：P2-3 ｜ **依赖**：无
- **目标**：为 `KnowledgeRepository` 与 checkpoint 库启用 WAL 模式 + 读写分离（read-only 连接池 + 单写连接）；写操作批量提交。
- **验收标准**：
  - [ ] `PRAGMA journal_mode=WAL` 启用
  - [ ] 读连接池存在，写连接单一
  - [ ] 高并发读写无 `database is locked`
  - [ ] checkpoint 库与知识库隔离不变
- **预估改动文件**：`src/knowledge/repository.py`、`src/agent/langgraph_checkpointer.py`
- **对应**：spec §T13 ｜ harness §T13

### T14 — 图边批量写入消除 N+1
- **优先级**：P2 ｜ **对应建议**：P2-4 ｜ **依赖**：无
- **目标**：`write_graph_edge` 批量预取节点 ID 到内存 map，或用 `INSERT ... ON CONFLICT` 单语句完成，消除循环内逐节点 SELECT。
- **验收标准**：
  - [ ] 批量导入图边不再有循环内 SELECT
  - [ ] 导入耗时不随边数线性增长（有对比数据）
  - [ ] 图结构正确性测试通过
- **预估改动文件**：`src/knowledge/repository.py`
- **对应**：spec §T14 ｜ harness §T14

### T15 — 补充生产路径测试覆盖
- **优先级**：P2 ｜ **对应建议**：P2-5 ｜ **依赖**：T01, T02, T03, T04
- **目标**：补 `test_deepseek_client.py`（超时/重试/429/5xx/URL 校验/流式）、`test_app_pipeline_concurrency.py`（并发/锁/checkpoint 隔离）；e2e 场景扩充至核心状态机路径。
- **验收标准**：
  - [ ] `tests/unit/services/test_deepseek_client.py` 存在并覆盖上述场景
  - [ ] `tests/integration/test_app_pipeline_concurrency.py` 存在
  - [ ] e2e 场景 ≥ 6 个（含 RETRIEVE_MORE 循环、SELF_CHECK 失败、REFUSE）
  - [ ] 整体测试全绿
- **预估改动文件**：`tests/unit/services/test_deepseek_client.py`（新增）、`tests/integration/test_app_pipeline_concurrency.py`（新增）、`tests/e2e/scenarios/`（扩充）
- **对应**：spec §T15 ｜ harness §T15

---

## 五、阶段四 · 长期演进（P3）

### T16 — LLM 驱动查询改写（HyDE/多查询）
- **优先级**：P3 ｜ **对应建议**：P3-1 ｜ **依赖**：T01
- **目标**：在确定性 `query_rewriter` 基础上，引入 LLM 驱动的 HyDE（生成假设文档）与多查询变体，作为可选增强。
- **验收标准**：
  - [ ] HyDE 与多查询改写可选启用（配置开关）
  - [ ] 复杂/长尾问题召回率提升（有对比）
  - [ ] 不影响确定性改写的稳定路径
- **预估改动文件**：`src/knowledge/query_rewriter.py`、`configs/rag.yaml`
- **对应**：spec §T16 ｜ harness §T16

### T17 — 统一 KeywordIndex
- **优先级**：P3 ｜ **对应建议**：P3-2 ｜ **依赖**：T08
- **目标**：移除内存词频索引，统一为 FTS5 BM25；评估用 BGE-M3 sparse 替代/补充。
- **验收标准**：
  - [ ] 仅保留 FTS5 BM25 一套 KeywordIndex
  - [ ] 行为等价或更优（有对比测试）
- **预估改动文件**：`src/knowledge/indexes/keyword_index.py`
- **对应**：spec §T17 ｜ harness §T17

### T18 — 多 Agent 协作可行性评估
- **优先级**：P3 ｜ **对应建议**：P3-3 ｜ **依赖**：T09, T10
- **目标**：产出评估文档，分析 supervisor-worker 模式将检索/生成/自查拆为子 Agent 的可行性与必要性；不立即实现。
- **验收标准**：
  - [ ] 评估文档存在，含利弊分析、业务必要性判断、实施成本
  - [ ] 给出"建议/不建议"明确结论
- **预估改动文件**：`docs/优化修复计划/multi_agent_assessment.md`（新增）
- **对应**：spec §T18 ｜ harness §T18

### T19 — 简化双轨制恢复
- **优先级**：P3 ｜ **对应建议**：P3-4 ｜ **依赖**：T09
- **目标**：评估将 `_rehydrate_*` 双轨（checkpoint + 进程内）统一为单一状态源，或引入显式状态版本号。
- **验收标准**：
  - [ ] 评估方案存在，含风险与收益
  - [ ] 若实施：rehydrate 路径收敛，指纹匹配逻辑简化且等价
- **预估改动文件**：`src/agent/artifact_rehydrator.py`、`src/agent/langgraph_runtime.py`
- **对应**：spec §T19 ｜ harness §T19

---

## 六、依赖关系图

```
T04 ──┬──> T03 ──┬──> T07 ──> T12
      │          │
      ├──> T08 ──┼──> T17
      │          │
      └──> T06   └──> T15
                                          
T01 ──┬──> T02 ──┬──> T09 ──┬──> T10 ──┬──> T18
      │          │          │          │
      ├──> T06   │          └──> T19   └──> T19
      │          │
      └──> T16   └──> T15

T05（独立基础设施）  T11（独立）  T13（独立）  T14（独立）
```

## 七、记忆系统切换前置约束（2026-07-26 新增）

> 长期记忆目标权威后端是 Java（`services/memory-service/`），当前 `backend: local` 仍跑 Python+SQLite，M5 切换进行中。详见 `harness.md §总则·8`。

- **冻结 `src/memory/` 深度重构**：M5 切换完成前，19 个任务均不得拆分/重写 `src/memory/controller.py` 及其子系统。本计划未包含任何此类任务（P1-1 拆的是 `langgraph_runtime.py`，与记忆后端无关），保持该约束。
- **T04/T08 不受影响**：它们改的是 `src/memory_worker/`（embedding worker：bge_m3），与 `src/memory/`（记忆系统）是两个独立模块，与 Java 记忆服务无关。
- **切换后解冻评估**：M5 完成后，先评估 Python 侧 `memory` 模块最终职责边界（是否退化为薄客户端），再决定是否新增记忆侧重构任务（潜在 T20）。

## 八、停止条件（沿用原 AUTO_DEV）

- 测试连续三次失败且无法定位原因 → 停止，记录 STATUS
- 需要未授权依赖 → 停止，提交 harness 评审
- 公共契约将破坏 → 停止
- 需要真实密钥/生产服务 → 停止
- **触及 `src/memory/` 深度重构 → 停止**（见 §七，切换前冻结）

---

文档完。执行步骤见 `spec.md`，约束规范见 `harness.md`。
