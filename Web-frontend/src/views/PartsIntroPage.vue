<template>
  <main class="parts-page" :class="`part-${activePart}`">
    <button class="back-btn" type="button" aria-label="返回主交互页" @click="$router.push('/interaction')"></button>

    <aside class="left-stack">
      <img class="part-panel part-basic-panel" :src="active.basicCardImage" :alt="`${active.label}基本信息`" />
      <img class="part-panel part-feature-panel" :src="active.featureCardImage" :alt="`${active.label}图文介绍`" />
    </aside>

    <section class="main-figure" :class="`figure-${activePart}`">
      <button class="figure-button" type="button" :aria-label="`查看${active.label}3D展示`" @click="goModel">
        <img :src="active.mainImage" :alt="`${active.title}结构标注`" />
      </button>
    </section>

    <aside class="right-stack">
      <button class="teacher-card" type="button" aria-label="AI语音老师" @click="toggleCoach">
        <img src="/c919-assets/ui/robot-default.png" alt="AI语音老师" />
      </button>

      <img
        v-if="active.assistantCardImage"
        class="assistant-info-card"
        :src="active.assistantCardImage"
        :alt="active.assistantCardAlt"
      />

      <article v-for="ring in active.rings" :key="ring.label" class="ring-card">
        <img :src="ring.image" :alt="`${ring.label}${ring.value}`" />
      </article>
    </aside>

    <h1 class="part-title">
      <img :src="active.titleImage" :alt="active.title" />
    </h1>

    <section class="bottom-board">
      <img class="bottom-board-image" :src="active.bottomCardImage" :alt="`${active.label}性能对比`" />
      <button class="board-return" type="button" aria-label="返回主交互页" @click="$router.push('/interaction')"></button>
    </section>

    <button class="model-3d-button" type="button" @click="goModel">
      <i aria-hidden="true"></i>
      <span>3D展示</span>
    </button>

    <nav class="part-switcher" aria-label="切换部件介绍">
      <button
        v-for="part in parts"
        :key="part.id"
        type="button"
        :class="{ active: activePart === part.id }"
        @click="selectPart(part.id)"
      >
        {{ part.label }}
      </button>
    </nav>

    <transition name="coach">
      <CoachChatPanel
        v-if="showCoach"
        scene="parts-intro"
        aircraft="C919"
        @close="showCoach = false"
      />
    </transition>
  </main>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CoachChatPanel from '../components/CoachChatPanel.vue'

const cyan = '#35d5ee'
const yellow = '#f5c21a'
const purple = '#9275ff'
const coral = '#f07f7d'

const parts = [
  {
    id: 'nose',
    label: '机头',
    title: '圆润流线型机头',
    mainImage: '/c919-assets/parts-slices/nose-main.png',
    miniImage: '/c919-assets/parts-slices/nose-mini.png',
    titleImage: '/c919-assets/parts-ui/nose-title.png',
    basicCardImage: '/c919-assets/parts-ui/nose-basic.png',
    featureCardImage: '/c919-assets/parts-ui/nose-feature.png',
    bottomCardImage: '/c919-assets/parts-ui/nose-bottom.png',
    specs: [
      { label: '机头总长', value: '6.66 m' },
      { label: '宽度', value: '3.96 m' },
      { label: '高度', value: '4.13 m' },
      { label: '重量', value: '2.4 t' }
    ],
    description: '雷达罩采用泡沫夹层（国内首次），铝质分流条能够减少反射，底漆+耐雨蚀+抗静电3层防护，全三维一体化。',
    rings: [{ label: '阻力降低', value: '15%', image: '/c919-assets/parts-ui/nose-ring-drag-crop.png' }],
    chart: {
      type: 'radar',
      legend: [
        { name: 'B737', color: purple },
        { name: 'A320', color: coral },
        { name: 'C919', color: cyan }
      ],
      polygons: [
        { name: 'B737', color: 'rgba(146,117,255,.45)', points: '50% 18%, 68% 31%, 72% 53%, 61% 72%, 45% 78%, 28% 66%, 22% 48%, 35% 29%' },
        { name: 'A320', color: 'rgba(240,127,125,.43)', points: '50% 28%, 64% 36%, 66% 54%, 57% 67%, 46% 70%, 34% 61%, 31% 48%, 39% 34%' },
        { name: 'C919', color: 'rgba(53,213,238,.48)', points: '50% 10%, 73% 27%, 82% 51%, 66% 75%, 48% 88%, 25% 70%, 16% 47%, 32% 23%' }
      ]
    },
    metrics: [
      { label: '重量减少\n(较B737)', value: 56, display: '14%' },
      { label: '阻力贡献\n(较整机)', value: 18, display: '4%' },
      { label: '燃油效率提升\n(较基准)', value: 44, display: '11%' }
    ]
  },
  {
    id: 'glass',
    label: '风挡',
    title: '四块式挡风玻璃',
    mainImage: '/c919-assets/parts-slices/glass-main.png',
    miniImage: '/c919-assets/parts-slices/glass-mini.png',
    titleImage: '/c919-assets/parts-ui/glass-title.png',
    basicCardImage: '/c919-assets/parts-ui/glass-basic.png',
    featureCardImage: '/c919-assets/parts-ui/glass-feature.png',
    bottomCardImage: '/c919-assets/parts-ui/glass-bottom.png',
    assistantCardImage: '/c919-assets/parts-ui/glass-vision-compare.png',
    assistantCardAlt: 'C919挡风玻璃视野对比',
    specs: [
      { label: '玻璃数量', value: '四块' },
      { label: '玻璃形态', value: '双曲面' },
      { label: '水平视野', value: '~180°' },
      { label: '垂直视野', value: '~45°' }
    ],
    description: '提供更广阔的视野，有助于增强对危险情况的判断能力并提升飞行操作的可视性与安全性。风挡与机头雷达罩线条融合，气动减阻效果明显。',
    rings: [],
    chart: {
      type: 'bars',
      variant: 'vertical-compare',
      legend: [
        { name: 'B737', color: cyan },
        { name: 'A320', color: coral },
        { name: 'C919', color: purple }
      ],
      rows: [
        {
          label: '水平视野角',
          values: [
            { name: 'B737', value: 82, color: cyan },
            { name: 'A320', value: 85, color: coral },
            { name: 'C919', value: 94, color: purple }
          ]
        },
        {
          label: '垂直视野角',
          values: [
            { name: 'B737', value: 24, color: cyan },
            { name: 'A320', value: 24, color: coral },
            { name: 'C919', value: 27, color: purple }
          ]
        }
      ]
    },
    metrics: [
      { label: '强度提升\n(较普通玻璃)', value: 50, display: '50%' },
      { label: '制造成本降低\n(较进口)', value: 30, display: '30%' },
      { label: '框架重量降低\n(较传统风挡)', value: 50, display: '50%' }
    ]
  },
  {
    id: 'engine',
    label: '发动机',
    title: 'LEAP-1C涡扇发动机',
    mainImage: '/c919-assets/parts-slices/engine-main.png',
    miniImage: '/c919-assets/parts-slices/engine-mini.png',
    titleImage: '/c919-assets/parts-ui/engine-title.png',
    basicCardImage: '/c919-assets/parts-ui/engine-basic.png',
    featureCardImage: '/c919-assets/parts-ui/engine-feature.png',
    bottomCardImage: '/c919-assets/parts-ui/engine-bottom.png',
    specs: [
      { label: '风扇直径', value: '1.96 m' },
      { label: '最大推力', value: '133.4 kn' },
      { label: '总压比', value: '40:1' },
      { label: '重量', value: '3929-3935 kg' }
    ],
    description: '采用三维技术编织，质量轻、耐久性好，抗外物打伤能力强、抗振动性能好。有18片风扇叶片，长0.79m、宽0.37m、重5.65kg。',
    rings: [{ label: '涵道比', value: '11:1', image: '/c919-assets/parts-ui/engine-ring-bypass-crop.png' }],
    chart: {
      type: 'bars',
      variant: 'horizontal-compare',
      legend: [
        { name: 'LEAP-1C', color: cyan },
        { name: 'CFM56-5B', color: yellow }
      ],
      rows: [
        {
          label: '涵道比',
          values: [
            { name: 'LEAP-1C', value: 78, color: cyan },
            { name: 'CFM56-5B', value: 20, color: yellow }
          ]
        },
        {
          label: '推力',
          values: [
            { name: 'LEAP-1C', value: 90, color: cyan },
            { name: 'CFM56-5B', value: 82, color: yellow }
          ]
        }
      ]
    },
    metrics: [
      { label: '燃油效率提升\n(较CFM56)', value: 42, display: '15%' },
      { label: '二氧化碳减排\n(较上代)', value: 48, display: '16%' },
      { label: '噪音降低\n(较CFM56)', value: 42, display: '15 dB' }
    ]
  },
  {
    id: 'wing',
    label: '机翼',
    title: '超临界机翼',
    mainImage: '/c919-assets/parts-slices/wing-main.png',
    miniImage: '/c919-assets/parts-slices/wing-mini.png',
    titleImage: '/c919-assets/parts-ui/wing-title.png',
    basicCardImage: '/c919-assets/parts-ui/wing-basic.png',
    featureCardImage: '/c919-assets/parts-ui/wing-feature.png',
    bottomCardImage: '/c919-assets/parts-ui/wing-bottom.png',
    specs: [
      { label: '翼梢小翼高度', value: '2.8 m' },
      { label: '翼展', value: '35.8 m' },
      { label: '减阻效果', value: '~3%' },
      { label: '节油效果', value: '3-5%' }
    ],
    description: '融合式翼梢小翼，与机翼过渡非常圆滑、较小，几乎完全是曲面，没有直翼面的部分。提高机翼效率、增强操纵性，最直接的好处是省油。',
    rings: [
      { label: '诱导阻力', value: '↓ 3%', image: '/c919-assets/parts-ui/wing-ring-drag-crop.png' },
      { label: '航程提升', value: '5%', image: '/c919-assets/parts-ui/wing-ring-range-crop.png' }
    ],
    chart: {
      type: 'radar',
      legend: [
        { name: '翼尖帆板', color: purple },
        { name: '传统翼梢小翼', color: coral },
        { name: '斜削式翼尖', color: cyan },
        { name: '分裂式翼刀', color: yellow },
        { name: 'C919融合式小翼', color: '#5a7dff' }
      ],
      polygons: [
        { name: '翼尖帆板', color: 'rgba(146,117,255,.4)', points: '50% 18%, 65% 34%, 67% 52%, 58% 78%, 45% 82%, 30% 62%, 19% 46%, 35% 24%' },
        { name: '传统翼梢小翼', color: 'rgba(240,127,125,.36)', points: '50% 34%, 66% 41%, 62% 56%, 55% 70%, 44% 66%, 31% 55%, 34% 43%, 43% 32%' },
        { name: '斜削式翼尖', color: 'rgba(53,213,238,.38)', points: '50% 22%, 74% 31%, 78% 52%, 63% 70%, 47% 73%, 29% 64%, 24% 45%, 36% 28%' },
        { name: '分裂式翼刀', color: 'rgba(245,194,26,.34)', points: '50% 14%, 69% 26%, 70% 52%, 60% 68%, 46% 62%, 35% 58%, 27% 43%, 40% 25%' },
        { name: 'C919融合式小翼', color: 'rgba(90,125,255,.38)', points: '50% 16%, 72% 29%, 81% 50%, 66% 73%, 49% 84%, 26% 68%, 18% 47%, 34% 21%' }
      ]
    },
    metrics: [
      { label: '诱导阻力降低', value: 30, display: '3%' },
      { label: '航程提升', value: 50, display: '5%' },
      { label: '节油效果', value: 45, display: '3-5%' }
    ]
  },
  {
    id: 'tail',
    label: '机尾',
    title: '蜂腰状机尾',
    mainImage: '/c919-assets/parts-slices/tail-main.png',
    miniImage: '/c919-assets/parts-slices/tail-mini.png',
    titleImage: '/c919-assets/parts-ui/tail-title.png',
    basicCardImage: '/c919-assets/parts-ui/tail-basic.png',
    featureCardImage: '/c919-assets/parts-ui/tail-feature.png',
    bottomCardImage: '/c919-assets/parts-ui/tail-bottom.png',
    specs: [
      { label: '后机身前段全长', value: '3.2 m' },
      { label: '后机身后段全长', value: '2.35 m' },
      { label: '前端最大截面直径', value: '3.1 m' },
      { label: '后机身后段重量', value: '260 kg' }
    ],
    description: '蜂腰状设计基于跨音速面积律，使机翼/尾翼与机身连接区截面积缩小，前后方截面积增大，当量旋成体横截面积分布更光滑，显著降低跨音速波阻力。',
    rings: [{ label: '燃油效率提升', value: '3%', image: '/c919-assets/parts-ui/tail-ring-efficiency-crop.png' }],
    chart: {
      type: 'bars',
      variant: 'horizontal-compare',
      legend: [
        { name: 'B737-800', color: purple },
        { name: 'A320', color: coral },
        { name: 'C919', color: cyan }
      ],
      rows: [
        {
          label: '后机身最小直径',
          values: [
            { name: 'B737-800', value: 29, color: purple },
            { name: 'A320', value: 31, color: coral },
            { name: 'C919', value: 28, color: cyan }
          ]
        },
        {
          label: '后机身最大直径',
          values: [
            { name: 'B737-800', value: 33, color: purple },
            { name: 'A320', value: 35, color: coral },
            { name: 'C919', value: 34, color: cyan }
          ]
        },
        {
          label: '整机高度',
          values: [
            { name: 'B737-800', value: 86, color: purple },
            { name: 'A320', value: 80, color: coral },
            { name: 'C919', value: 82, color: cyan }
          ]
        }
      ]
    },
    metrics: [
      { label: '总阻力削减\n(较传统)', value: 46, display: '20%' },
      { label: '结构减重\n(较全铝合金)', value: 50, display: '20%' },
      { label: '燃油效率提升\n(机尾贡献)', value: 18, display: '3%' }
    ]
  }
]

const route = useRoute()
const router = useRouter()
const ids = new Set(parts.map((part) => part.id))
const activePart = ref(ids.has(route.query.part) ? route.query.part : 'nose')
const active = computed(() => parts.find((part) => part.id === activePart.value) ?? parts[0])
const showCoach = ref(false)

function toggleCoach() {
  showCoach.value = !showCoach.value
}

function selectPart(id) {
  activePart.value = id
  router.replace({ path: '/parts', query: { part: id } })
}

function goModel() {
  router.push({ path: '/parts-3d', query: { part: activePart.value } })
}

watch(
  () => route.query.part,
  (part) => {
    if (ids.has(part)) activePart.value = part
  }
)
</script>

<style scoped>
.parts-page {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  color: #fff;
  font-family: var(--font-main);
  background:
    radial-gradient(circle at 50% 42%, rgba(23, 164, 186, 0.16), transparent 28%),
    var(--color-bg);
  isolation: isolate;
}

.left-stack {
  position: absolute;
  left: 11.55%;
  top: 22.7%;
  z-index: 4;
  display: grid;
  gap: 2.35cqh;
  width: 13.7%;
}

.part-panel {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}

.glass-card {
  overflow: hidden;
  border: 1px solid rgba(91, 221, 238, 0.72);
  border-radius: 8px;
  background: rgba(69, 69, 69, 0.86);
  box-shadow: 0 0 18px rgba(53, 213, 238, 0.7), inset 0 0 22px rgba(255, 255, 255, 0.04);
}

.spec-card {
  height: 17.25cqh;
  padding: 1.45cqh 1.25cqw;
}

.spec-card dl {
  display: grid;
}

.spec-card div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  min-height: 3.6cqh;
  border-bottom: 1px solid rgba(255, 255, 255, 0.18);
}

.spec-card div:last-child {
  border-bottom: 0;
}

.spec-card dt {
  font-size: clamp(12px, 1.02cqw, 18px);
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}

.spec-card dd {
  font-size: clamp(12px, 1cqw, 18px);
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}

.feature-card {
  display: grid;
  gap: 9px;
  height: 30.5cqh;
  padding: 1.05cqh 1.05cqw 1.1cqh;
}

.mini-image {
  display: grid;
  place-items: center;
  height: 14.6cqh;
  padding: 0.45cqh 0.9cqw;
  box-sizing: border-box;
  overflow: hidden;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.03);
}

.mini-image img {
  display: block;
  width: auto;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.feature-card p {
  font-size: clamp(10px, 0.74cqw, 12px);
  font-weight: 500;
  line-height: 1.42;
  text-align: left;
}

.main-figure {
  position: absolute;
  left: 48.95%;
  top: 17.6%;
  z-index: 2;
  width: 45.4%;
  height: 43.3%;
  transform: translateX(-50%);
}

.figure-button {
  position: absolute;
  inset: 0;
  overflow: hidden;
  border: 1px solid transparent;
  border-radius: 10px;
}

.figure-button:hover,
.figure-button:focus-visible {
  border-color: rgba(53, 213, 238, 0.48);
  box-shadow: inset 0 0 0 1px rgba(53, 213, 238, 0.18), 0 0 24px rgba(53, 213, 238, 0.18);
}

.figure-button:focus-visible {
  outline: none;
}

.figure-button img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  pointer-events: none;
}

.figure-engine {
  left: 49.4%;
  top: 17.1%;
  width: 46.9%;
  height: 45.2%;
}

.figure-glass {
  left: 49.4%;
  top: 19.2%;
  width: 48.1%;
  height: 38.5%;
}

.figure-wing {
  left: 50.9%;
  top: 21.4%;
  width: 45.1%;
  height: 36.3%;
}

.figure-tail {
  left: 50.9%;
  top: 14.4%;
  width: 45.1%;
  height: 43.3%;
}

.right-stack {
  position: absolute;
  right: 8.1%;
  top: 12.9%;
  z-index: 4;
  display: grid;
  gap: 2.55cqh;
  justify-items: center;
  width: 12.6%;
}

.teacher-card {
  display: block;
  width: min(108px, 8cqw);
  padding: 0;
  border-radius: 8px;
}

.teacher-card img {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}

.teacher-card:hover,
.teacher-card:focus-visible {
  filter: drop-shadow(0 0 14px rgba(53, 213, 238, 0.34));
}

.assistant-info-card {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}

.ring-card {
  width: min(166px, 12.2cqw);
  line-height: 0;
}

.ring-card img {
  display: block;
  width: 100%;
  height: auto;
}

.part-wing .right-stack {
  top: 12.4%;
  gap: 1.1cqh;
}

.part-wing .ring-card {
  width: min(154px, 11.3cqw);
}

.part-glass .right-stack {
  top: 12%;
  right: 5.2%;
  gap: 1.2cqh;
  width: min(216px, 15.8cqw);
}

.part-title {
  position: absolute;
  left: 50%;
  top: 64.2%;
  z-index: 3;
  width: auto;
  line-height: 0;
  transform: translateX(-50%);
}

.part-title img {
  display: block;
  width: auto;
  height: 4.4cqh;
  max-width: 46cqw;
}

.bottom-board {
  position: absolute;
  left: 48.3%;
  top: 69.7%;
  z-index: 3;
  display: block;
  width: 53.2%;
  line-height: 0;
  transform: translateX(-50%);
}

.bottom-board-image {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}

.chart-panel,
.metric-panel {
  min-width: 0;
}

.bar-chart {
  display: grid;
  gap: 10px;
}

.bars {
  display: grid;
  gap: 10px;
}

.bar-row {
  display: grid;
  grid-template-columns: 86px repeat(3, minmax(0, 1fr));
  gap: 8px;
  align-items: center;
}

.horizontal-compare .bar-row {
  grid-template-columns: 78px 1fr 1fr;
}

.bar-row span {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.25;
}

.bar-row i {
  display: block;
  height: 14px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--bar-color), color-mix(in srgb, var(--bar-color), white 34%));
}

.legend,
.radar-labels {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  list-style: none;
}

.legend b,
.radar-labels b {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.radar-panel {
  display: grid;
  grid-template-columns: minmax(110px, 1fr) auto;
  gap: 12px;
  align-items: center;
}

.radar {
  position: relative;
  width: 150px;
  aspect-ratio: 1;
  margin: auto;
}

.radar span,
.radar i,
.radar b {
  position: absolute;
  inset: 0;
}

.radar span {
  border: 1px solid rgba(255, 255, 255, 0.32);
  clip-path: polygon(50% 0, 86% 15%, 100% 50%, 86% 85%, 50% 100%, 14% 85%, 0 50%, 14% 15%);
  transform: scale(calc(var(--scale, 1)));
}

.radar span:nth-child(1) { --scale: .18; }
.radar span:nth-child(2) { --scale: .34; }
.radar span:nth-child(3) { --scale: .5; }
.radar span:nth-child(4) { --scale: .66; }
.radar span:nth-child(5) { --scale: .82; }
.radar span:nth-child(6) { --scale: 1; }

.radar i {
  left: 50%;
  width: 1px;
  height: 50%;
  background: rgba(255, 255, 255, 0.35);
  transform-origin: bottom;
  transform: translateX(-50%) rotate(calc(var(--i) * 45deg));
}

.radar b {
  background: var(--poly);
  clip-path: polygon(var(--points));
}

.radar-labels {
  display: grid;
  justify-content: start;
  gap: 6px;
  font-size: 11px;
}

.radar-labels li {
  display: flex;
  gap: 6px;
  align-items: center;
  white-space: nowrap;
}

.board-return {
  position: absolute;
  left: 50%;
  top: 51%;
  width: 8%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: rgba(165, 165, 165, 0.86);
  transform: translate(-50%, -50%);
}

.board-return::before,
.board-return::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 34px;
  height: 3px;
  border-radius: 999px;
  background: #fff;
}

.board-return::before {
  transform: translate(-50%, -50%) rotate(45deg);
}

.board-return::after {
  transform: translate(-50%, -50%) rotate(-45deg);
}

.metric-panel {
  display: grid;
  gap: 8px;
}

.metric-row {
  display: grid;
  grid-template-columns: minmax(116px, 1fr) minmax(0, .8fr) 52px;
  gap: 9px;
  align-items: center;
}

.metric-row span {
  white-space: pre-line;
  font-size: 13px;
  font-weight: 800;
  line-height: 1.25;
}

.metric-row i {
  height: 12px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(210, 210, 210, 0.72);
}

.metric-row i b {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #35d5ee;
}

.metric-row strong {
  font-size: 17px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
}

.model-3d-button {
  position: absolute;
  right: 9.3%;
  bottom: 3.4%;
  z-index: 6;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  min-width: 136px;
  min-height: 48px;
  padding: 10px 18px;
  border: 1px solid rgba(210, 252, 255, 0.82);
  border-radius: 8px;
  color: #061014;
  background: linear-gradient(180deg, #8df5ff, #36dceb);
  box-shadow: 0 0 24px rgba(56, 232, 255, 0.34), inset 0 1px 0 rgba(255, 255, 255, 0.62);
  font-size: 14px;
  font-weight: 800;
}

.model-3d-button i {
  width: 18px;
  height: 18px;
  border: 2px solid currentColor;
  border-radius: 4px;
  transform: rotate(45deg);
}

.part-switcher {
  position: absolute;
  left: 50%;
  bottom: 0.7%;
  z-index: 7;
  display: flex;
  gap: 8px;
  padding: 6px;
  overflow: hidden;
  border: 1px solid rgba(53, 213, 238, 0.28);
  border-radius: 8px;
  background: rgba(9, 14, 20, 0.76);
  box-shadow: 0 0 16px rgba(53, 213, 238, 0.18);
  transform: translateX(-50%);
}

.part-switcher button {
  min-width: clamp(58px, 6.45cqw, 88px);
  min-height: clamp(26px, 4.4cqh, 34px);
  padding: clamp(5px, 0.9cqh, 7px) clamp(10px, 1.32cqw, 18px);
  border-radius: 7px;
  color: rgba(255, 255, 255, 0.78);
  font-size: clamp(11px, 1.1cqw, 15px);
  font-weight: 800;
  line-height: 1;
}

.part-switcher button:hover {
  background: rgba(255, 255, 255, 0.1);
}

.part-switcher button.active {
  color: #061014;
  background: #35d5ee;
}

@container (max-height: 620px) {
  .left-stack {
    gap: 1.7cqh;
  }

  .part-title {
    top: 63.8%;
  }

  .part-title img {
    height: 4.2cqh;
  }

  .bottom-board {
    min-height: 0;
    padding: 0;
  }
}

.coach-enter-active,
.coach-leave-active {
  transition:
    opacity 0.28s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)),
    transform 0.28s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.coach-enter-from,
.coach-leave-to {
  opacity: 0;
  transform: translateY(-12px) scale(0.98);
}
</style>
