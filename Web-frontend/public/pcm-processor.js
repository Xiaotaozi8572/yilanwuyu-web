/**
 * T9 · 翼览无余 语音前端 —— AudioWorklet 处理器（pcm-frame-processor）
 *
 * 职责（与 src/voice/websocket_server.py 二步成帧协议对齐）：
 *   1. 采集 mono 输入（getUserMedia channelCount:1 已约束，若浏览器多声道则只取第 0 声道）。
 *   2. 若 AudioContext 实际采样率不是 16000（浏览器未采纳 sampleRate:16000 约束），
 *      在 worklet 内做线性平均重采样到 16000；已是 16000 则直通（免重采样）。
 *   3. 按 20ms 帧切分（16kHz 下 320 样本），转换为 Int16Array（16-bit PCM）。
 *   4. 每帧 postMessage 到主线程：{type:"pcm_frame", buffer, timestampMs, energy}。
 *
 * 注意：本项目严禁使用已废弃的 ScriptProcessorNode，仅使用 AudioWorklet。
 * 能量为 RMS 归一化（0..1），与服务器 VAD 的 energy 字段（0..1）一致。
 */

class PCMFrameProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = (options && options.processorOptions) || {};
    this.targetSampleRate = Number(opts.targetSampleRate) || 16000;
    const frameMs = Number(opts.frameDurationMs) || 20;
    this.frameSamples = Math.round((this.targetSampleRate * frameMs) / 1000); // 320
    this.accumulator = []; // 已按目标采样率的 Float32 样本
    this.resampleAcc = 0; // 重采样累加值
    this.resampleCount = 0; // 重采样计数
  }

  process(inputs) {
    const input = inputs && inputs[0];
    const channel = input && input[0];
    if (!channel || channel.length === 0) {
      return true; // 静默帧：不发送（服务器拒绝空 payload）
    }
    // 注意：sampleRate 是 AudioWorkletGlobalContext 的全局属性（同 currentTime），
    // 不能写成 this.sampleRate（undefined → ratio=NaN → 永不出帧）。
    if (sampleRate === this.targetSampleRate) {
      for (let i = 0; i < channel.length; i += 1) {
        this.accumulator.push(channel[i]);
      }
    } else {
      this.pushResampled(channel);
    }
    this.emitFrames();
    return true;
  }

  /**
   * 线性平均重采样：每 ratio 个输入样本平均出 1 个目标样本。
   * ratio = 实际采样率 / 16000（如 48000/16000 = 3）。
   */
  pushResampled(channel) {
    const ratio = sampleRate / this.targetSampleRate;
    for (let i = 0; i < channel.length; i += 1) {
      this.resampleAcc += channel[i];
      this.resampleCount += 1;
      if (this.resampleCount >= ratio) {
        this.accumulator.push(this.resampleAcc / this.resampleCount);
        this.resampleAcc = 0;
        this.resampleCount = 0;
      }
    }
  }

  /** 切出完整帧 → Int16Array → 传输到主线程（帧头字段由主线程按协议组装）。 */
  emitFrames() {
    const frameSamples = this.frameSamples;
    while (this.accumulator.length >= frameSamples) {
      const chunk = this.accumulator.splice(0, frameSamples);
      const int16 = new Int16Array(frameSamples);
      let sumSq = 0;
      for (let i = 0; i < frameSamples; i += 1) {
        const sample = chunk[i];
        sumSq += sample * sample;
        let value = Math.round(sample * 32768);
        value = Math.max(-32768, Math.min(32767, value));
        int16[i] = value;
      }
      const rms = Math.sqrt(sumSq / frameSamples);
      const energy = Math.min(1, rms); // 0..1，与服务器 energy 校验一致
      const timestampMs = Math.round(currentTime * 1000);
      this.port.postMessage(
        {
          type: "pcm_frame",
          buffer: int16.buffer,
          timestampMs: timestampMs,
          energy: energy,
        },
        [int16.buffer] // 转移所有权，避免拷贝
      );
    }
  }
}

registerProcessor("pcm-frame-processor", PCMFrameProcessor);
