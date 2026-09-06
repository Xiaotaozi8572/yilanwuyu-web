# RAG 检索质量测试题库

## 1. 题库说明

本题库与 `scripts/_rag_bench.py` 中的 `BANK` 保持一致，共 100 道题，覆盖信息检索准确性、相关性排序、生成-源文档一致性、复杂查询和鲁棒性与边界五个维度。

- 正式召回/排序指标分母：90 道 `gold` 非空题。
- 其余 10 道为 weak、空查询、乱码、域外对象或安全边界题，单独统计，不进入 Recall/MRR 平均。
- `gold` 表示预期答案来源的 `source_id`；空值表示不应强行检索或生成事实答案。
- `expect=hit` 表示应检索到黄金来源；`expect=weak` 表示应返回证据不足、澄清或安全拒答。
- 题库只读使用本地知识库，不包含回答生成质量判定。

## 2. 题目总览

### A. 信息检索准确性（35 题）

| ID | 测试问题 | 期望行为 | Gold 来源 | 测试重点 |
|---|---|---|---|---|
| A01 | C919 的翼展是多少米？ | hit | `c919-science-parameters-v1`; `c919-science-specifications-summary-v1` | 翼展参数 |
| A02 | C919 的巡航马赫数是多少？ | hit | `c919-science-parameters-v1`; `c919-science-specifications-summary-v1` | 巡航马赫数 |
| A03 | C919 的机身全长是多少米？ | hit | `c919-science-parameters-v1` | 机身全长 |
| A04 | C919 采用的是哪款发动机？ | hit | `c919-science-propulsion-v1`; `c919-science-engine-principle-v1` | LEAP-1C |
| A05 | C919 的客舱布局是什么样的？ | hit | `c919-science-parameters-v1`; `c919-science-cabin-v1` | 3-3 单过道 |
| A06 | C919 的国产化率大约是多少？ | hit | `c919-science-localization-rate-v1` | 国产化率 |
| A07 | C919 首飞是哪一天？ | hit | `c919-science-history-2017-first-flight-v1` | 首飞日期 |
| A08 | C919 什么时候取得型号合格证？ | hit | `c919-science-certification-v1`; `c919-science-history-2022-2026-delivery-v1` | 型号合格证 |
| A09 | C919 的最大起飞重量是多少？ | hit | `c919-science-parameters-v1` | MTOW |
| A10 | C919 的实用升限是多少？ | hit | `c919-science-specifications-summary-v1` | 实用升限 |
| A11 | C919 的制造商是哪家公司？ | hit | `c919-science-specifications-summary-v1` | 制造商 |
| A12 | C919 的客舱宽度是多少？ | hit | `c919-science-parameters-v1` | 客舱宽度 |
| A13 | 歼-20 采用的是哪种气动布局？ | hit | `j20-science-canard-aero-v1`; `j20-science-canard-wing-v1` | 鸭式布局 |
| A14 | 歼-20 的进气道采用了什么设计？ | hit | `j20-science-dsi-intake-v1` | DSI 进气道 |
| A15 | 歼-20 装备了什么雷达？ | hit | `j20-science-aesa-principle-v1` | 有源相控阵雷达 |
| A16 | 运-20 的货舱宽度是多少？ | hit | `y20-science-parameters-v1` | 货舱宽度 |
| A17 | 运-20 采用了几台发动机？ | hit | `y20-science-powerplant-v1` | 发动机数量 |
| A18 | 直-20 的最大起飞重量是多少？ | hit | `z20-science-parameters-v1` | MTOW |
| A19 | 直-20 装备了什么飞控系统？ | hit | `z20-science-fly-by-wire-v1` | 电传飞控 |
| A20 | 运-20 的机翼是什么结构？ | hit | `y20-science-high-wing-v1` | 上单翼 |
| A21 | 超临界翼型的阻力发散马赫数是多少？ | hit | `c919-science-supercritical-airfoil-physics-v1` | 参数与概念过滤 |
| A22 | 激波会产生哪些不利影响？ | hit | `c919-science-supercritical-airfoil-physics-v1` | 无型号概念查询 |
| A23 | 电传操纵相比机械操纵有什么优势？ | hit | `c919-science-fly-by-wire-v1` | 对比查询 |
| A24 | 涡扇发动机的推力是怎么产生的？ | hit | `c919-science-engine-principle-v1` | 发动机原理 |
| A25 | 翼尖小翼有什么作用？ | hit | `c919-science-drag-breakdown-v1` | 机翼部件别名 |
| A26 | 铝锂合金相比普通铝合金有什么优势？ | hit | `c919-science-aluminum-lithium-v1` | 材料对比 |
| A27 | 复合材料有什么特点？ | hit | `c919-science-composite-material-v1` | 材料概念 |
| A28 | 什么是飞行包线？ | hit | `j20-science-flight-envelope-v1` | 定义查询 |
| A29 | 有源相控阵雷达的工作原理是什么？ | hit | `j20-science-aesa-principle-v1` | 雷达原理 |
| A30 | 鸭式布局的优势有哪些？ | hit | `j20-science-canard-aero-v1` | 气动布局 |
| A31 | 红外隐身的基本原理是什么？ | hit | `j20-science-ir-stealth-v1` | 隐身原理 |
| A32 | 什么是第五代战斗机？ | hit | `j20-science-5th-gen-definition-v1` | 定义查询 |
| A33 | 后掠翼有什么作用？ | hit | `y20-science-sweep-wing-v1` | 机翼主题 |
| A34 | 旋翼是怎么产生升力的？ | hit | `z20-science-rotor-lift-v1` | 旋翼原理 |
| A35 | 涡升力是什么？ | hit | `j20-science-vortex-lift-v1` | 气动概念 |

### B. 相关性排序（20 题）

| ID | 测试问题 | 期望行为 | Gold 来源 | 测试重点 |
|---|---|---|---|---|
| B01 | 激波对翼型性能有哪些影响？ | hit | `c919-science-supercritical-airfoil-physics-v1` | 相关性排序 |
| B02 | 超临界翼型相比常规翼型有什么改进？ | hit | `c919-science-supercritical-airfoil-physics-v1` | 相关性排序 |
| B03 | 复合材料在航空中有哪些应用？ | hit | `c919-science-composite-material-v1`; `c919-science-composite-mechanics-v1` | 多候选排序 |
| B04 | 电传飞控的余度设计是怎样的？ | hit | `c919-science-fbw-redundancy-v1`; `c919-science-fly-by-wire-v1` | 主题匹配 |
| B05 | 航电系统的 IMA 架构是什么？ | hit | `c919-science-avionics-ima-v1` | 航电主题 |
| B06 | 液压系统的余度设计有什么特点？ | hit | `c919-science-hydraulic-system-details-v1` | 组件过滤与排序 |
| B07 | 燃油系统的惰性气体系统是做什么的？ | hit | `c919-science-fuel-system-details-v1` | 组件主题 |
| B08 | 起落架的刹车系统由谁提供？ | hit | `c919-science-landing-gear-details-v1` | 供应方检索 |
| B09 | 飞机的防冰系统有哪些类型？ | hit | `c919-science-ice-protection-v1` | 系统主题 |
| B10 | 适航取证有哪些符合性方法？ | hit | `c919-science-airworthiness-process-v1` | 规章主题 |
| B11 | 复合材料力学的各向异性是什么？ | hit | `c919-science-composite-mechanics-v1` | 专业术语排序 |
| B12 | 疲劳与损伤容限设计的原则是什么？ | hit | `c919-science-fatigue-damage-tolerance-v1` | 专业术语排序 |
| B13 | 电传操纵的控制律有哪些模式？ | hit | `c919-science-fbw-control-laws-v1` | 控制律主题 |
| B14 | S-N 曲线描述的是什么？ | hit | `c919-science-fatigue-damage-tolerance-v1` | 缩写与概念 |
| B15 | 高速飞行中的气动加热是怎么产生的？ | hit | `j20-science-aerodynamic-heating-v1` | 因果解释 |
| B16 | 弹射座椅的作用是什么？ | hit | `j20-science-ejection-seat-v1` | 功能查询 |
| B17 | 机载数据链的主要功能是什么？ | hit | `j20-science-data-link-v1`; `j20-science-datalink-sys-v1` | 多来源排序 |
| B18 | 光电瞄准系统用于完成什么任务？ | hit | `j20-science-eots-v1` | 系统功能 |
| B19 | 空中受油系统有什么作用？ | hit | `j20-science-aerial-refueling-v1` | 系统功能 |
| B20 | 大迎角机动的基本原理是什么？ | hit | `j20-science-high-aoa-v1`; `j20-science-vortex-lift-v1` | 多候选排序 |

### C. 生成-源文档一致性（20 题）

| ID | 测试问题 | 期望行为 | Gold 来源 | 测试重点 |
|---|---|---|---|---|
| C01 | C919 的巡航马赫数是多少？ | hit | `c919-science-parameters-v1`; `c919-science-specifications-summary-v1` | 有据可答 |
| C02 | C919 超临界翼型的阻力发散马赫数是多少？ | hit | `c919-science-supercritical-airfoil-physics-v1` | 有据可答 |
| C03 | 激波会产生哪些不利影响？ | hit | `c919-science-supercritical-airfoil-physics-v1` | 有据可答 |
| C04 | C919 发动机的推力数值是多少？ | hit | `c919-science-engine-bypass-ratio-v1`; `c919-science-parameters-v1` | 参数表 |
| C05 | C919 的座位数是多少？ | hit | `c919-science-parameters-v1` | 参数表 |
| C06 | 波音 737 的最大巡航速度是多少？ | weak | 无 | 域外对象，拒绝编造 |
| C07 | 歼-20 的单价是多少？ | weak | 无 | 未公开信息，拒绝编造 |
| C08 | C919 油箱的容量是多少升？ | hit | `c919-science-fuel-system-details-v1` | 参数检索 |
| C09 | 空客 A380 的翼展是多少？ | weak | 无 | 域外对象 |
| C10 | 长征五号火箭的推力是多少？ | weak | 无 | 域外对象 |
| C11 | 运-20 的最大载重是多少？ | hit | `y20-science-parameters-v1` | 参数检索 |
| C12 | 直-20 的巡航速度是多少？ | hit | `z20-science-parameters-v1` | 参数检索 |
| C13 | C919 的单价是多少？ | weak | `c919-science-commercial-value-v1` | 可能未定价，谨慎回答 |
| C14 | 涡扇发动机的涵道比越大越好吗？ | hit | `c919-science-engine-bypass-ratio-v1` | 原理权衡 |
| C15 | C919 采用了钛合金材料吗？ | hit | `c919-science-composite-material-v1`; `c919-science-fuselage-structure-details-v1` | 材料证据 |
| C16 | 波音 747 是双层客机吗？ | weak | 无 | 域外对象 |
| C17 | C919 的失速速度是多少？ | hit | `c919-science-stall-speed-margin-v1` | 参数检索 |
| C18 | 运-8 的用途是什么？ | hit | `y20-science-vs-y8-y9-v1` | 对比来源 |
| C19 | C919 有哪些国际合作？ | hit | `c919-science-international-cooperation-v1` | 合作信息 |
| C20 | 什么是马赫数？ | hit | `c919-science-mach-number-effects-v1` | 概念定义 |

### D. 复杂查询（15 题）

| ID | 测试问题 | 期望行为 | Gold 来源 | 测试重点 |
|---|---|---|---|---|
| D01 | 升力与推力有什么区别？ | hit | `c919-science-engine-principle-v1`; `c919-science-supercritical-airfoil-physics-v1` | 对比与跨主题 |
| D02 | C919 与空客 A320 相比有哪些差异？ | hit | `c919-science-vs-a320-v1`; `c919-science-vs-a320-range-payload-v1` | 对比与型号 |
| D03 | C919 的航程与 A320 相比如何？ | hit | `c919-science-vs-a320-range-payload-v1`; `c919-science-vs-a320-v1` | 对比与型号 |
| D04 | 为什么超临界翼型能降低巡航阻力？ | hit | `c919-science-supercritical-airfoil-physics-v1` | 因果推理 |
| D05 | C919 的电传飞控与普通飞控有什么区别？ | hit | `c919-science-fbw-control-laws-v1`; `c919-science-fly-by-wire-v1` | 对比与型号 |
| D06 | 运-20 与伊尔-76 哪个载重更大？ | hit | `y20-science-vs-il76-v1` | 对比与型号 |
| D07 | C919 国产化率与制造工艺有什么关系？ | hit | `c919-science-localization-rate-v1`; `c919-science-manufacturing-v1` | 跨源聚合 |
| D08 | C919 的超临界机翼设计经历了哪些风洞试验？ | hit | `c919-science-aero-windtunnel-v1` | 过程性查询 |
| D09 | 歼-20 的隐身设计与发动机有什么关系？ | hit | `j20-science-em-stealth-v1`; `j20-science-engine-nozzle-v1` | 跨组件关系 |
| D10 | C919 的复合材料占比是多少？ | hit | `c919-science-specifications-summary-v1`; `c919-science-composite-material-v1` | 参数聚合 |
| D11 | C919 的首飞与取证相隔了多久？ | hit | `c919-science-history-2017-first-flight-v1`; `c919-science-certification-v1` | 跨源时程 |
| D12 | C919 适航取证需要满足哪些规章？ | hit | `c919-science-certification-basis-v1` | 多规章检索 |
| D13 | 直-20 与 UH-60 的定位有什么不同？ | hit | `z20-science-vs-uh60-v1` | 对比与型号 |
| D14 | 涵道比越大，燃油效率越高吗？ | hit | `c919-science-engine-bypass-ratio-v1` | 原理权衡 |
| D15 | C919 的哪些部件由合资企业供应？ | hit | `c919-science-supplier-system-v1` | 跨源列举 |

### E. 鲁棒性与边界（10 题）

| ID | 测试问题 | 期望行为 | Gold 来源 | 测试重点 |
|---|---|---|---|---|
| E01 | 波音 737 的维修步骤是什么？ | weak | 无 | 域外且安全敏感 |
| E02 | 升力 | hit | `c919-science-supercritical-airfoil-physics-v1`; `c919-science-lift-coefficient-aoa-v1` | 歧义单概念 |
| E03 | ？？？ | weak | 无 | 乱码 |
| E04 | （空字符串） | weak | 无 | 空查询 |
| E05 | C919 怎么进行故障处置？ | weak | 无 | 安全敏感 |
| E06 | C919 的飞机翅膀有多长？ | hit | `c919-science-parameters-v1` | 口语别名/同义替换 |
| E07 | 请简单一点解释超临界机翼 | hit | `c919-science-supercritical-airfoil-physics-v1` | 反馈意图：简化解释 |
| E08 | 你刚才说错了，涵道比不是这个数 | weak | `c919-science-engine-bypass-ratio-v1` | 反馈意图：事实质疑 |
| E09 | C919 与 A320 哪个更好？ | hit | `c919-science-vs-a320-v1` | 比较问题需澄清维度 |
| E10 | 什么是？？ | weak | 无 | 残缺疑问 |

## 3. 执行约束

正式检索质量测试使用：

```powershell
$env:PYTHONDONTWRITEBYTECODE = "1"
$env:HF_HUB_OFFLINE = "1"
.\.venv\Scripts\python.exe scripts/_rag_bench.py --denominator 90 --channel all --output tmp/rag_quality_all_run1.json
```

BM25-only 和 dense-only 消融分别将 `--channel` 设置为 `bm25` 和 `dense`。测试结果、逐题排名和证据包不写回知识库；正式质量指标按 90 道 gold 题计算。

## 4. 维护规则

本文件是题库的人类可读副本。若题目、gold 来源、期望行为发生变更，必须同步修改 `scripts/_rag_bench.py` 中的 `BANK`，并重新执行完整评测与报告更新。
