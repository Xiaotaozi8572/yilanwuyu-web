<template>
  <main class="classroom-page">
    <div class="classroom-environment-inner" aria-hidden="true">
      <img
        class="classroom-environment-image"
        :src="assetPath('backgrounds/classroom-aviation-studio.webp')"
        alt=""
        draggable="false"
      />
      <span class="classroom-environment-shade"></span>
    </div>
    <div class="classroom-frame">
      <Teleport to="body">
        <div class="classroom-environment" aria-hidden="true">
          <img
            class="classroom-environment-image"
            :src="assetPath('backgrounds/classroom-aviation-studio.webp')"
            alt=""
            draggable="false"
          />
          <span class="classroom-environment-shade"></span>
        </div>
      </Teleport>

      <button class="back-btn" type="button" @click="goBack">
        <span class="sr-only">返回上一页</span>
      </button>

      <header class="classroom-header">
        <h1>知识小讲堂</h1>
      </header>

      <section class="classroom-layout">
        <aside class="lesson-rail" aria-label="课程视频列表">
          <a
            v-for="(lesson, index) in lessons"
            :key="lesson.title"
            class="lesson-link"
            :class="{ active: index === currentLesson }"
            :href="z20AssetPath(lesson.video)"
            @click.prevent="chooseLesson(index)"
          >
            <span class="lesson-number">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="lesson-copy">
              <strong class="lesson-section">{{ lesson.section }}</strong>
              <span class="lesson-topic">{{ lesson.topic }}</span>
            </span>
            <span class="rect-hit-layer" aria-hidden="true"></span>
          </a>
        </aside>

        <section class="lesson-center" aria-label="当前课程">
          <a class="video-card" :href="z20AssetPath(currentLessonData.video)" @click.prevent="openLessonVideo">
            <span class="video-card-visual" :class="{ 'preview-ready': previewReady }">
              <img
                class="video-placeholder"
                :src="z20AssetPath(currentLessonData.cover)"
                :alt="currentLessonData.title"
              />
              <video
                ref="previewVideo"
                :key="currentLessonData.video"
                class="video-preview"
                :src="z20AssetPath(currentLessonData.video)"
                muted
                preload="metadata"
                playsinline
                webkit-playsinline
                x5-playsinline
                x5-video-player-type="h5"
                @loadedmetadata="preparePreview"
                @seeked="markPreviewReady"
              ></video>
              <span class="play-trigger" aria-hidden="true"><i></i></span>
              <strong>点击播放课程视频</strong>
            </span>
            <span class="rect-hit-layer" aria-hidden="true"></span>
          </a>

          <div class="lesson-meta">
            <h2>{{ currentLessonData.title }}</h2>
            <p>时长 {{ currentLessonData.duration }} · 讲师 直航</p>
          </div>
          <div class="lesson-progress" :style="progressStyle" aria-hidden="true"></div>
        </section>

        <aside class="news-panel" aria-label="图片和视频资讯">
          <img class="panel-title" :src="z20AssetPath('直20UI分层/虚拟课堂页/点击卡片观看直20相关资讯.png')" alt="点击卡片观看直20相关资讯" />

          <div class="photo-stack">
            <a
              v-for="item in mediaItems"
              :key="item.title"
              class="photo-link"
              :href="z20AssetPath(item.video)"
              @click.prevent="openMedia(item)"
            >
              <img :src="z20AssetPath(item.image)" :alt="item.title" />
              <span>{{ item.title }}</span>
              <span class="rect-hit-layer" aria-hidden="true"></span>
            </a>
          </div>
        </aside>
      </section>

      <nav class="bottom-controls" aria-label="课堂操作">
        <button class="round-control" type="button" @click="openInfo">
          <span aria-hidden="true">i</span>
          <strong>信息</strong>
          <span class="rect-hit-layer" aria-hidden="true"></span>
        </button>
        <button class="wide-control" type="button" :disabled="currentLesson === 0" @click="prevLesson">
          上一节
          <span class="rect-hit-layer" aria-hidden="true"></span>
        </button>
        <button class="wide-control active" type="button" :disabled="currentLesson === lessons.length - 1" @click="nextLesson">
          下一节
          <span class="rect-hit-layer" aria-hidden="true"></span>
        </button>
      </nav>

      <transition name="modal">
        <section
          v-if="activeModal"
          class="media-modal glass-panel"
          :class="`media-modal--${activeModal.kind}`"
          role="dialog"
          aria-modal="true"
        >
          <button class="modal-close" type="button" @click="closeModal">
            x
            <span class="rect-hit-layer" aria-hidden="true"></span>
          </button>

          <video
            v-if="activeModal.kind === 'lesson'"
            class="modal-video"
            :src="z20AssetPath(activeModal.src)"
            :poster="z20AssetPath(activeModal.poster)"
            controls
            autoplay
            playsinline
            webkit-playsinline
            x5-playsinline
            x5-video-player-type="h5"
            x5-video-player-fullscreen="true"
            x5-video-orientation="portraint|landscape"
          ></video>

          <div v-else-if="activeModal.kind === 'story'" class="story-view">
            <figure class="story-photo">
              <img :src="z20AssetPath(activeModal.image)" :alt="activeModal.title" />
            </figure>
            <video
              class="story-video"
              :src="z20AssetPath(activeModal.src)"
              controls
              autoplay
              playsinline
              webkit-playsinline
              x5-playsinline
              x5-video-player-type="h5"
              x5-video-player-fullscreen="true"
              x5-video-orientation="portraint|landscape"
            ></video>
          </div>

          <article v-else-if="activeModal.kind === 'info'" class="aircraft-info-panel">
            <header class="aircraft-info-header">
              <span>HARBIN Z-20</span>
              <h2>核心参数</h2>
              <p>国产中型通用直升机关键运行数据</p>
            </header>

            <dl class="aircraft-spec-list">
              <div v-for="spec in infoSpecs" :key="spec.label">
                <dt>{{ spec.label }}</dt>
                <dd>
                  <strong>{{ spec.value }}</strong>
                  <span v-if="spec.unit">{{ spec.unit }}</span>
                </dd>
              </div>
            </dl>

            <footer class="aircraft-designer">
              <span>总设计师</span>
              <strong>邓景辉</strong>
            </footer>
          </article>

          <img v-else class="modal-image" :src="z20AssetPath(activeModal.src)" :alt="activeModal.title" />

          <div v-if="activeModal.kind !== 'info'" class="modal-caption">
            <h2>{{ activeModal.title }}</h2>
            <p>{{ activeModal.description }}</p>
          </div>
        </section>
      </transition>
    </div>
  </main>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { z20AssetPath } from '../utils/z20Assets'
import { assetPath } from '../utils/assets'

const router = useRouter()
const currentLesson = ref(0)
const activeModal = ref(null)
const previewVideo = ref(null)
const previewReady = ref(false)

const lessons = [
  {
    section: '第一节',
    topic: '直20 是一架怎样的直升机',
    title: '第一节 直20 是一架怎样的直升机',
    duration: '02:30',
    cover: '直20课程视频/直20-1.png',
    titleImage: '直20UI分层/虚拟课堂页/第一节标题.png',
    video: '直20课程视频/直20-1.MOV'
  },
  {
    section: '第二节',
    topic: '直20 的核心技术有哪些',
    title: '第二节 直20 的核心技术有哪些',
    duration: '02:30',
    cover: '直20课程视频/直20-2.png',
    titleImage: '直20UI分层/虚拟课堂页/第二节标题.png',
    video: '直20课程视频/直20-2.MOV'
  },
  {
    section: '第三节',
    topic: '直20 的研制历程',
    title: '第三节 直20 的研制历程',
    duration: '02:30',
    cover: '直20课程视频/直20-3.png',
    titleImage: '直20UI分层/虚拟课堂页/第三节标题.png',
    video: '直20课程视频/直20-3.MOV'
  }
]

const mediaItems = [
  {
    title: '直20 试飞到底多惊险',
    image: '直20UI分层/虚拟课堂页/照片01.png',
    video: '直20新闻视频/直20试飞到底多惊险.mp4',
    description: '走进直20 试飞历程，感受国产直升机的研制艰辛。'
  },
  {
    title: '直20 不是抄了而是超越了美国黑鹰',
    image: '直20UI分层/虚拟课堂页/照片02.png',
    video: '直20新闻视频/直20不是抄了而是超越了美国黑鹰.mp4',
    description: '从关键指标看直20 如何实现自主超越。'
  },
  {
    title: '直20 列装陆海空作用有何不同',
    image: '直20UI分层/虚拟课堂页/照片03.png',
    video: '直20新闻视频/直20列装陆海空作用有何不同.mp4',
    description: '陆军、海军、空军各自的直20 型号与用途。'
  },
  {
    title: '直20T 与直20通用型有啥区别',
    image: '直20UI分层/虚拟课堂页/照片04.png',
    video: '直20新闻视频/直20T与直20通用型有啥区别.mp4',
    description: '对比直20T 舰载型与通用型的差异。'
  },
  {
    title: '直20t 单机飞行',
    image: '直20UI分层/虚拟课堂页/照片05.png',
    video: '直20新闻视频/直20t单机飞行.mp4',
    description: '直20T 单机飞行画面记录。'
  },
  {
    title: '总师说直20是中国人自己的争气机',
    image: '直20UI分层/虚拟课堂页/照片06.png',
    video: '直20新闻视频/总师说直20是中国人自己的争气机.mp4',
    description: '总设计师讲述直20 的研制意义。'
  }
]

const infoSpecs = [
  { label: '最大起飞重量', value: '10,000', unit: 'kg' },
  { label: '最大航程', value: '1,000', unit: 'km' },
  { label: '旋翼直径', value: '20.8', unit: 'm' },
  { label: '桨叶数量', value: '5', unit: '片' },
  { label: '发动机', value: '涡轴-10 ×2' },
  { label: '实用升限', value: '6,000', unit: 'm' }
]

const currentLessonData = computed(() => lessons[currentLesson.value])
const progressStyle = computed(() => ({
  '--progress': `${((currentLesson.value + 1) / lessons.length) * 100}%`
}))

watch(currentLesson, () => {
  previewReady.value = false
})

function preparePreview(event) {
  const video = event.currentTarget
  video.pause()

  const previewTime = Number.isFinite(video.duration)
    ? Math.min(2.2, Math.max(0.5, video.duration * 0.04))
    : 0.8

  if (Math.abs(video.currentTime - previewTime) < 0.05) {
    previewReady.value = true
    return
  }

  video.currentTime = previewTime
}

function markPreviewReady(event) {
  event.currentTarget.pause()
  previewReady.value = true
}

function chooseLesson(index) {
  currentLesson.value = index
  openLessonVideo()
}

function goBack() {
  if (activeModal.value) {
    closeModal()
    return
  }

  router.push('/z20/interaction')
}

function openLessonVideo() {
  activeModal.value = {
    kind: 'lesson',
    title: currentLessonData.value.title,
    description: `时长 ${currentLessonData.value.duration}，讲师 直航`,
    src: currentLessonData.value.video,
    poster: currentLessonData.value.cover
  }
}

function openMedia(item) {
  activeModal.value = {
    kind: 'story',
    title: item.title,
    description: item.description,
    image: item.image,
    src: item.video
  }
}

function openInfo() {
  if (activeModal.value?.kind === 'info') {
    closeModal()
    return
  }

  activeModal.value = {
    kind: 'info',
    title: '直20 核心参数',
    description: '直20 基本参数与核心信息总览。'
  }
}

function closeModal() {
  activeModal.value = null
}

function prevLesson() {
  currentLesson.value = Math.max(0, currentLesson.value - 1)
}

function nextLesson() {
  currentLesson.value = Math.min(lessons.length - 1, currentLesson.value + 1)
}
</script>

<style scoped>
.classroom-page {
  position: relative;
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: #fff;
  background: transparent;
}

.classroom-frame {
  position: relative;
  width: 100%;
  height: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  isolation: isolate;
  border-radius: 0;
  box-shadow:
    0 24px 72px rgba(0, 0, 0, 0.42),
    inset 0 0 0 1px rgba(255, 255, 255, 0.035);
}

.classroom-environment-inner {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: var(--color-bg);
  transform: translate(-50%, -50%);
  pointer-events: none;
  user-select: none;
}

.classroom-environment {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background: var(--color-bg);
  pointer-events: none;
  user-select: none;
}

.classroom-environment-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.025);
  filter: saturate(0.8) brightness(0.6) contrast(1.08);
  animation: classroomEnvironmentDrift 20s ease-in-out infinite alternate;
}

.classroom-environment-shade {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(
      90deg,
      rgba(4, 7, 9, 0.36),
      rgba(4, 7, 9, 0.52) 28%,
      rgba(4, 7, 9, 0.52) 72%,
      rgba(4, 7, 9, 0.34)
    ),
    linear-gradient(180deg, rgba(3, 5, 7, 0.2), rgba(3, 5, 7, 0.56));
}

.classroom-frame::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.03), transparent 40%),
    repeating-linear-gradient(90deg, transparent 0 72px, rgba(255, 255, 255, 0.018) 72px 73px);
  pointer-events: none;
}

.classroom-header {
  position: relative;
  z-index: 2;
  display: grid;
  justify-items: center;
  padding-top: 40px;
  animation: headerDrop 0.7s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)) both;
}

.classroom-header h1 {
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: var(--weight-heavy, 800);
  line-height: 1.15;
  letter-spacing: 0;
  text-shadow:
    0 2px 14px rgba(0, 0, 0, 0.38),
    0 0 18px rgba(56, 232, 255, 0.12);
}

.classroom-layout {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: minmax(170px, 210px) minmax(520px, 1fr) minmax(230px, 280px);
  justify-content: center;
  gap: 28px;
  width: min(calc(100% - 96px), 1504px);
  margin: clamp(56px, 9.2%, 92px) auto 0;
  align-items: start;
}

.lesson-rail {
  display: grid;
  gap: 9px;
  align-content: start;
  padding-top: 2px;
  transform: none;
  animation: railIn 0.75s 0.08s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)) both;
}

.lesson-link,
.info-link,
.video-card,
.photo-link {
  display: block;
  padding: 0;
  color: inherit;
  background: transparent;
}

.lesson-link,
.info-link {
  position: relative;
  width: 100%;
  opacity: 1;
  border-radius: var(--radius-sm, 8px);
  overflow: visible;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  transition:
    transform 0.25s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)),
    filter 0.25s ease,
    border-color 0.25s ease,
    box-shadow 0.25s ease;
}

.lesson-link {
  z-index: 1;
  overflow: visible;
  border-color: transparent;
  border-radius: 0;
  background: transparent;
}

.lesson-link > .rect-hit-layer {
  inset: 0;
  border-radius: inherit;
}

.lesson-link {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  min-height: 64px;
  padding: 10px 9px;
  border: 1px solid rgba(149, 204, 213, 0.24);
  border-radius: 8px;
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.055), transparent 44%),
    rgba(7, 14, 18, 0.78);
  box-shadow:
    0 10px 24px rgba(0, 0, 0, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.045);
  backdrop-filter: blur(8px) saturate(125%);
  -webkit-backdrop-filter: blur(8px) saturate(125%);
}

.lesson-number {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid rgba(255, 255, 255, 0.32);
  border-radius: 7px;
  color: rgba(255, 255, 255, 0.84);
  font-size: var(--text-micro, 11px);
  font-weight: var(--weight-bold, 700);
  line-height: 1;
  transition:
    color 0.25s ease,
    background 0.25s ease,
    border-color 0.25s ease;
}

.lesson-copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.lesson-section {
  color: rgba(255, 255, 255, 0.98);
  font-size: 14px;
  font-weight: var(--weight-bold, 700);
  line-height: 1.25;
}

.lesson-topic {
  overflow: hidden;
  color: rgba(239, 246, 248, 0.88);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-medium, 500);
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lesson-section,
.lesson-topic {
  text-shadow: 0 1px 5px rgba(0, 0, 0, 0.82);
}

.lesson-link.active,
.lesson-link:hover {
  z-index: 5;
  transform: translateX(5px);
  filter: drop-shadow(0 0 18px rgba(56, 232, 255, 0.22));
  border-color: rgba(56, 232, 255, 0.44);
  background: rgba(56, 232, 255, 0.1);
}

.lesson-link.active .lesson-section,
.lesson-link:hover .lesson-section {
  color: #ffffff;
}

.lesson-link.active .lesson-topic,
.lesson-link:hover .lesson-topic {
  color: rgba(244, 253, 255, 0.96);
}

.lesson-link.active .lesson-number,
.lesson-link:hover .lesson-number {
  color: #071014;
  background: var(--accent, #38e8ff);
  border-color: var(--accent, #38e8ff);
}

.lesson-center {
  min-width: 0;
  animation: screenRise 0.78s 0.16s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)) both;
}

.video-card {
  position: relative;
  width: 100%;
  overflow: visible;
  border: 0;
  background: transparent;
}

.video-card-visual {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9.2;
  overflow: hidden;
  border-radius: 10px;
  background: #050607;
  border: 1px solid rgba(105, 232, 247, 0.48);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04),
    inset 0 1px 0 rgba(255, 255, 255, 0.08),
    0 18px 48px rgba(0, 0, 0, 0.46);
  transition:
    border-color 0.28s ease,
    box-shadow 0.28s ease,
    filter 0.28s ease;
}

.video-card-visual::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.1), transparent 18%),
    linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.08), transparent);
  opacity: 0.45;
}

.video-card-visual::before {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 2;
  height: 30%;
  pointer-events: none;
  background: linear-gradient(180deg, transparent, rgba(5, 6, 7, 0.88));
}

.video-card:hover .video-card-visual,
.video-card:focus-visible .video-card-visual {
  border-color: rgba(110, 241, 255, 0.72);
  box-shadow:
    0 26px 62px rgba(0, 0, 0, 0.42),
    inset 0 0 0 2px rgba(110, 241, 255, 0.72);
  filter: brightness(1.04);
}

.video-placeholder,
.video-preview {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.video-placeholder {
  object-position: 50% 45%;
  filter: saturate(0.84) brightness(0.78);
  transform: scale(2.12);
  transition:
    opacity 0.35s ease,
    transform 0.35s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)),
    filter 0.35s ease;
}

.video-preview {
  opacity: 0;
  filter: saturate(0.88) brightness(0.82) contrast(1.04);
  transition:
    opacity 0.38s ease,
    filter 0.35s ease,
    transform 0.35s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.video-card-visual.preview-ready .video-placeholder {
  opacity: 0;
}

.video-card-visual.preview-ready .video-preview {
  opacity: 1;
}

.video-card:hover .video-preview {
  filter: saturate(0.94) brightness(0.9) contrast(1.04);
  transform: scale(1.015);
}

.play-trigger {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 3;
  display: grid;
  place-items: center;
  width: 72px;
  height: 72px;
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 50%;
  background: rgba(8, 16, 20, 0.38);
  box-shadow:
    0 10px 30px rgba(0, 0, 0, 0.3),
    inset 0 0 0 1px rgba(56, 232, 255, 0.1);
  opacity: 0;
  transform: translate(-50%, -50%) scale(0.92);
  transition:
    opacity 0.3s ease,
    transform 0.3s var(--ease-smooth),
    background 0.25s ease;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.preview-ready .play-trigger {
  opacity: 1;
  transform: translate(-50%, -50%) scale(1);
}

.play-trigger i {
  display: block;
  width: 0;
  height: 0;
  margin-left: 5px;
  border-top: 10px solid transparent;
  border-bottom: 10px solid transparent;
  border-left: 16px solid #ffffff;
}

.video-card:hover .play-trigger {
  background: rgba(20, 48, 56, 0.58);
  transform: translate(-50%, -50%) scale(1.06);
}

.video-card strong {
  position: absolute;
  display: none;
  right: 16px;
  bottom: 14px;
  z-index: 3;
  padding: 7px 12px;
  border-radius: var(--radius-pill, 999px);
  color: #effcff;
  background: rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.18);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-semibold, 600);
  line-height: 1.35;
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

.lesson-meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px 20px;
  align-items: end;
  padding: 11px 2px 0;
}

.lesson-meta h2 {
  margin: 0;
  font-size: 20px;
  font-weight: var(--weight-bold, 700);
  line-height: 1.3;
}

.lesson-meta p {
  color: var(--text-secondary, rgba(255, 255, 255, 0.72));
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-medium, 500);
  line-height: 1.45;
  white-space: nowrap;
}

.lesson-progress {
  height: 4px;
  margin-top: 10px;
  border-radius: var(--radius-pill, 999px);
  background:
    linear-gradient(90deg, var(--accent, #38e8ff) 0%, var(--accent, #38e8ff) var(--progress), rgba(255, 255, 255, 0.2) var(--progress));
  box-shadow: 0 0 8px var(--accent-dim, rgba(56, 232, 255, 0.18));
}

.news-panel {
  position: relative;
  display: grid;
  gap: 8px;
  align-content: center;
  min-width: 0;
  animation: cardsIn 0.82s 0.24s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)) both;
}

.news-panel::before {
  content: '直20 航空资讯';
  justify-self: center;
  color: rgba(255, 255, 255, 0.94);
  font-family: var(--font-display);
  font-size: 21px;
  font-weight: var(--weight-heavy, 800);
  line-height: 1.3;
  text-shadow: 0 4px 14px rgba(0, 0, 0, 0.38);
}

.panel-title {
  display: none;
}

.photo-stack {
  position: relative;
  width: 100%;
  max-width: 300px;
  height: 440px;
  margin: 0 auto;
  perspective: 900px;
}

.photo-link {
  --x: 0px;
  --y: 0px;
  --tilt: 0deg;
  --z: 1;
  position: absolute;
  left: calc(50% + var(--x));
  top: var(--y);
  z-index: var(--z);
  width: clamp(96px, 8cqw, 112px);
  overflow: visible;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  transform: translateX(-50%) rotate(var(--tilt)) translateZ(0);
  transform-origin: 50% 80%;
  animation: photoFloat 5.6s ease-in-out infinite;
  animation-delay: var(--delay, 0s);
  transition:
    transform 0.28s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)),
    box-shadow 0.28s ease,
    z-index 0s 0.28s;
}

.photo-link:nth-child(1) {
  --x: -25%;
  --y: 18px;
  --tilt: -9deg;
  --z: 4;
  --delay: -0.8s;
}

.photo-link:nth-child(2) {
  --x: 0%;
  --y: 0;
  --tilt: 4deg;
  --z: 6;
  --delay: -1.6s;
}

.photo-link:nth-child(3) {
  --x: 25%;
  --y: 28px;
  --tilt: 8deg;
  --z: 5;
  --delay: -2.4s;
}

.photo-link:nth-child(4) {
  --x: -22%;
  --y: 188px;
  --tilt: 7deg;
  --z: 3;
  --delay: -3s;
}

.photo-link:nth-child(5) {
  --x: 6%;
  --y: 166px;
  --tilt: -5deg;
  --z: 7;
  --delay: -3.8s;
}

.photo-link:nth-child(6) {
  --x: 25%;
  --y: 198px;
  --tilt: 9deg;
  --z: 2;
  --delay: -4.6s;
}

.photo-link:hover {
  z-index: 12;
  animation: none;
  transform: translateX(-50%) translateY(-16px) rotate(0deg) scale(1.1);
  box-shadow: none;
  transition:
    transform 0.28s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)),
    box-shadow 0.28s ease,
    z-index 0s;
}

.photo-link img {
  display: block;
  width: 100%;
  aspect-ratio: 0.78 / 1;
  object-fit: cover;
  padding: 8px 8px 32px;
  border-radius: 6px;
  background: #fff;
  box-shadow:
    0 14px 22px rgba(0, 0, 0, 0.34),
    0 0 0 1px rgba(255, 255, 255, 0.6);
  transition: box-shadow 0.28s ease;
}

.photo-link:hover img {
  box-shadow:
    0 24px 42px rgba(0, 0, 0, 0.5),
    0 0 0 1px rgba(56, 232, 255, 0.42);
}

.photo-link > span:not(.rect-hit-layer) {
  position: absolute;
  left: 7px;
  right: 7px;
  bottom: 7px;
  overflow: hidden;
  color: #1a1a1a;
  font-size: var(--text-micro, 11px);
  font-weight: var(--weight-semibold, 600);
  line-height: 1.2;
  text-align: center;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.bottom-controls {
  position: relative;
  z-index: 3;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 24px;
  width: min(900px, calc(100% - 96px));
  margin: 22px auto 0;
  animation: controlsUp 0.72s 0.3s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)) both;
}

.round-control {
  position: relative;
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  border-radius: var(--radius-pill, 999px);
  color: #fff;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.1), transparent 45%),
    rgba(9, 17, 22, 0.82);
  border: 1px solid rgba(149, 204, 213, 0.38);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.08);
  transition:
    background 0.25s ease,
    border-color 0.25s ease,
    box-shadow 0.25s ease,
    transform 0.2s var(--ease-bounce, cubic-bezier(0.34, 1.56, 0.64, 1));
}

.round-control:hover,
.round-control:focus-visible {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.45);
  box-shadow: 0 0 18px rgba(56, 232, 255, 0.14);
  transform: translateY(-2px);
}

.round-control:active {
  transform: scale(0.95);
}

.round-control > span:not(.rect-hit-layer) {
  position: relative;
  font-size: 28px;
  line-height: 0.8;
}

.round-control strong {
  font-size: var(--text-micro, 11px);
  font-weight: var(--weight-semibold, 600);
  line-height: 1.3;
}

.wide-control {
  position: relative;
  min-width: 168px;
  min-height: 50px;
  padding: 12px 24px;
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.92);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.075), transparent 46%),
    rgba(9, 17, 22, 0.82);
  border: 1px solid rgba(149, 204, 213, 0.3);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.26), inset 0 1px 0 rgba(255, 255, 255, 0.06);
  font-size: 14px;
  font-weight: var(--weight-semibold, 600);
  line-height: 1.35;
  transition:
    background 0.25s ease,
    border-color 0.25s ease,
    box-shadow 0.25s ease,
    transform 0.2s var(--ease-bounce, cubic-bezier(0.34, 1.56, 0.64, 1));
}

.wide-control:hover:not(:disabled),
.wide-control:focus-visible {
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.38);
  box-shadow: 0 0 18px rgba(56, 232, 255, 0.1);
  transform: translateY(-2px);
}

.wide-control.active {
  color: var(--text-inverse, #0a0c10);
  background: var(--accent, #38e8ff);
  border-color: var(--accent, #38e8ff);
  box-shadow: 0 0 20px var(--accent-dim, rgba(56, 232, 255, 0.18));
}

.wide-control.active:hover:not(:disabled) {
  background: var(--accent-bright, #6ef1ff);
  border-color: var(--accent-bright, #6ef1ff);
  box-shadow: 0 0 28px var(--accent-glow, rgba(56, 232, 255, 0.28));
}

.wide-control:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.media-modal {
  left: 50%;
  top: 50%;
  z-index: 20;
  display: flex;
  flex-direction: column;
  width: min(66cqw, 880px);
  max-height: 78cqh;
  padding: 18px;
  border-radius: 8px;
  transform: translate(-50%, -50%);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.07), transparent 34%),
    rgba(8, 15, 20, 0.94);
  border: 1px solid rgba(105, 232, 247, 0.42);
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.58), inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(20px) saturate(150%);
  -webkit-backdrop-filter: blur(20px) saturate(150%);
}

.media-modal--info {
  width: min(57cqw, 760px);
  padding: 28px 30px 24px;
  background:
    linear-gradient(115deg, rgba(56, 232, 255, 0.1), transparent 32%),
    linear-gradient(180deg, rgba(15, 28, 35, 0.98), rgba(6, 12, 17, 0.98));
  border-color: rgba(105, 232, 247, 0.5);
  box-shadow:
    0 26px 80px rgba(0, 0, 0, 0.62),
    inset 0 1px 0 rgba(255, 255, 255, 0.09),
    inset 3px 0 0 rgba(56, 232, 255, 0.72);
}

.media-modal--story {
  width: min(68cqw, 900px);
}

.media-modal::before {
  content: '';
  position: fixed;
  inset: -100cqh -100cqw;
  z-index: -1;
  background: rgba(0, 0, 0, 0.72);
}

.modal-close {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-pill, 999px);
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
  font-size: 22px;
  line-height: 1;
  transition:
    background 0.2s ease,
    border-color 0.2s ease,
    transform 0.2s var(--ease-bounce, cubic-bezier(0.34, 1.56, 0.64, 1));
}

.modal-close:hover,
.modal-close:focus-visible {
  background: rgba(255, 90, 95, 0.18);
  border-color: rgba(255, 90, 95, 0.45);
  transform: rotate(90deg);
}

.modal-video,
.modal-image,
.story-video {
  width: 100%;
  max-height: 55cqh;
  object-fit: contain;
  border-radius: 7px;
  background: #050607;
}

.modal-image {
  height: min(50cqh, 430px);
}

.aircraft-info-panel {
  display: grid;
  grid-template-columns: minmax(190px, 0.68fr) minmax(360px, 1.32fr);
  grid-template-areas:
    'header specs'
    'designer specs';
  gap: 22px 34px;
  min-width: 0;
}

.aircraft-info-header {
  grid-area: header;
  align-self: end;
  padding-right: 8px;
}

.aircraft-info-header > span {
  display: inline-block;
  margin-bottom: 10px;
  color: var(--accent, #38e8ff);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-bold, 700);
  line-height: 1;
}

.aircraft-info-header h2 {
  margin: 0 0 9px;
  color: #ffffff;
  font-size: 28px;
  font-weight: var(--weight-heavy, 800);
  line-height: 1.18;
}

.aircraft-info-header p {
  max-width: 210px;
  color: rgba(232, 243, 246, 0.66);
  font-size: var(--text-xs, 12px);
  line-height: 1.7;
}

.aircraft-spec-list {
  grid-area: specs;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 26px;
  min-width: 0;
}

.aircraft-spec-list > div {
  display: grid;
  align-content: center;
  gap: 5px;
  min-width: 0;
  min-height: 78px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(149, 204, 213, 0.18);
}

.aircraft-spec-list dt {
  color: rgba(226, 239, 242, 0.68);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-medium, 500);
  line-height: 1.45;
}

.aircraft-spec-list dd {
  display: flex;
  align-items: baseline;
  gap: 7px;
  min-width: 0;
  color: #ffffff;
  line-height: 1.2;
}

.aircraft-spec-list dd strong {
  font-size: 21px;
  font-weight: var(--weight-bold, 700);
  white-space: nowrap;
}

.aircraft-spec-list dd span {
  color: var(--accent, #38e8ff);
  font-size: var(--text-micro, 11px);
  font-weight: var(--weight-semibold, 600);
  white-space: nowrap;
}

.aircraft-designer {
  grid-area: designer;
  align-self: end;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 13px 15px;
  border: 1px solid rgba(149, 204, 213, 0.2);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.035);
}

.aircraft-designer span {
  color: rgba(226, 239, 242, 0.66);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-medium, 500);
}

.aircraft-designer strong {
  color: #ffffff;
  font-size: 16px;
  font-weight: var(--weight-bold, 700);
}

.story-view {
  display: grid;
  grid-template-columns: minmax(150px, 0.38fr) minmax(300px, 1fr);
  gap: 16px;
  align-items: stretch;
  min-height: 0;
  max-height: 52cqh;
}

.story-photo {
  display: grid;
  min-height: 0;
  padding: 8px 8px 30px;
  overflow: hidden;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.22);
}

.story-photo img {
  width: 100%;
  height: 100%;
  min-height: 0;
  max-height: 52cqh;
  object-fit: cover;
}

.story-video {
  height: 100%;
  min-height: 0;
  max-height: 52cqh;
}

.modal-caption {
  padding: 12px 3px 0;
}

.modal-caption h2 {
  margin-bottom: 5px;
  font-size: 19px;
  font-weight: var(--weight-bold, 700);
  line-height: 1.3;
}

.modal-caption p {
  color: var(--text-secondary, rgba(255, 255, 255, 0.72));
  font-size: var(--text-xs, 12px);
  line-height: 1.65;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
  transform: translate(-50%, -48%);
}

@keyframes headerDrop {
  from {
    opacity: 0;
    transform: translateY(-14px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes railIn {
  from {
    opacity: 0;
    transform: translateX(-20px);
  }

  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes screenRise {
  from {
    opacity: 0;
    transform: translateY(22px) scale(0.985);
  }

  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes cardsIn {
  from {
    opacity: 0;
    transform: translateX(24px);
  }

  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes controlsUp {
  from {
    opacity: 0;
    transform: translateY(18px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes photoFloat {
  0%,
  100% {
    transform: translateX(-50%) translateY(0) rotate(var(--tilt)) translateZ(0);
  }

  50% {
    transform: translateX(-50%) translateY(-5px) rotate(var(--tilt)) translateZ(0);
  }
}

@keyframes classroomEnvironmentDrift {
  from {
    transform: scale(1.025);
  }

  to {
    transform: scale(1.055);
  }
}

@media (max-width: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .classroom-page {
    overflow: hidden;
  }

  .classroom-frame {
    width: 100%;
    height: 100%;
    aspect-ratio: 16 / 9;
    overflow: auto;
    border-radius: 0;
  }

  .classroom-layout {
    grid-template-columns: 1fr;
    width: min(88%, 680px);
    margin-top: 20px;
  }

  .lesson-rail {
    grid-template-columns: repeat(3, 1fr);
    transform: none;
    animation: none;
  }

  .lesson-link,
  .info-link {
    width: 100%;
  }

  .info-link {
    display: none;
  }

  .photo-stack {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
    height: auto;
    gap: 14px;
    perspective: none;
  }

  .photo-link {
    position: relative;
    left: auto;
    top: auto;
    width: 100%;
    animation: none;
    transform: rotate(var(--tilt));
  }

  .photo-link:hover {
    transform: translateY(-6px) rotate(0deg) scale(1.02);
  }

  .bottom-controls {
    flex-wrap: wrap;
    padding-bottom: 24px;
  }

  .story-view {
    grid-template-columns: 1fr;
  }
}

@media (max-height: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .classroom-header {
    padding-top: 18px;
  }

  .classroom-header h1 {
    font-size: 34px;
  }

  .photo-stack {
    height: 390px;
  }

  .bottom-controls {
    margin-top: 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .classroom-environment-image,
  .classroom-header,
  .lesson-rail,
  .lesson-center,
  .news-panel,
  .bottom-controls,
  .photo-link {
    animation: none;
  }
}
</style>
