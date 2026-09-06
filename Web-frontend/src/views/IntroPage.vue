<template>
  <SceneShell
    class="intro-shell"
    image="backgrounds/aircraft-selection-gallery.jpg"
    alt="中国航空发展导入页"
  >
    <main class="intro-page">
      <video
        ref="videoRef"
        class="intro-video"
        :src="introVideoSrc"
        autoplay
        muted
        playsinline
        webkit-playsinline
        x5-playsinline
        x5-video-player-type="h5"
        x5-video-player-fullscreen="true"
        x5-video-orientation="portraint|landscape"
        disablepictureinpicture
        controlslist="nodownload noplaybackrate nofullscreen"
        preload="auto"
        aria-label="中国航空发展导入视频"
        @play="isPlaying = true"
        @pause="isPlaying = false"
        @ended="goToSelect"
        @error="onVideoError"
        @volumechange="syncMuted"
      ></video>

      <div class="intro-controls">
        <button
          class="sound-toggle"
          :class="{ 'is-muted': isMuted }"
          type="button"
          :aria-label="isMuted ? '开启声音' : '关闭声音'"
          @click.stop="toggleMute"
        >
          <svg
            v-if="!isMuted"
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M11 5 6 9H3v6h3l5 4V5z" />
            <path d="M15.5 8.5a5 5 0 0 1 0 7" />
            <path d="M18.5 5.5a9 9 0 0 1 0 13" />
          </svg>
          <svg
            v-else
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M11 5 6 9H3v6h3l5 4V5z" />
            <path d="m16 9 5 5" />
            <path d="m21 9-5 5" />
          </svg>
          <span>{{ isMuted ? '开启声音' : '关闭声音' }}</span>
        </button>

        <button
          class="skip-btn"
          type="button"
          aria-label="跳过导入视频"
          @click.stop="goToSelect"
        >
          <svg
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <polygon points="5 4 15 12 5 20 5 4" />
            <line x1="19" y1="5" x2="19" y2="19" />
          </svg>
          <span>跳过</span>
        </button>
      </div>
    </main>
  </SceneShell>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SceneShell from '../components/SceneShell.vue'
import { assetPath } from '../utils/assets'

const router = useRouter()
const videoRef = ref(null)
const isPlaying = ref(false)
const isMuted = ref(true)
const introVideoSrc = assetPath('导入页/026771503d2b485fca0e3c50e928cc84.mp4')
const videoFailed = ref(false)

function onVideoError() {
  if (videoFailed.value) return
  videoFailed.value = true
  // 视频不可用（404 / 解码失败 / Range 不支持）时自动进入选择页，避免黑屏卡死
  setTimeout(() => {
    if (router.currentRoute.value.name === 'Intro') goToSelect()
  }, 1200)
}

function syncMuted() {
  const video = videoRef.value
  if (video) isMuted.value = video.muted
}

function toggleMute() {
  const video = videoRef.value
  if (!video) return
  video.muted = !video.muted
  syncMuted()
}

async function startVideo() {
  const video = videoRef.value
  if (!video) return

  try {
    await video.play()
    isPlaying.value = true
  } catch {
    isPlaying.value = false
  }
}

function onPageClick(event) {
  const video = videoRef.value
  if (!video) return

  if (event.target === video) {
    toggleVideo()
    return
  }

  if (isPlaying.value) {
    goToSelect()
    return
  }
  startVideo()
}

function toggleVideo() {
  const video = videoRef.value
  if (!video) return

  if (video.paused) {
    startVideo()
  } else {
    video.pause()
    isPlaying.value = false
  }
}

function goToSelect() {
  router.push('/select')
}

function onKey(event) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    startVideo()
  }
}

onMounted(() => {
  const video = videoRef.value
  if (video) video.muted = true
  startVideo()
  window.addEventListener('click', onPageClick)
  window.addEventListener('keydown', onKey)
})

onUnmounted(() => {
  window.removeEventListener('click', onPageClick)
  window.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
:deep(.image-layer),
:deep(.scene-vignette) {
  display: none;
}

.intro-shell,
.intro-shell :deep(.scene-frame) {
  background: #000000;
}

.intro-page {
  position: absolute;
  inset: 0;
  overflow: hidden;
  display: grid;
  place-items: center;
  background: transparent;
  cursor: pointer;
}

.intro-video {
  display: block;
  width: 100%;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  object-position: center;
  user-select: none;
  outline: none;
  background: transparent;
}

.sound-toggle {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 10px 20px;
  border: 1px solid rgba(105, 232, 247, 0.4);
  border-radius: 999px;
  background: rgba(6, 20, 27, 0.58);
  color: #d7f6ff;
  font-family: var(--font-display, inherit);
  font-size: min(0.92cqw, 1.6cqh);
  font-weight: var(--weight-semibold, 600);
  letter-spacing: 0.1em;
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

.intro-controls {
  position: absolute;
  right: clamp(18px, 3.2cqw, 46px);
  bottom: clamp(18px, 4.4cqh, 48px);
  z-index: 4;
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.skip-btn {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 10px 20px;
  border: 1px solid rgba(255, 209, 102, 0.45);
  border-radius: 999px;
  background: rgba(31, 24, 8, 0.58);
  color: #ffe7b3;
  font-family: var(--font-display, inherit);
  font-size: min(0.92cqw, 1.6cqh);
  font-weight: var(--weight-semibold, 600);
  letter-spacing: 0.1em;
  cursor: pointer;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow:
    0 0 22px rgba(255, 209, 102, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
  transition:
    transform 0.24s var(--ease-bounce),
    border-color 0.24s ease,
    background 0.24s ease,
    box-shadow 0.24s ease;
}

.skip-btn:hover,
.skip-btn:focus-visible {
  transform: translateY(-2px);
  border-color: rgba(255, 209, 102, 0.85);
  background: rgba(255, 209, 102, 0.16);
}

.skip-btn:focus-visible {
  outline: 2px solid rgba(255, 224, 138, 0.86);
  outline-offset: 3px;
}

.sound-toggle:hover,
.sound-toggle:focus-visible {
  transform: translateY(-2px);
  border-color: rgba(110, 241, 255, 0.85);
  background: rgba(56, 232, 255, 0.16);
  box-shadow:
    0 0 30px rgba(56, 232, 255, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.sound-toggle:focus-visible {
  outline: 2px solid rgba(110, 241, 255, 0.86);
  outline-offset: 3px;
}

.sound-toggle.is-muted {
  animation: soundHintPulse 2.4s ease-in-out infinite;
}

@keyframes soundHintPulse {
  0%,
  100% {
    box-shadow:
      0 0 18px rgba(56, 232, 255, 0.12),
      inset 0 1px 0 rgba(255, 255, 255, 0.08);
  }

  50% {
    box-shadow:
      0 0 36px rgba(56, 232, 255, 0.42),
      inset 0 1px 0 rgba(255, 255, 255, 0.12);
  }
}

@media (prefers-reduced-motion: reduce) {
  .sound-toggle.is-muted {
    animation: none;
  }
}
</style>

<style>
body #app-root:has(.intro-shell),
body #app-stage:has(.intro-shell) {
  background: #000000;
}
</style>
