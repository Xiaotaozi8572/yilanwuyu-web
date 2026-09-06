<template>
  <div id="app-root">
    <section id="app-stage" aria-label="C919 interactive 16:9 stage">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" class="app-route" />
        </transition>
      </router-view>
      <img
        class="brand-logo"
        src="/c919-assets/logo.png"
        alt="中国航空科普品牌 Logo"
      />
    </section>

    <!-- 手机竖屏旋转提示：作品为 16:9 横屏交互设计 -->
    <div class="rotate-prompt" aria-live="polite">
      <span class="rotate-prompt__icon" aria-hidden="true"></span>
      <p class="rotate-prompt__title">请将手机横屏观看</p>
      <p class="rotate-prompt__sub">本作品为 16:9 横屏交互设计<br />旋转手机后自动适配屏幕</p>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
#app-root {
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--color-bg);
}

#app-stage {
  position: relative;
  z-index: 1;
  width: min(100vw, calc(100vh * 16 / 9));
  width: min(100vw, calc(100dvh * 16 / 9));
  height: min(100vh, calc(100vw * 9 / 16));
  height: min(100dvh, calc(100vw * 9 / 16));
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: transparent;
  isolation: isolate;
  container-type: size;
}

#app-stage > :deep(.app-route) {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}

.brand-logo {
  position: absolute;
  top: 2.2cqh;
  left: 2cqw;
  width: 11.5cqw;
  height: auto;
  z-index: 50;
  pointer-events: none;
  user-select: none;
  object-fit: contain;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.45s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ---------- 手机竖屏旋转提示（仅触屏竖屏显示，桌面窄窗口不受影响） ---------- */
.rotate-prompt {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: none;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 26px;
  padding: 32px;
  text-align: center;
  background: var(--color-bg);
}

.rotate-prompt__icon {
  position: relative;
  width: clamp(56px, 18vmin, 92px);
  aspect-ratio: 9 / 16;
  border: 2.5px solid rgba(105, 232, 247, 0.85);
  border-radius: clamp(10px, 3vmin, 16px);
  animation: rotateHint 2.6s ease-in-out infinite;
}

.rotate-prompt__icon::after {
  content: '';
  position: absolute;
  bottom: 7%;
  left: 50%;
  width: 34%;
  height: 3.5%;
  border-radius: 999px;
  background: rgba(105, 232, 247, 0.85);
  transform: translateX(-50%);
}

.rotate-prompt__title {
  margin: 0;
  color: #d7f6ff;
  font-family: var(--font-display, inherit);
  font-size: clamp(17px, 5vmin, 26px);
  font-weight: var(--weight-semibold, 600);
  letter-spacing: 0.3em;
  text-indent: 0.3em;
}

.rotate-prompt__sub {
  margin: 0;
  color: rgba(159, 184, 208, 0.9);
  font-size: clamp(12px, 3.2vmin, 15px);
  line-height: 1.9;
  letter-spacing: 0.12em;
}

@keyframes rotateHint {
  0%,
  18% {
    transform: rotate(0deg);
  }

  46%,
  62% {
    transform: rotate(-90deg);
  }

  90%,
  100% {
    transform: rotate(0deg);
  }
}

@media (orientation: portrait) and (pointer: coarse) {
  .rotate-prompt {
    display: flex;
  }
}

@media (prefers-reduced-motion: reduce) {
  .rotate-prompt__icon {
    animation: none;
  }
}
</style>
