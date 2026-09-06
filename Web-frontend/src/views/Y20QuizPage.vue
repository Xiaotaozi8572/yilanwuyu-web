<template>
  <SceneShell asset-base="/y20-assets/" :image="sceneImage" alt="运20 知识巩固测试" image-blend-mode="screen">
    <template #background>
      <div class="quiz-environment" aria-hidden="true">
        <img
          class="quiz-environment-image"
          :src="assetPath('backgrounds/quiz-training-hangar.png')"
          alt=""
draggable="false"
        />
        <span class="quiz-environment-shade"></span>
      </div>
    </template>

    <img
      class="quiz-card-preserver"
      :src="y20AssetPath(sceneImage)"
      alt=""
      aria-hidden="true"
      draggable="false"
    />

    <div class="quiz-meta-overlay" aria-hidden="true">
      <span class="quiz-progress-copy">当前进度：{{ currentIndex + 1 }} / {{ questions.length }}</span>
      <span class="quiz-type-copy">{{ questions[currentIndex].type === 'choice' ? '单选题' : '判断题' }}</span>
    </div>

    <button class="scene-hotspot back-hotspot" @click="$router.push('/y20/interaction')">
      <span class="sr-only">返回主交互页</span>
    </button>

    <button
      v-for="option in optionHotspots"
      :key="option.index"
      class="scene-hotspot option-hotspot"
      :class="optionClass(option.index)"
      :style="option.style"
      :disabled="feedbackVisible"
      @click="selectAnswer(option.index)"
    >
      <span class="sr-only">选择 {{ option.letter }}</span>
    </button>

    <transition name="feedback">
      <section v-if="feedbackVisible" class="quiz-feedback glass-panel" :class="feedbackCorrect ? 'correct' : 'wrong'">
        <strong>{{ feedbackCorrect ? '回答正确' : '回答错误' }}</strong>
        <span>{{ feedbackCorrect ? '继续保持，下一题马上开始。' : `正确答案是 ${answerLetter}。` }}</span>
      </section>
    </transition>
  </SceneShell>
</template>

<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SceneShell from '../components/Z20SceneShell.vue'
import { y20AssetPath } from '../utils/y20Assets'
import { assetPath } from '../utils/assets'

const router = useRouter()

const questionBank = [
  { image: '运20.jpg', answer: 1, type: 'choice' },
  { image: '运20-1.jpg', answer: 1, type: 'choice' },
  { image: '运20-2.jpg', answer: 0, type: 'judge' },
  { image: '运20-3.jpg', answer: 2, type: 'choice' },
  { image: '运20-4.jpg', answer: 1, type: 'judge' },
  { image: '运20-5.jpg', answer: 2, type: 'choice' },
  { image: '运20-6.jpg', answer: 1, type: 'judge' },
  { image: '运20-7.jpg', answer: 1, type: 'judge' }
]

function shuffleQuestions(items) {
  const shuffled = [...items]

  for (let index = shuffled.length - 1; index > 0; index -= 1) {
    const randomIndex = Math.floor(Math.random() * (index + 1))
    ;[shuffled[index], shuffled[randomIndex]] = [shuffled[randomIndex], shuffled[index]]
  }

  return shuffled
}

function createQuestionOrder(items) {
  const previousFirstImage = sessionStorage.getItem('y20-quiz-first-image')
  const shuffled = shuffleQuestions(items)

  if (items.length > 1 && shuffled[0].image === previousFirstImage) {
    const replacementIndex = shuffled.findIndex((question) => question.image !== previousFirstImage)
    ;[shuffled[0], shuffled[replacementIndex]] = [shuffled[replacementIndex], shuffled[0]]
  }

  sessionStorage.setItem('y20-quiz-first-image', shuffled[0].image)
  return shuffled
}

const questions = createQuestionOrder(questionBank)

const optionLayouts = {
  choice: [
    { index: 0, letter: 'A', style: { '--x': 26.74, '--y': 53.21, '--w': 22.71, '--h': 6.52 } },
    { index: 1, letter: 'B', style: { '--x': 50.83, '--y': 53.1, '--w': 22.71, '--h': 6.52 } },
    { index: 2, letter: 'C', style: { '--x': 26.74, '--y': 63.68, '--w': 22.71, '--h': 6.52 } },
    { index: 3, letter: 'D', style: { '--x': 50.83, '--y': 63.57, '--w': 22.71, '--h': 6.52 } }
  ],
  judge: [
    { index: 0, letter: '正确', style: { '--x': 26.74, '--y': 53.21, '--w': 22.71, '--h': 6.52 } },
    { index: 1, letter: '错误', style: { '--x': 50.97, '--y': 53.21, '--w': 22.71, '--h': 6.52 } }
  ],
  judgeLow: [
    { index: 0, letter: '正确', style: { '--x': 19.67, '--y': 57.05, '--w': 22.71, '--h': 6.52 } },
    { index: 1, letter: '错误', style: { '--x': 43.92, '--y': 57.05, '--w': 22.71, '--h': 6.52 } }
  ]
}

const layoutOverrides = {
  '运20-6.jpg': [
    { index: 0, letter: '正确', style: { '--x': 26.66, '--y': 56.61, '--w': 22.71, '--h': 6.52 } },
    { index: 1, letter: '错误', style: { '--x': 50.95, '--y': 56.61, '--w': 22.71, '--h': 6.52 } }
  ],
}

const currentIndex = ref(0)
const selectedAnswer = ref(null)
const feedbackVisible = ref(false)
const feedbackCorrect = ref(false)
const score = ref(0)
let advanceTimer = null

const activeQuestion = computed(() => questions[currentIndex.value])
const sceneImage = computed(() => `学习页/${activeQuestion.value.image}`)
const optionHotspots = computed(() => layoutOverrides[questions[currentIndex.value].image] ?? optionLayouts[questions[currentIndex.value].type])
const answerLetter = computed(() => ['A', 'B', 'C', 'D'][questions[currentIndex.value].answer])

function optionClass(index) {
  if (!feedbackVisible.value || selectedAnswer.value !== index) return ''
  return feedbackCorrect.value ? 'selected-correct' : 'selected-wrong'
}


function selectAnswer(index) {
  if (feedbackVisible.value) return

  selectedAnswer.value = index
  feedbackCorrect.value = index === activeQuestion.value.answer
  if (feedbackCorrect.value) score.value += 1
  feedbackVisible.value = true

  clearTimeout(advanceTimer)
  advanceTimer = setTimeout(advance, 1150)
}

function advance() {
  feedbackVisible.value = false
  selectedAnswer.value = null

  if (currentIndex.value < questions.length - 1) {
    currentIndex.value += 1
    return
  }

  router.push({ path: '/y20/result', query: { score: score.value, total: questions.length } })
}

onUnmounted(() => clearTimeout(advanceTimer))
</script>

<style scoped>
.quiz-environment {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
background: var(--color-bg);
}

.quiz-environment-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.025);
  filter: saturate(0.9) brightness(0.82) contrast(1.06);
  animation: quizEnvironmentBreathe 16s ease-in-out infinite alternate;
}

.quiz-environment-shade {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(5, 9, 12, 0.16), rgba(5, 9, 12, 0.03) 48%, rgba(5, 9, 12, 0.2)),
    radial-gradient(
      ellipse at 50% 52%,
      rgba(4, 8, 11, 0.34) 0 36%,
      rgba(4, 8, 11, 0.18) 58%,
      rgba(4, 8, 11, 0.28) 78%
    );
}

.quiz-card-preserver {
  position: absolute;
  inset: 0;
  z-index: 3;
  width: 100%;
  height: 100%;
  object-fit: cover;
  clip-path: inset(28.8% 20.4% 19.6% 20.4% round 14px);
  filter: brightness(0.86) contrast(1.28) saturate(1.04);
  pointer-events: none;
  user-select: none;
}

.quiz-meta-overlay {
  position: absolute;
  inset: 0;
  z-index: 3;
  color: rgba(43, 48, 53, 0.94);
  font-family: var(--font-display);
  font-size: min(0.86cqw, 1.48cqh);
  font-weight: var(--weight-semibold, 600);
  line-height: 1.15;
  pointer-events: none;
}

.quiz-meta-overlay span {
  position: absolute;
  left: 26.74%;
  z-index: 0;
  box-sizing: border-box;
  display: block;
  min-height: 2.9cqh;
  padding: 0.1cqh 1.45cqw 0.1cqh 0;
  letter-spacing: 0;
  white-space: nowrap;
  background: rgb(160, 160, 160);
}

.quiz-progress-copy {
  top: 36.25%;
  min-width: 14.2cqw;
}

.quiz-type-copy {
  top: 42.5%;
  min-width: 7.2cqw;
}

.back-hotspot {
  --x: 6.181;
  --y: 9.829;
  --w: 5.556;
  --h: 8.547;
  --hotspot-radius: 999px;
}

.option-hotspot {
  --hotspot-radius: var(--radius-md, 14px);
  appearance: none;
  background: transparent;
  border: 0;
  color: transparent;
  padding: 0;
  transform: none;
}

.option-hotspot::before {
  box-sizing: border-box;
  background: transparent;
  box-shadow: none;
  border: 2px solid transparent;
}

.option-hotspot:hover::before,
.option-hotspot:focus-visible::before {
  background: rgba(56, 232, 255, 0.08);
  border-color: rgba(56, 232, 255, 0.9);
  box-shadow: 0 0 14px rgba(56, 232, 255, 0.2);
}

.option-hotspot.selected-correct::before {
  background: var(--success-dim, rgba(46, 204, 113, 0.18));
  border-color: var(--success, #2ecc71);
}

.option-hotspot.selected-wrong::before {
  background: var(--error-dim, rgba(255, 90, 95, 0.18));
  border-color: var(--error, #ff5a5f);
}

.quiz-feedback {
  left: 50%;
  top: 82%;
  display: grid;
  gap: 5px;
  width: min(360px, 34%);
  padding: 14px 22px 15px;
  border-radius: 8px;
  text-align: center;
  transform: translateX(-50%);
  background: var(--glass-bg, rgba(17, 21, 29, 0.72));
  box-shadow: var(--glass-glow, 0 0 28px rgba(56, 232, 255, 0.12));
}

.quiz-feedback strong {
  font-size: 19px;
  font-weight: var(--weight-bold, 700);
  line-height: 1.4;
}

.quiz-feedback span {
  color: var(--text-secondary, rgba(255, 255, 255, 0.72));
  font-size: var(--text-xs, 12px);
  line-height: 1.65;
}

.quiz-feedback.correct {
  border-color: rgba(95, 238, 154, 0.7);
}

.quiz-feedback.correct strong {
  color: var(--success, #2ecc71);
  text-shadow: 0 0 12px rgba(46, 204, 113, 0.28);
}

.quiz-feedback.wrong {
  border-color: rgba(255, 100, 104, 0.72);
}

.quiz-feedback.wrong strong {
  color: var(--error, #ff5a5f);
  text-shadow: 0 0 12px rgba(255, 90, 95, 0.28);
}

.feedback-enter-active,
.feedback-leave-active {
  transition:
    opacity 0.25s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1)),
    transform 0.25s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.feedback-enter-from,
.feedback-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(12px) scale(0.98);
}

@keyframes quizEnvironmentBreathe {
  from {
    transform: scale(1.025);
  }

  to {
    transform: scale(1.055);
  }
}

@media (prefers-reduced-motion: reduce) {
  .quiz-environment-image {
    animation: none;
  }
}
</style>

