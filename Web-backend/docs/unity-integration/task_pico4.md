# Pico 4 接入任务拆解

> 任务编号在 `spec_pico4.md` 与 `harness_pico4.md` 中一一对应。强约束优先：不得新增依赖、不得替换既有 Agent 架构；若现有依赖或项目总控白名单无法支撑某项，记录“待确认”并停止，不得以裸链路、伪实现或第三方包绕过。

## 执行顺序

`PICO-01 → PICO-02 → {PICO-03 → PICO-04，PICO-05 → PICO-06 → PICO-07 → PICO-08}；PICO-01 → PICO-09 → PICO-10；{PICO-06，PICO-07，PICO-09} → PICO-11 → PICO-12；{PICO-04，PICO-08，PICO-10，PICO-12} → PICO-13`。

| 编号 | 目标 | 优先级 | 预计改动范围 | 可衡量验收标准 | 依赖 |
|---|---|---|---|---|---|
| PICO-01 | 记录零新增依赖准入结论，并建立独立 Python 虚拟环境。 | 高 | 后端文档、契约 fixture、`.venv-pico4`（临时）。 | 证明/否定现有 Python 与 Unity 原生 WSS、MP3 能力；既有最小回归在独立 venv 通过；任一能力失败即 STATUS 留痕并阻断后续相关任务。 | 无 |
| PICO-02 | 冻结文本 HTTP 与 `voice-ws-v2` 公开契约。 | 高 | 接口文档、ADR、契约测试。 | JSON 正例、反例、PCM、TTS header/binary 顺序、错误/关闭码全部可由 fixture 校验。 | PICO-01 |
| PICO-03 | 在不引入框架的前提下增加文本传输安全基础。 | 高 | `app/api` 薄适配、配置、启动脚本、单测。 | 固定配置、HMAC Bearer、`healthz/readyz`、32 KiB 限制可验证；若总控未授权新入口则停止。 | PICO-02 |
| PICO-04 | 接入受控文本问答与幂等响应。 | 高 | 文本 handler、模型、幂等缓存、集成测。 | 有效=200，空/超限=400/413，同 key 不重复 pipeline，故障=503 且无 trace。 | PICO-03 |
| PICO-05 | 保持 v1 兼容地增加 Pico 专用固定监听配置。 | 高 | `voice` server、专用启动脚本、语音回归。 | 原 `serve_voice()` 默认 loopback 不变；Pico 入口只有显式配置才固定端口/LAN 启动。 | PICO-02 |
| PICO-06 | 加入 v2 心跳与 `tts.frame → binary` 边界。 | 高 | 语音协议/传输和测试。 | v2 版本精确校验；TTS 每段唯一 header；取消后没有旧音频；v1 回归不变。 | PICO-05 |
| PICO-07 | 在升级前认证语音连接并隔离重连。 | 高 | 认证、限流、voice server、集成测。 | 无/错身份不可升级；4408 用新 turn 重连；一连接异常不影响其他连接。 | PICO-03、PICO-06 |
| PICO-08 | 写入零新增软件依赖的 TLS/LAN 部署和回滚证据。 | 高 | 运行脚本、部署文档、部署 smoke。 | 缺证书拒绝启动；测试证书可 HTTPS/WSS；只交付 TLS 地址。 | PICO-04、PICO-07 |
| PICO-09 | 建立 Unity 零包依赖配置、契约与能力探针。 | 高 | 新 C#、能力报告。 | `JsonUtility`、原生 WSS、内置 MP3 路径均有 Pico 真机 PASS/FAIL 证据；token 不落资源/日志。 | PICO-01、PICO-02 |
| PICO-10 | 接入 Unity 文本请求、取消和展示。 | 高 | 文本 C#、状态 C#、真机检查。 | 一次点击一次请求；30 s 取消；错误映射；旧响应不覆盖新 turn。 | PICO-04、PICO-09 |
| PICO-11 | 接入 Unity 录音、v2 WSS 和 MP3 队列。 | 高 | 语音 C#、真机检查。 | PCM 为 16 kHz/mono/640 B；错配帧不播放；cancel 清理；超限内存受控。 | PICO-06、PICO-07、PICO-09 |
| PICO-12 | 整合 Unity 双状态机、场景状态和生命周期释放。 | 中 | Controller、Bridge、指定场景。 | 4408 退避重连；4400 不重连；失焦停止 mic/WS/HTTP/TTS；不重传 PCM。 | PICO-10、PICO-11 |
| PICO-13 | 端到端验收、交付、删除独立 venv。 | 高 | e2e、验收/交接文档、STATUS。 | 全部指定测试通过；Pico 真机闭环与 p50/p95 记录齐全；`.venv-pico4` 已删除。 | PICO-04、PICO-08、PICO-10、PICO-12 |

## 共同完成规则

- 每任务只进行一个中文原子提交，格式：`PICO-XX：中文动作`；不 push、不合并。
- PICO-01 在后端根目录建立 `.venv-pico4`；只安装仓库已有锁定依赖；PICO-13 成功记录后删除它。
- 不保留未调用接口、重复实现、调试分支、注释掉代码、临时 token、证书或音频文件。
