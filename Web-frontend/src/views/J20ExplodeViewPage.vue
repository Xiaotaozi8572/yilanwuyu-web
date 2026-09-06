<template>
  <main class="explode-page">
    <div class="environment-inner" aria-hidden="true">
      <img :src="assetPath('backgrounds/explode-engineering-lab.webp')" alt="" draggable="false" />
      <span></span>
    </div>
    <Teleport to="body">
      <div class="environment" aria-hidden="true">
        <img :src="assetPath('backgrounds/explode-engineering-lab.webp')" alt="" draggable="false" />
        <span></span>
      </div>
    </Teleport>

    <J20ExplodeModelViewer
      :exploded-model-url="j20AssetPath('models/歼20爆炸图.glb')"
      :assembled-model-url="j20AssetPath('models/歼20本体.glb')"
      :exploded="isExploded"
      @ready="modelReady = true"
    />

    <button class="back-btn" type="button" aria-label="返回主交互页面" @click="$router.push('/j20/interaction')"></button>

    <header class="page-title">
      <span>INTERACTIVE 3D</span>
      <h1>歼20 爆炸拆解</h1>
      <p>拖动模型观察机体结构，切换拆分状态查看部件关系</p>
    </header>

    <aside class="status-panel" aria-live="polite">
      <span>当前状态</span>
      <strong>{{ isExploded ? '部件拆分' : '整机合并' }}</strong>
      <p>{{ isExploded ? '各结构件已沿机体中心向外展开' : '结构件已恢复至整机装配位置' }}</p>
    </aside>

    <div class="model-floor" aria-hidden="true">
      <i></i>
      <i></i>
      <i></i>
    </div>

    <div class="explode-controls" role="group" aria-label="拆解状态">
      <button
        type="button"
        :class="{ active: !isExploded }"
        :disabled="!modelReady"
        @click="isExploded = true"
      >
        <i class="split-icon" aria-hidden="true"></i>
        点击拆分
      </button>
      <button
        type="button"
        :class="{ active: isExploded }"
        :disabled="!modelReady"
        @click="isExploded = false"
      >
        <i class="merge-icon" aria-hidden="true"></i>
        点击合并
      </button>
    </div>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import J20ExplodeModelViewer from '../components/J20ExplodeModelViewer.vue'
import { assetPath } from '../utils/assets'
import { j20AssetPath } from '../utils/j20Assets'

const isExploded = ref(false)
const modelReady = ref(false)
</script>

<style scoped>
.explode-page {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  isolation: isolate;
  background: transparent;
}

.environment-inner {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.environment-inner::after,
.environment-inner span {
  position: absolute;
  inset: 0;
}

.environment-inner img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.045);
  filter: saturate(0.75) brightness(0.58) contrast(1.12);
}

.environment-inner::after {
  content: '';
  background:
    linear-gradient(90deg, rgba(3, 6, 8, 0.72), transparent 22%, transparent 78%, rgba(3, 6, 8, 0.72)),
    linear-gradient(180deg, rgba(2, 5, 7, 0.55), transparent 34%, rgba(2, 4, 6, 0.74));
}

.environment-inner span {
  background:
    linear-gradient(rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.025) 1px, transparent 1px);
  background-size: 62px 62px;
  mask-image: radial-gradient(circle at 50% 48%, #000, transparent 74%);
}

.environment::after,
.environment span {
  position: absolute;
  inset: 0;
}

.environment {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
}

.environment img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.045);
  filter: saturate(0.75) brightness(0.58) contrast(1.12);
}

.environment::after {
  content: '';
  background:
    linear-gradient(90deg, rgba(3, 6, 8, 0.72), transparent 22%, transparent 78%, rgba(3, 6, 8, 0.72)),
    linear-gradient(180deg, rgba(2, 5, 7, 0.55), transparent 34%, rgba(2, 4, 6, 0.74));
}

.environment span {
  background:
    linear-gradient(rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.025) 1px, transparent 1px);
  background-size: 62px 62px;
  mask-image: radial-gradient(circle at 50% 48%, #000, transparent 74%);
}

.explode-page :deep(.explode-model-viewer) {
  z-index: 1;
  inset: 6% 6% 10%;
  transform: translateX(4%);
}

.explode-page::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background: radial-gradient(circle at 50% 48%, transparent 26%, rgba(2, 4, 6, 0.42) 82%);
}

.page-title,
.status-panel,
.explode-controls,
.model-floor {
  position: absolute;
  z-index: 4;
}

.page-title {
  top: 34px;
  left: 50%;
  width: min(620px, calc(100% - 340px));
  text-align: center;
  transform: translateX(-50%);
  pointer-events: none;
}

.page-title span,
.status-panel > span {
  color: #69efff;
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-bold, 700);
  line-height: 1.5;
}

.page-title h1 {
  margin-top: 3px;
  color: #fff;
  font-size: 42px;
  line-height: 1.16;
  font-weight: var(--weight-heavy, 800);
  text-shadow: 0 10px 32px rgba(0, 0, 0, 0.52);
}

.page-title p {
  margin-top: 7px;
  color: rgba(255, 255, 255, 0.68);
  font-size: 13px;
  line-height: 1.65;
}

.status-panel {
  left: clamp(30px, 5.2cqw, 82px);
  top: 50%;
  width: min(250px, 20cqw);
  padding-left: 18px;
  border-left: 2px solid rgba(105, 239, 255, 0.62);
  transform: translateY(-34%);
}

.status-panel strong {
  display: block;
  margin-top: 12px;
  color: #fff;
  font-size: 24px;
  line-height: 1.3;
  font-weight: var(--weight-bold, 700);
}

.status-panel p {
  margin-top: 10px;
  color: rgba(255, 255, 255, 0.62);
  font-size: var(--text-xs, 12px);
  line-height: 1.7;
}

.explode-controls {
  left: 50%;
  bottom: clamp(28px, 5cqh, 54px);
  display: grid;
  grid-template-columns: repeat(2, 176px);
  gap: 18px;
  transform: translateX(-50%);
}

.explode-controls button {
  display: flex;
  min-height: 50px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 2px solid transparent;
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(90, 96, 103, 0.94), rgba(54, 59, 64, 0.94));
  color: #fff;
  font-size: 15px;
  font-weight: var(--weight-semibold, 600);
  box-shadow: 0 10px 26px rgba(0, 0, 0, 0.34);
  transition: border-color 0.22s ease, box-shadow 0.22s ease, background 0.22s ease, transform 0.22s ease;
}

.explode-controls button:hover:not(:disabled) {
  background: linear-gradient(180deg, rgba(99, 106, 113, 0.96), rgba(62, 68, 74, 0.96));
  transform: translateY(-1px);
}

.explode-controls button.active {
  border-color: rgba(105, 239, 255, 0.92);
  box-shadow: 0 0 18px rgba(105, 239, 255, 0.3), 0 10px 26px rgba(0, 0, 0, 0.34);
}

.explode-controls button:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 3px;
}

.explode-controls button:disabled {
  cursor: wait;
  opacity: 0.5;
}

.split-icon,
.merge-icon {
  position: relative;
  width: 22px;
  height: 22px;
  flex: none;
}

.split-icon::before,
.split-icon::after,
.merge-icon::before,
.merge-icon::after {
  content: '';
  position: absolute;
  border: 1.5px solid currentColor;
  border-radius: 3px;
}

.split-icon::before,
.split-icon::after {
  width: 10px;
  height: 10px;
}

.split-icon::before {
  left: 0;
  top: 12px;
}

.split-icon::after {
  right: 0;
  top: 0;
}

.merge-icon::before,
.merge-icon::after {
  width: 12px;
  height: 12px;
}

.merge-icon::before {
  left: 0;
  top: 0;
  opacity: 0.55;
}

.merge-icon::after {
  right: 0;
  bottom: 0;
}

.model-floor {
  left: 50%;
  bottom: clamp(98px, 11cqh, 132px);
  display: grid;
  width: min(720px, 48cqw);
  height: 64px;
  place-items: center;
  transform: translateX(-50%);
  pointer-events: none;
}

.model-floor i {
  position: absolute;
  border: 1px solid rgba(105, 239, 255, 0.34);
  border-radius: 50%;
  transform: perspective(520px) rotateX(68deg);
}

.model-floor i:nth-child(1) {
  width: 86%;
  height: 126px;
}

.model-floor i:nth-child(2) {
  width: 58%;
  height: 88px;
  opacity: 0.58;
}

.model-floor i:nth-child(3) {
  width: 28%;
  height: 48px;
  border-color: rgba(255, 209, 102, 0.46);
}

@container (max-height: 520px) {
  .page-title {
    top: 9px;
  }

  .page-title h1 {
    font-size: 30px;
  }

  .page-title p,
  .status-panel,
  .model-floor {
    display: none;
  }

  .explode-page :deep(.explode-model-viewer) {
    inset: 16% 8% 20%;
    transform: none;
  }

  .explode-controls {
    bottom: 9px;
    grid-template-columns: repeat(2, 138px);
    gap: 12px;
  }

  .explode-controls button {
    min-height: 38px;
    border-radius: 11px;
    font-size: 12px;
  }

  .split-icon,
  .merge-icon {
    transform: scale(0.8);
  }
}
</style>



