const DEFAULT_WS_URL = 'ws://127.0.0.1:8765'
const MAX_RECONNECT_ATTEMPTS = 3
const RECONNECT_BASE_DELAY_MS = 1000
const RECONNECT_MAX_DELAY_MS = 8000
const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/

const SERVER_EVENTS = new Set([
  'voice.state',
  'asr.partial',
  'asr.final',
  'clarification.required',
  'answer.display',
  'barge_in.accepted',
  'voice.error'
])

export class VoiceWsClient {
  constructor(options = {}) {
    this.wsUrl = options.wsUrl || import.meta.env.VITE_VOICE_WS_URL || DEFAULT_WS_URL
    this.socket = null
    this.sessionIds = null
    this.sequenceCounter = 0
    this.audioEnded = false
    this.turnUsed = false
    this.intentionalClose = false
    this.reconnectAttempts = 0
    this.reconnectTimer = null
    this.onEvent = options.onEvent || null
    this.onBinary = options.onBinary || null
    this.onStatus = options.onStatus || null
    this.onClose = options.onClose || null
  }

  get isOpen() {
    return Boolean(this.socket && this.socket.readyState === WebSocket.OPEN)
  }

  newId(prefix) {
    return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
  }

  emitStatus(text) {
    if (this.onStatus) this.onStatus(text)
  }

  emitClose(code, reason) {
    if (this.onClose) this.onClose(code, reason)
  }

  buildSceneState(scene = {}) {
    const state = {
      scene_state_id: this.newId('scene'),
      aircraft_id: scene.aircraft || scene.aircraft_id || null,
      component_id: scene.component_id || null,
      hotspot_label: scene.hotspot_label || null,
      camera_view: scene.scene || scene.camera_view || null,
      visual_refs: [],
      scene_confidence: 0,
      selected_object_id: scene.selected_object_id || null,
      candidate_object_ids: []
    }
    return state
  }

  connect(payload = {}) {
    if (this.isOpen) return
    this.sessionIds = {
      session_id: this.newId('web_sess'),
      turn_id: this.newId('web_turn'),
      audio_stream_id: this.newId('web_stream')
    }
    this.sequenceCounter = 0
    this.audioEnded = false
    this.turnUsed = false
    this.intentionalClose = false
    this.openSocket(payload)
  }

  beginNewTurn(payload = {}) {
    // 长连接多轮：复用 session_id（保留会话记忆），换新 turn_id 重开一轮，
    // 并复位 audioEnded，否则后续音频帧会被 sendAudioFrame 静默丢弃。
    if (!this.isOpen || !this.sessionIds) return
    this.sessionIds = {
      session_id: this.sessionIds.session_id,
      turn_id: this.newId('web_turn'),
      audio_stream_id: this.newId('web_stream')
    }
    this.sequenceCounter = 0
    this.audioEnded = false
    this.turnUsed = false
    this.sendSessionStart(payload)
  }

  openSocket(payload) {
    let socket
    try {
      socket = new WebSocket(this.wsUrl)
    } catch (err) {
      this.emitStatus('无法创建 WebSocket 连接：' + err.message)
      this.emitClose(-1, err.message)
      return
    }
    socket.binaryType = 'arraybuffer'
    this.socket = socket

    socket.onopen = () => {
      this.reconnectAttempts = 0
      this.emitStatus('已连接，会话建立中…')
      this.sendSessionStart(payload)
    }

    socket.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        let event
        try {
          event = JSON.parse(ev.data)
        } catch (e) {
          return
        }
        if (event.type === 'tts.frame') {
          if (this.onEvent) this.onEvent(event)
          return
        }
        if (SERVER_EVENTS.has(event.type)) {
          if (this.onEvent) this.onEvent(event)
        }
      } else if (ev.data instanceof ArrayBuffer) {
        if (this.onBinary) this.onBinary(ev.data)
      } else if (ev.data && typeof ev.data.arrayBuffer === 'function') {
        ev.data.arrayBuffer().then((buf) => {
          if (this.onBinary) this.onBinary(buf)
        }).catch(() => {})
      }
    }

    socket.onerror = () => {
      this.emitStatus('连接错误')
    }

    socket.onclose = (ev) => {
      if (this.socket === socket) this.socket = null
      this.emitClose(ev.code, ev.reason)
      if (this.intentionalClose) {
        this.emitStatus('已断开')
        return
      }
      const code = ev.code || 0
      if (code === 4400) {
        this.emitStatus('协议错误（4400）：请检查帧格式后重新唤醒')
        return
      }
      if (code === 4401) {
        this.emitStatus('会话冲突（4401）：正在重建会话…')
        this.sessionIds = {
          session_id: this.newId('web_sess'),
          turn_id: this.newId('web_turn'),
          audio_stream_id: this.newId('web_stream')
        }
        this.sequenceCounter = 0
        this.audioEnded = false
        this.turnUsed = false
        this.openSocket(payload)
        return
      }
      if (code === 4408) {
        this.emitStatus('会话超时（4408）：请重新唤醒')
        return
      }
      this.scheduleReconnect(payload)
    }
  }

  sendSessionStart(payload = {}) {
    if (!this.isOpen || !this.sessionIds) return
    this.socket.send(JSON.stringify({
      type: 'session.start',
      session_id: this.sessionIds.session_id,
      turn_id: this.sessionIds.turn_id,
      audio_stream_id: this.sessionIds.audio_stream_id
    }))
    const sceneState = this.buildSceneState(payload.scene)
    if (sceneState.aircraft_id || sceneState.selected_object_id || payload.scene) {
      this.sendSceneUpdate(sceneState)
    }
  }

  sendSceneUpdate(sceneState) {
    if (!this.isOpen || !this.sessionIds) return
    const state = sceneState || this.buildSceneState()
    this.socket.send(JSON.stringify({
      type: 'scene.update',
      session_id: this.sessionIds.session_id,
      turn_id: this.sessionIds.turn_id,
      audio_stream_id: this.sessionIds.audio_stream_id,
      scene_state: state
    }))
  }

  sendAudioFrame(header, pcmBuffer) {
    if (!this.isOpen || this.audioEnded) return
    this.socket.send(JSON.stringify(header))
    this.socket.send(pcmBuffer)
  }

  buildAudioHeader({ sequence, sampleRate, channels, timestampMs, energy }) {
    return {
      type: 'audio.frame',
      session_id: this.sessionIds.session_id,
      turn_id: this.sessionIds.turn_id,
      audio_stream_id: this.sessionIds.audio_stream_id,
      sequence,
      sample_rate: sampleRate,
      channels,
      timestamp_ms: timestampMs,
      energy
    }
  }

  nextSequence() {
    const seq = this.sequenceCounter
    this.sequenceCounter += 1
    return seq
  }

  sendAudioEnd() {
    if (!this.isOpen || this.audioEnded) return
    this.audioEnded = true
    this.turnUsed = true
    this.socket.send(JSON.stringify({
      type: 'audio.end',
      session_id: this.sessionIds.session_id,
      turn_id: this.sessionIds.turn_id,
      audio_stream_id: this.sessionIds.audio_stream_id
    }))
  }

  sendTextPrompt(text) {
    if (!this.isOpen || !this.sessionIds) return false
    this.socket.send(JSON.stringify({
      type: 'query.text',
      session_id: this.sessionIds.session_id,
      turn_id: this.sessionIds.turn_id,
      audio_stream_id: this.sessionIds.audio_stream_id,
      text: String(text)
    }))
    this.turnUsed = true
    return true
  }

  sendCancel(feedback = '停止') {
    if (!this.isOpen || !this.sessionIds) return false
    this.socket.send(JSON.stringify({
      type: 'session.cancel',
      session_id: this.sessionIds.session_id,
      turn_id: this.sessionIds.turn_id,
      audio_stream_id: this.sessionIds.audio_stream_id,
      feedback
    }))
    return true
  }

  scheduleReconnect(payload) {
    if (this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this.emitStatus('多次重连失败，请确认语音服务已启动后重试')
      return
    }
    const delay = Math.min(
      RECONNECT_BASE_DELAY_MS * 2 ** this.reconnectAttempts,
      RECONNECT_MAX_DELAY_MS
    )
    this.reconnectAttempts += 1
    this.emitStatus(`连接断开，${Math.round(delay / 1000)} 秒后第 ${this.reconnectAttempts} 次重连…`)
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = setTimeout(() => {
      if (!this.isOpen) this.openSocket(payload)
    }, delay)
  }

  close(code = 1000, reason = 'turn_done') {
    this.intentionalClose = true
    clearTimeout(this.reconnectTimer)
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      try {
        this.socket.close(code, reason)
      } catch (e) {
        /* ignore */
      }
    }
    this.socket = null
  }

  destroy() {
    this.close(1000, 'destroy')
  }
}
