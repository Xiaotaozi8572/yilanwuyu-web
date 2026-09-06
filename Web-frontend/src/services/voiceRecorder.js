const SAMPLE_RATE = 16000
const CHANNELS = 1
const FRAME_MS = 20
const VAD_THRESHOLD = 0.2
const END_SILENCE_MS = 600

export class VoiceRecorder {
  constructor({ onFrame, onStatus } = {}) {
    this.onFrame = onFrame || null
    this.onStatus = onStatus || null
    this.audioContext = null
    this.micStream = null
    this.workletNode = null
    this.capturing = false
    this.vadActive = false
    this.vadSilenceMs = 0
  }

  get isCapturing() {
    return this.capturing
  }

  static async checkSupport() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error('当前浏览器不支持麦克风采集（需安全上下文，本页使用 127.0.0.1）')
    }
    if (!window.AudioContext || !window.AudioWorkletNode) {
      throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome / Edge')
    }
  }

  async start() {
    await VoiceRecorder.checkSupport()
    try {
      this.micStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: CHANNELS,
          echoCancellation: true,
          noiseSuppression: true
        }
      })
    } catch (err) {
      if (err && (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError')) {
        throw new Error('麦克风权限被拒绝：请在浏览器地址栏左侧的权限图标中允许麦克风，然后重试。')
      } else if (err && err.name === 'NotFoundError') {
        throw new Error('未检测到麦克风设备，请连接麦克风后重试。')
      }
      throw err
    }
    // 不强制 16kHz：Windows/Chromium 上 16kHz AudioContext 接 48kHz 麦克风流
    // 会导致输入静音（图内采样率不匹配），改用默认采样率，
    // 由 pcm-processor.js 的 pushResampled 重采样到 16kHz。
    this.audioContext = new AudioContext()
    if (this.audioContext.state === 'suspended') {
      try {
        await this.audioContext.resume()
      } catch (e) {
        /* 由后续 play 触发恢复 */
      }
    }
    // 缓存穿透：AudioWorklet 模块按 URL 缓存且无法被 Vite HMR 更新，
    // 加时间戳保证每次录音都加载磁盘上的最新版本（修复后无需整页刷新即生效）。
    const moduleUrl = new URL(`/pcm-processor.js?t=${Date.now()}`, window.location.origin).href
    let workletModule
    try {
      workletModule = await this.audioContext.audioWorklet.addModule(moduleUrl)
    } catch (err) {
      throw new Error(
        '音频处理模块加载失败（AudioWorklet）：' + (err && err.message ? err.message : err)
      )
    }
    if (!workletModule && !window.AudioWorkletNode) {
      throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome / Edge')
    }
    this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm-frame-processor', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      processorOptions: { targetSampleRate: SAMPLE_RATE, frameDurationMs: FRAME_MS }
    })
    this.workletNode.port.onmessage = (msg) => this.handlePcmFrame(msg.data)
    const source = this.audioContext.createMediaStreamSource(this.micStream)
    source.connect(this.workletNode)
    // 输出保持静音（process 不写输出缓冲），仅用于驱动音频图调度：
    // 不连 destination 时部分 Chromium 版本不会调用 process()，导致零帧。
    this.workletNode.connect(this.audioContext.destination)
    this.capturing = true
    this.vadActive = false
    this.vadSilenceMs = 0
    if (this.audioContext.sampleRate !== SAMPLE_RATE && this.onStatus) {
      this.onStatus(`浏览器未采纳 16kHz 采样（实际 ${this.audioContext.sampleRate}Hz），已由 AudioWorklet 内部重采样。`)
    }
  }

  handlePcmFrame(msg) {
    if (!this.capturing) return
    if (msg.energy >= VAD_THRESHOLD) {
      this.vadActive = true
      this.vadSilenceMs = 0
    } else if (this.vadActive) {
      this.vadSilenceMs += FRAME_MS
    }
    if (this.onFrame) {
      const endRequested = this.vadActive && this.vadSilenceMs >= END_SILENCE_MS
      this.onFrame({
        buffer: msg.buffer,
        timestampMs: msg.timestampMs,
        energy: msg.energy,
        endRequested
      })
    }
  }

  stop() {
    this.capturing = false
    if (this.workletNode) {
      try {
        this.workletNode.port.close()
      } catch (e) {
        /* ignore */
      }
      try {
        this.workletNode.disconnect()
      } catch (e) {
        /* ignore */
      }
      this.workletNode = null
    }
    if (this.micStream) {
      this.micStream.getTracks().forEach((t) => {
        try {
          t.stop()
        } catch (e) {
          /* ignore */
        }
      })
      this.micStream = null
    }
    if (this.audioContext) {
      try {
        if (this.audioContext.state !== 'closed') this.audioContext.close()
      } catch (e) {
        /* ignore */
      }
      this.audioContext = null
    }
  }
}
