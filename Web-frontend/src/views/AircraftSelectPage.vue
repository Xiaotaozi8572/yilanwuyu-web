<template>
  <main class="aircraft-select-page">
    <Teleport to="body">
      <div class="select-viewport-bg" aria-hidden="true"></div>
    </Teleport>
    <div ref="frameRef" class="select-frame" :style="{ '--select-scale': canvasScale }">
      <div class="select-inner-bg" aria-hidden="true"></div>
      <button class="back-btn select-back-btn" type="button" aria-label="返回导入页" @click="$router.push('/')"></button>

      <div class="select-canvas">
        <header class="select-title">
          <span>中国航空代表机型</span>
          <h1>选择你感兴趣的机型</h1>
          <img :src="assetPath('机型选择页/点击选择你感兴趣的机型.png')" alt="点击选择你感兴趣的机型" />
        </header>

        <section
          class="carousel"
          aria-label="滚动鼠标滚轮切换机型"
          tabindex="0"
          @wheel.prevent="handleWheel"
          @keydown.down.prevent="next"
          @keydown.up.prevent="prev"
          @keydown.right.prevent="next"
          @keydown.left.prevent="prev"
        >
          <img class="arc top" :src="assetPath('机型选择页/机型选择上弧形线.png')" alt="" />

          <div class="card-stage">
            <RouterLink
              v-for="(aircraft, index) in aircraftList"
              :key="aircraft.id"
              class="aircraft-card"
              :class="{ current: index === activeIndex }"
              :style="getCardStyle(index)"
              :to="aircraft.route"
              :aria-label="`查看 ${aircraft.name}`"
            >
              <span class="aircraft-card-visual">
                <img :src="assetPath(aircraft.image)" :alt="aircraft.name" draggable="false" />
              </span>
            </RouterLink>
          </div>

          <img class="arc bottom" :src="assetPath('机型选择页/机型选择下弧形线.png')" alt="" />
        </section>

        <div class="slide-indicator" aria-hidden="true">
          <span v-for="(_, index) in aircraftList" :key="index" :class="{ active: index === activeIndex }"></span>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { assetPath } from '../utils/assets'

const DESIGN_WIDTH = 2048
const DESIGN_HEIGHT = 956

const activeIndex = ref(0)
const frameRef = ref(null)
const canvasScale = ref(1)
const wheelDistance = ref(0)
let wheelLocked = false
let wheelTimer = null
let resizeObserver = null

const aircraftList = [
  {
    id: 'c919',
    name: 'C919',
    image: '机型选择页/c919机型选择卡片.png',
    route: '/interaction'
  },
  {
    id: 'y20',
    name: 'Y-20',
    image: '机型选择页/Y20机型选择卡片.png',
    route: '/y20/interaction'
  },
  {
    id: 'j20',
    name: 'J-20',
    image: '机型选择页/J20机型选择卡片.png',
    route: '/j20/interaction'
  },
  {
    id: 'z20',
    name: 'Z-20',
    image: '机型选择页/Z20机型选择卡片.png',
    route: '/z20/interaction'
  }
]

function getSlot(index) {
  return (index - activeIndex.value + aircraftList.length) % aircraftList.length
}

function getCardStyle(index) {
  const slot = getSlot(index)
  const rotations = [4, 1.5, -1.5, -4]
  const curveShapes = [
    'polygon(0 1%, 100% 7%, 100% 93%, 0 99%)',
    'polygon(0 7%, 100% 9%, 100% 91%, 0 93%)',
    'polygon(0 9%, 100% 7%, 100% 93%, 0 91%)',
    'polygon(0 7%, 100% 1%, 100% 99%, 0 93%)'
  ]
  const contentInsets = [1.4, 5.2, 5.2, 1.4]

  return {
    '--slot': slot,
    '--card-rotation': `${rotations[slot]}deg`,
    '--card-clip': curveShapes[slot],
    '--content-inset': `${contentInsets[slot]}%`,
    zIndex: slot === 1 || slot === 2 ? 3 : 2
  }
}

function handleWheel(event) {
  if (wheelLocked) return

  wheelDistance.value += event.deltaY || event.deltaX

  if (Math.abs(wheelDistance.value) < 38) return

  if (wheelDistance.value > 0) {
    next()
  } else {
    prev()
  }

  wheelDistance.value = 0
  wheelLocked = true
  clearTimeout(wheelTimer)
  wheelTimer = setTimeout(() => {
    wheelLocked = false
  }, 520)
}

function prev() {
  activeIndex.value = (activeIndex.value + aircraftList.length - 1) % aircraftList.length
}

function next() {
  activeIndex.value = (activeIndex.value + 1) % aircraftList.length
}

function updateCanvasScale() {
  if (!frameRef.value) return
  const frameWidth = frameRef.value.clientWidth
  const frameHeight = frameRef.value.clientHeight
  canvasScale.value = Math.min(frameWidth / DESIGN_WIDTH, frameHeight / DESIGN_HEIGHT)
}

onMounted(() => {
  updateCanvasScale()
  resizeObserver = new ResizeObserver(updateCanvasScale)
  if (frameRef.value) resizeObserver.observe(frameRef.value)
})

onUnmounted(() => {
  clearTimeout(wheelTimer)
  resizeObserver?.disconnect()
})
</script>

<style scoped>
.aircraft-select-page {
  position: relative;
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: #fff;
  background: transparent;
}

.select-inner-bg {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 0;
  width: 100%;
  height: 100%;
  transform: translate(-50%, -50%);
  pointer-events: none;
  background-color: var(--color-bg);
  background-image: url('/c919-assets/backgrounds/aircraft-selection-gallery.jpg');
  background-position: center;
  background-size: cover;
  background-repeat: no-repeat;
}

.select-inner-bg::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 50% 54%, rgba(5, 9, 12, 0.58), rgba(5, 9, 12, 0.7) 58%, rgba(5, 9, 12, 0.42)),
    linear-gradient(180deg, rgba(4, 8, 11, 0.24), transparent 30%, rgba(4, 8, 11, 0.38));
  pointer-events: none;
}

.select-viewport-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-color: var(--color-bg);
  background-image: url('/c919-assets/backgrounds/aircraft-selection-gallery.jpg');
  background-position: center;
  background-size: cover;
  background-repeat: no-repeat;
}

.select-viewport-bg::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 50% 54%, rgba(5, 9, 12, 0.58), rgba(5, 9, 12, 0.7) 58%, rgba(5, 9, 12, 0.42)),
    linear-gradient(180deg, rgba(4, 8, 11, 0.24), transparent 30%, rgba(4, 8, 11, 0.38));
  pointer-events: none;
}

.select-frame {
  position: relative;
  width: 100%;
  height: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  isolation: isolate;
  border-radius: 0;
  box-shadow: var(--shadow-xl, 0 24px 64px rgba(0, 0, 0, 0.42));
}

.select-canvas {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 2048px;
  height: 956px;
  transform: translate(-50%, -50%) scale(var(--select-scale, 1));
  transform-origin: center;
}

.select-back-btn {
  top: var(--back-control-top);
  left: var(--back-control-left);
  width: var(--back-control-width);
  height: var(--back-control-height);
  min-width: 0;
  padding: 0;
}

.select-back-btn::before {
  width: 31%;
}

.select-title {
  position: absolute;
  top: 54px;
  left: 50%;
  z-index: 4;
  width: 720px;
  text-align: center;
  transform: translateX(-50%);
}

.select-title span {
  display: block;
  margin-bottom: 9px;
  color: #5cf0ff;
  font-size: 24px;
  font-weight: var(--weight-bold, 700);
  line-height: 1.35;
  text-shadow:
    0 2px 8px rgba(0, 0, 0, 0.82),
    0 0 16px rgba(56, 232, 255, 0.28);
}

.select-title h1 {
  color: #ffffff;
  font-size: 48px;
  font-weight: var(--weight-heavy, 800);
  line-height: 1.16;
  text-shadow:
    0 4px 18px rgba(0, 0, 0, 0.42),
    0 0 20px rgba(56, 232, 255, 0.1);
}

.select-title::after {
  content: '';
  display: block;
  width: 88px;
  height: 2px;
  margin: 14px auto 0;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
  box-shadow: 0 0 12px var(--accent-glow);
}

.select-title img {
  display: none;
  width: 610px;
  user-select: none;
}

.carousel {
  position: absolute;
  top: 180px;
  right: 61px;
  bottom: 57px;
  left: 61px;
  z-index: 2;
  outline: none;
  perspective: 1800px;
}

.arc {
  position: absolute;
  left: 0;
  z-index: 5;
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
  user-select: none;
}

.arc.top {
  top: 0;
}

.arc.bottom {
  bottom: 0;
}

.card-stage {
  position: absolute;
  inset: 0;
}

.aircraft-card {
  position: absolute;
  top: 0;
  bottom: 0;
  left: calc(var(--slot) * 25% + 0.55%);
  z-index: 2;
  display: block;
  width: 23.9%;
  overflow: visible;
  border-radius: 0;
  transition:
    left 0.68s cubic-bezier(0.22, 0.75, 0.18, 1);
  will-change: left;
}

.aircraft-card-visual {
  position: absolute;
  inset: 0;
  display: block;
  overflow: hidden;
  border-radius: clamp(7px, 1.1cqw, 16px);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.14), rgba(160, 160, 170, 0.86));
  box-shadow:
    0 18px 28px rgba(0, 0, 0, 0.24),
    0 0 0 1px rgba(255, 255, 255, 0.12);
  clip-path: var(--card-clip);
  transform: rotateY(var(--card-rotation));
  transform-origin: center;
  pointer-events: none;
  transition:
    clip-path 0.68s cubic-bezier(0.22, 0.75, 0.18, 1),
    transform 0.68s cubic-bezier(0.22, 0.75, 0.18, 1),
    box-shadow 0.22s ease,
    filter 0.22s ease,
    background 0.22s ease;
  will-change: transform;
}

.aircraft-card-visual img {
  display: block;
  width: 100%;
  height: calc(100% - var(--content-inset) - var(--content-inset));
  margin-top: var(--content-inset);
  object-fit: fill;
  user-select: none;
  pointer-events: none;
}

.aircraft-card:hover,
.aircraft-card:focus-visible {
  z-index: 6 !important;
}

.aircraft-card:hover .aircraft-card-visual,
.aircraft-card:focus-visible .aircraft-card-visual {
  box-shadow:
    0 22px 36px rgba(0, 0, 0, 0.38),
    inset 0 0 0 2px var(--accent, #38e8ff);
  filter: brightness(1.1);
}

.aircraft-card:focus-visible {
  outline: none;
}

.aircraft-card.current .aircraft-card-visual {
  filter: brightness(1.05);
}

.slide-indicator {
  position: absolute;
  bottom: 21px;
  left: 50%;
  z-index: 4;
  display: flex;
  gap: var(--space-2, 8px);
  transform: translateX(-50%);
  pointer-events: none;
}

.slide-indicator span {
  width: 7px;
  height: 7px;
  border-radius: var(--radius-pill, 999px);
  background: rgba(255, 255, 255, 0.25);
  box-shadow: inset 0 0 4px rgba(0, 0, 0, 0.2);
  transition:
    width 0.3s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)),
    background 0.3s ease,
    box-shadow 0.3s ease;
}

.slide-indicator span.active {
  width: 28px;
  background: var(--accent, #38e8ff);
  box-shadow: 0 0 10px var(--accent-glow, rgba(56, 232, 255, 0.28));
}

@media (max-width: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .select-frame {
    border-radius: 0;
  }
}
</style>
