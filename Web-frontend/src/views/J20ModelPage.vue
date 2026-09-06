<template>
  <main class="model-3d-page">
    <TailModelViewer
      :key="activeModel.id"
      :model-url="j20AssetPath(activeModel.model)"
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
import { j20AssetPath } from '../utils/j20Assets'

const route = useRoute()
const router = useRouter()

const modelParts = [
  {
    id: 'canard',
    label: '鸭式布局',
    title: '鸭式气动布局',
    subtitle: '歼-20 前鸭翼与三角主翼气动布局',
    model: 'models/歼20鸭式气动布局.glb',
    sourcePart: 'canard',
    specs: [
      { name: '布局', value: '鸭式' },
      { name: '主翼', value: '三角翼' },
      { name: '鸭翼', value: '前翼' },
      { name: '机动性', value: '高机动' }
    ],
    features: [
      { title: '涡流增升', body: '鸭翼涡流吹过主翼上方，大幅提升升力与机动性。' },
      { title: '超音速适配', body: '三角主翼兼顾跨声速与超音速气动性能。' },
      { title: '高机动', body: '鸭式布局支持大迎角机动与快速姿态变换。' }
    ]
  },
  {
    id: 'tail',
    label: '全动双垂尾',
    title: '全动双垂尾',
    subtitle: '歼-20 隐身全动双垂尾',
    model: 'models/歼20全动双垂尾.glb',
    sourcePart: 'tail',
    specs: [
      { name: '布局', value: '双垂尾' },
      { name: '舵面', value: '全动' },
      { name: '方向舵', value: '可动' },
      { name: '隐身', value: '菱形切角' }
    ],
    features: [
      { title: '全动设计', body: '垂尾整体偏转，航向控制响应更迅速。' },
      { title: '隐身切角', body: '菱形切角与倾斜布局降低侧向雷达反射。' },
      { title: '差动控制', body: '左右垂尾差动辅助横滚与偏航机动。' }
    ]
  }
]

const modelMap = Object.fromEntries(modelParts.map((model) => [model.id, model]))
const alias = { inlet: 'canard', engine: 'canard' }
const requestedPart = computed(() => {
  const raw = typeof route.query.part === 'string' ? route.query.part : 'canard'
  return alias[raw] ?? raw
})
const activeModel = computed(() => modelMap[requestedPart.value] ?? modelMap.canard)

function selectModel(id) {
  router.replace({ path: '/j20/parts-3d', query: { part: id } })
}

function goBack() {
  router.push({ path: '/j20/parts', query: { part: activeModel.value.sourcePart } })
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
