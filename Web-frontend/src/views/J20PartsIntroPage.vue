<template>
  <main class="parts-page" :class="`part-${activePart}`">
    <button class="back-btn" type="button" aria-label="返回主交互页" @click="$router.push('/j20/interaction')"></button>

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
      <button class="board-return" type="button" aria-label="返回主交互页" @click="$router.push('/j20/interaction')"></button>
    </section>

    <button class="model-3d-button" type="button" @click="goModel">
      <i aria-hidden="true"></i>
      <span>3D展示</span>
    </button>

    <transition name="coach">
      <CoachChatPanel
        v-if="showCoach"
        scene="parts-intro"
        aircraft="J-20"
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
import { j20AssetPath } from '../utils/j20Assets'
import CoachChatPanel from '../components/CoachChatPanel.vue'

const route = useRoute()
const router = useRouter()
const showCoach = ref(false)

const p = (part, file) => j20AssetPath(`歼20UI分层/部件介绍页/${part}/${file}`)
const defaultRobot = j20AssetPath('歼20UI分层/爆炸拆分页/机器人.png')

const parts = [
  {
    id: 'canard',
    label: '鸭式气动布局',
    title: '鸭式气动布局',
    titleImage: p('鸭气式布局', '__ 鸭式气动布局 __.png'),
    mainImage: p('鸭气式布局', '标注合成主图.png'),
    basicCardImage: p('鸭气式布局', '信息介绍卡片.png'),
    featureCardImage: p('鸭气式布局', '图文介绍卡片.png'),
    bottomCardImage: p('鸭气式布局', '玻璃下卡片.png'),
    rightCardImage: p('鸭气式布局', '右侧卡片.png'),
    teacherCardImage: p('鸭气式布局', '机器人.png'),
    rings: []
  },
  {
    id: 'inlet',
    label: '蚌式进气道',
    title: '蚌式进气道',
    titleImage: p('蚌式进气道', '__ 蚌式进气道 __.png'),
    mainImage: p('蚌式进气道', '标注合成主图.png'),
    basicCardImage: p('蚌式进气道', '信息介绍卡片.png'),
    featureCardImage: p('蚌式进气道', '图文介绍卡片.png'),
    bottomCardImage: p('蚌式进气道', '玻璃下卡片.png'),
    rightCardImage: p('蚌式进气道', '右侧卡片.png'),
    teacherCardImage: defaultRobot,
    rings: []
  },
  {
    id: 'engine',
    label: '发动机',
    title: 'WS-15 涡扇发动机',
    titleImage: p('发动机', '__ WS-15 涡扇发动机 __.png'),
    mainImage: p('发动机', '歼20发动机 3.png'),
    basicCardImage: p('发动机', '信息介绍卡片.png'),
    featureCardImage: p('发动机', '图文介绍卡片 1.png'),
    bottomCardImage: p('发动机', '玻璃下卡片 1.png'),
    rightCardImage: p('发动机', '右侧卡片 1.png'),
    teacherCardImage: p('发动机', '机器人.png'),
    rings: []
  },
  {
    id: 'tail',
    label: '全动双垂尾',
    title: '全动双垂尾',
    titleImage: p('全动双垂尾', '__ 全动双垂尾 __.png'),
    mainImage: p('全动双垂尾', '标注合成主图.png'),
    basicCardImage: p('全动双垂尾', '信息介绍卡片.png'),
    featureCardImage: p('全动双垂尾', '图文介绍卡片.png'),
    bottomCardImage: p('全动双垂尾', '玻璃下卡片.png'),
    rightCardImage: p('全动双垂尾', '右侧卡片.png'),
    teacherCardImage: defaultRobot,
    rings: []
  }
]

const ids = new Set(parts.map((part) => part.id))
const activePart = ref(ids.has(route.query.part) ? route.query.part : 'canard')
const active = computed(() => parts.find((part) => part.id === activePart.value) ?? parts[0])

function selectPart(id) {
  activePart.value = id
  router.replace({ path: '/j20/parts', query: { part: id } })
}

function goModel() {
  router.push({ path: '/j20/parts-3d', query: { part: activePart.value } })
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

.figure-inlet {
  left: 50%;
  top: 18%;
  width: 48%;
  height: 42%;
}

.figure-engine {
  left: 50%;
  top: 20%;
  width: 48%;
  height: 40%;
}

.figure-tail {
  left: 50%;
  top: 15%;
  width: 47%;
  height: 46%;
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
