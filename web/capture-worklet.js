// マイク音声を約43msごとにまとめてメインスレッドへ送る
class Capture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buf = new Float32Array(2048);
    this.len = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch && ch.length) {
      if (this.len + ch.length > this.buf.length) {
        this.port.postMessage(this.buf.slice(0, this.len));
        this.len = 0;
      }
      this.buf.set(ch, this.len);
      this.len += ch.length;
      if (this.len >= this.buf.length) {
        this.port.postMessage(this.buf.slice(0, this.len));
        this.len = 0;
      }
    }
    return true;
  }
}
registerProcessor("capture", Capture);
