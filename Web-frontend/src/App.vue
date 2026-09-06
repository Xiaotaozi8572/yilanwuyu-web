<template>
  <div id="app-root" :class="{ 'is-rotated': stageRotated }">
    <section id="app-stage" aria-label="C919 interactive 16:9 stage" :style="{ '--stage-scale': stageScale }">
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

    <!-- 控件组：固定于屏幕右上角，不随舞台旋转，竖持时也能正向点击。
     .stop 阻止冒泡到 window：导入页在 window 上监听点击"任意处跳过视频"，
     这两个系统控件不应触发跳过（页面其余区域点击仍照常跳过） -->
    <div class="stage-controls">
      <button
        v-if="isTouch && isPortrait"
        class="stage-controls__btn stage-controls__btn--rotate"
        type="button"
        :title="stageRotated ? '切换为竖屏观看' : '切换为横屏观看'"
        :aria-label="stageRotated ? '切换为竖屏观看' : '切换为横屏观看'"
        @click.stop="toggleViewMode"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <rect x="7" y="3" width="10" height="18" rx="2" />
          <path d="M2 8a9.7 9.7 0 0 1 2.2-4.4" />
          <path d="M2 16a9.7 9.7 0 0 0 2.2 4.4" />
          <path d="M22 8a9.7 9.7 0 0 0-2.2-4.4" />
          <path d="M22 16a9.7 9.7 0 0 1-2.2 4.4" />
        </svg>
        <span>{{ stageRotated ? '竖屏观看' : '横屏观看' }}</span>
      </button>

      <button
        v-if="fsSupported"
        class="stage-controls__btn"
        type="button"
        :title="isFullscreen ? '退出全屏' : '进入全屏'"
        :aria-label="isFullscreen ? '退出全屏' : '进入全屏'"
        @click.stop="toggleFullscreen"
      >
        <svg
          v-if="!isFullscreen"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M8 3H5a2 2 0 0 0-2 2v3" />
          <path d="M21 8V5a2 2 0 0 0-2-2h-3" />
          <path d="M3 16v3a2 2 0 0 0 2 2h3" />
          <path d="M16 21h3a2 2 0 0 0 2-2v-3" />
        </svg>
        <svg
          v-else
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M8 3v3a2 2 0 0 1-2 2H3" />
          <path d="M21 8h-3a2 2 0 0 1-2-2V3" />
          <path d="M3 16h3a2 2 0 0 1 2 2v3" />
          <path d="M16 21v-3a2 2 0 0 1 2-2h3" />
        </svg>
      </button>
    </div>

    <p v-if="fsHint" class="fullscreen-hint" aria-live="polite">{{ fsHint }}</p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const isTouch =
  typeof window !== 'undefined' &&
  ('ontouchstart' in window || navigator.maxTouchPoints > 0)
const isWeChat =
  typeof navigator !== 'undefined' && /MicroMessenger/i.test(navigator.userAgent)
const isIos =
  typeof navigator !== 'undefined' &&
  (/iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))

const docEl = document.documentElement
const fsSupported =
  typeof docEl.requestFullscreen === 'function' ||
  typeof docEl.webkitRequestFullscreen === 'function'
const isIosNoFs = isIos && !fsSupported

const viewportW = ref(window.innerWidth)
const viewportH = ref(window.innerHeight)
const isPortrait = computed(() => viewportW.value < viewportH.value)

// 横屏呈现开关：true = 舞台旋转 90° 放大铺满竖屏（手机横过来看）
// false = 舞台缩小居中正着完整显示（竖持直接看）
const landscapeView = ref(true)
const stageRotated = computed(() => isTouch && isPortrait.value && landscapeView.value)

// 舞台整体等比缩放：内部固定按 1280×720 参考分辨率布局，外层 transform:scale 适配屏幕。
// 移动端与桌面渲染同一套版式（纯等比克隆），固定 px 元素不再因舞台变小而相对放大错位。
const STAGE_W = 1280
const STAGE_H = 720
const stageScale = computed(() => {
  const w = viewportW.value || 1
  const h = viewportH.value || 1
  // 旋转模式：舞台 1280 长边贴屏幕长边(vh)、720 短边贴屏幕短边(vw)
  return stageRotated.value
    ? Math.min(h / STAGE_W, w / STAGE_H)
    : Math.min(w / STAGE_W, h / STAGE_H)
})

const isFullscreen = ref(false)
const fsHint = ref('')
let viewportTimer = null
let fsHintTimer = null

function syncViewport() {
  viewportW.value = window.innerWidth
  viewportH.value = window.innerHeight
  docEl.style.setProperty('--app-w', `${window.innerWidth}px`)
  docEl.style.setProperty('--app-h', `${window.innerHeight}px`)
}

const prevPortrait = ref(isPortrait.value)

function onViewportChange() {
  clearTimeout(viewportTimer)
  viewportTimer = setTimeout(() => {
    syncViewport()
    // 仅在物理朝向真正翻转时回到默认体验（横屏呈现）。
    // iOS Safari 地址栏/工具栏伸缩会频繁触发 resize，若无条件重置会覆盖用户手动切换，
    // 表现为点「横屏观看」后被瞬间拉回原状态（看起来像按钮无反应）。
    if (isPortrait.value !== prevPortrait.value) {
      prevPortrait.value = isPortrait.value
      landscapeView.value = true
    }
  }, 80)
}

function toggleViewMode() {
  landscapeView.value = !landscapeView.value
}

function activeFullscreenElement() {
  return document.fullscreenElement || document.webkitFullscreenElement
}

function showHint(text) {
  fsHint.value = text
  clearTimeout(fsHintTimer)
  fsHintTimer = setTimeout(() => {
    fsHint.value = ''
  }, 3200)
}

async function toggleFullscreen() {
  try {
    if (activeFullscreenElement()) {
      if (document.exitFullscreen) await document.exitFullscreen()
      else if (document.webkitExitFullscreen) document.webkitExitFullscreen()
      return
    }
    if (typeof docEl.requestFullscreen === 'function') {
      await docEl.requestFullscreen()
    } else if (typeof docEl.webkitRequestFullscreen === 'function') {
      docEl.webkitRequestFullscreen()
    } else {
      showHint('当前浏览器不支持全屏')
      return
    }
    if (isTouch) {
      try {
        await screen.orientation.lock('landscape')
      } catch {
        /* iOS / 微信不支持锁屏方向，忽略 */
      }
    }
  } catch {
    showHint(
      isWeChat
        ? '微信内无法全屏：请点右上角「···」→「在浏览器打开」'
        : '当前环境无法全屏，可点击切换按钮调整观看方式'
    )
  }
}

function onFullscreenChange() {
  isFullscreen.value = !!activeFullscreenElement()
}

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', onViewportChange)
  window.addEventListener('orientationchange', onViewportChange)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('webkitfullscreenchange', onFullscreenChange)

  if (stageRotated.value) {
    // 首次进入旋转模式的引导，避免用户误以为画面转错
    setTimeout(() => {
      if (stageRotated.value) {
        showHint('已放大为横屏画面，点击右上角「竖屏观看」可切换')
      }
    }, 1200)
  }
})

onBeforeUnmount(() => {
  clearTimeout(viewportTimer)
  clearTimeout(fsHintTimer)
  window.removeEventListener('resize', onViewportChange)
  window.removeEventListener('orientationchange', onViewportChange)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('webkitfullscreenchange', onFullscreenChange)
})
</script>

<style scoped>
#app-root {
  position: relative;
  width: var(--app-w, 100vw);
  height: var(--app-h, 100vh);
  height: var(--app-h, 100dvh);
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--color-bg);
}

#app-stage {
  position: absolute;
  top: 50%;
  left: 50%;
  z-index: 1;
  width: 1280px;
  height: 720px;
  overflow: hidden;
  background: transparent;
  isolation: isolate;
  container-type: size;
  /* absolute+translate 居中：grid 居中对超过容器的 1280×720 item 会失效(轨道被撑大贴左上角)，
     必须用自身尺寸百分比位移严格居中，再叠加 scale 整体适配 */
  transform: translate(-50%, -50%) scale(var(--stage-scale, 1));
  transform-origin: center center;
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

.stage-controls {
  position: fixed;
  top: max(10px, env(safe-area-inset-top));
  right: max(10px, env(safe-area-inset-right));
  z-index: 100;
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.stage-controls__btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 42px;
  min-height: 42px;
  padding: 9px 16px;
  border: 1px solid rgba(105, 232, 247, 0.4);
  border-radius: 999px;
  background: rgba(6, 20, 27, 0.58);
  color: #d7f6ff;
  font-family: var(--font-display, inherit);
  font-size: clamp(12px, 1.1cqw, 14px);
  font-weight: var(--weight-semibold, 600);
  letter-spacing: 0.06em;
  cursor: pointer;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow:
    0 0 22px rgba(56, 232, 255, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
  transition:
    transform 0.24s var(--ease-bounce),
    border-color 0.24s ease,
    background 0.24s ease,
    box-shadow 0.24s ease;
}

.stage-controls__btn svg {
  width: 18px;
  height: 18px;
}

.stage-controls__btn:hover,
.stage-controls__btn:focus-visible {
  border-color: rgba(110, 241, 255, 0.85);
  background: rgba(56, 232, 255, 0.16);
  transform: translateY(-2px);
}

.stage-controls__btn:focus-visible {
  outline: 2px solid rgba(110, 241, 255, 0.86);
  outline-offset: 3px;
}

.stage-controls__btn:active {
  transform: translateY(0) scale(0.97);
}

.stage-controls__btn--rotate {
  border-color: rgba(255, 209, 102, 0.45);
  color: #ffe7b3;
  box-shadow:
    0 0 22px rgba(255, 209, 102, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.stage-controls__btn--rotate:hover,
.stage-controls__btn--rotate:focus-visible {
  border-color: rgba(255, 209, 102, 0.85);
  background: rgba(255, 209, 102, 0.16);
}

.fullscreen-hint {
  position: fixed;
  top: calc(max(10px, env(safe-area-inset-top)) + 54px);
  right: max(10px, env(safe-area-inset-right));
  z-index: 101;
  max-width: min(72vw, 340px);
  margin: 0;
  padding: 10px 16px;
  border: 1px solid rgba(105, 232, 247, 0.4);
  border-radius: 12px;
  background: rgba(6, 20, 27, 0.9);
  color: #d7f6ff;
  font-size: clamp(12px, 1.05cqw, 14px);
  line-height: 1.6;
  text-align: left;
  pointer-events: none;
}
</style>
