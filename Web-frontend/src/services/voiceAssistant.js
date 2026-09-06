import { VoiceWsClient } from './voiceWsClient'
import { VoiceRecorder } from './voiceRecorder'

const configuredWsUrl = import.meta.env.VITE_VOICE_WS_URL
const assistantEndpoint = import.meta.env.VITE_VOICE_ASSISTANT_ENDPOINT || ''
const wsUrl = configuredWsUrl || assistantEndpoint || 'ws://127.0.0.1:8765'
const voiceDisabled =
  import.meta.env.VITE_VOICE_DISABLE !== undefined && import.meta.env.VITE_VOICE_DISABLE !== ''
const hasConfig = !voiceDisabled

const ANSWER_TIMEOUT_MS = 180000
const CONNECT_WAIT_MS = 3000
const CONNECT_POLL_MS = 100
const SAMPLE_RATE = 16000
const CHANNELS = 1

const REASON_LABELS = {
  no_speech_detected: '没有听清，请靠近麦克风再试一次',
  voice_input_unclear: '语音不够清晰',
  asr_provider_unavailable: '识别服务不可用',
  asr_final_missing: '未能完成识别',
  asr_processing_failed: '识别处理失败',
  utterance_too_long: '说的内容太长，请换个更短的问法',
  pipeline_processing_failed: '回答问题失败，请换个问法',
  pipeline_response_unavailable: '回答暂时不可用，请稍后再试',
  default: '请说得更完整一些'
}

// 澄清/无答案提示：不再使用"需要澄清"式表述指责用户问法，
// 按原因给出自然、有用的回复。
const NO_DATA_TEXT = '这个问题我暂时没有相关资料。可以问我 C919、运-20、直-20、歼-20 等机型知识。'
const CLARIFY_TEXTS = {
  no_speech_detected: '没有听清，请靠近麦克风再说一次。',
  voice_input_unclear: '没太听清，可以再大声一点说一遍吗？',
  asr_provider_unavailable: '语音识别服务暂时不可用，请改用文字提问。',
  asr_final_missing: '没有听到完整的问题，请再说一次。',
  asr_processing_failed: '语音识别出了点问题，请再试一次。',
  utterance_too_long: '说的内容有点长，可以精简后再试。',
  pipeline_processing_failed: '这个问题我暂时没能处理好，换个问法试试。',
  pipeline_response_unavailable: NO_DATA_TEXT,
  missing_scene_object: NO_DATA_TEXT,
  no_relevant_evidence: NO_DATA_TEXT,
  formal_retrieval_gate_rejected: NO_DATA_TEXT,
  ambiguous_scene_reference: '想问的是哪架飞机？比如 C919、运-20、直-20 或歼-20。',
  default: NO_DATA_TEXT
}

function placeholderResult() {
  return {
    status: 'pending-integration',
    message: '我在这里，后续接入语音问答服务后可以继续对话。'
  }
}

function extractAnswerText(answer) {
  if (!answer || typeof answer !== 'object') return ''
  return answer.main_answer || answer.short_answer || ''
}

function buildSceneState(payload) {
  return {
    aircraft: payload.aircraft || payload.aircraft_id,
    scene: payload.scene || 'core-interaction',
    component_id: payload.component_id,
    selected_object_id: payload.selected_object_id
  }
}

function dispatchAssistantEvent(kind, data) {
  window.dispatchEvent(
    new CustomEvent('voice-assistant:update', { detail: { kind, ...data } })
  )
}

const activeSession = {
  client: null,
  recorder: null,
  payload: null,
  options: null,
  lastAnswerText: '',
  noAnswerTimer: null,
  pendingTextQuestion: false,
  answerTimer: null,
  captureMicStarted: false,
  capturePendingText: ''
}

function clearAnswerWatchdog(session) {
  clearTimeout(session.answerTimer)
  session.answerTimer = null
  session.pendingTextQuestion = false
}

function stopActiveSession(reason) {
  const session = activeSession
  clearTimeout(session.noAnswerTimer)
  clearTimeout(session.answerTimer)
  session.pendingTextQuestion = false
  if (session.recorder) session.recorder.stop()
  if (session.client) session.client.close(1000, reason || 'turn_done')
  session.client = null
  session.recorder = null
  session.captureMicStarted = false
}

export function stopVoiceAssistant() {
  const session = activeSession
  if (session.client && session.client.isOpen) {
    session.client.sendCancel('停止')
  }
}

export function openVoiceAssistantSession(payload = {}, options = {}) {
  if (!hasConfig) {
    return Promise.resolve(placeholderResult())
  }
  const session = activeSession
  session.payload = payload
  session.options = options
  session.lastAnswerText = ''
  session.capturePendingText = ''

  if (session.client && session.client.isOpen) {
    return Promise.resolve({
      status: 'ready',
message: 'AI 助手已就绪，请直接说或输入你的问题。'
    })
  }

  const client = new VoiceWsClient({
    wsUrl: wsUrl || assistantEndpoint,
    onStatus: (text) => {
      dispatchAssistantEvent('status', { text })
      if (session.options && session.options.onStatus) session.options.onStatus(text)
    },
    onEvent: (event) => {
      if (session.options && session.options.onEvent) session.options.onEvent(event)
      handleSessionEvent(event)
    },
    onClose: (code) => {
      // 纯文本沟通模式：连接断开不打断用户，下次发送文本时会自动重连。
      clearTimeout(session.noAnswerTimer)
      clearTimeout(session.answerTimer)
      if (session.pendingTextQuestion) {
        // 回答未返回时连接断开：派发 timeout 让面板复位 busy。
        session.pendingTextQuestion = false
        dispatchAssistantEvent('timeout', {
          text: '连接中断，未能收到回答，请重试。'
        })
      }
      if (session.recorder) session.recorder.stop()
      session.captureMicStarted = false
    }
  })
  session.client = client
  client.connect({ scene: buildSceneState(payload) })

  return Promise.resolve({
    status: 'ready',
    message: 'AI 语音老师已就绪，请直接说或输入你的问题。'
  })
}

function handleSessionEvent(event) {
  const session = activeSession
  const type = event.type
  if (type === 'voice.state') {
    if (session.options && session.options.onVoiceState) session.options.onVoiceState(event)
    if (event.status === 'session.started' || event.status === 'scene.updated') {
      dispatchAssistantEvent('ready', { text: 'AI 助手已就绪，请直接说或输入你的问题。' })
    }
    if (event.status === 'audio.input_ended') {
      if (session.recorder) session.recorder.stop()
      dispatchAssistantEvent('status', { text: '正在识别，请稍候…' })
    }
  } else if (type === 'asr.partial' || type === 'asr.final') {
    const text = event.transcript || ''
    const isFinal = type === 'asr.final'
    if (isFinal && session.recorder) session.recorder.stop()
    session.capturePendingText = text
    dispatchAssistantEvent('transcript', { text, final: isFinal })
    if (session.options && session.options.onTranscript) {
      session.options.onTranscript({ text, confidence: event.confidence || 0, final: isFinal })
    }
  } else if (type === 'answer.display') {
    clearTimeout(session.noAnswerTimer)
    clearAnswerWatchdog(session)
    session.lastAnswerText = extractAnswerText(event.answer) || ''
    dispatchAssistantEvent('answer', {
      text: session.lastAnswerText,
      answer_id: event.answer_id,
      evidence_package_id: event.evidence_package_id
    })
    if (session.options && session.options.onAnswer) {
      session.options.onAnswer({
        text: session.lastAnswerText,
        answer_id: event.answer_id,
        evidence_package_id: event.evidence_package_id
      })
    }
  } else if (type === 'clarification.required') {
    clearAnswerWatchdog(session)
    const reason = event.reason
    dispatchAssistantEvent('clarification', {
      text: CLARIFY_TEXTS[reason] || CLARIFY_TEXTS.default
    })
  } else if (type === 'voice.error') {
    // 已有文字答案后到达的语音/TTS 错误在纯文本模式下忽略。
    // 尚无答案且错误码与文本/识别链路相关时给出可读提示并复位。
    if (session.pendingTextQuestion) {
      clearAnswerWatchdog(session)
      const reason = REASON_LABELS[event.code] || REASON_LABELS.default
      dispatchAssistantEvent('error', {
        text: '回答失败：' + reason + '，请换个问法再试。'
      })
      return
    }
    const asrRelated = ['asr_provider_unavailable', 'asr_processing_failed', 'asr_final_missing']
    if (!session.lastAnswerText && (session.captureMicStarted || session.recorder)) {
      if (asrRelated.includes(event.code)) {
        dispatchAssistantEvent('status', {
          text: '识别服务加载中，请稍后再试，或改用文字提问。'
        })
      }
    }
  }
}

// ---- 语音转文字：按需采集 ----
export async function beginVoiceCapture() {
  const session = activeSession
  if (!hasConfig) {
    dispatchAssistantEvent('error', { text: '语音服务未配置，无法使用语音输入。' })
    return false
  }
  if (!session.client || !session.client.isOpen) {
    await openVoiceAssistantSession(session.payload || {})
  }
  if (!session.client || !session.client.isOpen) {
      dispatchAssistantEvent('error', { text: '连接尚未建立，请稍后再试。' })
      return false
    }

    if (session.client.turnUsed) {
      // 上一轮已用（语音已结束或文字已发出）：换新 turn_id 重开一轮，
      // 否则音频帧会被 audioEnded 拦截、或撞上后端完成轮次校验。
      session.client.beginNewTurn({ scene: buildSceneState(session.payload || {}) })
    }

    if (session.recorder && session.recorder.isCapturing) {
    return true
  }
  const recorder = new VoiceRecorder()
  session.recorder = recorder
  recorder.onFrame = (frame) => {
    const header = session.client.buildAudioHeader({
      sequence: session.client.nextSequence(),
      sampleRate: SAMPLE_RATE,
      channels: CHANNELS,
      timestampMs: frame.timestampMs,
      energy: frame.energy
    })
    session.client.sendAudioFrame(header, frame.buffer)
    if (frame.endRequested && session.recorder === recorder) {
      session.client.sendAudioEnd()
      recorder.stop()
    }
  }
  try {
    await recorder.start()
    dispatchAssistantEvent('status', { text: '请说话…' })
    return true
  } catch (err) {
    session.recorder = null
    const msg = err && err.message ? err.message : '麦克风启动失败，请稍后再试。'
    dispatchAssistantEvent('error', { text: msg })
    return false
  }
}

export function endVoiceCapture() {
  const session = activeSession
  if (session.recorder && session.recorder.isCapturing) {
    if (session.client && session.client.isOpen) session.client.sendAudioEnd()
    session.recorder.stop()
  }
}

async function waitForOpen(client, timeoutMs) {
  const deadline = Date.now() + (timeoutMs || CONNECT_WAIT_MS)
  while (Date.now() < deadline) {
    if (client.isOpen) return true
    await new Promise((resolve) => setTimeout(resolve, CONNECT_POLL_MS))
  }
  return Boolean(client.isOpen)
}

export function askVoiceAssistant(question, payload = {}, options = {}) {
  if (!hasConfig) {
    return Promise.resolve(placeholderResult())
  }
  const text = question == null ? '' : String(question).trim()
  if (!text) {
    return Promise.resolve({ status: 'ok', message: '请输入要问的问题。' })
  }
  const mergedPayload = { ...(activeSession.payload || {}), ...payload }
  return openVoiceAssistantSession(mergedPayload, options).then(async () => {
    const session = activeSession
    // 面板刚打开时连接可能尚未就绪，短暂轮询等待后再决定是否失败。
    if (!session.client) {
      return { status: 'error', message: '连接尚未建立，请稍后再试。' }
    }
    const isOpen = await waitForOpen(session.client, CONNECT_WAIT_MS)
    if (!isOpen) {
      return { status: 'error', message: '连接尚未建立，请稍后再试。' }
    }
    if (session.client.turnUsed) {
      // 上一问已完成：换新 turn_id 重开一轮，避免命中后端
      // "completed voice turn retry" 校验导致断连。
      session.client.beginNewTurn({ scene: buildSceneState(mergedPayload) })
    }
    session.capturePendingText = ''
    const sent = session.client.sendTextPrompt(text)
    if (!sent) {
      return { status: 'error', message: '发送失败，请重试。' }
    }
    clearAnswerWatchdog(session)
    session.pendingTextQuestion = true
    session.answerTimer = setTimeout(() => {
      // 后端真实生成可能较慢（约 3 分钟内），超过则提示用户并复位。
      session.pendingTextQuestion = false
      dispatchAssistantEvent('timeout', {
        text: '长时间未收到服务器应答，请重试。'
      })
    }, ANSWER_TIMEOUT_MS)
    return { status: 'sent', message: '已发送，等待回答…' }
  })
}

export function closeVoiceAssistant() {
  stopActiveSession('panel_close')
}
