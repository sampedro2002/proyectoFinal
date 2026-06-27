/** Sonidos de retroalimentación generados con WebAudio (sin assets externos). */
let ctx;
function audio() {
  if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
  // En navegadores modernos el AudioContext arranca "suspended" hasta que hay un
  // gesto del usuario; intentar resume() aquí (silencioso si ya está running).
  if (ctx.state === 'suspended') {
    ctx.resume().catch(() => {});
  }
  return ctx;
}

function beep(freq, duration, type = 'sine', when = 0) {
  const ac = audio();
  const osc = ac.createOscillator();
  const gain = ac.createGain();
  osc.type = type;
  osc.frequency.value = freq;
  gain.gain.setValueAtTime(0.0001, ac.currentTime + when);
  gain.gain.exponentialRampToValueAtTime(0.3, ac.currentTime + when + 0.01);
  gain.gain.exponentialRampToValueAtTime(0.0001, ac.currentTime + when + duration);
  osc.connect(gain).connect(ac.destination);
  osc.start(ac.currentTime + when);
  osc.stop(ac.currentTime + when + duration);
}

export function playSuccess() {
  beep(880, 0.12, 'sine', 0);
  beep(1320, 0.18, 'sine', 0.12);
}

export function playError() {
  beep(220, 0.35, 'square', 0);
}
