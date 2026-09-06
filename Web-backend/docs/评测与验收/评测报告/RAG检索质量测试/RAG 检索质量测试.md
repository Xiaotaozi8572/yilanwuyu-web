# RAG 系统 100 题基准测试报告

- 测试时间：553.7s（100 题 × 2 次检索 + 缓存演示）
- 知识库：`data/processed/knowledge.sqlite3`（98.6 MB，3189 块，活跃索引 `index_b32dcafd946c`）
- Embedding：BAAI/bge-m3（本地，1024 维，is_mock=0），provider=`bge_m3`

## 1. 执行摘要

- **整体召回率（系统原样）**：FusedRecall=0.404，Recall@3=0.378，Recall@5=0.411
- **诊断上限（放宽查询过滤器后）**：FusedRecall=0.879，Recall@3=0.700 → 检索引擎本身可用，召回崩溃主要源于查询理解层过滤器
- **过滤器导致的召回损失**：47 题
- **aircraft 大小写错配阻断**：33 题（所有含 C919/AG600 字样的查询全部归零；歼-20/运-20/直-20 中文名未收录进别名表，反而无过滤、可正常检索）
- 平均检索延迟：2613.0ms（p95 10835.6ms）

## 2. 测试方法

### 2.1 题库构成

| 维度 | 题数 | 说明 |
|---|---|---|
| A. 信息检索准确性 | 35 | 每题绑定黄金来源（gold source_id，均已在库内校验存在） |
| B. 相关性排序 | 20 | 每题绑定黄金来源（gold source_id，均已在库内校验存在） |
| C. 生成-源文档一致性 | 20 | 每题绑定黄金来源（gold source_id，均已在库内校验存在） |
| D. 复杂查询 | 15 | 每题绑定黄金来源（gold source_id，均已在库内校验存在） |
| E. 鲁棒性与边界 | 10 | 每题绑定黄金来源（gold source_id，均已在库内校验存在） |

### 2.2 指标定义

检索链路分三级口径，避免用「候选层召回」冒充「最终可用召回」：

- **candidate（候选层 / document recall）**：黄金来源是否出现在 RRF 融合后的候选集——即 FusedRecall，反映纯检索层召回，**不含**资格/证据门过滤。
- **qualified（资格层）**：候选集经过资格/证据门（gate）后仍保留的候选项命中情况。
- **final-context（最终上下文层）**：黄金来源是否出现在最终证据包 top-k——即 Recall@k，反映生成实际可用的召回。

- **FusedRecall（= candidate 层 document recall）**：黄金来源是否出现在 RRF 融合后的候选集（含被资格/证据门拒绝的候选项），反映纯检索层召回。
- **Recall@k**：黄金来源是否出现在最终证据包（gate 通过后）的 top-k 证据中，反映生成实际可用的召回。
- **MRR**：黄金来源在最终证据中的首个命中排名的倒数；**Precision@5**：前 5 条证据中黄金来源占比。
- **诊断回放（relaxed filters）**：对同一问题把 plan 的过滤器降级为仅 `allowed_review_status`，单独评估检索引擎能力，差值即查询理解层过滤器造成的召回损失。
- **关于保守性**：Recall@k 以「黄金来源是否出现在 top-k 证据的 source 中」计（来源级），而证据按 chunk 级排序；当黄金来源存在但被排到 top-k 之外（如 A13/A16 属 fused 命中但 rank>3）时计为未召回，故 Recall@k 是保守下界。

## 3. 整体召回率统计

### 3.1 系统原样 vs 诊断上限

| 指标 | 系统原样 | 诊断上限（放宽过滤） | 说明 |
|---|---|---|---|
| FusedRecall | 0.404 | 0.879 | 融合候选层 |
| Recall@1 | 0.289 | 0.511 | 证据包首位 |
| Recall@3 | 0.378 | 0.700 | 证据包前3 |
| Recall@5 | 0.411 | 0.778 | 证据包前5 |
| Recall@10 | 0.422 | 0.844 | 证据包前10 |
| MRR | 0.305 | 0.566 | 排序质量 |
| Precision@5 | 0.079 | 0.149 | 排序精度 |

### 3.2 各维度召回

| 维度 | 题数 | FusedRecall | Recall@3 | Recall@5 | MRR | gate分布 | 错误用例 |
|---|---|---|---|---|---|---|---|
| A. 信息检索准确性 | 35 | 0.429 | 0.314 | 0.400 | 0.270 | weak:18 / confident:17 | 0 |
| B. 相关性排序 | 20 | 0.900 | 0.850 | 0.850 | 0.763 | confident:20 | 0 |
| C. 生成-源文档一致性 | 20 | 0.250 | 0.267 | 0.267 | 0.175 | weak:16 / confident:4 | 0 |
| D. 复杂查询 | 15 | 0.133 | 0.133 | 0.133 | 0.133 | weak:12 / confident:3 | 0 |
| E. 鲁棒性与边界 | 10 | 0.000 | 0.000 | 0.000 | 0.000 | confident:2 / weak:7 | 1 |

### 3.3 失败根因分类

| 根因 | 题数 | 代表题目 |
|---|---|---|
| 命中 | 40 | A13, A14, A15, A16, A18, A19… |
| aircraft 大小写错配 | 33 | A01, A02, A03, A04, A05, A06… |
| 别名过滤器错配 | 20 | A17, A20, A21, A24, A25, A33… |
| 理解层异常 | 1 | E04 |
| 无黄金来源(域外) | 6 | C06, C07, C16, E01, E03, E10 |

## 4. 关键缺陷（根因链）

1. **aircraft 元数据大小写错配（最高优先级）**：`text_chunks.aircraft` 存小写 `c919/j20/y20/z20`，查询理解层 `AIRCRAFT_ALIASES` 仅收录 C919/AG600 且产出大写 `C919`。`_matches_filters` 严格相等比较 `'C919' != 'c919'`，导致**所有含 C919/AG600 字样的查询，keyword 与 dense 双通道全部零命中**（33 题）。反直觉的是：歼-20/运-20/直-20 的中文名未收录进别名表，查询反而无 aircraft 过滤、能正常检索。文件：`src/input/query_understanding.py:8-13`、`src/knowledge/indexes/keyword_index.py:89-100`、`src/knowledge/indexes/vector_store.py:688-699`。
2. **component/concept 别名过滤器与元数据词汇不一致**：`CONCEPT_ALIASES` 把 `阻力/涵道比/失速` 映射为同词，而库内 chunk 的 `concept` 是 `supercritical_airfoil/engine_bypass_ratio/stall_margin` 等；`COMPONENT_ALIASES` 把 `翼/机翼` 映射为 `wing`，而相关原理内容 chunk 的 `component` 是 `principle`。过滤器把真正相关的证据全部排除。文件：`src/input/query_understanding.py:15-30`。
3. **复杂度分类器优先级缺陷**：`component_scene` 意图优先于因果标记，`为什么超临界翼型能降低巡航阻力` 被分到 L2（scene+keyword）而非 L3（dense+parent），且 scene 通道无数据、keyword 被别名过滤 → 零证据。文件：`src/knowledge/retrieval_planner.py:63-78`。
4. **空查询/乱码在理解层抛异常**：`understand_query('')` 直接抛 `ContractValidationError: raw_query: required field is empty`，未做输入防御（E04 用例使整个进程崩溃）。文件：`src/input/query_understanding.py:84-101`。

## 5. 生成-源文档一致性（C 维度）

以证据门（EvidenceGate）为生成一致性的结构性保障：生成只使用通过资格+相关性+权威审核的证据。

| 行为假设 | 题数 | 结果 | 结论 |
|---|---|---|---|
| 有据可答（expect=hit） | 14 | 见下方命中明细 | 证据包存在且 gate=confident 时生成有据可依 |
| 无据应拒绝（expect=weak） | 6 | gate 分布见上 | 域外问题多被判 weak + missing，未编造 |

命中明细（gate=confident 且黄金在 top3 的题 = 可一致生成的题）：

| 题号 | 查询 | gate | 黄金@top3 |
|---|---|---|---|
| C01 | C919 的巡航马赫数是多少？ | weak | ✗ |
| C02 | C919 超临界翼型的阻力发散马赫数是多少？ | weak | ✗ |
| C03 | 激波会产生哪些不利影响？ | confident | ✓ |
| C04 | C919 发动机的推力数值是多少？ | weak | ✗ |
| C05 | C919 的座位数是多少？ | weak | ✗ |
| C06 | 波音 737 的最大巡航速度是多少？ | weak | ✗ |
| C07 | 歼-20 的单价是多少？ | weak | ✗ |
| C08 | C919 油箱的容量是多少升？ | weak | ✗ |
| C09 | 空客 A380 的翼展是多少？ | weak | ✗ |
| C10 | 长征五号火箭的推力是多少？ | weak | ✗ |
| C11 | 运-20 的最大载重是多少？ | weak | ✓ |
| C12 | 直-20 的巡航速度是多少？ | weak | ✗ |
| C13 | C919 的单价是多少？ | weak | ✗ |
| C14 | 涡扇发动机的涵道比越大越好吗？ | weak | ✗ |
| C15 | C919 采用了钛合金材料吗？ | weak | ✗ |
| C16 | 波音 747 是双层客机吗？ | confident | ✗ |
| C17 | C919 的失速速度是多少？ | weak | ✗ |
| C18 | 运-8 的用途是什么？ | confident | ✓ |
| C19 | C919 有哪些国际合作？ | weak | ✗ |
| C20 | 什么是马赫数？ | confident | ✓ |

**一致性异常观察**：
- C16「波音 747 是双层客机吗？」（域外，无黄金来源）gate=**confident**：库内对比类来源含 747 字样的片段被当作核心证据，存在「用 C919 对比素材回答域外问题」的越界生成风险。
- E01「波音 737 的维修步骤是什么？」（域外+安全敏感）gate=**confident** 且 intent=operation_safety：安全敏感问题本应走安全兜底，但检索层仍给出了高置信证据，需在生成层确认 safety classifier 是否拦截。

### 5.1 真实生成抽样（DeepSeek，3 题）

对 3 道题现场重跑检索，取证据包 top-3 真实内容喂给 `deepseek-v4-flash`，要求仅依据证据作答并标注引用：

| 题号 | 查询 | 引用 | 落地率 | 结论 |
|---|---|---|---|---|
| A22 | 激波会产生哪些不利影响？ | [1] | 1.0 | 答案逐句忠于证据 |
| B06 | 液压系统的余度设计有什么特点？ | [] | 0.0 | 答案有据但引用格式为 sources 非 [n]（启发式误判，见注） |
| A32 | 什么是第五代战斗机？ | [1] | 1.0 | 答案逐句忠于证据 |

> 注：A22「激波不利影响」与 A32「五代机定义」的回答与证据逐句一致并标注 `[1]`；B06 回答内容有据（三余度/CCAR-25），但用 `sources:[…]` 标注，词袋落地率启发式未能识别，属检测假阴性而非越界。

## 6. 记忆系统（向量库）状态评估

### 6.1 检索耗时

- 整体检索延迟：均值 2613.0ms，p50 1916.7ms，p95 10835.6ms，峰值 11668.1ms
- 各通道延迟（跨全部 200 次执行）：

| 通道 | 执行次数 | 均值(ms) | p95(ms) | 峰值(ms) |
|---|---|---|---|---|
| keyword | 93 | 162.4 | 633.9 | 1087.8 |
| dense | 82 | 2944.3 | 9989.7 | 10927.8 |
| scene | 17 | 0.0 | 0.1 | 0.1 |
| parent | 6 | 470.0 | 587.0 | 587.0 |
| graph | 3 | 0.1 | 0.1 | 0.1 |

### 6.2 缓存（cache hit/miss）

| 项目 | 值 |
|---|---|
| 检索 TTL 缓存命中 | 3 |
| 检索 TTL 缓存未命中 | 197 |
| 缓存命中率 | 0.015 |
| 重复查询演示 | 第二次调用 1ms 级（TTL 300s 生效，返回同一 evidence package：True） |

### 6.3 Embedding 子系统

| 项目 | 值 |
|---|---|
| Provider / Model | bge_m3 / BAAI/bge-m3（本地） |
| is_mock | False（真实语义向量） |
| 本次测试 embed 调用次数 | 235 |
| 单次 embed 平均耗时 | 106.8ms |

### 6.4 资源占用与索引

| 项目 | 值 |
|---|---|
| 知识库文件大小 | 98.6 MB（WAL 模式，page 4096 × 25236） |
| 进程 RSS（实测） | 加载控制器+BGE-M3 后约 **1960 MB**（模型驻留 ~1.9 GB，进程基线 13 MB） |
| 向量数 / 维度 | 3189 / 1024 |
| 活跃索引版本 | index_b32dcafd946c（bge_m3, is_mock=0） |

### 6.5 性能瓶颈（关键）

1. **ANN 索引被系统性旁路（最大瓶颈）**：`SQLiteVectorStore.search` 仅在 `not filters` 时走 sqlite-vec `vec0` ANN 快速路径，而 `RetrievalPlanner` **总是**注入 `allowed_review_status`（甚至 `intent_type`），因此 dense 通道 100% 落入暴力全量扫描：Python 循环对 3189 个 1024 维向量逐行计算余弦相似度。文件：`src/knowledge/indexes/vector_store.py:393-408`、`src/knowledge/retrieval_planner.py:140-147`。这是单次检索 1.7~2.8s 的主要来源之一。
2. **BGE-M3 为 CPU 本地推理**：每次查询 ~1-2s，与向量扫描叠加后整体延迟放大。
3. **查询理解层空转**：大量查询在过滤器阶段即被排除，检索管线的计算资源浪费在空候选上。

## 7. 修复建议（按优先级）

1. **统一 aircraft 大小写**：入库时规范化为大写（或查询侧小写化），并把 `_matches_filters` 的 aircraft 比较改为大小写不敏感；优先修复 `query_understanding.py` 或 `keyword_index.py/vector_store.py` 的过滤比较。
2. **别名过滤器语义化**：把 concept 别名映射到库内实际概念值（如 `阻力→supercritical_airfoil 相关` 需走语义而不是严格相等），或将 concept 过滤改为词项包含而非严格相等。
3. **修复复杂度分类优先级**：先命中因果/对比标记再考虑 component_scene。
4. **空查询输入防御**：`understand_query` 对空/纯标点输入返回 clarification 而非抛异常。
5. **启用 ANN 快速路径**：把 `allowed_review_status` 等恒定过滤从 ANN 判定中剥离（如仅按是否存在 aircraft/component/concept 走暴力路径），预计 dense 检索延迟可降 10 倍以上。

## 8. 附：失败/命中明细

> 完整 100 题逐题数据见 `tmp/rag_bench_results.json`（cases + diagnostic_cases）。

### A. 信息检索准确性

| 题号 | 查询 | 意图 | 复杂度 | 通道 | gate | 命中 | Recall@3 | 根因 |
|---|---|---|---|---|---|---|---|---|
| A01 | C919 的翼展是多少米？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A02 | C919 的巡航马赫数是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A03 | C919 的机身全长是多少米？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A04 | C919 采用的是哪款发动机？ | component_scene | L2 | scene+keyword | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A05 | C919 的客舱布局是什么样的？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A06 | C919 的国产化率大约是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A07 | C919 首飞是哪一天？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A08 | C919 什么时候取得型号合格证？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A09 | C919 的最大起飞重量是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A10 | C919 的实用升限是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A11 | C919 的制造商是哪家公司？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A12 | C919 的客舱宽度是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| A13 | 歼-20 采用的是哪种气动布局？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A14 | 歼-20 的进气道采用了什么设计？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 0.000 | 命中 |
| A15 | 歼-20 装备了什么雷达？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 0.000 | 命中 |
| A16 | 运-20 的货舱宽度是多少？ | parameter_fact | L1 | keyword+dense | weak | ✓ | 0.000 | 命中 |
| A17 | 运-20 采用了几台发动机？ | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| A18 | 直-20 的最大起飞重量是多少？ | parameter_fact | L1 | keyword+dense | weak | ✓ | 1.000 | 命中 |
| A19 | 直-20 装备了什么飞控系统？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A20 | 运-20 的机翼是什么结构？ | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| A21 | 超临界翼型的阻力发散马赫数是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | 别名过滤器错配 |
| A22 | 激波会产生哪些不利影响？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A23 | 电传操纵相比机械操纵有什么优势？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A24 | 涡扇发动机的推力是怎么产生的？ | component_scene | L2 | scene+keyword | weak | ✗ | 0.000 | 别名过滤器错配 |
| A25 | 翼尖小翼有什么作用？ | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| A26 | 铝锂合金相比普通铝合金有什么优势？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A27 | 复合材料有什么特点？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 0.000 | 命中 |
| A28 | 什么是飞行包线？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A29 | 有源相控阵雷达的工作原理是什么？ | concept_explanation | L3 | dense+parent | confident | ✓ | 1.000 | 命中 |
| A30 | 鸭式布局的优势有哪些？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A31 | 红外隐身的基本原理是什么？ | concept_explanation | L3 | dense+parent | confident | ✓ | 1.000 | 命中 |
| A32 | 什么是第五代战斗机？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| A33 | 后掠翼有什么作用？ | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| A34 | 旋翼是怎么产生升力的？ | component_scene | L2 | scene+keyword | weak | ✗ | 0.000 | 别名过滤器错配 |
| A35 | 涡升力是什么？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | 别名过滤器错配 |

### B. 相关性排序

| 题号 | 查询 | 意图 | 复杂度 | 通道 | gate | 命中 | Recall@3 | 根因 |
|---|---|---|---|---|---|---|---|---|
| B01 | 激波对翼型性能有哪些影响？ | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| B02 | 超临界翼型相比常规翼型有什么改进？ | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| B03 | 复合材料在航空中有哪些应用？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B04 | 电传飞控的余度设计是怎样的？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 0.000 | 命中 |
| B05 | 航电系统的 IMA 架构是什么？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B06 | 液压系统的余度设计有什么特点？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B07 | 燃油系统的惰性气体系统是做什么的？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B08 | 起落架的刹车系统由谁提供？ | component_scene | L2 | scene+keyword | confident | ✓ | 1.000 | 命中 |
| B09 | 飞机的防冰系统有哪些类型？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B10 | 适航取证有哪些符合性方法？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B11 | 复合材料力学的各向异性是什么？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B12 | 疲劳与损伤容限设计的原则是什么？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B13 | 电传操纵的控制律有哪些模式？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B14 | S-N 曲线描述的是什么？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B15 | 高速飞行中的气动加热是怎么产生的？ | concept_explanation | L3 | dense+parent | confident | ✓ | 1.000 | 命中 |
| B16 | 弹射座椅的作用是什么？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B17 | 机载数据链的主要功能是什么？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B18 | 光电瞄准系统用于完成什么任务？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B19 | 空中受油系统有什么作用？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| B20 | 大迎角机动的基本原理是什么？ | concept_explanation | L3 | dense+parent | confident | ✓ | 1.000 | 命中 |

### C. 生成-源文档一致性

| 题号 | 查询 | 意图 | 复杂度 | 通道 | gate | 命中 | Recall@3 | 根因 |
|---|---|---|---|---|---|---|---|---|
| C01 | C919 的巡航马赫数是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C02 | C919 超临界翼型的阻力发散马赫数是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C03 | 激波会产生哪些不利影响？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| C04 | C919 发动机的推力数值是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C05 | C919 的座位数是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C06 | 波音 737 的最大巡航速度是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | - | 无黄金来源(域外) |
| C07 | 歼-20 的单价是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | - | 无黄金来源(域外) |
| C08 | C919 油箱的容量是多少升？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C09 | 空客 A380 的翼展是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | - | 别名过滤器错配 |
| C10 | 长征五号火箭的推力是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | - | 别名过滤器错配 |
| C11 | 运-20 的最大载重是多少？ | parameter_fact | L1 | keyword+dense | weak | ✓ | 1.000 | 命中 |
| C12 | 直-20 的巡航速度是多少？ | parameter_fact | L1 | keyword+dense | weak | ✓ | 0.000 | 命中 |
| C13 | C919 的单价是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C14 | 涡扇发动机的涵道比越大越好吗？ | component_scene | L2 | scene+keyword | weak | ✗ | 0.000 | 别名过滤器错配 |
| C15 | C919 采用了钛合金材料吗？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C16 | 波音 747 是双层客机吗？ | concept_explanation | L1 | keyword+dense | confident | ✗ | - | 无黄金来源(域外) |
| C17 | C919 的失速速度是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C18 | 运-8 的用途是什么？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| C19 | C919 有哪些国际合作？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| C20 | 什么是马赫数？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |

### D. 复杂查询

| 题号 | 查询 | 意图 | 复杂度 | 通道 | gate | 命中 | Recall@3 | 根因 |
|---|---|---|---|---|---|---|---|---|
| D01 | 升力与推力有什么区别？ | comparison | L4 | keyword+dense+graph | weak | ✗ | 0.000 | 别名过滤器错配 |
| D02 | C919 与空客 A320 相比有哪些差异？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D03 | C919 的航程与 A320 相比如何？ | concept_explanation | L3 | dense+parent | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D04 | 为什么超临界翼型能降低巡航阻力？ | component_scene | L2 | scene+keyword | weak | ✗ | 0.000 | 别名过滤器错配 |
| D05 | C919 的电传飞控与普通飞控有什么区别？ | concept_explanation | L4 | keyword+dense+graph | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D06 | 运-20 与伊尔-76 哪个载重更大？ | concept_explanation | L1 | keyword+dense | confident | ✓ | 1.000 | 命中 |
| D07 | C919 国产化率与制造工艺有什么关系？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D08 | C919 的超临界机翼设计经历了哪些风洞试验？ | component_scene | L2 | scene+keyword | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D09 | 歼-20 的隐身设计与发动机有什么关系？ | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| D10 | C919 的复合材料占比是多少？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D11 | C919 的首飞与取证相隔了多久？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D12 | C919 适航取证需要满足哪些规章？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| D13 | 直-20 与 UH-60 的定位有什么不同？ | concept_explanation | L4 | keyword+dense+graph | confident | ✓ | 1.000 | 命中 |
| D14 | 涵道比越大，燃油效率越高吗？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | 别名过滤器错配 |
| D15 | C919 的哪些部件由合资企业供应？ | component_scene | L2 | scene+keyword | weak | ✗ | 0.000 | aircraft 大小写错配 |

### E. 鲁棒性与边界

| 题号 | 查询 | 意图 | 复杂度 | 通道 | gate | 命中 | Recall@3 | 根因 |
|---|---|---|---|---|---|---|---|---|
| E01 | 波音 737 的维修步骤是什么？ | operation_safety | L1 | keyword+dense | confident | ✗ | - | 无黄金来源(域外) |
| E02 | 升力 | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | 别名过滤器错配 |
| E03 | ？？？ | concept_explanation | L1 | keyword+dense | weak | ✗ | - | 无黄金来源(域外) |
| E04 |  | - | - | - | - | ✗ | - | 异常:ContractValidationError: raw_query: requ |
| E05 | C919 怎么进行故障处置？ | operation_safety | L3 | dense+parent | weak | ✗ | - | aircraft 大小写错配 |
| E06 | C919 的飞机翅膀有多长？ | parameter_fact | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| E07 | 请简单一点解释超临界机翼 | component_scene | L2 | scene+keyword | confident | ✗ | 0.000 | 别名过滤器错配 |
| E08 | 你刚才说错了，涵道比不是这个数 | feedback | L2 | scene+keyword | weak | ✗ | 0.000 | 别名过滤器错配 |
| E09 | C919 与 A320 哪个更好？ | concept_explanation | L1 | keyword+dense | weak | ✗ | 0.000 | aircraft 大小写错配 |
| E10 | 什么是？？ | concept_explanation | L1 | keyword+dense | weak | ✗ | - | 无黄金来源(域外) |

---

## 9. 修复前后对比（T15/T16 前后，2026-08-15 复核）

> 本节约数据来源文件（tmp/ 下，不入库）：
> - **T15/T16 前**：`tmp/rag_bench_results_pre_t15t16.json`（2026-08-11 21:36 生成，即 T15/T16 之前的最后一次全量 bench，203.0s）
> - **T15/T16 后**：`tmp/rag_bench_results.json`（2026-08-15 18:29 生成，T17 复核重跑，202.6s）
>
> 本节只对 **gold 非空**的题统计召回/排序指标（gold_n=90，weak_n=10，两次运行一致），与本文档第 1–8 节的早期口径不同（早期口径为全量 100 题平均、且当时缺陷未修复，故数值差异大）。

### 9.1 总体指标对比

| 指标 | T15/T16 前 | T15/T16 后 | 变化 |
|---|---|---|---|
| FusedRecall（融合候选层） | 0.9556 | 0.9889 | **+0.0333** |
| Recall@1 | 0.4778 | 0.5000 | +0.0222 |
| Recall@3 | 0.7222 | 0.7000 | −0.0222 |
| Recall@5 | 0.7556 | 0.7778 | +0.0222 |
| MRR | 0.6068 | 0.6173 | +0.0105 |
| Precision@5 | 0.1600 | 0.1644 | +0.0044 |
| 延迟 p50 | 374.8ms | 369.9ms | −4.9ms |
| 延迟 p95 | 3909.7ms | 4164.3ms | +254.6ms |
| 延迟均值 | 1537.1ms | 1540.3ms | +3.2ms |
| 过滤器致召回损失（题） | 0 | 0 | — |
| aircraft 过滤零命中（题） | 2（D15、E05） | 2（D15、E05） | — |
| 诊断上限 FusedRecall（放宽过滤） | 0.9000 | 0.9889 | +0.0889 |
| 总耗时 | 203.0s | 202.6s | ≈持平 |

**结论摘要**：T15/T16 后 FusedRecall 提升至 0.9889（89/90），Recall@1/MRR/Precision@5 同步小幅提升；Recall@3 下降 0.0222（由 D01/D03/B04 三道题的证据排序变化所致，见 9.3）。诊断上限与系统原样几乎重合，说明过滤器已不再是召回瓶颈。p95 延迟 +254.6ms 属 dense 通道随机波动（dense 通道 p95 两次为 3619.6ms / 3905.1ms），无结构性劣化。

### 9.2 分维度对比

| 维度 | FusedRecall 前→后 | Recall@1 前→后 | Recall@3 前→后 | MRR 前→后 |
|---|---|---|---|---|
| A. 信息检索准确性 | 0.9429 → 1.0000 | 0.3714 → 0.4857 | 0.6286 → 0.6571 | 0.5284 → 0.6024 |
| B. 相关性排序 | 1.0000 → 1.0000 | 0.7500 → 0.7500 | 1.0000 → 0.9500 | 0.8417 → 0.8383 |
| C. 生成-源文档一致性 | 1.0000 → 1.0000 | 0.5333 → 0.5333 | 0.6667 → 0.6667 | 0.6000 → 0.6000 |
| D. 复杂查询 | 0.8667 → 0.9333 | 0.3333 → 0.2000 | 0.7333 → 0.6000 | 0.5356 → 0.4229 |
| E. 鲁棒性与边界 | 1.0000 → 1.0000 | 0.4000 → 0.4000 | 0.4000 → 0.4000 | 0.4500 → 0.4722 |

### 9.3 逐题变化明细（跨两次运行）

fused_recall 由 0 → 1（3 题，均为 dense 通道 ANN 重建后命中）：

| 题号 | 查询 | 说明 |
|---|---|---|
| A33 | 后掠翼有什么作用？ | T15 ANN 索引重建后融合命中黄金来源 |
| A34 | 旋翼是怎么产生升力的？ | 同上 |
| D09 | 歼-20 的隐身设计与发动机有什么关系？ | 同上 |

Recall@3 变化（有 gold 的题，4 题）：

| 题号 | 查询 | Recall@3 前→后 | 说明 |
|---|---|---|---|
| A20 | 运-20 的机翼是什么结构？ | 0.0 → 1.0 | 改善：黄金来源进入证据 top-3 |
| B04 | 电传飞控的余度设计是怎样的？ | 1.0 → 0.0 | 回归：gold 仍在融合候选（fused=1），排序跌出 top-3 |
| D01 | 升力与推力有什么区别？ | 1.0 → 0.0 | 回归：同 B04，排序变化（弱 gate，证据门槛未拦截） |
| D03 | C919 的航程与 A320 相比如何？ | 1.0 → 0.0 | 回归：gold 跌出 top-3（新 run 中排第 6 位，fused=1） |

> 注：B04/D01/D03 的 fused_recall 两次均为 1.0，属证据排序（RRF 融合后 top-k 截断）层面的回归，不是过滤召回回归；由 T15 的 ANN 索引重建改变 dense 通道得分分布所致。

### 9.4 C919 逐题核对（大小写 bug 状态——分歧 D-6 结论）

**D-6 结论：C919 大小写 bug 未再复现，C919 查询不再因大小写「团灭」。**

依据：两次运行中，33 道 C919 查询的 `fused_recall=1.0` 均为 **31/33**（D15、E05 两道除外）；查询理解层输出过滤器 `aircraft="C919"`（大写），经 R1/T16 的大小写不敏感 `aircraft_matches`（共享实现 `src/knowledge/indexes/filter_utils.py`）匹配库内小写 `c919`，keyword/dense/parent/table 全通道一致，未再出现“所有含 C919 查询全部零命中”的团灭现象（早期报告第 3.3 节 33 题 aircraft 大小写错配已归零）。

逐题核对表（33 题；fused=融合候选层命中，r@1/r@3 仅对有 gold 的题统计，`-` 表示无 gold 不统计）：

| 题号 | 查询 | 有gold | fused 前→后 | r@1 前→后 | r@3 前→后 | gate 前→后 | 大小写 bug 复现 |
|---|---|---|---|---|---|---|---|
| A01 | C919 的翼展是多少米？ | 是 | 1→1 | 0→0 | 1→1 | weak→weak | 否 |
| A02 | C919 的巡航马赫数是多少？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| A03 | C919 的机身全长是多少米？ | 是 | 1→1 | 1→1 | 1→1 | weak→weak | 否 |
| A04 | C919 采用的是哪款发动机？ | 是 | 1→1 | 0→0 | 0→0 | confident→confident | 否 |
| A05 | C919 的客舱布局是什么样的？ | 是 | 1→1 | 1→1 | 1→1 | confident→confident | 否 |
| A06 | C919 的国产化率大约是多少？ | 是 | 1→1 | 1→1 | 1→1 | weak→weak | 否 |
| A07 | C919 首飞是哪一天？ | 是 | 1→1 | 1→1 | 1→1 | confident→confident | 否 |
| A08 | C919 什么时候取得型号合格证？ | 是 | 1→1 | 1→1 | 1→1 | confident→confident | 否 |
| A09 | C919 的最大起飞重量是多少？ | 是 | 1→1 | 1→1 | 1→1 | weak→weak | 否 |
| A10 | C919 的实用升限是多少？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| A11 | C919 的制造商是哪家公司？ | 是 | 1→1 | 0→0 | 0→0 | confident→confident | 否 |
| A12 | C919 的客舱宽度是多少？ | 是 | 1→1 | 1→1 | 1→1 | weak→weak | 否 |
| C01 | C919 的巡航马赫数是多少？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| C02 | C919 超临界翼型的阻力发散马赫数是多少？ | 是 | 1→1 | 1→1 | 1→1 | weak→weak | 否 |
| C04 | C919 发动机的推力数值是多少？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| C05 | C919 的座位数是多少？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| C08 | C919 油箱的容量是多少升？ | 是 | 1→1 | 1→1 | 1→1 | weak→weak | 否 |
| C13 | C919 的单价是多少？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| C15 | C919 采用了钛合金材料吗？ | 是 | 1→1 | 1→1 | 1→1 | confident→confident | 否 |
| C17 | C919 的失速速度是多少？ | 是 | 1→1 | 0→0 | 1→1 | weak→weak | 否 |
| C19 | C919 有哪些国际合作？ | 是 | 1→1 | 1→1 | 1→1 | confident→confident | 否 |
| D02 | C919 与空客 A320 相比有哪些差异？ | 是 | 1→1 | 0→0 | 1→1 | confident→confident | 否 |
| D03 | C919 的航程与 A320 相比如何？ | 是 | 1→1 | 1→0 | 1→0 | confident→confident | 否（排序回归，见 9.3） |
| D05 | C919 的电传飞控与普通飞控有什么区别？ | 是 | 1→1 | 0→0 | 1→1 | confident→confident | 否 |
| D07 | C919 国产化率与制造工艺有什么关系？ | 是 | 1→1 | 0→0 | 1→1 | confident→confident | 否 |
| D08 | C919 的超临界机翼设计经历了哪些风洞试验？ | 是 | 1→1 | 0→0 | 1→1 | confident→confident | 否 |
| D10 | C919 的复合材料占比是多少？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| D11 | C919 的首飞与取证相隔了多久？ | 是 | 1→1 | 0→0 | 1→1 | confident→confident | 否 |
| D12 | C919 适航取证需要满足哪些规章？ | 是 | 1→1 | 0→0 | 0→0 | confident→confident | 否 |
| D15 | C919 的哪些部件由合资企业供应？ | 是 | 0→0 | 0→0 | 0→0 | confident→confident | 否（scene 通道无数据，非大小写问题） |
| E05 | C919 怎么进行故障处置？ | 否 | 0→0 | -→- | -→- | confident→confident | 否（域外无黄金来源） |
| E06 | C919 的飞机翅膀有多长？ | 是 | 1→1 | 0→0 | 0→0 | weak→weak | 否 |
| E09 | C919 与 A320 哪个更好？ | 是 | 1→1 | 1→1 | 1→1 | confident→confident | 否 |

**C919 汇总**：33 题中 32 题有 gold；fused=1 两次均为 31/33（93.9%）。两道 fused=0 的题（D15、E05）与大小写无关：D15 为 component_scene 意图、scene 通道无数据且 keyword 别名过滤未命中黄金来源；E05 为 operation_safety 域外题、无黄金来源（gold=[]，不计入召回指标）。所有含 C919 查询在两次运行中过滤器均产出 `aircraft="C919"` 且命中库内小写 `c919` 数据——**大小写 bug 未复现**。

---

## 10. 评测口径并列与通道消融（T22，2026-08-15）

> 本节数据来源文件（tmp/ 下，不入库）：
> - 旧口径（分母 100）：`tmp/rag_bench_t22_100_all.json`（2026-08-15 20:24 生成，693.4s）
> - 新口径（分母 90）：`tmp/rag_bench_t22_90_all.json`（2026-08-15 19:30 生成，211.7s）
> - 消融 bm25：`tmp/rag_bench_t22_90_bm25.json`（2026-08-15 19:33 生成，136.0s）
> - 消融 dense：`tmp/rag_bench_t22_90_dense.json`（2026-08-15 19:51 生成，538.8s）
> - 复核组：`tmp/rag_bench_t22_90_all_recheck.json`（2026-08-15 19:52 生成，741.5s）
>
> 运行命令示例：`python scripts/_rag_bench.py --denominator 100 --channel all --output tmp/rag_bench_t22_100_all.json`
> （T22 起 bench 脚本支持 `--denominator {100,90}` 与 `--channel {all,bm25,dense}` 参数开关，
> bm25 对应 keyword 通道；`--channel` 只覆盖 plan.channels，其余环节——查询理解过滤器、RRF 融合、
> 证据门、诊断回放——均不变。三组消融的 gate 分布完全一致（confident 71 / weak 29），证明通道切换
> 不改变证据门判定。）

### 10.1 口径说明（变更原因成文）

**结论：召回/排序指标统一采用「90 题口径」（只对 gold 非空的题取均值，T17 起已按此重跑），本节并列新旧两种口径数字，后续报告一律标注分母。**

变更原因（为何不用全量 100 题平均）：

1. **weak 题没有召回语义**：gold 为空（10 题，C06/C07/C09/C10/C16/E01/E03/E04/E05/E10）的题没有黄金来源，
   FusedRecall/Recall@k/MRR/Precision@5 在其上无定义（Recall@k 为 NaN）或恒 0。把它们计入平均，
   等于把「拒答正确性」与「召回质量」两个不同维度混进同一个数字。
2. **指标数学上限被锁死**：fused_recall 按全量平均时，即使检索引擎完美命中全部 90 道 gold 题，
   上限也只有 90/100=0.90（10 道 weak 题恒 0），指标无法表达「检索层已完美」。
3. **职责分离**：weak 题的考核已由 gate 分布（gate_dist）、error_count 与 T23 的 faithfulness 评测
   （拒答恰当性）承载，召回指标不应重复计 0。
4. **既有约定**：本文档 §9（T17 复核）已按 gold_n=90 重跑并显式标注；§1–8 为早期全量口径。
   为可追溯，本节将两种口径同页并列；此后统一 90 题口径。

### 10.2 新旧口径数字并列（同一代码、同一题库、同一运行数据，仅分母不同）

| 指标 | 旧口径（分母 100，全量平均） | 新口径（分母 90，仅 gold 非空） | 差异 |
|---|---|---|---|
| FusedRecall（融合候选层） | 0.8900 | 0.9889 | +0.0989 |
| Recall@1 | 0.4500 | 0.5000 | +0.0500 |
| Recall@3 | 0.6300 | 0.7000 | +0.0700 |
| Recall@5 | 0.7000 | 0.7778 | +0.0778 |
| Recall@10 | 0.7800 | 0.8667 | +0.0867 |
| MRR | 0.5555 | 0.6173 | +0.0618 |
| Precision@5 | 0.1480 | 0.1644 | +0.0164 |
| gate 分布 | weak:29 / confident:71 | weak:29 / confident:71 | 一致 |
| 延迟 p50 / p95 | 369.9ms / 4164.3ms | 369.9ms / 4164.3ms | 一致 |

> 差异即 10 道 weak 题按 0 计入的稀释量（如 FusedRecall：89/90 vs 89/100）。两种口径下
> gate 分布、延迟、过滤损失均一致，说明差异纯粹来自分母口径，不涉及行为变化。

### 10.3 通道消融实验（90 题口径）

**总体对比（只统计 90 道 gold 非空题）**：

| 通道 | FusedRecall | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR | Precision@5 |
|---|---|---|---|---|---|---|---|
| all（默认融合计划） | **0.9889** | 0.5000 | 0.7000 | 0.7778 | 0.8667 | 0.6173 | 0.1644 |
| dense（仅向量通道） | 0.9667 | **0.5222** | **0.7556** | **0.8111** | **0.9000** | **0.6541** | **0.1756** |
| bm25（仅 keyword 通道） | 0.9111 | 0.4667 | 0.7111 | 0.7889 | 0.8667 | 0.5984 | 0.1644 |

**分维度（FusedRecall / Recall@3）**：

| 维度 | all Fused | dense Fused | bm25 Fused | all R@3 | dense R@3 | bm25 R@3 |
|---|---|---|---|---|---|---|
| A. 信息检索准确性 | 1.0000 | 0.9143 | 0.9429 | 0.6571 | 0.7429 | 0.6857 |
| B. 相关性排序 | 1.0000 | 1.0000 | 1.0000 | 0.9500 | 0.9000 | 1.0000 |
| C. 生成-源文档一致性 | 1.0000 | 1.0000 | 0.8000 | 0.6667 | 0.6000 | 0.7333 |
| D. 复杂查询 | 0.9333 | 1.0000 | 0.8667 | 0.6000 | 0.7333 | 0.4667 |
| E. 鲁棒性与边界 | 1.0000 | 1.0000 | 0.8000 | 0.4000 | 0.8000 | 0.4000 |

**互补性（逐题交叉）**：

- 仅 bm25 命中（dense 未命中）3 题：A10（C919 实用升限）、A11（C919 制造商）、A15（歼-20 雷达）——精确词项召回。
- 仅 dense 命中（bm25 未命中）8 题：A02、A33、C01、C05、C13、D09、D15、E06——语义召回。
- 融合 FusedRecall=0.9889（89/90）为三组最高，未出现「任何单通道命中但融合未命中」的题
  （唯一例外 D15 属计划层差异，见下）。

**消融结论**：

1. **dense 是召回与排序主力**：单通道 Recall@1/Recall@3/MRR/Precision@5 全部高于融合；语义检索在
   本知识库（3189 块、BGE-M3）上质量显著优于 BM25 词项检索。
2. **bm25 提供词项兜底**：FusedRecall 0.9111，负责 3 道 dense 未命中的题，并显著提升 B 维度
   （ranking）Recall@3 至 1.0000（dense 单通道仅 0.9000）。
3. **融合的 Recall@3（0.70）低于 dense 单通道（0.7556）**：RRF 融合（keyword 权重 1.0 / dense 1.2）
   后 top-3 排序被 keyword 候选稀释，15 题出现「融合 r@3=0 但某单通道 r@3=1」（A04/A10/A14/A16/
   A24/A27/A34/B04/C04/D03/D10/D12/D15/E06/E07）——这是排序层损失而非召回层损失（FusedRecall
   融合仍最高）。后续若提升排序质量，可考虑提高 dense 权重或对融合候选做二段重排。
4. **D15 例外（计划层）**：D15（C919 哪些部件由合资企业供应）为 L2（component_scene）查询，
   默认计划通道是 scene+keyword（scene 通道数据未入库 → 实际仅 keyword），默认融合计划 fused=0；
   而 dense-only 消融中 fused=1、r@3=1。即 **L2 计划不含 dense 是当前规划器特征，该题可由 dense 救回**。
5. **gate 三组一致**：通道切换不改变证据门判定（confident 71 / weak 29）。
6. **复核通过**：90-all 复核组与 90-all 基线的所有指标完全一致（FusedRecall=0.9889、Recall@3=0.70、
   MRR=0.6173），数字可复现。

---

## 11. faithfulness 与证据门误拦/漏拦补测（T23，2026-08-15）

> 数据来源：`tmp/rag_faithfulness_results.json`（100 题，总耗时 873s，2026-08-15 20:05 生成）。
> 评测脚本：`scripts/_rag_faithfulness.py`（只读评测；密钥从用户环境变量 DEEPSEEK_API_KEY 读取，
> 仓库内不出现密钥；**未修改证据门实现与任何阈值参数**）。

### 11.1 评测方法（LLM-as-judge）

- 对 `scripts/_rag_bench.py` 同一题库 100 题，逐题现场重跑检索（真实知识库），取证据包 **top-3**
  内容与问题一起交给 deepseek-v4-flash 判定，**每题 1 次 LLM 调用**（temperature=0.2）。
- judge 任务分两步：先依据证据撰写参考答案（证据不足必须拒答、不得编造），再对答案打两维分：
  - **faithful_score（0-5）**：答案中的事实性陈述是否全部被证据蕴含（5=全部有据，0=完全编造）；
  - **relevance_score（0-5）**：答案是否直接、完整地回答用户问题（证据不足时恰当拒答给 4-5 分，
    证据充足却拒答给 0-1 分）；
  - **verdict**：correct / wrong / unverifiable（correct=忠实且正确作答或证据不足时恰当拒答；
    wrong=编造/与证据矛盾/误导/答非所问；unverifiable=证据为空无法判定）。
- 100 题全部返回有效 JSON，0 解析错误、0 LLM 错误；全部 100 条记录逐条入库。

### 11.2 总体结果

| 指标 | 值 |
|---|---|
| 证据门四态分布 | confident **71** / weak **29** / conflict **0** / unclear **0**（与 §10 bench 一致） |
| faithfulness 均值（0-5） | **4.94**（confident 4.986 / weak 4.828） |
| relevance 均值（0-5） | **4.92**（confident 4.972 / weak 4.793） |
| verdict 分布 | correct 99 / wrong 0 / unverifiable 1（E10 乱码题，证据为空） |
| **误拦率** = P(verdict=wrong \| gate=confident) | **0/71 = 0.0%** |
| **漏拦率** = P(verdict=wrong \| gate=weak) | **0/29 = 0.0%** |

分维度（faithfulness / relevance）：

| 维度 | faithful 均值 | relevance 均值 |
|---|---|---|
| A. 信息检索准确性 | 5.000 | 5.000 |
| B. 相关性排序 | 5.000 | 4.950 |
| C. 生成-源文档一致性 | 5.000 | 4.950 |
| D. 复杂查询 | 4.933 | 5.000 |
| E. 鲁棒性与边界 | 4.500 | 4.400 |

> 低分案例仅 5 例：B12/C17/E02 的 relevance=4（证据只含部分相关内容、judge 判为部分回答），
> D14 的 faithfulness=4（结论句「并非单调提高」为两要点合理推断、无外部事实），
> E10（乱码查询）证据为空 unverifiable=0/0；其余 95 题两维均为 5 分（含满分拒答）。

### 11.3 误拦/漏拦率解读与门行为观察

1. **误拦率 0/71**：71 道 confident 题中，judge 基于门放行的证据作答/拒答，**无任何错误答案**
   （无编造、无与证据矛盾、无答非所问）。证据门当前没有放行过错误答案。
2. **漏拦率 0/29**：29 道 weak 题中 10 题 judge 同样判定证据不足并恰当拒答（门拦截正确），
   19 题 judge 从证据正确作答（verdict=correct）——**没有任何 weak 放行后答错的实例**。
3. **门过度保守（weak 侧）**：29 道 weak 题中 19 道（**65.5%**）的证据其实足以让 judge 正确作答
   （如 A01 翼展/A02 巡航马赫数/A03 机身全长等参数题），门却判 weak 拒绝回答——回答率损失。
4. **门过度自信（confident 侧）**：71 道 confident 题中 **18 道（25.4%）judge 判定证据不足以回答**
   （恰当拒答）——门放行但证据实际不可用。其中：
   - 域外题 3 道：C16（波音 747 双层客机）、E01（波音 737 维修步骤）、E05（C919 故障处置）——
     库内无对应事实，检索到的是 C919 对比/认证类素材；
   - 库内题 15 道：A17/A24/A29/A31/A33/A34/A35/B12/B15/B20/C20/D03/D05/D07/E09——
     黄金来源虽被检索到（recall 计命中），但库内 chunk 内容稀疏（多为 22-400 字表格/导语，
     如 `j20-science-aesa-principle-v1` 仅 59 字免责声明），实际不含可直接作答的事实。

### 11.4 C16「波音 747」类域外实例逐条复核（gold 为空 10 题）

| 题号 | 查询 | gate | judge 判定（fs/rs/verdict） | 定性 |
|---|---|---|---|---|
| C06 | 波音 737 的最大巡航速度是多少？ | weak | 拒答（5/5/correct） | 门正确拦截 |
| C07 | 歼-20 的单价是多少？ | weak | 拒答（5/5/correct） | 门正确拦截 |
| C09 | 空客 A380 的翼展是多少？ | weak | 拒答（5/5/correct） | 门正确拦截 |
| C10 | 长征五号火箭的推力是多少？ | weak | 拒答（5/5/correct） | 门正确拦截 |
| **C16** | **波音 747 是双层客机吗？** | **confident** | **拒答（5/5/correct）** | **门误判：confident 但证据仅 C919 vs B737 对比素材，无 747 双层事实 → 越界生成风险（主案例）** |
| **E01** | 波音 737 的维修步骤是什么？ | **confident** | **拒答（5/5/correct）** | **门误判：confident 但无据可答，且为安全敏感问题 → 越界生成风险** |
| E03 | ？？？ | weak | 拒答（5/5/correct） | 门正确拦截 |
| E04 | （空查询） | weak | 拒答（5/5/correct） | 门正确拦截 |
| **E05** | C919 怎么进行故障处置？ | **confident** | **拒答（5/5/correct）** | **门误判：confident 但证据仅涉认证/交付/对比，无故障处置内容 → 越界生成风险** |
| E10 | 什么是？？ | weak | unverifiable（0/0） | 门正确拦截（证据为空） |

**C16 主案例细述**：`波音 747 是双层客机吗？`（域外、无黄金来源）在 bench 与本次补测中 gate 均为
**confident**，检索证据为 `c919-science-vs-b737max-orders-v1` / `c919-science-vs-b737-v1`（C919 与
B737 对比素材，其中 747 字样仅作为市场背景出现）。judge 基于该证据判定不可作答并拒答（fs=5/rs=5/
correct）。**若生成层依 confident 直接作答，将被迫脱离证据编造（或答非所问），这正是 §5 观察到的
「用 C919 对比素材回答域外问题」越界风险**。该风险当前由生成层 safety/self-check 兜底，证据门本身
未拦截（本次任务按约束不修改证据门）。

### 11.5 结论与局限

- **结论**：在当前修复后状态（T15/T16 后），证据门未放行任何错误答案（误拦率 0/71、漏拦率 0/29），
  faithfulness/relevance 两维均值 4.94/4.92；主要问题不是「答案错」而是「门与证据可用性错配」：
  confident 侧 25.4% 无据可答（含 3 道域外题放行风险），weak 侧 65.5% 过度保守（可答未答）。
- **局限**：LLM-as-judge 单次调用、证据取 top-3 且每块截断 600 字；judge 整体偏保守（对
  「证据含相关内容但未直接表述」的题倾向拒答，如 B12/A17）；分数总体偏高，反映题库与知识库
  当前匹配良好但覆盖仍依赖 chunk 内容质量（稀疏 chunk 是 confident 不可答的主要来源）。

## 12. RAG 检索质量测试实施结果（T1.1–T1.5，2026-08-17）

### 12.1 测试边界与环境

本次按《系统性能测试方案》执行只读、离线检索质量测试，不调用 DeepSeek、Web Search 或真实外部服务，
使用 `.venv` 的 Python 3.11.9、真实本地 `bge_m3` embedding 和现有 `scripts/_rag_bench.py`。

本次运行时正式知识库已经不是 T22 使用的旧快照：

| 项目 | T22 历史快照 | 本次 fresh 快照 |
|---|---:|---:|
| knowledge sources | 未在 T22 报告固定 | 404 |
| text chunks / vectors | 3189 | 3241 / 3241 |
| active index | `index_b32dcafd946c` | `index_60c063a1e122` |
| embedding | BGE-M3，非 mock | BGE-M3，非 mock |

因此 T22 的 0.9889 / 0.7000 / 0.6173 仅作为历史基线，本节 fresh 结果以当前数据库快照为准，不能直接混合成同一快照的 before/after。

本次未调用知识库 ingest、rebuild 或迁移接口；但 pytest 组合回归期间出现数据库文件锁，且文件更新时间发生变化。由于测试前未保存数据库哈希，
本次只能确认未主动执行写入操作，不能把数据库内容完整性表述为已完成证明；该只读边界列为待确认。

### 12.2 all 通道结果与复现性

命令：

```powershell
$env:PYTHONDONTWRITEBYTECODE = "1"
$env:HF_HUB_OFFLINE = "1"
.\.venv\Scripts\python.exe scripts/_rag_bench.py --denominator 90 --channel all --output tmp/rag_quality_all_run1.json
.\.venv\Scripts\python.exe scripts/_rag_bench.py --denominator 90 --channel all --output tmp/rag_quality_all_run2.json
```

两次运行的质量指标逐项完全一致，结果如下：

| 指标 | 本次 fresh 结果 | 门槛/目标 | 结论 |
|---|---:|---:|---|
| FusedRecall | 1.0000 | ≥0.95 | 通过 |
| QualifiedRecall | 0.9889 | 记录项 | — |
| Recall@1 | 0.4778 | 记录项 | — |
| Recall@3 | 0.6778 | ≥0.70 | 未通过 |
| Recall@5 | 0.7444 | 记录项 | — |
| Recall@10 | 0.8333 | ≥0.85 | 未达到目标 |
| MRR | 0.5960 | ≥0.50；目标≥0.60 | 通过最低线，未达目标 |
| Precision@5 | 0.1578 | 记录项 | — |
| nDCG@5 / @10 | 0.5934 / 0.6250 | 记录项 | — |
| error_count | 0 | =0 | 通过 |
| embedding_mock | 0 | =0 | 通过 |
| filter-attributable loss | 0 | ≤2 | 通过 |

gold 非空题的 aircraft 过滤阻断数为 0。原始报告字段 `aircraft_case_blocked_ids` 包含 `E05`，但 E05 是 gold 为空的安全边界题，
不应计入 gold 召回阻断；该字段需与 `gold_n=90` 一起解释，不能作为当前知识召回失败。

### 12.3 通道消融

| 通道 | FusedRecall | Recall@3 | MRR | error_count |
|---|---:|---:|---:|---:|
| all（keyword+dense，按计划） | 1.0000 | 0.6778 | 0.5960 | 0 |
| bm25（keyword only） | 0.9333 | 0.6111 | 0.5006 | 0 |
| dense only | 0.9667 | 0.6111 | 0.5807 | 0 |

结论：融合通道的 FusedRecall 最高，BM25 与 dense 存在互补；本次 fresh 快照中融合 Recall@3 高于两个单通道，
但仍未达到 0.70 门槛。BM25 原始 blocked 列表包含 C05/C13/D15/E05/E06，dense 原始列表包含 A10/A11/E05；其中 E05 仍属于 gold 为空的边界题。

### 12.4 分维度与失败明细

| 维度 | gold_n | FusedRecall | Recall@3 | MRR | error_count |
|---|---:|---:|---:|---:|---:|
| A 信息检索准确性 | 35 | 1.0000 | 0.6000 | 0.5432 | 0 |
| B 相关性排序 | 20 | 1.0000 | 0.9500 | 0.8667 | 0 |
| C 生成-源文档一致性（仅检索层） | 15 | 1.0000 | 0.6667 | 0.6667 | 0 |
| D 复杂查询 | 15 | 1.0000 | 0.6000 | 0.3630 | 0 |
| E 鲁棒性与边界 | 5 | 1.0000 | 0.4000 | 0.3700 | 0 |

gold 已进入 fused 但未进入 top-3 的题目为：
`A02, A04, A05, A10, A11, A14, A15, A21, A24, A25, A27, A32, A33, A34, B04, C01, C02, C05, C13, C15, D01, D03, D09, D10, D12, D15, E02, E06, E07`。

其中 D15 在当前新快照中已进入 fused，但排序仍未进入 top-3；A33、D09 等历史遗留项仍需单独跟踪。当前结果未修改业务逻辑，
也未删除或隐藏任何失败题。

### 12.5 自动化回归与验收结论

命令：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/unit/knowledge `
  tests/unit/input/test_component_alias_precision.py `
  tests/unit/input/test_empty_query_defense.py `
  tests/integration/rag_pipeline -p no:cacheprovider -q
```

结果：组合命令测试进度达到 100%，主命令返回退出码 0；但退出清理阶段残留一个由本次测试产生的 pytest 子进程并持续占用知识库，已定位并终止该进程树。随后单独执行 `tests/integration/rag_pipeline`，退出码 0，全部测试通过。该清理异常作为环境性待确认项记录，不将进度条单独等同于完整回归通过。

最终结论：RAG 检索链路无检索异常，融合召回和单独 RAG 集成回归通过；但当前知识库快照下 Recall@3 未达到 0.70，Recall@10 未达到 0.85，
因此本次 RAG 检索质量测试**不判定为完全通过**。下一轮测试前必须先确认是否冻结当前 `index_60c063a1e122` 作为新基线，
或恢复/提供与 T22 相同的 3189 块知识库快照；在基线未确认前，不应把本次 fresh 结果与历史结果直接作修复收益结论。

## 13. RAG 检索质量修复复验（最终，2026-08-18）

本节记录用户授权的检索质量修复复验结果。修复仅涉及既有 RAG 白名单：查询理解的型号/主题别名、FeatureReranker 的查询词与源标题弱匹配、跨组件关系排序，以及候选窗口内的 source-level diversity；未修改知识库、公开 API、依赖或回答生成链路。

### 13.1 最终 all 结果与独立复现

最终复验命令为：

```powershell
$env:PYTHONDONTWRITEBYTECODE = "1"
$env:HF_HUB_OFFLINE = "1"
\.venv\Scripts\python.exe scripts/_rag_bench.py --denominator 90 --channel all --output tmp/rag_quality_all_run1.json
\.venv\Scripts\python.exe scripts/_rag_bench.py --denominator 90 --channel all --output tmp/rag_quality_all_run2.json
```

两次 all 运行质量指标完全一致，最终以 `tmp/rag_quality_all_run2.json` 为独立复跑证据：

| 指标 | run1/run2 | 用户目标/门槛 | 结论 |
|---|---:|---:|---|
| FusedRecall | 0.9889 | ≥0.95 | 通过 |
| QualifiedRecall | 0.9889 | 记录项 | — |
| Recall@1 / @3 / @5 / @10 | 0.7556 / 0.9778 / 0.9778 / **0.9889** | Recall@10 ≥0.95 | 通过 |
| MRR | **0.8568** | ≥0.80 | 通过 |
| Precision@5 | 0.2222 | 记录项 | — |
| nDCG@5 / nDCG@10 | 0.8489 / 0.8574 | 记录项 | — |
| error_count | 0 | =0 | 通过 |
| embedding_mock | 0 | =0 | 通过 |
| filter-attributable loss | 1（B06） | ≤2 | 通过 |
| aircraft_case_blocked_ids | [] | 空 | 通过 |

当前 100 题仍按 90 道 gold 题计算召回/排序平均，10 道 weak/边界题不进入 gold 分母。gate 分布为 `confident=95 / weak=5`。最终 all 结果通过本次 RAG 检索质量门禁。

### 13.2 通道消融与互补性

| 通道 | FusedRecall | Recall@3 | Recall@10 | MRR | error_count |
|---|---:|---:|---:|---:|---:|
| all（keyword+dense） | 0.9889 | 0.9778 | 0.9889 | 0.8568 | 0 |
| bm25（keyword only） | 0.9111 | 0.8556 | 0.9111 | 0.7606 | 0 |
| dense only | 0.9667 | 0.9556 | 0.9667 | 0.8477 | 0 |

all 的 FusedRecall 最高；BM25-only 命中而 dense-only 未命中的 gold 题为 A10、A11，dense-only 额外命中而 BM25-only 未命中的题为 A33、C05、C13、D09、D12、D15、E06；两通道都未命中的 gold 题仅 B06。结论为通道存在明确互补，且融合结果满足正式目标。

### 13.3 五维度结果与逐题失败明细

| 维度 | gold_n | FusedRecall | Recall@3 | Recall@10 | MRR |
|---|---:|---:|---:|---:|---:|
| A accuracy | 35 | 1.0000 | 1.0000 | 1.0000 | 0.9048 |
| B ranking | 20 | 0.9500 | 0.9500 | 0.9500 | 0.8417 |
| C consistency（检索层） | 15 | 1.0000 | 1.0000 | 1.0000 | 0.8222 |
| D complex | 15 | 1.0000 | 1.0000 | 1.0000 | 0.8889 |
| E robustness（gold 子集） | 5 | 1.0000 | 0.8000 | 1.0000 | 0.5889 |

最终逐题明细全部保留在 JSON。需要关注的两题如下：

- **B06**「液压系统的余度设计有什么特点？」：gold 未进入 fused；放宽过滤后恢复，归类为 component 过滤导致的召回损失，计入 1 题诊断，不影响 Recall@10 门槛。
- **E02**「升力」：gold 已进入最终证据包，但首个 gold 排名为 9；这是无型号单概念查询的歧义排序遗留项，仍记录在逐题明细中，未隐藏或删除。

其余历史重点题已改善：D09 进入第 1 位，A33/C13/D12 等进入 top-3 或 top-10；source-level diversity 防止同一来源的重复 chunk 挤占最终证据包。

### 13.4 自动化回归与最终判定

最终回归命令：

```powershell
\.venv\Scripts\python.exe -m pytest tests/unit/knowledge `
  tests/unit/input/test_component_alias_precision.py `
  tests/unit/input/test_empty_query_defense.py `
  tests/integration/rag_pipeline -p no:cacheprovider -q
```

结果：**全量 RAG 相关测试通过**；`git diff --check` 通过，修改源文件 compileall 通过。中间一次组合运行暴露了宽泛主题硬过滤对临时 fixture 的兼容问题，已撤回该硬过滤并单独复测通过，最终组合命令退出码为 0。

最终判定：在当前 `404 reviewed sources / 3241 chunks / index_60c063a1e122` 快照、离线真实 BGE-M3 条件下，RAG 检索质量目标 **Recall@10 ≥95%、MRR ≥0.80 已通过**。B06 的过滤归因损失和 E02 的歧义排序作为后续优化项保留。
