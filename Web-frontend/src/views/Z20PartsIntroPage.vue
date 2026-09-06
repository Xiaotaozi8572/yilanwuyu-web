<template>
  <main class="parts-page" :class="`part-${activePart}`">
    <button class="back-btn" type="button" aria-label="返回主交互页" @click="$router.push('/z20/interaction')"></button>

    <aside class="left-stack">
      <img class="part-panel part-basic-panel" :src="active.basicCardImage" :alt="`${active.label}基本信息`" />
      <img class="part-panel part-feature-panel" :src="active.featureCardImage" :alt="`${active.label}图文介绍`" />
    </aside>

    <section class="main-figure" :class="`figure-${activePart}`">
      <button class="figure-button" type="button" :aria-label="`查看${active.label}3D展示`" @click="goModel">
        <img :src="active.mainImage" :alt="`${active.title}结构标注`" />
      </button>

      <img
        v-if="active.floorShadow"
        class="floor-shadow"
        :src="active.floorShadow"
        alt=""
        draggable="false"
      />

      <img
        v-for="line in active.lines"
        :key="line.image"
        class="indicator-fragment"
        :src="line.image"
        :style="{ left: line.left + '%', top: line.top + '%', width: line.width + '%' }"
        alt=""
        draggable="false"
      />

      <img
        v-for="chip in active.callouts"
        :key="chip.image"
        class="callout-chip"
        :src="chip.image"
        :style="{ left: chip.left + '%', top: chip.top + '%', width: chip.width + '%' }"
        :alt="chip.label"
        draggable="false"
      />
    </section>

    <aside class="right-stack">
      <button class="teacher-card" type="button" aria-label="AI语音老师" @click="toggleCoach">
        <img :src="active.teacherCardImage" alt="AI语音老师" />
      </button>

      <img
        v-if="active.rightCardImage"
        class="right-card-image"
        :src="active.rightCardImage"
        :alt="`${active.label}右侧卡片`"
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
      <button class="board-return" type="button" aria-label="返回主交互页" @click="$router.push('/z20/interaction')"></button>
    </section>

    <button class="model-3d-button" type="button" @click="goModel">
      <i aria-hidden="true"></i>
      <span>3D展示</span>
    </button>

    <transition name="coach">
      <CoachChatPanel
        v-if="showCoach"
        scene="parts-intro"
        aircraft="Z-20"
        @close="showCoach = false"
      />
    </transition>

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
  </main>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { z20AssetPath } from '../utils/z20Assets'
import CoachChatPanel from '../components/CoachChatPanel.vue'

const route = useRoute()
const router = useRouter()
const showCoach = ref(false)

const p = (part, file) => z20AssetPath(`直20UI分层/部件介绍页/${part}/${file}`)

const parts = [
  {
    id: 'engine',
    label: '发动机',
    title: 'WZ-10 涡轴发动机',
    titleImage: p('发动机', '__ WZ - 10涡轴发动机 __.png'),
    mainImage: p('发动机', '发动机.png'),
    basicCardImage: p('发动机', '玻璃信息介绍卡片.png'),
    featureCardImage: p('发动机', '玻璃图文卡片.png'),
    bottomCardImage: p('发动机', '玻璃下卡片.png'),
    rightCardImage: p('发动机', '右侧卡片.png'),
    teacherCardImage: p('发动机', '机器人.png'),
    floorShadow: null,
    lines: [],
    callouts: [],
    rings: []
  },
  {
    id: 'glass',
    label: '风挡',
    title: '两块式大风挡',
    titleImage: p('两块玻璃', '__ 两块式大风挡 __.png'),
    mainImage: p('两块玻璃', '部件指示线12.png'),
    basicCardImage: p('两块玻璃', '玻璃信息介绍卡片.png'),
    featureCardImage: p('两块玻璃', '玻璃图文卡片.png'),
    bottomCardImage: p('两块玻璃', '玻璃下卡片.png'),
    rightCardImage: p('两块玻璃', '右侧卡片.png'),
    teacherCardImage: p('两块玻璃', '机器人.png'),
    floorShadow: null,
    lines: [
      { image: p('两块玻璃', 'Vector 73.png'), left: 40.0, top: -1.7, width: 0.28 },
      { image: p('两块玻璃', '部件指示线13.png'), left: 24.0, top: 22.0, width: 9.7 },
      { image: p('两块玻璃', '部件指示线14.png'), left: 70.0, top: 67.0, width: 6.7 }
    ],
    callouts: [
      { image: p('两块玻璃', '竖向分隔框梁.png'), label: '竖向分隔框梁', left: 32.4, top: -9.4, width: 15.0 },
      { image: p('两块玻璃', '弧形一体化外轮廓曲面.png'), label: '弧形一体化外轮廓曲面', left: 77.3, top: 66.8, width: 25.0 },
      { image: p('两块玻璃', '多层复合夹胶玻璃.png'), label: '多层复合夹胶玻璃', left: 4.3, top: 17.0, width: 19.8 }
    ],
    rings: []
  },
  {
    id: 'tail',
    label: '尾桨',
    title: '剪刀式尾桨',
    titleImage: p('剪刀尾桨', '__ 剪刀式尾桨 __.png'),
    mainImage: p('剪刀尾桨', '9c15bdd610e580e2b6d097f144a00814 1.png'),
    basicCardImage: p('剪刀尾桨', '玻璃信息介绍卡片.png'),
    featureCardImage: p('剪刀尾桨', '玻璃图文卡片.png'),
    bottomCardImage: p('剪刀尾桨', '玻璃下卡片.png'),
    rightCardImage: p('剪刀尾桨', '右侧卡片.png'),
    teacherCardImage: p('剪刀尾桨', '机器人.png'),
    floorShadow: p('剪刀尾桨', 'Ellipse 12.png'),
    lines: [
      // The short upper leader terminates on the right blade instead of the hub.
      { image: p('剪刀尾桨', '部件指示线18.png'), left: 30.5, top: -2.0, width: 14.2 },
      // The steep leader belongs to the hub callout; the shallow one belongs to the tail-beam base.
      { image: p('剪刀尾桨', '部件指示线19.png'), left: 23.3, top: 26.2, width: 4.3 },
      { image: p('剪刀尾桨', '部件指示线20.png'), left: 35.5, top: 43.8, width: 12.5 }
    ],
    callouts: [
      { image: p('剪刀尾桨', '剪刀式不对称尾桨叶片.png'), label: '剪刀式不对称尾桨叶片', left: 42.6, top: -8.8, width: 26.4 },
      { image: p('剪刀尾桨', '倾斜尾梁安装基座.png'), label: '倾斜尾梁安装基座', left: 48.7, top: 32.2, width: 21.4 },
      { image: p('剪刀尾桨', '尾桨毂传动总成.png'), label: '尾桨毂传动总成', left: 4.5, top: 57.8, width: 18.7 }
    ],
    rings: []
  },
  {
    id: 'gear',
    label: '起落架',
    title: '起落架',
    titleImage: p('起落架', '__ 起落架 __.png'),
    mainImage: p('起落架', '起落架 1.png'),
    basicCardImage: p('起落架', '玻璃信息介绍卡片.png'),
    featureCardImage: p('起落架', '玻璃图文卡片.png'),
    bottomCardImage: p('起落架', '玻璃下卡片.png'),
    rightCardImage: p('起落架', '右侧卡片.png'),
    teacherCardImage: p('起落架', '机器人.png'),
    floorShadow: null,
    lines: [
      { image: p('起落架', '部件指示线15.png'), left: 35.0, top: 54.0, width: 9.2 },
      { image: p('起落架', '部件指示线16.png'), left: 37.1, top: 80.3, width: 18.5 },
      { image: p('起落架', '部件指示线17.png'), left: 82.0, top: 10.0, width: 10.0 }
    ],
    callouts: [
      { image: p('起落架', '前起落架转向收放组件.png'), label: '前起落架转向收放组件', left: 57.6, top: 4.5, width: 24.0 },
      { image: p('起落架', '主起落架缓冲支柱.png'), label: '主起落架缓冲支柱', left: 44.1, top: 51.4, width: 19.2 },
      { image: p('起落架', '大尺寸承压机轮.png'), label: '大尺寸承压机轮', left: 56.3, top: 79.0, width: 16.9 }
    ],
    rings: []
  },
  {
    id: 'rotor',
    label: '主旋翼',
    title: '五叶主旋翼',
    titleImage: p('五叶主旋翼', '__ 五叶主旋翼 __.png'),
    mainImage: p('五叶主旋翼', '5叶主旋翼 1.png'),
    basicCardImage: p('五叶主旋翼', '玻璃信息介绍卡片.png'),
    featureCardImage: p('五叶主旋翼', '玻璃图文卡片.png'),
    bottomCardImage: p('五叶主旋翼', '玻璃下卡片.png'),
    rightCardImage: p('五叶主旋翼', '右侧卡片.png'),
    teacherCardImage: p('五叶主旋翼', '机器人.png'),
    floorShadow: null,
    lines: [
      { image: p('五叶主旋翼', '部件指示线07.png'), left: 70.4, top: 85.0, width: 9.9 },
      { image: p('五叶主旋翼', '部件指示线08.png'), left: 58.8, top: 3.1, width: 0.29 },
      { image: p('五叶主旋翼', '部件指示线09.png'), left: 41.6, top: 19.4, width: 3.7 },
      { image: p('五叶主旋翼', '部件指示线10.png'), left: 96.8, top: 24.7, width: 0.29 },
      { image: p('五叶主旋翼', '部件指示线11.png'), left: 50.7, top: 42.0, width: 0.29 }
    ],
    callouts: [
      { image: p('五叶主旋翼', '碳纤维复合材料.png'), label: '碳纤维复合材料', left: 50.2, top: -6.0, width: 17.6 },
      { image: p('五叶主旋翼', '根部平滑过渡曲面.png'), label: '根部平滑过渡曲面', left: 31.7, top: 11.5, width: 19.8 },
      { image: p('五叶主旋翼', '后掠式桨尖降噪构型.png'), label: '后掠式桨尖降噪构型', left: 85.8, top: 18.4, width: 22.4 },
      { image: p('五叶主旋翼', '弹性桨毂减摆结构.png'), label: '弹性桨毂减摆结构', left: 35.3, top: 65.0, width: 19.8 },
      { image: p('五叶主旋翼', '后缘修型抑制气动噪声.png'), label: '后缘修型抑制气动噪声', left: 76.0, top: 83.3, width: 24.8 }
    ],
    rings: []
  }
]

const ids = new Set(parts.map((part) => part.id))
const activePart = ref(ids.has(route.query.part) ? route.query.part : 'engine')
const active = computed(() => parts.find((part) => part.id === activePart.value) ?? parts[0])

function selectPart(id) {
  activePart.value = id
  router.replace({ path: '/z20/parts', query: { part: id } })
}

function goModel() {
  router.push({ path: '/z20/parts-3d', query: { part: activePart.value } })
}

function toggleCoach() {
  showCoach.value = !showCoach.value
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
  border: 1px solid transparent;
  border-radius: 10px;
  overflow: hidden;
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

.indicator-fragment {
  position: absolute;
  z-index: 5;
  height: auto;
  pointer-events: none;
  opacity: 0.95;
}

.callout-chip {
  position: absolute;
  z-index: 5;
  height: auto;
  pointer-events: none;
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.45));
}

.figure-engine {
  left: 50%;
  top: 20%;
  width: 48%;
  height: 40%;
}

.figure-glass {
  left: 50.5%;
  top: 13%;
  width: 49%;
  height: 38.5%;
}

.figure-tail {
  left: 49.4%;
  top: 9.7%;
  width: 47%;
  height: 46%;
}

.figure-tail .floor-shadow {
  position: absolute;
  left: 50%;
  bottom: -8%;
  width: 90%;
  transform: translateX(-50%);
  pointer-events: none;
}

.figure-gear {
  left: 50.8%;
  top: 14.5%;
  width: 47%;
  height: 46%;
}

.figure-rotor {
  left: 50%;
  top: 16.7%;
  width: 49%;
  height: 40%;
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

.right-card-image {
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

.part-title {
  position: absolute;
  left: 50%;
  top: 62.3%;
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
  top: 67.4%;
  z-index: 3;
  display: block;
  width: 50%;
  line-height: 0;
  transform: translateX(-50%);
}

.bottom-board-image {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
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

.board-return:hover,
.board-return:focus-visible {
  background: rgba(53, 213, 238, 0.92);
}

.board-return:focus-visible {
  outline: none;
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

@container (max-height: 620px) {
  .left-stack {
    gap: 1.7cqh;
  }

  .part-title {
    top: 61.8%;
  }

  .part-title img {
    height: 4.2cqh;
  }

  .bottom-board {
    width: 45%;
    min-height: 0;
    padding: 0;
  }
}
</style>
