# spec.md — 翼览无余系统优化修复执行步骤

> 本文档为 `task.md` 中 T01–T19 每个任务的详细执行步骤。每个 `§T##` 与 `task.md` 任务一一对应，与 `harness.md §T##` 文件约束一一对应。
> 每步含：操作、文件路径、改动范围、预期行为、验证方式。所有步骤须具体可执行，禁止模糊指令。
> 执行前必读 `harness.md`：每任务允许修改文件清单、备份回滚规则、验证检查项、受控依赖白名单。

---

## §T01 — LLM 客户端异步化

**前置**：阅读 `src/services/deepseek_client.py` 全文、`configs/providers.yaml`、`src/generation/pipeline.py` 调用处。

### 步骤
1. **新增异步客户端方法**
   - 文件：`src/services/deepseek_client.py`
   - 改动：新增 `async def complete_async(self, messages, **kwargs) -> dict`，底层用 `httpx.AsyncClient`（HTTP/2、连接池、SSE 流式）。保留超时/重试/429 逻辑，从原 `complete` 提取为 `_build_request`/`_parse_response` 共享方法。
   - 预期：`complete_async` 返回结构与原 `complete` 一致（`{"content": ..., "usage": ...}`）。
   - 验证：单元测试 `test_deepseek_client.py::test_complete_async_returns_same_shape`（用 mock httpx transport）。

2. **sync 入口桥接**
   - 文件：`src/services/deepseek_client.py`
   - 改动：原 `def complete` 改为内部调用 `asyncio.run(self.complete_async(...))` 或在有 event loop 时 `asyncio.to_thread`。保持签名与返回兼容。
   - 预期：现有调用方零改动。
   - 验证：`pytest tests/unit/services/test_model_client.py` 全绿。

3. **流式响应支持**
   - 文件：`src/services/deepseek_client.py`
   - 改动：新增 `async def stream_async(self, messages, **kwargs) -> AsyncIterator[str]`，解析 SSE `data:` 行。
   - 预期：首字回调可在 mock transport 下验证回调链路。
   - 验证：单元测试消费前 3 个 chunk 断言非空。

4. **配置同步**
   - 文件：`configs/providers.yaml`
   - 改动：新增 `stream: false`（默认关闭）、`http2: true`、`max_connections: 10` 字段；`timeout_seconds` 保留。
   - 验证：`Settings.get("providers.yaml")` 能读取新字段。

5. **工厂适配**
   - 文件：`src/services/model_factory.py`
   - 改动：`create_client` 仍返回兼容对象；新增 `create_async_client` 可选。
   - 验证：`model_alias` 行为不变。

### 验证方式
- `pytest tests/unit/services/` 全绿
- 新增 `test_deepseek_client.py` 覆盖 async/stream/桥接（T15 完善，本任务先建骨架）
- 手动：CLI 一次问答成功，日志含 httpx 连接复用

---

## §T02 — 拆分全局锁为细粒度锁

**前置**：阅读 `src/services/app_pipeline.py` 的 `_run_lock` 所有使用点、`src/agent/langgraph_checkpointer.py`。

### 步骤
1. **识别只读 vs 写资源**
   - 文件：`src/services/app_pipeline.py`
   - 改动：注释标注 `_run_lock` 内每个区段是只读（检索索引、Prompt 资产读取）还是写（checkpoint 写、trace 写）。
   - 验证：代码注释完整，review 可核对。

2. **移除全局锁，引入 per-run 写锁**
   - 文件：`src/services/app_pipeline.py`
   - 改动：删除 `self._run_lock`。新增 `self._checkpoint_locks: dict[str, RLock]`（按 `run_id`），仅包裹 checkpoint/trace 写入段。只读段无锁。
   - 预期：两个并发请求可同时进入检索。
   - 验证：`test_app_pipeline_concurrency.py::test_concurrent_retrieve_no_block`（T15 完善）。

3. **checkpoint 写入线程安全**
   - 文件：`src/agent/langgraph_checkpointer.py`
   - 改动：确认写路径在 per-run 锁内；连接 `check_same_thread=False` 保留，配合 T13 连接池。
   - 验证：并发写无 `database is locked`。

4. **公共契约不变性校验**
   - 改动：无代码改动，仅验证。
   - 验证：`TextQueryRequest`/`TextQueryResponse.to_dict()`/`run_text_query` 签名与返回不变；`pytest tests/integration/` 全绿。

### 验证方式
- `pytest tests/integration/` 全绿
- 并发测试：2 并发请求检索阶段时间 ≈ 单请求（无串行叠加）

---

## §T03 — 引入 ANN 向量索引

**前置**：阅读 `src/knowledge/indexes/vector_store.py` 全文、`src/knowledge/ingestion/text_ingestor.py` 摄入流程。确认 `sqlite-vec` 在受控依赖白名单（harness §总则·白名单）。

### 步骤
1. **安装受控依赖**
   - 操作：`pip install sqlite-vec`（仅入 venv，不入全局）
   - 验证：`python -c "import sqlite_vec; print(sqlite_vec.loadable_path())"` 成功。

2. **建 ANN 虚拟表**
   - 文件：`src/knowledge/indexes/vector_store.py`
   - 改动：在 `_init_schema` 中，连接加载 `sqlite_vec` 扩展后，`CREATE VIRTUAL TABLE IF NOT EXISTS vector_embeddings_ann USING vec0(embedding float[<dim>])`。`<dim>` 从配置读取（T04 后为 1024）。
   - 预期：表创建成功，无全表扫描。
   - 验证：`SELECT * FROM vec0_virtual_tables` 可见。

3. **查询走 ANN**
   - 文件：`src/knowledge/indexes/vector_store.py`
   - 改动：`_search_vector` 改为 `SELECT chunk_id, distance FROM vector_embeddings_ann WHERE embedding MATCH ? AND k = ? ORDER BY distance`，参数为查询向量与 top_k。移除 Python cosine 循环。
   - 预期：延迟 O(log N)。
   - 验证：1000 样本 P95 < 50ms；top-10 与 brute-force IoU ≥ 0.9。

4. **摄入期重建索引**
   - 文件：`src/knowledge/ingestion/text_ingestor.py`
   - 改动：摄入完成后 `INSERT INTO vector_embeddings_ann` 批量写入 embedding。
   - 验证：重建后检索可用。

5. **配置开关**
   - 文件：`configs/rag.yaml`
   - 改动：新增 `vector_index: ann`（默认）/ `brute_force`（回退）。
   - 验证：切换回退仍可用。

### 验证方式
- 性能基准脚本 `scripts/bench_vector_search.py`（新增，harness 允许）：对比 ANN vs brute-force 延迟与 IoU
- `pytest tests/unit/knowledge/test_vector_store.py` 全绿

---

## §T04 — 激活 BGE-M3 真实 embedding

**前置**：阅读 `src/memory_worker/providers/bge_m3.py`、`src/services/embedding_provider.py`、`configs/rag.yaml`。

### 步骤
1. **切换默认 provider**
   - 文件：`configs/rag.yaml`
   - 改动：`embedding.provider: mock` → `bge_m3`；`dimension: 8` → `1024`。保留 `mock` 作为可选。
   - 验证：`Settings.get("rag.yaml").embedding.provider == "bge_m3"`。

2. **provider 工厂适配**
   - 文件：`src/services/embedding_provider.py`
   - 改动：`EmbeddingProviderFactory.create` 根据 provider 名返回 `BGE_M3_Provider` 或 `MockEmbeddingProvider`。确认 `bge_m3.py` 的 `encode` 接口与 `MockEmbeddingProvider` 一致（输入文本列表，返回 float 列表）。
   - 验证：工厂单测覆盖两种 provider。

3. **阈值校准**
   - 文件：`configs/rag.yaml`
   - 改动：`non_mock_vector_min_score` 暂留 0.72，标注"待校准"。
   - 操作：运行校准脚本 `scripts/calibrate_threshold.py`（新增）：对已知相关/不相关文档对计算 BGE-M3 cosine 分布，输出建议阈值。
   - 验证：校准报告写入 `docs/优化修复计划/threshold_calibration.md`，据此更新阈值。

4. **摄入期预计算 embedding**
   - 文件：`src/knowledge/ingestion/text_ingestor.py`
   - 改动：摄入时批量 `encode` 并写入 `vector_embeddings` 表，查询期不再重复编码。
   - 验证：查询期 profiling 无 encode 调用。

5. **Mock 保留**
   - 文件：`configs/rag.yaml`
   - 改动：测试 profile `embedding.provider: mock` 用于离线测试。
   - 验证：CI mock 路径全绿。

### 验证方式
- 语义召回对比：`scripts/eval_recall.py`（新增）对比 mock vs bge_m3 在测试集上的 top-k 召回率
- `pytest tests/` 全绿（含 mock 与非 mock profile）

---

## §T05 — 接入结构化日志体系

**前置**：阅读 `src/observability/` 现有结构、全仓 `except Exception` 分布（`grep -rn "except Exception" src/`）。

### 步骤
1. **日志配置**
   - 文件：`src/observability/logging_config.py`（新增）
   - 改动：配置 `structlog`，输出 JSON 结构化日志，含 `run_id`/`node`/`level`/`timestamp` 上下文处理器。
   - 验证：`get_logger(__name__)` 返回可用的 bound logger。

2. **核心模块接入**
   - 文件：`src/agent/langgraph_runtime.py`、`src/services/app_pipeline.py`、`src/knowledge/retrieval_controller.py`、`src/generation/pipeline.py`、`src/self_check/*`、`src/voice/orchestrator.py`
   - 改动：每个模块顶部 `logger = get_logger(__name__)`；关键路径加 INFO（检索开始/完成、生成开始/完成、自查结果）；异常加 `logger.exception()`。
   - 验证：一次问答日志可按 run_id 串联全链路。

3. **收敛 except Exception**
   - 操作：遍历 32 处 `except Exception:`，替换为具体异常类型（`httpx.HTTPError`/`sqlite3.OperationalError`/`json.JSONDecodeError` 等）；确需兜底处加注释说明恢复语义。
   - 文件：`langgraph_runtime.py:204` 的 `discard` → `logger.exception("node failed")` + 显式恢复动作。
   - 验证：`grep -rn "except Exception" src/` 数量 ≤ 5（仅保留有显式注释的兜底）。

4. **脱敏校验**
   - 改动：无代码改动，仅验证。
   - 验证：日志样本中无 checkpoint 明文、无用户隐私字段。

### 验证方式
- `grep -rn "except Exception" src/ | wc -l` ≤ 5
- 一次问答产出结构化日志，含完整 run_id 链路
- `pytest tests/` 全绿

---

## §T06 — 引入多级缓存层

**前置**：T01、T04 完成。阅读 `src/services/embedding_provider.py`、`src/knowledge/retrieval_controller.py`、`src/prompts/assembler.py`。

### 步骤
1. **Embedding 缓存**
   - 文件：`src/services/embedding_provider.py`
   - 改动：`encode` 方法加 `functools.lru_cache(maxsize=4096)`，key 为文本 hash。
   - 验证：重复文本第二次 encode 耗时 ≈ 0。

2. **检索结果 TTL 缓存**
   - 文件：`src/knowledge/retrieval_controller.py`
   - 改动：引入轻量 TTL dict（key=查询指纹，ttl=300s）；知识库重建时 `cache.clear()`。
   - 验证：重复查询命中缓存；重建后失效。

3. **Prompt 装配缓存**
   - 文件：`src/prompts/assembler.py`
   - 改动：按输入指纹（evidence + memory_context 的 hash）缓存装配结果。
   - 验证：相同输入第二次装配命中。

4. **命中率可观测**
   - 改动：每级缓存记录 hit/miss 日志或 metric。
   - 验证：日志可见命中率。

### 验证方式
- 重复查询性能提升 ≥ 50%（有 benchmark）
- 缓存失效逻辑测试通过

---

## §T07 — 引入 cross-encoder 二阶段重排

**前置**：T03、T04 完成。阅读 `src/knowledge/reranking.py`、`src/knowledge/fusion.py`。确认 `bge-reranker-base` 在白名单（harness §总则·白名单）。

### 步骤
1. **引入 reranker 模型**
   - 文件：`src/knowledge/reranking.py`
   - 改动：新增 `CrossEncoderReranker` 类，加载本地 `bge-reranker-base`（sentence-transformers），`rerank(query, docs) -> scored_list`。
   - 验证：模型加载成功，输出分数单调。

2. **FeatureReranker 降级为特征抽取器**
   - 文件：`src/knowledge/reranking.py`
   - 改动：`FeatureReranker` 输出特征 dict 而非最终分数；最终分数由 `CrossEncoderReranker` 给出（可加权融合）。
   - 验证：接口契约测试通过。

3. **分数归一化**
   - 文件：`src/knowledge/fusion.py`
   - 改动：各通道 `normalized_score` 在融合前统一 z-score 归一化。
   - 验证：跨通道分数量纲一致。

4. **精度对比**
   - 操作：`scripts/eval_rerank.py`（新增）对比纯特征重排 vs +cross-encoder 的 top-5 语义相关性。
   - 验证：cross-encoder 优于纯特征（有人工标注或 proxy 指标）。

### 验证方式
- 对比报告写入 `docs/优化修复计划/rerank_eval.md`
- `pytest tests/unit/knowledge/test_reranking.py` 全绿

---

## §T08 — 启用 BGE-M3 稀疏/多向量

**前置**：T04 完成。阅读 `src/memory_worker/providers/bge_m3.py`、`src/knowledge/indexes/keyword_index.py`。

### 步骤
1. **启用 sparse 输出**
   - 文件：`src/memory_worker/providers/bge_m3.py`
   - 改动：`encode` 增加 `return_sparse=True`，返回 `{"dense": [...], "sparse": {...}}`。
   - 验证：sparse 字典非空。

2. **sparse 增强 keyword 通道**
   - 文件：`src/knowledge/indexes/keyword_index.py`
   - 改动：新增 `sparse_search(query_sparse)` 方法，与 BM25 结果 RRF 融合。
   - 验证：混合召回率 ≥ 纯 BM25。

3. **ColBERT 接口暴露**
   - 文件：`src/memory_worker/providers/bge_m3.py`
   - 改动：`encode(return_colbert_vecs=True)` 暴露多向量接口（T07 精排可用）。
   - 验证：接口可调用。

### 验证方式
- `scripts/eval_sparse.py` 对比 dense vs dense+sparse 召回率
- `pytest tests/unit/knowledge/` 全绿

---

## §T09 — 拆分 LangGraphAgentRuntime God Object

**前置**：T01、T02 完成。阅读 `src/agent/langgraph_runtime.py` 全文（1398 行）。

### 步骤
1. **定义 NodeHandler 协议**
   - 文件：`src/agent/nodes/base.py`（新增）
   - 改动：`class NodeHandler(Protocol): def __call__(self, state: GraphState) -> GraphState: ...`，含 `name`/`logger`。
   - 验证：协议可被节点类实现。

2. **抽取节点类**
   - 文件：`src/agent/nodes/context_resolution.py`、`retrieve_evidence.py`、`generate_draft.py`、`self_check.py`、`finalize.py`、`rewrite.py`、`retrieve_more.py`、`supervisor_route.py`（新增）
   - 改动：将 `langgraph_runtime.py` 中 8 个节点方法迁移为独立 NodeHandler 类，逻辑等价迁移（不改行为）。
   - 验证：每节点独立可测。

3. **抽取 ArtifactRehydrator**
   - 文件：`src/agent/artifact_rehydrator.py`（新增）
   - 改动：`_rehydrate_*` 系列 ~15 方法迁移到此类。
   - 验证：rehydrate 行为等价。

4. **runtime 瘦身**
   - 文件：`src/agent/langgraph_runtime.py`
   - 改动：仅保留图编译（`_compile_graph` 引用节点类）、状态机绑定、checkpoint 协调。目标 ≤ 400 行。
   - 验证：行数检查；图行为等价。

5. **等价性回归**
   - 改动：无，仅验证。
   - 验证：`pytest tests/` 全绿，状态机转换/trace/checkpoint 输出与拆分前 byte 级等价（diff run_trace）。

### 验证方式
- `wc -l src/agent/langgraph_runtime.py` ≤ 400
- run_trace diff 为空（行为等价）

---

## §T10 — 建立工具抽象与节点注册表

**前置**：T09 完成。

### 步骤
1. **定义 Tool 协议**
   - 文件：`src/agent/tool_protocol.py`（新增）
   - 改动：`class Tool(Protocol): name: str; description: str; def invoke(self, state) -> state: ...`
   - 验证：协议定义。

2. **节点注册表**
   - 文件：`src/agent/node_registry.py`（新增）
   - 改动：`NodeRegistry` 单例，`register(name, handler)`/`get(name)`；T09 的节点类默认注册。
   - 验证：注册表可查询。

3. **_compile_graph 从注册表装配**
   - 文件：`src/agent/langgraph_runtime.py`
   - 改动：`_compile_graph` 从 `NodeRegistry` 取节点，而非硬编码方法引用。保留默认 DAG 拓扑。
   - 验证：默认行为不变；新增节点注册后无需改 `_compile_graph`。

### 验证方式
- 注册一个 dummy 节点，验证可被图调用
- `pytest tests/` 全绿

---

## §T11 — 分块/分词/术语外置化

**前置**：无。阅读 `src/knowledge/indexes/keyword_index.py:12`、`src/knowledge/ingestion/text_ingestor.py:15`。确认 `jieba` 在白名单。

### 步骤
1. **jieba 分词**
   - 文件：`src/knowledge/indexes/keyword_index.py`
   - 改动：`tokenize` 用 `jieba.cut_for_search`，移除 CJK 单字切分。
   - 验证：分词结果含词组。

2. **术语外置**
   - 文件：`configs/terminology.yaml`（新增）
   - 改动：航空术语列表（升力/机翼/发动机…）外置；`keyword_index.py` 启动时加载为 jieba 自定义词典。
   - 验证：术语被整词识别；无硬编码。

3. **句子边界**
   - 文件：`src/knowledge/ingestion/text_ingestor.py`
   - 改动：句子边界正则 `[。！？；.!?]` + 换行。
   - 验证：中英混排分块合理。

### 验证方式
- 关键词召回碎片化对比（分词前后 token 数）
- `pytest tests/unit/knowledge/` 全绿

---

## §T12 — 上下文压缩/去重/token 预算

**前置**：T07 完成。阅读 `src/prompts/assembler.py`。

### 步骤
1. **token 计数**
   - 文件：`src/prompts/assembler.py`
   - 改动：引入 tokenizer 计数（按目标模型，mock 用字符数 proxy），硬上限从配置读取。
   - 验证：超限时有截断。

2. **相关度截断 + 语义去重**
   - 文件：`src/prompts/assembler.py`
   - 改动：检索结果按 score 截断；embedding 相似度 > 阈值者去重（保留高分）。
   - 验证：去重后无近似重复段。

3. **超限优先级**
   - 改动：超限时按 score 降序保留，丢弃低分。
   - 验证：截断后 token ≤ 上限。

4. **可观测**
   - 改动：装配日志含 token 用量/丢弃数。
   - 验证：日志可见。

### 验证方式
- 长证据场景不超窗口
- `pytest tests/unit/prompts/` 全绿

---

## §T13 — SQLite 连接池 + WAL

**前置**：无。阅读 `src/knowledge/repository.py:286`、`src/agent/langgraph_checkpointer.py:41`。

### 步骤
1. **启用 WAL**
   - 文件：`src/knowledge/repository.py`、`src/agent/langgraph_checkpointer.py`
   - 改动：连接初始化 `PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;`
   - 验证：`PRAGMA journal_mode` 返回 wal。

2. **读写分离**
   - 文件：`src/knowledge/repository.py`
   - 改动：读路径用 read-only 连接池（`uri=true&mode=ro`），写路径单一连接 + per-run 锁（配合 T02）。
   - 验证：并发读写无锁。

3. **批量提交**
   - 改动：写操作批量 commit。
   - 验证：事务数下降。

### 验证方式
- 并发压测无 `database is locked`
- `pytest tests/` 全绿

---

## §T14 — 图边批量写入消除 N+1

**前置**：无。阅读 `src/knowledge/repository.py:777-793`。

### 步骤
1. **预取节点 ID**
   - 文件：`src/knowledge/repository.py`
   - 改动：`write_graph_edges_batch(edges)` 一次性 `SELECT node_id FROM graph_nodes WHERE node_id IN (...)`，构建内存 map。
   - 验证：无循环内 SELECT。

2. **批量 INSERT**
   - 改动：用 `executemany` 或 `INSERT ... ON CONFLICT DO NOTHING` 单语句。
   - 验证：批量导入耗时非线性下降。

### 验证方式
- 导入 1000 边耗时对比
- 图结构正确性测试通过

---

## §T15 — 补充生产路径测试覆盖

**前置**：T01–T04 完成。

### 步骤
1. **deepseek_client 测试**
   - 文件：`tests/unit/services/test_deepseek_client.py`（新增）
   - 改动：覆盖 async/sync 桥接、超时、重试、429、5xx、URL 校验、流式 chunk。
   - 验证：覆盖率 ≥ 90%。

2. **并发测试**
   - 文件：`tests/integration/test_app_pipeline_concurrency.py`（新增）
   - 改动：2 并发请求检索不互斥；checkpoint 写隔离；per-run 锁正确。
   - 验证：测试通过。

3. **e2e 扩充**
   - 文件：`tests/e2e/scenarios/`（新增 ≥ 3 个）
   - 改动：RETRIEVE_MORE 循环、SELF_CHECK 失败回退、REFUSE 路径。
   - 验证：场景 ≥ 6 个全绿。

### 验证方式
- `pytest tests/` 全绿
- 覆盖率报告提升

---

## §T16 — LLM 驱动查询改写

**前置**：T01 完成。阅读 `src/knowledge/query_rewriter.py`。

### 步骤
1. **HyDE 模块**
   - 文件：`src/knowledge/query_rewriter.py`
   - 改动：新增 `HyDERewriter`，调 LLM 生成假设文档，用于 dense 检索。配置开关 `query_rewrite.hyde: false`。
   - 验证：开关开启时生成假设文档。

2. **多查询变体**
   - 文件：`src/knowledge/query_rewriter.py`
   - 改动：新增 `MultiQueryRewriter`，生成 N 个变体并发检索后 RRF。
   - 验证：召回提升。

3. **配置**
   - 文件：`configs/rag.yaml`
   - 改动：`query_rewrite` 段新增 hyde/multi_query 开关与参数。
   - 验证：默认关闭，不影响稳定路径。

### 验证方式
- 复杂问题召回对比
- `pytest tests/` 全绿

---

## §T17 — 统一 KeywordIndex

**前置**：T08 完成。阅读 `src/knowledge/indexes/keyword_index.py`。

### 步骤
1. **移除内存词频索引**
   - 文件：`src/knowledge/indexes/keyword_index.py`
   - 改动：删除内存词频实现，仅保留 FTS5 BM25。
   - 验证：仅一套实现。

2. **BGE-M3 sparse 补充评估**
   - 改动：评估 T08 的 sparse 是否可替代部分 BM25；若可行则融合。
   - 验证：召回等价或更优。

### 验证方式
- 行为对比测试通过
- `pytest tests/` 全绿

---

## §T18 — 多 Agent 协作可行性评估

**前置**：T09、T10 完成。**本任务仅产出文档，不改代码。**

### 步骤
1. **产出评估文档**
   - 文件：`docs/优化修复计划/multi_agent_assessment.md`（新增）
   - 改动：分析 supervisor-worker 拆分（检索/生成/自查为子 Agent）的利弊、业务必要性、实施成本、对现有契约的影响。
   - 验证：文档含明确"建议/不建议"结论与依据。

### 验证方式
- 文档评审

---

## §T19 — 简化双轨制恢复

**前置**：T09 完成。阅读 `src/agent/artifact_rehydrator.py`（T09 产出）。

### 步骤
1. **评估方案**
   - 文件：`docs/优化修复计划/rehydrate_simplification.md`（新增）
   - 改动：对比"checkpoint 权威单一源" vs "显式状态版本号"两种方案的风险与收益。
   - 验证：文档含推荐方案。

2. **若实施：收敛 rehydrate 路径**
   - 文件：`src/agent/artifact_rehydrator.py`
   - 改动：按选定方案简化，移除冗余指纹匹配。
   - 验证：run_trace 等价；`pytest tests/` 全绿。

### 验证方式
- run_trace diff 为空
- `pytest tests/` 全绿

---

文档完。任务清单见 `task.md`，约束规范见 `harness.md`。
