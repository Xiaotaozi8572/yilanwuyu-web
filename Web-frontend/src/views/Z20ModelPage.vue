<template>
  <main class="model-3d-page">
    <TailModelViewer
      :key="activeModel.id"
      :model-url="z20AssetPath(activeModel.model)"
      :model-label="`${activeModel.label}三维模型`"
    />

    <button class="back-btn" type="button" :aria-label="`返回${activeModel.label}介绍`" @click="goBack"></button>

    <header class="model-title">
      <span>GLB 3D 模型</span>
      <h1>{{ activeModel.title }}</h1>
      <p>{{ activeModel.subtitle }}</p>
    </header>

    <section class="spec-panel" :aria-label="`${activeModel.label}参数`">
      <span class="panel-kicker">结构参数</span>
      <dl>
        <div v-for="spec in activeModel.specs" :key="spec.name">
          <dt>{{ spec.name }}</dt>
          <dd>
            {{ spec.value }}
            <span v-if="spec.unit">{{ spec.unit }}</span>
          </dd>
        </div>
      </dl>
    </section>

    <section class="design-panel" :aria-label="`${activeModel.label}设计亮点`">
      <span class="panel-kicker">设计亮点</span>
      <ol>
        <li v-for="(feature, index) in activeModel.features" :key="feature.title">
          <i>{{ String(index + 1).padStart(2, '0') }}</i>
          <strong>{{ feature.title }}</strong>
          <span>{{ feature.body }}</span>
        </li>
      </ol>
    </section>

    <nav class="model-switcher" aria-label="切换3D模型">
      <button
        v-for="model in modelParts"
        :key="model.id"
        type="button"
        :class="{ active: activeModel.id === model.id }"
        @click="selectModel(model.id)"
      >
        {{ model.label }}
        <span class="rect-hit-layer" aria-hidden="true"></span>
      </button>
    </nav>

    <div class="model-floor" aria-hidden="true">
      <span></span>
      <span></span>
      <span></span>
    </div>
  </main>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TailModelViewer from '../components/TailModelViewer.vue'
import { z20AssetPath } from '../utils/z20Assets'

const route = useRoute()
const router = useRouter()

const modelParts = [
  {
    id: 'cockpit',
    label: '驾驶舱',
    title: '驾驶舱空间',
    subtitle: '直20 飞行控制与显示区域模型',
    model: 'models/直20-驾驶舱.glb',
    sourcePart: 'engine',
    specs: [
      { name: '核心区域', value: '飞行控制' },
      { name: '显示布局', value: '综合航电' },
      { name: '观察方向', value: '前向视野' },
      { name: '交互重点', value: '人机工效' }
    ],
    features: [
      { title: '操作聚焦', body: '座舱模型突出仪表、操纵和前窗的空间关系。' },
      { title: '信息集中', body: '显示区域让飞行状态与导航信息更易被快速读取。' },
      { title: '工效布局', body: '控制区围绕飞行员坐姿展开，降低学习与操作负担。' }
    ]
  },
  {
    id: 'glass',
    label: '风挡',
    title: '两块式大风挡',
    subtitle: '直20 驾驶舱前窗与视野结构',
    model: 'models/直20-两块玻璃.glb',
    sourcePart: 'glass',
    specs: [
      { name: '布局形式', value: '两块式' },
      { name: '视野覆盖', value: '广角' },
      { name: '防护设计', value: '抗鸟撞' },
      { name: '除雾除冰', value: '电加热' }
    ],
    features: [
      { title: '宽阔视野', value: '' },
      { title: '一体曲面', body: '弧形一体化外轮廓减小迎风阻力。' },
      { title: '多层复合', body: '多层复合夹胶结构提升抗冲击与耐候能力。' }
    ]
  },
  {
    id: 'rotor',
    label: '主旋翼',
    title: '五叶主旋翼',
    subtitle: '直20 五桨叶复合材料旋翼系统',
    model: 'models/直20-五叶主旋翼.glb',
    sourcePart: 'rotor',
    specs: [
      { name: '桨叶数量', value: '5 片' },
      { name: '桨叶材料', value: '复合材料' },
      { name: '桨尖设计', value: '后掠降噪' },
      { name: '桨毂结构', value: '弹性减摆' }
    ],
    features: [
      { title: '五叶构型', body: '五片桨叶显著提升升力与高原飞行表现。' },
      { title: '降噪桨尖', body: '后掠式桨尖与后缘修型抑制气动噪声。' },
      { title: '复合材料', body: '碳纤维复合材料桨叶兼顾轻量化与强度。' }
    ]
  },
  {
    id: 'gear',
    label: '起落架',
    title: '起落架系统',
    subtitle: '直20 前三点式起落架结构',
    model: 'models/直20-起落架.glb',
    sourcePart: 'gear',
    specs: [
      { name: '布局形式', value: '前三点式' },
      { name: '前起落架', value: '转向收放' },
      { name: '主起落架', value: '缓冲支柱' },
      { name: '机轮', value: '大尺寸承压' }
    ],
    features: [
      { title: '前起转向', body: '前起落架转向收放组件提升地面机动性。' },
      { title: '缓冲支柱', body: '主起落架缓冲支柱吸收着陆冲击载荷。' },
      { title: '承压机轮', body: '大尺寸机轮适应多种野战起降环境。' }
    ]
  },
  {
    id: 'tail',
    label: '尾桨',
    title: '剪刀式尾桨',
    subtitle: '直20 低噪声剪刀式尾桨',
    model: 'models/直20-剪刀式尾桨.glb',
    sourcePart: 'tail',
    specs: [
      { name: '布局形式', value: '剪刀式' },
      { name: '叶片设计', value: '不对称' },
      { name: '安装基座', value: '倾斜尾梁' },
      { name: '传动总成', value: '尾桨毂传动' }
    ],
    features: [
      { title: '剪刀构型', body: '不对称叶片布局有效抑制尾桨气动噪声。' },
      { title: '倾斜安装', body: '倾斜尾梁安装基座优化尾部气动衔接。' },
      { title: '紧凑传动', body: '尾桨毂传动总成结构紧凑、维护友好。' }
    ]
  }
]

const modelMap = Object.fromEntries(modelParts.map((model) => [model.id, model]))
const requestedPart = computed(() => (typeof route.query.part === 'string' ? route.query.part : 'engine'))
const activeModel = computed(() => modelMap[requestedPart.value] ?? modelMap.cockpit)

function selectModel(id) {
  router.replace({ path: '/z20/parts-3d', query: { part: id } })
}

function goBack() {
  router.push({ path: '/z20/parts', query: { part: activeModel.value.sourcePart } })
}
</script>

<style scoped>
.model-3d-page {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  isolation: isolate;
  background: var(--color-bg);
}

.model-3d-page::before,
.model-3d-page::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
}

.model-3d-page::before {
  background:
    linear-gradient(90deg, rgba(10, 14, 19, 0.86) 0%, transparent 24%, transparent 76%, rgba(10, 14, 19, 0.86) 100%),
    linear-gradient(180deg, rgba(3, 6, 9, 0.72) 0%, transparent 30%, transparent 65%, rgba(3, 6, 9, 0.86) 100%);
}

.model-3d-page::after {
  opacity: 0.26;
  background:
    repeating-linear-gradient(90deg, transparent 0 58px, rgba(255, 255, 255, 0.055) 59px 60px),
    repeating-linear-gradient(0deg, transparent 0 58px, rgba(255, 255, 255, 0.038) 59px 60px);
  mask-image: linear-gradient(180deg, transparent, #000 22%, #000 82%, transparent);
}

.model-3d-page :deep(.tail-model-viewer) {
  z-index: 1;
  background:
    radial-gradient(circle at 50% 38%, rgba(31, 126, 142, 0.2), transparent 36%),
    var(--color-bg);
}

.model-3d-page :deep(.model-stage) {
  inset: 8% 18% 12%;
}

.model-3d-page :deep(.tail-model-viewer::after) {
  background:
    linear-gradient(90deg, rgba(0, 0, 0, 0.44), transparent 22%, transparent 78%, rgba(0, 0, 0, 0.44)),
    linear-gradient(180deg, rgba(0, 0, 0, 0.2), transparent 40%, rgba(0, 0, 0, 0.46));
}

.model-title,
.spec-panel,
.design-panel,
.model-switcher,
.model-floor {
  position: absolute;
  z-index: 4;
}

.model-title {
  top: 34px;
  left: 50%;
  width: min(620px, calc(100% - 320px));
  text-align: center;
  pointer-events: none;
  transform: translateX(-50%);
}

.model-title span,
.panel-kicker {
  color: #6ef1ff;
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-bold, 700);
  line-height: 1.5;
  letter-spacing: 0;
}

.model-title h1 {
  margin-top: 3px;
  padding-bottom: 3px;
  color: #ffffff;
  font-size: 44px;
  line-height: 1.16;
  font-weight: var(--weight-heavy, 800);
  text-shadow: 0 10px 36px rgba(0, 0, 0, 0.55);
}

.model-title p {
  margin-top: 7px;
  color: rgba(255, 255, 255, 0.66);
  font-size: 13px;
  line-height: 1.65;
}

.spec-panel {
  left: clamp(28px, 4.6cqw, 72px);
  bottom: 138px;
  width: min(300px, 24cqw);
  padding-left: 18px;
  border-left: 2px solid rgba(110, 241, 255, 0.56);
}

.spec-panel dl {
  display: grid;
  gap: 10px;
  margin-top: 15px;
}

.spec-panel div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  min-height: 44px;
  padding: 0 0 9px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.13);
}

.spec-panel dt {
  color: rgba(255, 255, 255, 0.64);
  font-size: var(--text-xs, 12px);
  line-height: 1.5;
}

.spec-panel dd {
  color: #ffffff;
  font-size: 23px;
  font-weight: var(--weight-bold, 700);
  line-height: 1.2;
  white-space: nowrap;
}

.spec-panel dd span {
  margin-left: 5px;
  color: #6ef1ff;
  font-size: 0.46em;
  font-weight: 700;
}

.design-panel {
  right: clamp(34px, 4.6cqw, 88px);
  top: 50%;
  width: min(270px, 21cqw);
  transform: translateY(-43%);
}

.design-panel ol {
  display: grid;
  gap: 18px;
  margin-top: 20px;
  list-style: none;
}

.design-panel li {
  position: relative;
  display: grid;
  gap: 5px;
  padding-left: 50px;
}

.design-panel i {
  position: absolute;
  left: 0;
  top: 2px;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  color: #081014;
  background: #6ef1ff;
  box-shadow: 0 0 20px rgba(56, 232, 255, 0.26);
  font-size: var(--text-micro, 11px);
  font-style: normal;
  font-weight: var(--weight-bold, 700);
}

.design-panel strong {
  color: #ffffff;
  font-size: 17px;
  line-height: 1.4;
  font-weight: var(--weight-bold, 700);
}

.design-panel li span {
  color: rgba(255, 255, 255, 0.58);
  font-size: var(--text-xs, 12px);
  line-height: 1.7;
}

.model-switcher {
  left: 50%;
  bottom: clamp(28px, 5cqh, 54px);
  display: flex;
  gap: 8px;
  max-width: calc(100% - 48px);
  padding: 8px;
  overflow-x: auto;
  border: 1px solid rgba(155, 245, 255, 0.24);
  border-radius: 999px;
  background: rgba(17, 21, 29, 0.68);
  box-shadow:
    0 16px 42px rgba(0, 0, 0, 0.34),
    inset 0 0 18px rgba(255, 255, 255, 0.035);
  transform: translateX(-50%);
  scrollbar-width: none;
  -ms-overflow-style: none;
  backdrop-filter: blur(14px) saturate(150%);
  -webkit-backdrop-filter: blur(14px) saturate(150%);
}

.model-switcher::-webkit-scrollbar {
  display: none;
}

.model-switcher button {
  position: relative;
  flex: 0 0 auto;
  min-width: 72px;
  min-height: 38px;
  padding: 9px 15px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.72);
  background: transparent;
  font-size: 13px;
  font-weight: var(--weight-semibold, 600);
  line-height: 1.35;
  transition:
    color 0.24s ease,
    background 0.24s ease,
    box-shadow 0.24s ease,
    transform 0.24s var(--ease-bounce);
}

.model-switcher button:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.09);
}

.model-switcher button.active {
  color: #071014;
  background: linear-gradient(180deg, #88f5ff, #37deee);
  box-shadow: 0 0 20px rgba(56, 232, 255, 0.32);
  transform: translateY(-2px);
}

.model-switcher button:focus-visible {
  outline: 2px solid #ffffff;
  outline-offset: 3px;
}

.model-floor {
  left: 50%;
  bottom: clamp(96px, 11cqh, 132px);
  display: grid;
  width: min(760px, 52cqw);
  height: 66px;
  place-items: center;
  transform: translateX(-50%);
  pointer-events: none;
}

.model-floor span {
  position: absolute;
  border: 1px solid rgba(110, 241, 255, 0.38);
  border-radius: 50%;
  transform: perspective(520px) rotateX(68deg);
}

.model-floor span:nth-child(1) {
  width: 84%;
  height: 130px;
  opacity: 0.62;
}

.model-floor span:nth-child(2) {
  width: 56%;
  height: 90px;
  opacity: 0.42;
}

.model-floor span:nth-child(3) {
  width: 28%;
  height: 52px;
  opacity: 0.58;
  border-color: rgba(255, 209, 102, 0.48);
}

@media (max-width: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .model-3d-page :deep(.model-stage) {
    inset: 8% 11% 13%;
  }

  .design-panel {
    display: none;
  }
}

@media (max-width: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .model-3d-page :deep(.model-stage) {
    inset: 21% 2% 36%;
  }

  .model-title {
    top: 22px;
    width: min(520px, 62%);
  }

  .spec-panel {
    left: 18px;
    right: 18px;
    bottom: 142px;
    width: auto;
    padding-left: 14px;
  }

  .spec-panel dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 18px;
    margin-top: 10px;
  }

  .spec-panel div {
    display: grid;
    gap: 4px;
    padding-bottom: 7px;
  }

  .spec-panel dd {
    font-size: 19px;
  }

  .design-panel {
    display: none;
  }

  .model-floor {
    display: none;
  }

  .model-switcher {
    bottom: 18px;
  }
}

@media (max-width: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .model-title {
    top: 70px;
    width: calc(100% - 36px);
  }

  .model-title h1 {
    font-size: 36px;
  }

  .model-3d-page :deep(.model-stage) {
    inset: 22% -10% 39%;
  }

  .spec-panel {
    bottom: 142px;
  }

  .model-switcher {
    left: 12px;
    right: 12px;
    bottom: 12px;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 5px;
    width: auto;
    max-width: none;
    padding: 7px;
    overflow: visible;
    border-radius: 12px;
    transform: none;
  }

  .model-switcher button {
    min-width: 0;
    min-height: 36px;
    padding: 7px 5px;
    border-radius: 7px;
    font-size: 13px;
  }
}

@container (max-height: 520px) {
  .model-title {
    top: 10px;
    width: min(520px, 68%);
  }

  .model-title h1 {
    margin-top: 1px;
    font-size: 30px;
  }

  .model-title p {
    margin-top: 2px;
    font-size: 11px;
  }

  .spec-panel,
  .design-panel,
  .model-floor {
    display: none;
  }

  .model-3d-page :deep(.model-stage) {
    inset: 20% 7% 24%;
  }

  .model-switcher {
    bottom: 10px;
    gap: 5px;
    padding: 5px;
  }

  .model-switcher button {
    min-height: 32px;
    padding: 6px 12px;
    font-size: 12px;
  }
}
</style>
