# C919 与 A320 航电系统对比：IMA 架构、ARINC 664、显示系统

## 来源
- 主要来源：Wikipedia "Comac C919"（en.wikipedia.org/wiki/Comac_C919）
- 访问日期：2026-07-29

## 正文

### 航电系统对比总览

C919 与空客 A320（含 A320ceo 和 A320neo）的航电系统对比：

| 航电特征 | C919 | A320ceo | A320neo |
| --- | --- | --- | --- |
| 架构 | IMA（综合模块化航电） | 联邦式架构 | 联邦式架构（部分改进） |
| 主总线 | ARINC 664（AFDX） | ARINC 429 | ARINC 429（部分 664） |
| 显示系统 | AVIAGE Systems | Thales/Honeywell | Thales/Honeywell |
| FMS | AVIAGE TrueCourse | Thales/Honeywell | Thales/Honeywell |
| 飞控 | 电传（Hongfei） | 电传 | 电传 |

### IMA 架构对比

C919 采用综合模块化航电（IMA）架构，A320 系列采用传统联邦式（Federated）架构。两者的核心区别：

| 特征 | IMA（C919） | 联邦式（A320） |
| --- | --- | --- |
| 计算资源 | 共享计算模块（CPM） | 各系统独立计算机 |
| 软件架构 | 分区化运行（ARINC 653） | 各计算机独立软件 |
| 资源利用 | 多功能共享硬件 | 一功能一硬件 |
| 重量 | 较轻（硬件共享） | 较重（独立计算机多） |
| 升级灵活性 | 软件加载即可扩展 | 需新增硬件 |

IMA 架构的优势：

- 减重：共享计算模块减少硬件数量
- 灵活性：通过软件加载增加新功能
- 标准化：ARINC 653 分区化保证应用间隔离

IMA 架构的挑战：

- 复杂性：共享资源的分配和隔离设计复杂
- 认证：共享计算模块的认证比独立计算机复杂
- 故障传播：需严格保证分区隔离，避免一个应用的故障影响其他应用

A320ceo 的联邦式架构虽不如 IMA 先进，但成熟度极高——每个系统有独立的计算机，故障隔离天然实现，认证和升级相对简单。A320neo 在 ceo 基础上做了部分航电升级，但未全面切换到 IMA。

### 总线对比

| 总线 | C919 | A320 |
| --- | --- | --- |
| 主航电总线 | ARINC 664（AFDX） | ARINC 429 |
| 带宽 | 高（100 Mbps） | 低（约 100 kbps） |
| 拓扑 | 星型以太网 | 点对点 |
| 双向通信 | 支持 | 主要单向 |

C919 采用 ARINC 664（航空全双工以太网交换式网络，AFDX）作为主航电总线，带宽 100 Mbps，支持双向通信，是现代民机航电网络的标准。A320 采用 ARINC 429，带宽约 100 kbps，主要单向传输，是 1980 年代的标准。

ARINC 664 相比 ARINC 429 的优势：

- 带宽高 1000 倍：支持更大数据量传输（如气象雷达图像、地形数据库）
- 双向通信：设备可双向交互
- 网络化：多设备共享网络，减少布线
- 冗余：双网络冗余设计

A320neo 部分新系统引入 ARINC 664 元素，但整体仍以 ARINC 429 为主，受 A320 平台历史架构约束。

### 显示系统对比

| 显示特征 | C919 | A320 |
| --- | --- | --- |
| 显示单元供应商 | AVIAGE Systems（GE-AVIC 合资） | Thales / Honeywell |
| 显示架构 | IMA 集成显示 | 独立显示计算机 |
| 显示数量 | 6 块大显示屏（典型） | 6 块显示屏（典型） |
| PFD/ND | 每侧 PFD + ND | 每侧 PFD + ND |
| ECAM | 中央发动机/警告显示 | 中央发动机/警告显示 |

C919 的显示单元由 AVIAGE Systems（昂际航电，GE 与 AVIC 合资）提供，基于 IMA 架构集成。A320 的显示系统由 Thales 或 Honeywell 提供（根据航空公司选型），基于联邦式架构。

两者的显示功能相似——主飞行显示（PFD）显示姿态/速度/高度/航向，导航显示（ND）显示航路/气象，ECAM 显示发动机参数和系统警告。差异主要在底层架构——C919 的 IMA 显示通过共享计算模块驱动，A320 的显示由独立显示计算机驱动。

### 飞行管理系统（FMS）对比

| FMS | C919 | A320 |
| --- | --- | --- |
| 供应商 | AVIAGE Systems | Thales / Honeywell |
| 型号 | TrueCourse | 各供应商型号 |
| 功能 | 导航、飞行计划、性能计算 | 导航、飞行计划、性能计算 |
| PBN 能力 | 支持 RNAV/RNP | 支持 RNAV/RNP |

C919 的 FMS 由 AVIAGE Systems 提供，型号 TrueCourse。A320 的 FMS 由 Thales 或 Honeywell 提供。两者的核心功能一致——导航计算、飞行计划管理、性能优化、引导指令生成。差异在于 TrueCourse 基于 IMA 架构集成，与 C919 的航电网络深度耦合。

### 飞控系统对比

| 飞控特征 | C919 | A320 |
| --- | --- | --- |
| 供应商 | Hongfei Flight Control（Honeywell-AVIC 合资） | Airbus 自研 |
| 架构 | 电传操纵 | 电传操纵 |
| 操纵输入 | 侧杆 | 侧杆 |
| 控制律 | 正常/备用/直接模式 | 正常/备用/直接/机械备份 |

C919 和 A320 均采用电传飞控和侧杆操纵。A320 作为全球首款电传窄体客机（1988 年投入运营），飞控经验积累深厚。C919 的飞控由 Hongfei（Honeywell-AVIC 合资）提供，借鉴 Honeywell 的飞控经验。

A320 的飞控控制律有正常/备用/直接三个主要模式，并保留有限的机械备份（方向舵和配平）。C919 的控制律同样有正常/备用/直接模式，具体备份配置属于审定数据。A320 作为 1980 年代设计的电传系统，保留了机械备份作为当时技术条件下的保守措施；C919 作为 2010 年代设计的电传系统，可采用更彻底的全电传架构。

### 平视显示器（HUD）与增强视景

| 系统 | C919 | A320 |
| --- | --- | --- |
| 平视显示器 | AVIC 光电 | 选装 |
| 增强视景系统 | Elbit Systems of America Kollsman EVS-SP | 选装 |

C919 配备 AVIC 光电提供的平视显示器（HUD）和 Elbit Systems of America 的增强视景系统（EVS，Kollsman EVS-SP）。A320 的 HUD 和 EVS 为选装配置，供应商根据航空公司选型确定。HUD 和 EVS 在低能见度运行（如 CAT II/III 进近）中提升机组情景意识。

### 传感器对比

| 传感器 | C919 | A320 |
| --- | --- | --- |
| 大气数据 | Honeywell | Honeywell / Thales |
| 惯性参考 | Honeywell LASEREF IV | Honeywell / Thales |
| 气象雷达 | ALRAC（Collins-AVIC 雷华） | Honeywell / Collins |
| 通信导航 | RCCAC（Collins-CETCA） | Collins / Honeywell |

C919 的传感器供应商以 Honeywell 和 Collins（通过合资）为主。A320 的传感器供应商包括 Honeywell、Thales、Collins 等，根据航空公司选型确定。两者的传感器类型相同（大气数据、IRS、气象雷达、通信导航），差异在供应商和具体型号。

### C919 航电的后发优势

C919 作为 2010 年代设计的飞机，在航电架构上相对 A320（1980 年代设计）有后发优势：

- IMA 架构：相比 A320 联邦式架构减重、增灵活性
- ARINC 664 总线：相比 A320 ARINC 429 带宽高 1000 倍
- 全新设计：不受 A320 历史架构约束

A320neo 作为 A320ceo 的改进型，航电架构继承 ceo 基础，未全面升级到 IMA + ARINC 664，受平台历史约束。C919 的航电架构更接近 A350、787 等新一代宽体客机的设计理念。

## 交叉核对
- 来源 A：Wikipedia Comac C919 —— 航电供应商 AVIAGE Systems、FMS TrueCourse、飞控 Hongfei、HUD AVIC 光电、EVS Elbit Kollsman、大气数据/IRS Honeywell LASEREF IV、气象雷达 ALRAC、通信导航 RCCAC
- 来源 B：Wikipedia Airbus A320 —— 航电架构、总线、显示系统特征
- 采用值：IMA vs 联邦式架构、ARINC 664 vs 429 为通用航电知识；A320 具体供应商配置根据航空公司选型不同
