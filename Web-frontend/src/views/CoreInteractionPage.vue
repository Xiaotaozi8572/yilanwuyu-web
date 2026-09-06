<template>
  <SceneShell
    class="core-interaction-scene"
    image="3核心交互页/c919按钮展开.jpg"
    image-blend-mode="screen"
    alt="C919 核心交互页"
  >
    <template #background>
      <div class="core-hangar-background" aria-hidden="true"></div>
    </template>

    <button class="scene-hotspot back-hotspot" @click="$router.push('/select')">
      <span class="sr-only">返回机型选择</span>
    </button>

    <button
      v-for="part in partHotspots"
      :key="part.id"
      class="scene-hotspot part-hotspot"
      :style="part.style"
      @click="openPart(part.id)"
    >
      <span class="sr-only">查看{{ part.label }}</span>
    </button>

    <button
      v-for="(item, index) in menuHotspots"
      :key="item.route"
      :class="['scene-hotspot', 'menu-hotspot', `menu-hotspot-${index}`]"
      :style="item.style"
      @click="$router.push(item.route)"
    >
      <span class="sr-only">{{ item.label }}</span>
    </button>

    <button
      v-for="area in aiHotspots"
      :key="area.id"
      class="scene-hotspot ai-hotspot"
      :style="area.style"
      @click="toggleCoach"
    >
      <span class="sr-only">打开 AI 语音老师</span>
    </button>

    <section class="timeline-stage" :class="{ 'skip-intro': skipTimelineIntro }" aria-hidden="true">
      <span
        v-for="(position, index) in timelineMilestones"
        :key="`mask-${position}`"
        class="timeline-item-mask"
        :style="{ '--i': index }"
      />
      <span class="timeline-runner" />
      <span
        v-for="(position, index) in timelineMilestones"
        :key="`hit-${position}`"
        class="timeline-hit"
        :style="{ '--position': position, '--i': index }"
      />
    </section>

    <transition name="coach">
      <CoachChatPanel
        v-if="showCoach"
        scene="core-interaction"
        aircraft="C919"
        @close="showCoach = false"
      />
    </transition>
  </SceneShell>
</template>

<script setup>
import { ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import SceneShell from '../components/SceneShell.vue'
import CoachChatPanel from '../components/CoachChatPanel.vue'

const router = useRouter()
const showCoach = ref(false)
const skipTimelineIntro = ref(false)

onBeforeRouteLeave(() => {
  skipTimelineIntro.value = true
})

const partHotspots = [
  { id: 'glass', label: '四块式风挡玻璃', style: { '--x': 10.764, '--y': 32.799, '--w': 12.014, '--h': 4.274 } },
  { id: 'nose', label: '圆润流线型机头', style: { '--x': 8.264, '--y': 49.038, '--w': 12.014, '--h': 4.274 } },
  { id: 'engine', label: 'LEAP-1C 发动机', style: { '--x': 32.569, '--y': 56.517, '--w': 7.569, '--h': 4.274 } },
  { id: 'wing', label: '翼梢小翼', style: { '--x': 72.361, '--y': 44.551, '--w': 8.681, '--h': 4.274 } },
  { id: 'tail', label: '蜂腰状机尾', style: { '--x': 67.083, '--y': 31.09, '--w': 9.792, '--h': 4.274 } }
]

const menuHotspots = [
  { label: '爆炸拆解', route: '/explode', style: { '--x': 83.819, '--y': 43.697, '--w': 7.361, '--h': 11.592 } },
  { label: '模拟驾驶', route: '/cockpit', style: { '--x': 83.819, '--y': 55.288, '--w': 7.361, '--h': 11.592 } },
  { label: '虚拟课堂', route: '/classroom', style: { '--x': 83.819, '--y': 66.88, '--w': 7.361, '--h': 11.592 } },
  { label: '知识巩固', route: '/quiz', style: { '--x': 83.819, '--y': 78.472, '--w': 7.361, '--h': 11.592 } }
]

const aiHotspots = [
  { id: 'title', style: { '--x': 82.986, '--y': 11.966, '--w': 9.167, '--h': 2.885 } },
  { id: 'robot', style: { '--x': 84.236, '--y': 16.4, '--w': 5.868, '--h': 15.171 } },
  { id: 'question', style: { '--x': 84.097, '--y': 32.158, '--w': 7.014, '--h': 2.832 } }
]

const timelineMilestones = [9.165, 25.436, 41.707, 57.978, 74.248, 90.519]

function toggleCoach() {
  showCoach.value = !showCoach.value
}

function openPart(part) {
  router.push({ path: '/parts', query: { part } })
}
</script>

<style scoped>
.core-hangar-background {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, rgba(0, 4, 8, 0.48), rgba(2, 12, 17, 0.06) 46%, rgba(0, 4, 8, 0.42)),
    linear-gradient(180deg, rgba(0, 2, 5, 0.38), rgba(0, 6, 10, 0.04) 42%, rgba(0, 2, 5, 0.46)),
    radial-gradient(ellipse at 50% 48%, rgba(0, 0, 0, 0), rgba(0, 0, 0, 0.26) 76%),
    url('/c919-assets/backgrounds/core-wind-tunnel.png') center 50% / cover no-repeat;
  filter: saturate(1.04) brightness(0.82) contrast(1.04) blur(0.18px);
  transform: scale(1.026);
  animation: coreBackgroundDrift 13s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)) infinite alternate;
}

.core-hangar-background::before,
.core-hangar-background::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.core-hangar-background::before {
  background:
    linear-gradient(90deg, transparent 0 9%, rgba(99, 232, 255, 0.18) 12%, transparent 20%),
    linear-gradient(90deg, transparent 0 78%, rgba(109, 231, 255, 0.17) 83%, transparent 96%),
    radial-gradient(ellipse at 50% 43%, rgba(55, 232, 255, 0.14), transparent 34%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.075), transparent 25%, transparent 68%, rgba(56, 232, 255, 0.08));
  mix-blend-mode: screen;
}

.core-hangar-background::after {
  opacity: 0.28;
  background:
    repeating-linear-gradient(90deg, transparent 0 72px, rgba(255, 255, 255, 0.024) 73px 74px),
    repeating-linear-gradient(0deg, transparent 0 76px, rgba(255, 255, 255, 0.018) 77px 78px);
  mask-image: radial-gradient(circle at 50% 48%, #000 0 58%, transparent 86%);
}

:deep(.image-layer) {
  mix-blend-mode: screen;
}

:deep(.scene-image) {
  filter: contrast(1.12) brightness(1.04) saturate(1.06);
}

.back-hotspot {
  --x: 6.181;
  --y: 9.829;
  --w: 5.556;
  --h: 8.547;
  --hotspot-radius: 999px;
}

.menu-hotspot::before {
  --hotspot-radius: 0;
}

.menu-hotspot-0::before {
  --hotspot-radius: 16px 16px 0 0;
}

.menu-hotspot-3::before {
  --hotspot-radius: 0 0 16px 16px;
}

.part-hotspot {
  --hotspot-radius: 999px;
}

.ai-hotspot {
  --hotspot-radius: 999px;
}

.ai-hotspot::before {
  display: none;
}

.ai-hotspot:hover,
.ai-hotspot:focus-visible {
  transform: none;
}

.ai-hotspot:focus-visible {
  outline-color: rgba(56, 232, 255, 0.75);
}

.timeline-stage {
  position: absolute;
  left: 8.35%;
  top: 70.94%;
  z-index: 3;
  width: 70.85%;
  height: 19.18%;
  border-radius: 10px;
  overflow: hidden;
  pointer-events: none;
}

.timeline-item-mask {
  position: absolute;
  left: calc(0.7% + var(--i) * 16.3%);
  top: 2%;
  z-index: 2;
  width: 16.4%;
  height: 96%;
  background:
    linear-gradient(180deg, rgba(45, 49, 51, 0.98), rgba(24, 27, 29, 0.99) 54%, rgba(7, 10, 12, 0.995)),
    radial-gradient(circle at 50% 24%, rgba(255, 255, 255, 0.04), transparent 55%);
  opacity: 1;
  animation: timelineItemReveal 0.42s calc(0.33s + var(--i) * 1.18s)
    var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)) forwards;
}

.timeline-runner {
  position: absolute;
  left: 3.8%;
  right: 4%;
  top: 44.3%;
  z-index: 3;
  height: 2px;
}

.timeline-runner::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 0;
  background: linear-gradient(90deg, rgba(56, 232, 255, 0.15), rgba(104, 242, 255, 0.88), #ffffff);
  box-shadow: 0 0 8px rgba(56, 232, 255, 0.7);
  animation: timelineProgress 6.6s linear forwards;
}

.timeline-runner::after {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: clamp(6px, 0.45cqw, 9px);
  height: clamp(6px, 0.45cqw, 9px);
  border-top: 2px solid rgba(255, 255, 255, 0.98);
  border-right: 2px solid rgba(255, 255, 255, 0.98);
  filter:
    drop-shadow(0 0 4px rgba(56, 232, 255, 0.75))
    drop-shadow(0 0 5px rgba(255, 255, 255, 0.65));
  transform: translate(-50%, -50%) rotate(45deg);
  animation: timelineArrowTravel 6.6s linear forwards;
}

.timeline-hit {
  position: absolute;
  left: calc(var(--position) * 1%);
  top: 37%;
  z-index: 4;
  width: 11.2%;
  height: 52%;
  opacity: 0;
  transform: translateX(-50%);
  animation: timelineHit 0.72s calc(0.33s + var(--i) * 1.18s) ease-out;
}

.timeline-hit::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 14%;
  width: clamp(8px, 0.78cqw, 14px);
  aspect-ratio: 1;
  border: 2px solid rgba(255, 255, 255, 0.94);
  border-radius: 50%;
  box-shadow:
    0 0 0 6px rgba(56, 232, 255, 0.1),
    0 0 18px rgba(56, 232, 255, 0.82);
  transform: translate(-50%, -50%) scale(0.75);
}

.timeline-hit::after {
  content: '';
  position: absolute;
  left: 4%;
  right: 4%;
  top: 33%;
  bottom: 0;
  border-radius: clamp(7px, 0.65cqw, 12px);
  box-shadow:
    0 0 0 2px rgba(91, 235, 255, 0.78),
    0 0 22px rgba(56, 232, 255, 0.55),
    inset 0 0 15px rgba(56, 232, 255, 0.08);
}

.timeline-stage.skip-intro .timeline-item-mask {
  animation: none;
  opacity: 0;
  visibility: hidden;
}

.timeline-stage.skip-intro .timeline-runner::before,
.timeline-stage.skip-intro .timeline-runner::after {
  animation: none;
  opacity: 0;
}

.timeline-stage.skip-intro .timeline-hit {
  animation: none;
  opacity: 0;
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

@keyframes timelineProgress {
  0% {
    width: 0;
    opacity: 0.2;
  }

  7%,
  92% {
    opacity: 1;
  }

  100% {
    width: 100%;
    opacity: 0;
  }
}

@keyframes timelineArrowTravel {
  0% {
    left: 0;
    opacity: 0.25;
  }

  5%,
  94% {
    opacity: 1;
  }

  100% {
    left: 100%;
    opacity: 0;
  }
}

@keyframes timelineItemReveal {
  0% {
    opacity: 1;
  }

  100% {
    opacity: 0;
    visibility: hidden;
  }
}

@keyframes timelineHit {
  0% {
    opacity: 0;
    transform: translateX(-50%) scale(0.94);
  }

  32%,
  70% {
    opacity: 1;
    transform: translateX(-50%) scale(1);
  }

  100% {
    opacity: 0;
    transform: translateX(-50%) scale(1.03);
  }
}

@keyframes coreBackgroundDrift {
  0% {
    background-position:
      center,
      center,
      center,
      50% 50%;
  }

  100% {
    background-position:
      center,
      center,
      center,
      50.8% 49.4%;
  }
}
</style>
