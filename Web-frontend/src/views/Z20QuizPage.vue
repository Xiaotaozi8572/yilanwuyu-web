<template>
  <SceneShell :image="sceneImage" alt="直20 知识巩固测试" image-blend-mode="screen">
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
      :class="{ judge: activeQuestion.type === 'judge' }"
      :src="z20AssetPath(sceneImage)"
      alt=""
      aria-hidden="true"
      draggable="false"
    />

    <div class="quiz-meta-overlay" aria-hidden="true">
      <span class="quiz-progress-copy">第 {{ currentIndex + 1 }} 题</span>
      <span class="quiz-type-copy">{{ questions[currentIndex].type === 'choice' ? '单选题' : '判断题' }}</span>
    </div>

    <div v-if="activeQuestion.type === 'judge'" class="options-clean-mask" aria-hidden="true"></div>

    <button class="scene-hotspot back-hotspot" @click="$router.push('/z20/interaction')">
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
      <span class="sr-only">选择 {{ option.label }}</span>
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
import { z20AssetPath } from '../utils/z20Assets'
import { assetPath } from '../utils/assets'

const router = useRouter()

const questionBank = [
  {
    type: 'choice',
    folder: 1,
    question: '直-20属于哪个级别的通用直升机？',
    questionImage: '学习页quiz/q1.png',
    answer: 'B',
    correctIndex: 1,
    options: ['A. 4吨级轻型', 'B.10吨级中型', 'C.20吨级重型', 'D.30吨级重型']
  },
  {
    type: 'judge',
    folder: 2,
    question: '直-20是完全仿制美国"黑鹰"直升机的产品，没有自主创新。',
    questionImage: '学习页quiz/q2.png',
    answer: '错误',
    correctIndex: 1,
    options: ['正确', '错误']
  },
  {
    type: 'choice',
    folder: 3,
    question: '直-20的主旋翼采用了几片桨叶设计？',
    questionImage: '学习页quiz/q3.png',
    answer: 'C',
    correctIndex: 2,
    options: ['A. 3片', 'B.4片', 'C.5片', 'D.6片']
  },
  {
    type: 'judge',
    folder: 4,
    question: '直-20目前只有陆军一个军种在使用，没有发展出海军或空军型号。',
    questionImage: '学习页quiz/q4.png',
    answer: '错误',
    correctIndex: 1,
    options: ['正确', '错误']
  },
  {
    type: 'choice',
    folder: 5,
    question: '直-20搭载的国产发动机型号是什么？',
    questionImage: '学习页quiz/q5.png',
    answer: 'C',
    correctIndex: 2,
    options: ['A. 涡扇-15', 'B.涡扇-20', 'C.涡轴-10', 'D.涡桨-6']
  },
  {
    type: 'judge',
    folder: 6,
    question: '直-20在海拔5000米的高原环境下仍能输出约85%的功率，具备出色的高原作战能力。',
    questionImage: '学习页quiz/q6.png',
    answer: '正确',
    correctIndex: 0,
    options: ['正确', '错误']
  },
  {
    type: 'choice',
    folder: 7,
    question: '直-20的原型机首次成功首飞是在哪一年？',
    questionImage: '学习页quiz/q7.png',
    answer: 'C',
    correctIndex: 2,
    options: ['A. 2008年', 'B.2011年', 'C.2013年', 'D.2016年']
  },
  {
    type: 'judge',
    folder: 8,
    question: '直-20是中国直升机领域首次应用电传操纵系统的机型。',
    questionImage: '学习页quiz/q8.png',
    answer: '正确',
    correctIndex: 0,
    options: ['正确', '错误']
  }
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
  const previousFirstImage = sessionStorage.getItem('z20-quiz-first-image')
  const shuffled = shuffleQuestions(items)

  if (items.length > 1 && shuffled[0].questionImage === previousFirstImage) {
    const replacementIndex = shuffled.findIndex((question) => question.questionImage !== previousFirstImage)
    ;[shuffled[0], shuffled[replacementIndex]] = [shuffled[replacementIndex], shuffled[0]]
  }

  sessionStorage.setItem('z20-quiz-first-image', shuffled[0].questionImage)
  return shuffled
}

const questions = createQuestionOrder(questionBank)

const optionLayouts = {
  choice: [
    { index: 0, style: { '--x': 26.701, '--y': 53.472, '--w': 22.743, '--h': 6.517 } },
    { index: 1, style: { '--x': 50.799, '--y': 53.472, '--w': 22.743, '--h': 6.517 } },
    { index: 2, style: { '--x': 26.701, '--y': 63.515, '--w': 22.743, '--h': 6.677 } },
    { index: 3, style: { '--x': 50.799, '--y': 63.515, '--w': 22.743, '--h': 6.677 } }
  ],
  judge: [
    { index: 0, style: { '--x': 26.701, '--y': 53.472, '--w': 22.743, '--h': 6.517 } },
    { index: 1, style: { '--x': 50.799, '--y': 53.472, '--w': 22.743, '--h': 6.517 } }
  ]
}


const currentIndex = ref(0)
const selectedAnswer = ref(null)
const feedbackVisible = ref(false)
const feedbackCorrect = ref(false)
const score = ref(0)
let advanceTimer = null

const activeQuestion = computed(() => questions[currentIndex.value])
const sceneImage = computed(() => `${activeQuestion.value.questionImage}`)
const optionHotspots = computed(() => {
  const question = questions[currentIndex.value]
  const layout = optionLayouts[question.type]
  return layout.map((option) => ({
    ...option,
    label: question.options[option.index]
  }))
})
const answerLetter = computed(() => activeQuestion.value.answer)

function optionClass(index) {
  if (!feedbackVisible.value || selectedAnswer.value !== index) return ''
  return feedbackCorrect.value ? 'selected-correct' : 'selected-wrong'
}

function selectAnswer(index) {
  if (feedbackVisible.value) return

  selectedAnswer.value = index
  feedbackCorrect.value = index === activeQuestion.value.correctIndex
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

  router.push({ path: '/z20/result', query: { score: score.value, total: questions.length } })
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
  clip-path: inset(29.5% 20.6% 19.9% 20.5% round 12px);
  filter: brightness(0.96) contrast(1.18) saturate(1.05);
  pointer-events: none;
  user-select: none;
}

.quiz-card-preserver.judge {
  clip-path: inset(29.5% 20.6% 19.9% 20.5% round 12px);
}

.options-clean-mask {
  position: absolute;
  left: 25%;
  top: 62%;
  width: 50%;
  height: 10.2%;
  z-index: 3;
  border-radius: 0;
  background: rgb(182, 182, 182);
  pointer-events: none;
}

.quiz-meta-overlay {
  position: absolute;
  inset: 0;
  z-index: 3;
  color: rgba(124, 127, 131, 0.96);
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
  min-height: 2.1cqh;
  padding: 0.22cqh 1.2cqw 0.1cqh 0;
  letter-spacing: 0;
  white-space: nowrap;
  color: rgba(43, 48, 53, 0.94);
  background: transparent;
}

.quiz-progress-copy {
  top: 36.25%;
  min-width: 6.2cqw;
}

.quiz-type-copy {
  top: 42.5%;
  min-width: 6.2cqw;
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
  z-index: 4;
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
  border: 2px solid rgba(56, 232, 255, 0.9);
}

.option-hotspot:hover::before,
.option-hotspot:focus-visible::before {
  background: rgba(56, 232, 255, 0.08);
  border-color: rgba(56, 232, 255, 0.9);
  box-shadow: none;
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
