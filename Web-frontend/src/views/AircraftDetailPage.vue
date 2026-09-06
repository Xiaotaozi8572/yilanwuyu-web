<template>
  <main class="aircraft-detail-page">
    <button class="back-btn" type="button" aria-label="返回机型选择" @click="router.push('/select')"></button>

    <section class="detail-layout">
      <figure class="aircraft-figure">
        <img :src="assetPath(currentAircraft.image)" :alt="currentAircraft.name" />
      </figure>

      <article class="detail-panel glass-panel">
        <p class="eyebrow">机型展示</p>
        <h1>{{ currentAircraft.name }}</h1>
        <p class="summary">{{ currentAircraft.summary }}</p>

        <dl class="spec-grid">
          <div v-for="item in currentAircraft.specs" :key="item.label">
            <dt>{{ item.label }}</dt>
            <dd>{{ item.value }}</dd>
          </div>
        </dl>
      </article>
    </section>
  </main>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assetPath } from '../utils/assets'

const route = useRoute()
const router = useRouter()

const aircraftMap = {
  c919: {
    name: 'C919',
    image: '机型选择页/c919机型选择卡片.png',
    summary: '中国商飞自主研制的单通道干线客机，是我国首款按照国际适航标准研制的国产大飞机。',
    specs: [
      { label: '载客量', value: '158~192 座' },
      { label: '航程', value: '5555 公里' }
    ]
  },
  y20: {
    name: 'Y-20',
    image: '机型选择页/Y20机型选择卡片.png',
    summary: '中国自主研发的首款大型运输机，承担战略投送和大型装备运输任务。',
    specs: [
      { label: '航程', value: '7800 公里' },
      { label: '载重', value: '66 吨' }
    ]
  },
  j20: {
    name: 'J-20',
    image: '机型选择页/J20机型选择卡片.png',
    summary: '中国自主研制的隐形第五代制空战斗机，具备高机动与隐身突防能力。',
    specs: [
      { label: '航程', value: '5500 公里' },
      { label: '最大起飞重量', value: '37 吨' }
    ]
  },
  z20: {
    name: 'Z-20',
    image: '机型选择页/Z20机型选择卡片.png',
    summary: '中国自主研制的第四代战术通用直升机，适用于多任务通用平台需求。',
    specs: [
      { label: '航程', value: '1000 公里' },
      { label: '最大起飞重量', value: '10 吨' }
    ]
  }
}

const currentAircraft = computed(() => aircraftMap[route.params.id] || aircraftMap.c919 || aircraftMap.y20)
</script>

<style scoped>
.aircraft-detail-page {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  color: #fff;
  background:
    radial-gradient(circle at 35% 42%, rgba(56, 232, 255, 0.06), transparent 34%),
    radial-gradient(circle at 90% 10%, rgba(255, 255, 255, 0.04), transparent 30%),
    var(--color-bg, #0a0c10);
}

.aircraft-detail-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.025), transparent 42%),
    repeating-linear-gradient(90deg, transparent 0 79px, rgba(255, 255, 255, 0.018) 80px),
    repeating-linear-gradient(0deg, transparent 0 79px, rgba(255, 255, 255, 0.014) 80px);
  mask-image: linear-gradient(180deg, transparent 2%, #000 22%, #000 82%, transparent 100%);
  pointer-events: none;
}

.detail-layout {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: minmax(300px, 0.78fr) minmax(440px, 1.22fr);
  gap: 56px;
  align-items: center;
  width: min(calc(100% - 160px), 1120px);
  height: 100%;
  margin: 0 auto;
}

.detail-layout::before {
  content: '';
  position: absolute;
  left: 2%;
  right: 2%;
  top: 66%;
  z-index: -1;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(110, 241, 255, 0.22), transparent);
  box-shadow: 0 0 22px rgba(56, 232, 255, 0.12);
}

.aircraft-figure {
  position: relative;
  display: grid;
  place-items: center;
  width: min(100%, 460px);
  aspect-ratio: 2;
  min-width: 0;
  overflow: hidden;
  isolation: isolate;
}

.aircraft-figure::before {
  content: '';
  position: absolute;
  inset: 12% 2% 4%;
  z-index: -1;
  border-radius: 50%;
  background: radial-gradient(ellipse at 50% 42%, rgba(86, 232, 250, 0.13), rgba(56, 232, 255, 0.04) 45%, transparent 72%);
  filter: blur(12px);
}

.aircraft-figure::after {
  content: '';
  position: absolute;
  left: 14%;
  right: 14%;
  bottom: 7%;
  z-index: -1;
  height: 18px;
  border: 1px solid rgba(110, 241, 255, 0.24);
  border-radius: 50%;
  box-shadow: 0 0 28px rgba(56, 232, 255, 0.14);
  transform: perspective(260px) rotateX(66deg);
}

.aircraft-figure img {
  position: absolute;
  left: 0;
  top: -2%;
  width: 100%;
  max-height: none;
  object-fit: initial;
  filter:
    saturate(1.08)
    contrast(1.1)
    brightness(1.04)
    drop-shadow(0 24px 32px rgba(0, 0, 0, 0.34));
  mask-image: radial-gradient(ellipse 72% 30% at 50% 18%, #000 0 45%, rgba(0, 0, 0, 0.88) 55%, transparent 72%);
  -webkit-mask-image: radial-gradient(ellipse 72% 30% at 50% 18%, #000 0 45%, rgba(0, 0, 0, 0.88) 55%, transparent 72%);
  transform: scale(1.14);
  transform-origin: 50% 18%;
  transition:
    filter 0.35s ease,
    transform 0.35s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.aircraft-figure:hover img {
  filter:
    saturate(1.13)
    contrast(1.12)
    brightness(1.08)
    drop-shadow(0 30px 38px rgba(0, 0, 0, 0.42));
  transform: translateY(-3px) scale(1.18);
}

.detail-panel {
  position: relative;
  padding: 34px 38px 36px;
  overflow: hidden;
  border-radius: 10px;
  background: rgba(17, 23, 31, 0.78);
  border: 1px solid var(--glass-border, rgba(56, 232, 255, 0.28));
  box-shadow:
    0 24px 58px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(16px) saturate(140%);
  -webkit-backdrop-filter: blur(16px) saturate(140%);
}

.detail-panel::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: linear-gradient(180deg, transparent 8%, var(--accent) 34%, var(--accent) 66%, transparent 92%);
  box-shadow: 0 0 16px var(--accent-glow);
}

.eyebrow {
  margin-bottom: 8px;
  color: var(--accent, #38e8ff);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-semibold, 600);
  line-height: 1.5;
  letter-spacing: 0;
}

.detail-panel h1 {
  margin-bottom: 11px;
  padding-bottom: 5px;
  font-size: 50px;
  font-weight: var(--weight-heavy, 800);
  line-height: 1.12;
  text-shadow: 0 2px 16px rgba(0, 0, 0, 0.35);
}

.summary {
  max-width: 560px;
  color: rgba(255, 255, 255, 0.76);
  font-size: 16px;
  line-height: 1.7;
}

.spec-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 28px;
}

.spec-grid div {
  min-height: 92px;
  padding: 16px 18px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.055);
  border: 1px solid rgba(255, 255, 255, 0.11);
  transition:
    background 0.25s ease,
    border-color 0.25s ease;
}

.spec-grid div:hover {
  background: rgba(255, 255, 255, 0.085);
  border-color: var(--accent-border, rgba(56, 232, 255, 0.55));
}

.spec-grid dt {
  color: var(--text-tertiary, rgba(255, 255, 255, 0.48));
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-medium, 500);
  line-height: 1.45;
}

.spec-grid dd {
  margin-top: 7px;
  color: #fff;
  font-size: 23px;
  font-weight: var(--weight-bold, 700);
  line-height: 1.3;
}

@media (max-width: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .aircraft-detail-page {
    overflow: auto;
  }

  .detail-layout {
    grid-template-columns: 1fr;
    width: min(86%, 640px);
    gap: 28px;
    height: auto;
    min-height: 100%;
    padding: 120px 0 48px;
  }

  .aircraft-figure img {
    width: 100%;
  }

  .aircraft-figure {
    width: min(76%, 420px);
    margin: 0 auto;
  }

  .detail-panel {
    padding: 28px;
  }

  .detail-panel h1 {
    font-size: 40px;
  }

  .summary {
    font-size: 15px;
  }
}
</style>
