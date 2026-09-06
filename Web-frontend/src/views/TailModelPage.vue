<template>
  <main class="model-3d-page">
    <TailModelViewer
      :key="activeModel.id"
      :model-url="assetPath(activeModel.model)"
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
import { assetPath } from '../utils/assets'

const route = useRoute()
const router = useRouter()

const modelParts = [
  {
    id: 'nose',
    label: '机头',
    title: '圆润流线型机头',
    subtitle: 'C919 前机身与驾驶舱过渡结构',
    model: 'models/c919-nose.glb',
    sourcePart: 'nose',
    specs: [
      { name: '前机身区域', value: '驾驶舱' },
      { name: '气动外形', value: '低阻过渡' },
      { name: '雷达罩曲率', value: '圆润连续' },
      { name: '结构重点', value: '视野与安全' }
    ],
    features: [
      { title: '流线外形', body: '机头外轮廓平滑收束，减少迎风阻力。' },
      { title: '驾驶舱融合', body: '风挡、舱门和雷达罩区域形成连续曲面。' },
      { title: '前向安全', body: '前机身空间兼顾航电、视野和维护需求。' }
    ]
  },
  {
    id: 'glass',
    label: '风挡',
    title: '四块式风挡玻璃',
    subtitle: 'C919 驾驶舱前窗与视野结构',
    model: 'models/c919-front-window.glb',
    sourcePart: 'glass',
    specs: [
      { name: '布局形式', value: '四块式' },
      { name: '视野覆盖', value: '广角' },
      { name: '防护设计', value: '抗鸟撞' },
      { name: '除雾除冰', value: '电加热' }
    ],
    features: [
      { title: '宽阔视野', body: '多块风挡分区提供起降和巡航时的稳定观察范围。' },
      { title: '夹层防护', body: '复合透明结构提升抗冲击与耐候能力。' },
      { title: '维护友好', body: '模块化玻璃区域便于检查、除雾和后期更换。' }
    ]
  },
  {
    id: 'cockpit',
    label: '驾驶舱',
    title: '驾驶舱空间',
    subtitle: 'C919 飞行控制与显示区域模型',
    model: 'models/c919-cockpit.glb',
    sourcePart: 'glass',
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
    id: 'engine',
    label: '发动机',
    title: '涡扇发动机',
    subtitle: 'C919 大涵道比动力装置',
    model: 'models/c919-engine.glb',
    sourcePart: 'engine',
    specs: [
      { name: '发动机类型', value: '涡扇' },
      { name: '涵道特征', value: '大涵道比' },
      { name: '降噪设计', value: '低噪声' },
      { name: '效率目标', value: '低油耗' }
    ],
    features: [
      { title: '高效推进', body: '大涵道比涡扇方案适合干线客机巡航工况。' },
      { title: '短舱整合', body: '发动机与吊挂、短舱共同影响气动与维护效率。' },
      { title: '降噪优化', body: '进气、风扇和喷口设计共同降低客舱与机场噪声。' }
    ]
  },
  {
    id: 'wing',
    label: '机翼',
    title: '超临界机翼',
    subtitle: 'C919 翼面与翼梢小翼气动结构',
    model: 'models/c919-wing.glb',
    sourcePart: 'wing',
    specs: [
      { name: '翼梢小翼高度', value: '2.8', unit: 'm' },
      { name: '翼展', value: '35.8', unit: 'm' },
      { name: '减阻效果', value: '~3', unit: '%' },
      { name: '节油效果', value: '3-5', unit: '%' }
    ],
    features: [
      { title: '超临界翼型', body: '平缓上表面延缓激波形成，提升巡航效率。' },
      { title: '翼梢减阻', body: '翼梢小翼削弱翼尖涡，改善升阻比。' },
      { title: '结构协同', body: '翼梁、蒙皮和增升装置共同承担升力与载荷。' }
    ]
  },
  {
    id: 'tail',
    label: '机尾',
    title: '蜂腰状机尾',
    subtitle: 'C919 后机身气动收缩结构',
    model: 'models/c919-tail.glb',
    sourcePart: 'tail',
    specs: [
      { name: '后机身前段全长', value: '3.2', unit: 'm' },
      { name: '后机身后段全长', value: '2.35', unit: 'm' },
      { name: '前端最大截面直径', value: '3.1', unit: 'm' },
      { name: '后机身后段重量', value: '260', unit: 'kg' }
    ],
    features: [
      { title: '双曲面后机身', body: '让尾部截面自然过渡，降低跨音速阻力。' },
      { title: '蜂腰收缩设计', body: '翼尾连接区域更紧凑，兼顾结构连续性。' },
      { title: '复合材料协同', body: '铝锂合金与复合材料共同控制重量。' }
    ]
  }
]

const modelMap = Object.fromEntries(modelParts.map((model) => [model.id, model]))
const requestedPart = computed(() => (typeof route.query.part === 'string' ? route.query.part : 'tail'))
const activeModel = computed(() => modelMap[requestedPart.value] ?? modelMap.tail)

function selectModel(id) {
  router.replace({ path: '/parts-3d', query: { part: id } })
}

function goBack() {
  router.push({ path: '/parts', query: { part: activeModel.value.sourcePart } })
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

@container (max-width: 560px) and (max-height: 320px) {
  .model-title {
    top: 5px;
    width: 72%;
  }

  .model-title span {
    font-size: 9px;
  }

  .model-title h1 {
    font-size: 23px;
  }

  .model-title p {
    display: none;
  }

  .model-3d-page :deep(.model-stage) {
    inset: 19% 1% 24%;
  }

  .model-switcher {
    left: 50%;
    right: auto;
    bottom: 4px;
    display: flex;
    width: auto;
    padding: 4px;
    border-radius: 999px;
    transform: translateX(-50%);
  }

  .model-switcher button {
    min-width: 48px;
    min-height: 26px;
    padding: 4px 6px;
    border-radius: 999px;
    font-size: 10px;
  }
}
</style>
