<template>
  <section ref="panelRef" class="coach-chat glass-panel" :style="panelStyle">
    <header class="coach-chat__head">
      <span
        class="coach-chat__move"
        aria-label="拖动移动面板"
        title="按住拖动移动"
        @pointerdown="startPanelDrag"
      >≡</span>
      <span class="coach-chat__title">AI助手</span>
      <span class="coach-chat__status" :class="{ 'is-busy': busy }">{{ statusText }}</span>
      <button type="button" class="coach-chat__close" aria-label="收起" @click="$emit('close')">
        <span class="rect-hit-layer" aria-hidden="true"></span>收起
      </button>
    </header>

    <div class="coach-chat__body" ref="bodyRef">
      <p v-if="!messages.length" class="coach-chat__hint">
        可以直接打字提问，或按住右下角麦克风说话（声音会自动转成文字）。<br />
        例如：C919 的发动机型号是什么？
      </p>
      <div
        v-for="(msg, index) in messages"
        :key="index"
        class="coach-chat__msg"
        :class="msg.role === 'user' ? 'is-user' : 'is-assistant'"
      >
        <div class="coach-chat__bubble">
          <span>{{ msg.role === 'user' ? '你' : 'AI助手' }}</span>
          <p class="coach-chat__text">{{ msg.text }}</p>
        </div>
      </div>
      <div v-if="thinking" class="coach-chat__msg is-assistant">
        <div class="coach-chat__bubble">
          <span>AI助手</span>
          <p class="coach-chat__text coach-chat__think">
            正在思考<em v-if="thinkingElapsed > 0">{{ thinkingElapsed }}s</em>
            <span class="coach-chat__dots" aria-hidden="true"><i></i><i></i><i></i></span>
          </p>
        </div>
      </div>
      <span v-if="typing" class="coach-chat__cursor" aria-hidden="true">▍</span>
    </div>

    <footer class="coach-chat__input">
      <textarea
        ref="inputRef"
        v-model="draft"
        rows="1"
        class="coach-chat__textarea"
        placeholder="输入问题，回车发送…"
        @keydown.enter.exact.prevent="send"
        @input="autoGrow"
      ></textarea>
      <div class="coach-chat__tools">
        <button
          type="button"
          class="coach-chat__mic"
          :class="{ active: recording }"
          aria-label="按住说话"
          @pointerdown="startSpeech"
          @pointerup="stopSpeech"
          @pointerleave="stopSpeech"
        >
          {{ recording ? '松开发送' : '🎤' }}
        </button>
        <button type="button" class="coach-chat__send" :disabled="!draft.trim() || busy" @click="send">
          发送
        </button>
      </div>
    </footer>

    <span
      class="coach-chat__resize"
      aria-hidden="true"
      @pointerdown="startPanelResize"
    ></span>
  </section>
</template>

<script setup>
import { ref, shallowRef, computed, onMounted, onUnmounted, nextTick } from 'vue'
import {
  openVoiceAssistantSession,
  beginVoiceCapture,
  endVoiceCapture,
  askVoiceAssistant,
  stopVoiceAssistant,
  closeVoiceAssistant
} from '../services/voiceAssistant'

const props = defineProps({
  scene: { type: String, default: 'core-interaction' },
  aircraft: { type: String, default: 'C919' }
})
defineEmits(['close'])

const messages = ref([])
const draft = ref('')
const typing = ref(false)
const recording = ref(false)
const busy = ref(false)
const thinking = ref(false)
const thinkingElapsed = ref(0)
let thinkingTimer = null
const statusText = ref('正在连接…')
const bodyRef = ref(null)
const inputRef = ref(null)
const panelRef = ref(null)
const panelSize = ref({ width: 304, height: 448 })
const panelPos = ref({ left: null, top: null })

const panelStyle = computed(() => ({
  width: panelSize.value.width + 'px',
  height: panelSize.value.height + 'px',
  left: panelPos.value.left != null ? panelPos.value.left + 'px' : null,
  top: panelPos.value.top != null ? panelPos.value.top + 'px' : null,
  right: panelPos.value.left != null ? 'auto' : null
}))

let panelResizing = false
let panelResizeStart = { x: 0, y: 0, w: 0, h: 0 }
let panelDragging = false
let panelDragStart = { x: 0, y: 0, left: 0, top: 0 }

let typeTimer = null
let typeBuffer = ''
let typeFull = ''
let typeIdx = 0
let transcriptReplaceNext = false

function startThinking() {
  thinking.value = true
  thinkingElapsed.value = 0
  clearInterval(thinkingTimer)
  thinkingTimer = setInterval(() => {
    thinkingElapsed.value += 1
  }, 1000)
}

function stopThinking() {
  thinking.value = false
  clearInterval(thinkingTimer)
  thinkingTimer = null
}

function scrollBottom() {
  nextTick(() => {
    const el = bodyRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function pushMessage(role, text) {
  messages.value.push({ role, text })
  scrollBottom()
}

function typewriter(text) {
  clearTimeout(typeTimer)
  typing.value = true
  typeFull = text
  typeBuffer = ''
  typeIdx = 0
  // 用一条助手气泡承载流式文本
  if (!messages.value.length || messages.value[messages.value.length - 1].role !== 'assistant') {
    messages.value.push({ role: 'assistant', text: '' })
  }
  const holder = messages.value[messages.value.length - 1]
  const step = () => {
    if (typeIdx < typeFull.length) {
      typeIdx += 1
      holder.text = typeFull.slice(0, typeIdx)
      scrollBottom()
      typeTimer = setTimeout(step, 30)
    } else {
      typing.value = false
    }
  }
  step()
}

async function ensureReady() {
  if (busy.value) return true
  const result = await openVoiceAssistantSession({ scene: props.scene, aircraft: props.aircraft })
  if (result.status === 'ready' || result.status === 'pending-integration') {
    statusText.value = '已就绪'
    return true
  }
  statusText.value = '连接失败'
  return false
}

function autoGrow() {
  const el = inputRef.value
  if (!el) return
  // 只在内容超出当前高度时向上自动撑开，绝不回缩，避免覆盖用户的手动拉伸。
  const needed = Math.max(40, el.scrollHeight)
  if (needed > el.clientHeight) {
    el.style.height = Math.min(needed, 120) + 'px'
  }
}

function startPanelResize(e) {
  e.preventDefault()
  if (e.pointerType === 'mouse' && e.button !== 0) return
  const el = panelRef.value
  if (!el) return
  panelResizing = true
  panelResizeStart = {
    x: e.clientX,
    y: e.clientY,
    w: el.offsetWidth,
    h: el.offsetHeight
  }
  window.addEventListener('pointermove', onPanelResizeMove)
  window.addEventListener('pointerup', stopPanelResize)
  window.addEventListener('pointercancel', stopPanelResize)
}

function onPanelResizeMove(e) {
  if (!panelResizing) return
  const el = panelRef.value
  if (!el) return
  const dx = e.clientX - panelResizeStart.x
  const dy = e.clientY - panelResizeStart.y
  let width = Math.min(560, Math.max(240, panelResizeStart.w + dx))
  let height = Math.min(760, Math.max(320, panelResizeStart.h + dy))
  const parent = el.offsetParent
  if (parent) {
    width = Math.min(width, Math.max(240, parent.clientWidth - el.offsetLeft))
    height = Math.min(height, Math.max(320, parent.clientHeight - el.offsetTop))
  }
  panelSize.value.width = width
  panelSize.value.height = height
  nextTick(scrollBottom)
}

function stopPanelResize() {
  panelResizing = false
  window.removeEventListener('pointermove', onPanelResizeMove)
  window.removeEventListener('pointerup', stopPanelResize)
  window.removeEventListener('pointercancel', stopPanelResize)
}

function startPanelDrag(e) {
  e.preventDefault()
  if (e.pointerType === 'mouse' && e.button !== 0) return
  const el = panelRef.value
  if (!el) return
  panelDragging = true
  panelDragStart = {
    x: e.clientX,
    y: e.clientY,
    left: el.offsetLeft,
    top: el.offsetTop
  }
  window.addEventListener('pointermove', onPanelDragMove)
  window.addEventListener('pointerup', stopPanelDrag)
  window.addEventListener('pointercancel', stopPanelDrag)
}

function onPanelDragMove(e) {
  if (!panelDragging) return
  const el = panelRef.value
  if (!el) return
  const dx = e.clientX - panelDragStart.x
  const dy = e.clientY - panelDragStart.y
  let left = panelDragStart.left + dx
  let top = panelDragStart.top + dy
  // 面板必须完整留在 offsetParent 内，否则会被外层 overflow:hidden 裁掉
  const parent = el.offsetParent
  if (parent) {
    left = Math.min(left, parent.clientWidth - el.offsetWidth)
    top = Math.min(top, parent.clientHeight - el.offsetHeight)
  }
  panelPos.value = { left: Math.max(0, left), top: Math.max(0, top) }
}

function stopPanelDrag() {
  panelDragging = false
  window.removeEventListener('pointermove', onPanelDragMove)
  window.removeEventListener('pointerup', stopPanelDrag)
  window.removeEventListener('pointercancel', stopPanelDrag)
}

async function send() {
  const text = draft.value.trim()
  if (!text || busy.value) return
  draft.value = ''
  autoGrow()
  pushMessage('user', text)
  busy.value = true
  statusText.value = '正在回答…'
  startThinking()
  const result = await askVoiceAssistant(text, { scene: props.scene, aircraft: props.aircraft })
  if (result.status !== 'sent') {
    statusText.value = '发送失败'
    busy.value = false
    stopThinking()
    return
  }
  // 答案由 voice-assistant:update 事件流式渲染
}

function startSpeech() {
  if (recording.value) return
  if (busy.value) return
  recording.value = true
  statusText.value = '正在听…'
  transcriptReplaceNext = true
  ensureReady().then((ok) => {
    if (!ok) {
      recording.value = false
      statusText.value = '连接失败'
      return
    }
    beginVoiceCapture()
  })
}

function stopSpeech() {
  if (!recording.value) return
  recording.value = false
  endVoiceCapture()
  statusText.value = '已就绪'
}

function handleUpdate(event) {
  const detail = event.detail || {}
  const kind = detail.kind
  if (kind === 'transcript') {
    if (detail.final) {
      draft.value = detail.text || ''
      autoGrow()
      transcriptReplaceNext = false
    } else if (transcriptReplaceNext) {
      draft.value = detail.text || ''
      autoGrow()
    } else {
      draft.value = detail.text || ''
      autoGrow()
    }
  } else if (kind === 'answer') {
    busy.value = false
    statusText.value = '已就绪'
    stopThinking()
    typewriter(detail.text || '（无文字答案）')
  } else if (kind === 'clarification') {
    busy.value = false
    statusText.value = '已就绪'
    stopThinking()
    pushMessage('assistant', detail.text || '这个问题我没太理解，可以说得更具体些吗？比如具体到某架飞机或部件。')
  } else if (kind === 'error') {
    // 纯文本沟通模式：语音相关错误不弹气泡打扰，仅复位状态，文本答案不受影响。
    busy.value = false
    stopThinking()
  } else if (kind === 'status') {
    statusText.value = detail.text || statusText.value
  } else if (kind === 'ready') {
    statusText.value = detail.text || '已就绪'
  } else if (kind === 'timeout') {
    busy.value = false
    statusText.value = '超时'
    stopThinking()
    pushMessage('assistant', detail.text || '长时间未收到服务器应答，请重试。')
  }
}

onMounted(() => {
  window.addEventListener('voice-assistant:update', handleUpdate)
  ensureReady().then(() => {
    nextTick(() => inputRef.value && inputRef.value.focus())
  })
})

onUnmounted(() => {
  window.removeEventListener('voice-assistant:update', handleUpdate)
  clearTimeout(typeTimer)
  stopThinking()
  stopPanelResize()
  stopPanelDrag()
  stopSpeech()
  stopVoiceAssistant()
  closeVoiceAssistant()
})
</script>

<style scoped>
.coach-chat {
  right: 8.5%;
  top: 13%;
  width: 304px;
  height: 448px;
  /* 覆盖 .glass-panel 的 z-index:8，确保浮在所有场景元素（热点/时间轴/卡片）之上 */
  z-index: 30;
  display: flex;
  flex-direction: column;
  padding: 16px 18px;
  border-radius: 8px;
  overflow: hidden;
}

.coach-chat__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid rgba(105, 232, 247, 0.2);
}

.coach-chat__move {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  cursor: move;
  user-select: none;
  color: rgba(105, 232, 247, 0.85);
  font-size: 16px;
  font-weight: 700;
  border: 1px solid rgba(105, 232, 247, 0.3);
  border-radius: 6px;
  background: rgba(56, 232, 255, 0.08);
}

.coach-chat__move:hover {
  background: rgba(56, 232, 255, 0.18);
}

.coach-chat__move:active {
  cursor: grabbing;
}

.coach-chat__resize {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 16px;
  height: 16px;
  cursor: se-resize;
  z-index: 30;
  background: linear-gradient(
    135deg,
    transparent 0 45%,
    rgba(105, 232, 247, 0.7) 45% 55%,
    transparent 55% 62%,
    rgba(105, 232, 247, 0.7) 62% 72%,
    transparent 72% 80%,
    rgba(105, 232, 247, 0.7) 80% 90%,
    transparent 90%
  );
  border-bottom-right-radius: 8px;
}

.coach-chat__title {
  font-size: 18px;
  font-weight: var(--weight-bold, 700);
  color: var(--accent, #38e8ff);
  text-shadow: 0 0 12px rgba(56, 232, 255, 0.28);
}

.coach-chat__status {
  flex: 1;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
}

.coach-chat__status.is-busy {
  color: var(--accent, #38e8ff);
}

.coach-chat__close {
  position: relative;
  font-size: 12px;
  color: #effcff;
  background: rgba(56, 232, 255, 0.14);
  border: 1px solid var(--accent-border, rgba(105, 232, 247, 0.34));
  border-radius: 999px;
  padding: 4px 10px;
}

.coach-chat__body {
  flex: 1;
  overflow-y: auto;
  margin: 12px 0 10px;
  padding-right: 4px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.coach-chat__hint {
  font-size: 12px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.5);
}

.coach-chat__msg {
  display: flex;
}

.coach-chat__msg.is-user {
  justify-content: flex-end;
}

.coach-chat__bubble {
  max-width: 82%;
  padding: 7px 11px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
  background: rgba(56, 232, 255, 0.1);
  border: 1px solid rgba(105, 232, 247, 0.28);
}

.coach-chat__msg.is-user .coach-chat__bubble {
  background: rgba(56, 232, 255, 0.22);
}

.coach-chat__bubble span {
  display: block;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  margin-bottom: 3px;
}

.coach-chat__text {
  margin: 0;
  color: #effcff;
}

.coach-chat__cursor {
  color: var(--accent, #38e8ff);
  font-size: 14px;
  animation: blink 0.8s steps(1) infinite;
}

.coach-chat__think {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.coach-chat__think em {
  font-style: normal;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
}

.coach-chat__dots {
  display: inline-flex;
  gap: 3px;
}

.coach-chat__dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent, #38e8ff);
  animation: dot-blink 1.2s ease-in-out infinite;
}

.coach-chat__dots i:nth-child(2) {
  animation-delay: 0.2s;
}

.coach-chat__dots i:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes dot-blink {
  0%,
  60%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.coach-chat__input {
  border-top: 1px solid rgba(105, 232, 247, 0.2);
  padding-top: 10px;
}

.coach-chat__textarea {
  width: 100%;
  height: 40px;
  min-height: 40px;
  max-height: 240px;
  resize: vertical;
  overflow-y: auto;
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(105, 232, 247, 0.28);
  border-radius: 6px;
  color: #effcff;
  font-size: 13px;
  line-height: 1.5;
  padding: 7px 9px;
  font-family: inherit;
  cursor: text;
}

.coach-chat__textarea::-webkit-resizer {
  background: linear-gradient(
    135deg,
    transparent 0 45%,
    rgba(105, 232, 247, 0.6) 45% 55%,
    transparent 55% 62%,
    rgba(105, 232, 247, 0.6) 62% 72%,
    transparent 72% 80%,
    rgba(105, 232, 247, 0.6) 80% 90%,
    transparent 90%
  );
  border-radius: 0 0 6px 0;
}

.coach-chat__textarea:focus {
  outline: none;
  border-color: var(--accent, #38e8ff);
}

.coach-chat__tools {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.coach-chat__mic,
.coach-chat__send {
  flex: 1;
  padding: 7px 0;
  font-size: 13px;
  color: #effcff;
  border-radius: 6px;
  border: 1px solid rgba(105, 232, 247, 0.34);
  background: rgba(56, 232, 255, 0.14);
}

.coach-chat__mic.active {
  background: rgba(255, 80, 80, 0.3);
  border-color: rgba(255, 120, 120, 0.5);
}

.coach-chat__send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
