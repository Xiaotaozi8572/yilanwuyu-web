<template>
  <main class="model-3d-page">
    <TailModelViewer
      :key="activeModel.id"
      :model-url="y20AssetPath(activeModel.model)"
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
import { y20AssetPath } from '../utils/y20Assets'

const route = useRoute()
const router = useRouter()

const modelParts = [
  {
    id: 'cockpit',
    label: '驾驶舱',
    title: '驾驶舱空间',
    subtitle: '运20 飞行控制与显示区域模型',
    model: 'models/运20驾驶舱.glb',
    sourcePart: 'nose',
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
    id: 'nose',
    label: '机头',
    title: '圆润无下巴机头',
    subtitle: '运20 前机身与雷达罩结构',
    model: 'models/机头.glb',
    sourcePart: 'nose',
    specs: [
      { name: '机头形式', value: '圆润流线' },
      { name: '雷达罩', value: '内置雷达' },
      { name: '风挡视野', value: '广角' },
      { name: '检修设计', value: '设备舱门' }
    ],
    features: [
      { title: '流线壳体', body: '圆润无"下巴"的机头轮廓减小高速阻力。' },
      { title: '雷达罩', body: '内置气象雷达，前向探测能力突出。' },
      { title: '检修便捷', body: '设备检修舱门让机头设备维护更高效。' }
    ]
  },
  {
    id: 'wing',
    label: '超临界机翼',
    title: '超临界机翼',
    subtitle: '运20 大展弦比超临界机翼',
    model: 'models/超临界机翼.glb',
    sourcePart: 'wing',
    specs: [
      { name: '翼型', value: '超临界' },
      { name: '展弦比', value: '大展弦比' },
      { name: '翼根', value: '强化连接' },
      { name: '增升装置', value: '前缘后缘' }
    ],
    features: [
      { title: '超临界翼型', body: '推迟激波、降低巡航阻力，航程更远。' },
      { title: '大展弦比', body: '提高升阻比，改善燃油经济性。' },
      { title: '机翼前缘后缘', body: '前缘后缘修型与增升装置提升起降性能。' }
    ]
  },
  {
    id: 'box',
    label: '中央翼盒',
    title: '外置中央翼盒',
    subtitle: '运20 机身外置中央翼盒',
    model: 'models/驼背外置中央翼盒.glb',
    sourcePart: 'box',
    specs: [
      { name: '布局', value: '外置驼背' },
      { name: '货舱', value: '全通高度' },
      { name: '承力', value: '拱起壳体' },
      { name: '对接', value: '左右翼盒' }
    ],
    features: [
      { title: '驼背外置', body: '翼盒外置让货舱净空从头到尾一致。' },
      { title: '拱起承力', body: '拱起上壳体兼顾气动与结构承载。' },
      { title: '减重设计', body: '中央镂空减重腔在保证强度下减轻结构重量。' }
    ]
  },
  {
    id: 'tail',
    label: 'T型高平尾',
    title: 'T型高平尾',
    subtitle: '运20 大型T型尾翼结构',
    model: 'models/尾翼.glb',
    sourcePart: 'tail',
    specs: [
      { name: '布局', value: 'T型' },
      { name: '水平安定面', value: '大翼展' },
      { name: '垂直安定面', value: '高置' },
      { name: '舵面', value: '升降+方向' }
    ],
    features: [
      { title: 'T型构型', body: '高置水平安定面远离翼盒湍流干扰。' },
      { title: '方向舵面', body: '垂尾方向舵面提供大角度航向控制。' },
      { title: '升降舵面', body: '平尾升降舵面配合重心实现俯仰配平。' }
    ]
  },
  {
    id: 'assembly',
    label: '整机',
    title: '运-20 整机',
    subtitle: '运20 鲲鹏大型运输机整体模型',
    model: 'models/运20本体.glb',
    sourcePart: 'nose',
    specs: [
      { name: '最大起飞重量', value: '200', unit: '吨级' },
      { name: '最大载重', value: '66', unit: '吨' },
      { name: '航程', value: '7800', unit: '公里' },
      { name: '发动机', value: '涡扇-20 ×4' }
    ],
    features: [
      { title: '大运载', body: '66吨级运载能力可运输99A主战坦克。' },
      { title: '远航程', body: '超临界机翼与高效动力支撑长航程战略投送。' },
      { title: '野战起降', body: '多轮起落架可适应山区与土质简易跑道。' }
    ]
  }
]

const modelMap = Object.fromEntries(modelParts.map((model) => [model.id, model]))
const requestedPart = computed(() => (typeof route.query.part === 'string' ? route.query.part : 'nose'))
const activeModel = computed(() => modelMap[requestedPart.value] ?? modelMap.cockpit)

function selectModel(id) {
  router.replace({ path: '/y20/parts-3d', query: { part: id } })
}

function goBack() {
  router.push({ path: '/y20/parts', query: { part: activeModel.value.sourcePart } })
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
