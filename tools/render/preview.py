"""Desktop renderer for Web mini apps.

Renders a project directory in headless Chrome at phone metrics and writes a PNG,
optionally clicking through selectors first. This exists because every "does the
page actually look/behave right" question used to cost a device round trip.

Usage:
  python tools/render/preview.py <project_dir> --out shot.png \
      [--assets D:/path/to/assets] [--width 390] [--height 844] [--dpr 3] \
      [--click "#btnDraw1"] [--script "document.title='x'"] [--full]

Notes:
  * The project must not contain a file named __harness.html.
  * Assets referenced as `assets/<name>` are copied from --assets when provided.
"""
from __future__ import annotations

import argparse
import http.server
import re
import shutil
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

HARNESS = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
html,body{{margin:0;padding:0;background:#202020;}}
iframe{{display:block;width:{w}px;height:{h}px;border:0;}}
</style></head><body>
<iframe id="f" src="{entry}"></iframe>
<script>
const frame = document.getElementById('f');
frame.addEventListener('load', () => {{
  const win = frame.contentWindow, doc = frame.contentDocument;
  const fail = (m) => {{ document.title = 'HARNESS_FAIL ' + m; console.error(m); }};
  try {{
    for (const sel of {clicks}) {{
      const el = doc.querySelector(sel);
      if (!el) {{ fail('missing ' + sel); return; }}
      el.click();
    }}
    if ({script!r}) win.eval({script!r});
    document.title = 'HARNESS_OK';
  }} catch (e) {{ fail(String(e)); }}
}});
</script></body></html>
"""

PROBE = """<script>
const out = [];
const push = (k, v) => out.push(k + '\\t' + v);
const vis = (el) => {
  const s = getComputedStyle(el), r = el.getBoundingClientRect();
  return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) > 0.05 && r.width > 0 && r.height > 0;
};
function alpha(c) {
  const m = String(c).match(/rgba?\\(([^)]+)\\)/);
  if (!m) return [0, 0, 0, 0];
  const p = m[1].split(',').map((x) => parseFloat(x));
  return [p[0] || 0, p[1] || 0, p[2] || 0, p.length > 3 ? p[3] : 1];
}
function over(fg, bg) {
  const a = fg[3] + bg[3] * (1 - fg[3]);
  if (a === 0) return [0, 0, 0, 0];
  return [0, 1, 2].map((i) => (fg[i] * fg[3] + bg[i] * bg[3] * (1 - fg[3])) / a).concat([a]);
}
function lum(c) {
  const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
}
function contrast(fg, bg) {
  const l1 = lum(fg), l2 = lum(bg);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}
/* Effective background: composite every translucent ancestor colour, top-down. */
function backdrop(el) {
  const stack = [];
  for (let n = el; n; n = n.parentElement) {
    const s = getComputedStyle(n);
    if (s.backgroundImage && s.backgroundImage !== 'none') return null;
    const c = alpha(s.backgroundColor);
    if (c[3] > 0) stack.push(c);
    if (c[3] >= 0.999) break;
  }
  let base = [0, 0, 0, 0];
  for (let i = stack.length - 1; i >= 0; i--) base = over(stack[i], base);
  if (base[3] < 0.999) base = over(base, [255, 255, 255, 1]);
  return base;
}
const name = (el) => el.tagName.toLowerCase() + (el.id ? '#' + el.id : '')
  + (el.classList && el.classList.length ? '.' + [...el.classList].slice(0, 2).join('.') : '');
const ownText = (el) => Array.from(el.childNodes)
  .filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join(' ').trim();
const checks = [];
for (const el of document.querySelectorAll('body *')) {
  if (!vis(el)) continue;
  if (el.closest('[aria-hidden=true]')) continue;
  const text = ownText(el);
  if (!text) continue;
  const s = getComputedStyle(el);
  const fg = alpha(s.color);
  const bg = backdrop(el.parentElement || document.body);
  if (!bg) continue;
  const eff = over(fg, bg);
  const ratio = contrast(eff, bg);
  const size = parseFloat(s.fontSize);
  const large = size >= 24 || (size >= 18.66 && parseInt(s.fontWeight, 10) >= 700);
  const need = large ? 3 : 4.5;
  checks.push({ sel: name(el), text: text.slice(0, 20), ratio: Math.round(ratio * 100) / 100,
    need, fg: s.color, bg: 'rgb(' + bg.slice(0, 3).map(Math.round).join(',') + ')' });
}
checks.sort((a, b) => a.ratio - b.ratio);
push('LOW_CONTRAST', JSON.stringify(checks.filter((c) => c.ratio < c.need).slice(0, 12)));
push('WORST', JSON.stringify(checks.slice(0, 5)));
push('BODY_BG', getComputedStyle(document.body).backgroundColor);
push('BODY_IMAGE', getComputedStyle(document.body).backgroundImage.slice(0, 120));
push('DIALOGS', JSON.stringify(Array.from(document.querySelectorAll('[role=dialog],dialog,.sheet,.dialog,.modal'))
  .map((el) => name(el) + ' visible=' + vis(el))));
push('SIZE', document.documentElement.scrollWidth + 'x' + document.documentElement.scrollHeight);
document.title = 'PROBE_DONE';
document.body.innerHTML = '<pre>' + out.join('\\n') + '</pre>';
</script>
"""


def find_browser(explicit: str | None) -> str:
    if explicit:
        return explicit
    for path in CHROME_CANDIDATES:
        if Path(path).exists():
            return path
    raise SystemExit("no Chrome/Edge found; pass --browser")


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def stage(project: Path, assets: Path | None, tmp: Path) -> str:
    for item in project.rglob("*"):
        if item.is_file():
            target = tmp / item.relative_to(project)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target)
    manifest = tmp / "manifest.json"
    entry = "index.html"
    if manifest.exists():
        m = re.search(r'"entry"\s*:\s*"([^"]+)"', manifest.read_text(encoding="utf-8"))
        if m:
            entry = m.group(1)
    wanted: set[str] = set()
    for item in tmp.rglob("*"):
        if item.suffix.lower() in {".html", ".css", ".js", ".json"}:
            wanted.update(re.findall(r"assets/([^\"'\s)<>]+)", item.read_text(encoding="utf-8", errors="ignore")))
    copied = 0
    if assets and assets.is_dir():
        (tmp / "assets").mkdir(exist_ok=True)
        for raw in sorted(wanted):
            from urllib.parse import unquote
            name = unquote(raw)
            source = assets / name
            if source.is_file():
                shutil.copy2(source, tmp / "assets" / name)
                copied += 1
    return entry, copied, len(wanted)


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):  # noqa: D102
        pass


def serve(directory: Path, port: int) -> socketserver.TCPServer:
    handler = lambda *a, **kw: Quiet(*a, directory=str(directory), **kw)  # noqa: E731
    httpd = socketserver.TCPServer(("127.0.0.1", port), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def shoot(browser: str, url: str, out: Path, width: int, height: int, dpr: float, timeout: int) -> None:
    args = [
        browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
        "--disable-extensions", "--force-device-scale-factor=" + str(dpr),
        f"--window-size={width},{height}", f"--screenshot={out}", "--virtual-time-budget=2500",
        url,
    ]
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if not out.exists():
        sys.stderr.write(result.stdout + result.stderr)
        raise SystemExit("screenshot failed")


def dump(browser: str, url: str, timeout: int) -> None:
    args = [
        browser, "--headless=new", "--disable-gpu", "--no-first-run", "--disable-extensions",
        "--dump-dom", "--virtual-time-budget=2500", url,
    ]
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    text = result.stdout
    body = text.split("<pre>", 1)[-1].split("</pre>", 1)[0] if "<pre>" in text else text[-2000:]
    print(body.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&amp;", "&"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("project")
    ap.add_argument("--out")
    ap.add_argument("--probe", action="store_true", help="dump contrast / dialog / size instead of shooting")
    ap.add_argument("--assets")
    ap.add_argument("--browser")
    ap.add_argument("--width", type=int, default=390)
    ap.add_argument("--height", type=int, default=844)
    ap.add_argument("--dpr", type=float, default=3.0)
    ap.add_argument("--click", action="append", default=[])
    ap.add_argument("--script", default="")
    ap.add_argument("--timeout", type=int, default=90)
    args = ap.parse_args()

    project = Path(args.project).resolve()
    if not project.is_dir():
        raise SystemExit(f"not a directory: {project}")
    browser = find_browser(args.browser)

    with tempfile.TemporaryDirectory(prefix="mini-preview-") as tmpdir:
        tmp = Path(tmpdir)
        entry, copied, wanted = stage(project, Path(args.assets).resolve() if args.assets else None, tmp)
        print(f"entry={entry} assets={copied}/{wanted}", file=sys.stderr)
        port = free_port()
        serve(tmp, port)
        if args.probe:
            html = (tmp / entry).read_text(encoding="utf-8")
            head, sep, tail = html.rpartition("</body>")
            if not sep:
                head, tail = html, ""
            click = "".join(f"try{{document.querySelector({sel!r}).click()}}catch(e){{}}\n"
                            for sel in args.click)
            (tmp / "__probe.html").write_text(head + click + PROBE + "</body>" + tail, encoding="utf-8")
            dump(browser, f"http://127.0.0.1:{port}/__probe.html", args.timeout)
            return 0
        # 一律套一层固定尺寸的 iframe：headless Chrome 的 --window-size 不等于页面的布局视口
        # （实测 354 的窗口里页面仍按 640 宽排版，截出来右边被切掉），而 iframe 的 width/height
        # 就是内层文档的 CSS 视口，这才是"手机宽度"唯一可靠的做法。
        (tmp / "__harness.html").write_text(
            HARNESS.format(w=args.width, h=args.height, entry=entry,
                           clicks=repr(args.click), script=args.script),
            encoding="utf-8")
        target = "__harness.html"
        out = Path(args.out or (project.name + ".png")).resolve()
        shoot(browser, f"http://127.0.0.1:{port}/{target}", out, args.width, args.height, args.dpr, args.timeout)
        print(f"wrote {out} ({out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
