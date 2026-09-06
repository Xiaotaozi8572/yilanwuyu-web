# 翼览无余 · 前端源码运行说明

本项目为「翼览无余」多机型航空科普交互作品的前端工程，基于 **Vue 3.5 + Vite 8 + Three.js 0.180 + OGL** 构建。

## 1. 环境要求

- Node.js ≥ 18（建议使用 LTS 版本）
- npm（与项目 `package-lock.json` 使用的 npm 版本兼容）

```bash
node -v
npm -v
```

## 2. 安装

```bash
npm install
```

## 3. 开发运行

```bash
npm run dev
```

默认地址 **http://localhost:5173**，打开浏览器访问即可。

## 4. 生产构建

```bash
npm run build      # 输出到 dist/
npm run preview    # 预览生产构建，默认 http://localhost:4173
```

## 5. 环境变量说明

| 变量 | 说明 | 示例 |
|------|------|------|
| `VITE_VOICE_WS_URL` | 后端语音 WebSocket 地址 | `ws://127.0.0.1:8765` |

- 开发环境：`.env.development`（已配置 `VITE_VOICE_WS_URL=ws://127.0.0.1:8765`）
- 生产环境：`.env.production`（部署前须替换占位地址为真实 `wss://` 地址），如部署环境不提供后端，可启用 `VITE_VOICE_DISABLE=1` 优雅禁用 AI 助手

> 提交源码包时仅保留 `.env.example`（示例），**不得包含真实密钥**。

## 6. 后端依赖说明

语音交互需先启动后端语音服务：

```bash
cd 挑战杯
# 使用挑战杯后端第二版 venv 的 Python（挑战杯自带 .venv 基础解释器不可用）
& "d:\WeChatFiles\挑战杯前端\c919-interactive\整个挑战杯的内容V2\挑战杯后端第二版\.venv\Scripts\python.exe" scripts\run_voice.py
# WS: ws://127.0.0.1:8765，健康检查: http://127.0.0.1:8765/health
```

前后端本地联调地址一致（`VITE_VOICE_WS_URL=ws://127.0.0.1:8765`），启动后端后即可在页面点击 AI 语音老师提问。

## 7. 目录结构

```
挑战杯前端/
├── src/
│   ├── views/         # 页面（32+，C919 根路由 + /j20、/y20、/z20 平行路由组）
│   ├── components/    # 复用组件（ExplodeModelViewer、SceneShell、TailModelViewer、CoachChatPanel、FerrofluidBackground 等）
│   ├── services/      # 语音链路（voiceWsClient / voiceAssistant / voiceRecorder / ttsPlayer）
│   ├── router/        # 路由配置（35 条，含导入页/选择页/详情页）
│   ├── utils/         # 机型资源路径工具（assets / j20Assets / y20Assets / z20Assets）
│   └── style.css      # 全局样式（含统一纯黑背景基准 --color-bg）
├── public/            # 静态资源（三维模型、背景图、模拟驾驶舱、课程视频）
└── vite.config.js
```

## 8. 打包红线（提交源码包前必查）

提交前必须剔除以下内容，只保留源码与 `.env.example`：

- `node_modules/`
- `dist/`、`dist-old-*/`
- `src-backup-*/`
- `.env`、`.env.production` 真实密钥（若含真实 `wss://` 地址，需以 `.env.example` 形式脱敏）
- `.venv` 类目录、临时日志文件（`*.log`）

## 9. 已知限制

- 语音打断：协议已打通，TTS 未接入生产媒体服务，生产环境待部署；
- 移动端 / 低端设备实时三维渲染性能待优化；
- 三维模型为科普示意级，非工程精确 CAD。

---

*本说明随前端提交材料，与《作品文档》§7.1 保持一致。*
