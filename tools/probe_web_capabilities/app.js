// 能力探测：只用短键与 1/0，结果塞进 document.title（宿主报告会原样带回）。
var R = {};
function b(k, v) { R[k] = v ? 1 : 0; }

b('c2d', !!document.createElement('canvas').getContext('2d'));
b('gl', !!(document.createElement('canvas').getContext('webgl') || document.createElement('canvas').getContext('experimental-webgl')));
b('gl2', !!document.createElement('canvas').getContext('webgl2'));
b('svg', typeof SVGSVGElement !== 'undefined');
b('audio', typeof AudioContext !== 'undefined' || typeof webkitAudioContext !== 'undefined');
b('ptr', 'PointerEvent' in window);
b('raf', typeof requestAnimationFrame === 'function');
b('blob', typeof Blob !== 'undefined');
b('ourl', !!(window.URL && URL.createObjectURL));
b('subtle', !!(window.crypto && crypto.subtle));
b('idb', typeof indexedDB !== 'undefined');
b('fface', typeof FontFace !== 'undefined');
b('ws', typeof WebSocket !== 'undefined');
b('es', typeof EventSource !== 'undefined');
b('workerctor', typeof Worker !== 'undefined');
b('wasm', typeof WebAssembly === 'object');
b('secure', window.isSecureContext);
b('geo', !!navigator.geolocation);
b('cam', !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia));
b('vib', typeof navigator.vibrate === 'function');
b('clip', !!navigator.clipboard);
b('speech', typeof speechSynthesis !== 'undefined');
b('notif', typeof Notification !== 'undefined');
b('share', typeof navigator.share === 'function');
b('fsapi', typeof window.showOpenFilePicker === 'function');
b('dnd', 'ondragstart' in window);
b('touch', 'ontouchstart' in window);
b('lang', navigator.language);
b('dpr', window.devicePixelRatio);
R['origin'] = location.origin;

try { localStorage.setItem('probe', '1'); R['ls'] = localStorage.getItem('probe') === '1' ? 1 : 0; localStorage.removeItem('probe'); }
catch (e) { R['ls'] = 'ERR:' + e.name; }
try { sessionStorage.setItem('probe', '1'); R['ss'] = 1; sessionStorage.removeItem('probe'); }
catch (e) { R['ss'] = 'ERR:' + e.name; }

document.title = JSON.stringify(R);
document.getElementById('out').textContent = JSON.stringify(R);

// 异步部分：完成后覆盖 title。宿主在 ready 后约 500ms 读取报告。
function settle() { document.title = JSON.stringify(R); document.getElementById('out').textContent = JSON.stringify(R); }

try {
  var mod = new WebAssembly.Module(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]));
  R['wasmRun'] = 1;
} catch (e) { R['wasmRun'] = 'ERR:' + e.name; }

try {
  var src = 'self.onmessage=function(){self.postMessage(1)}';
  var w = new Worker(URL.createObjectURL(new Blob([src], { type: 'application/javascript' })));
  w.onmessage = function () { R['workerRun'] = 1; settle(); };
  w.onerror = function () { R['workerRun'] = 'ERR:onerror'; settle(); };
  setTimeout(function () { if (R['workerRun'] === undefined) { R['workerRun'] = 'ERR:silent'; settle(); } }, 400);
} catch (e) { R['workerRun'] = 'ERR:' + e.name; }

fetch('data.json').then(function (r) { return r.text(); })
  .then(function (t) { R['fetchSelf'] = t.indexOf('world') >= 0 ? 1 : ('RAW:' + t.slice(0, 12)); settle(); })
  .catch(function (e) { R['fetchSelf'] = 'ERR:' + e.name; settle(); });

fetch('https://example.com/').then(function () { R['fetchExt'] = 'OK(未拦截)'; settle(); })
  .catch(function (e) { R['fetchExt'] = 'ERR:' + e.name; settle(); });

settle();
