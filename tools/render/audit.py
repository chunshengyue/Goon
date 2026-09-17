"""Run the app's own audit script against any local HTML page, in headless Chrome.

Why: the same audit runs on device inside WebPreview.verify. To calibrate its thresholds
(and to answer "is this page actually well made?") we need to score real pages, including
ones that were never a mini app. This extracts the live script from WebPreview.kt so the
scoring here cannot drift from what the agent is judged by.

Usage:
  python tools/render/audit.py <page.html> [--width 390] [--height 844] [--json]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from preview import find_browser, free_port, serve  # noqa: E402

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/java/com/example/goon/core/WebPreview.kt"


def audit_script() -> str:
    text = SOURCE.read_text(encoding="utf-8")
    start = text.index('val script = """') + len('val script = """')
    end = text.index('""".trimIndent()', start)
    return text[start:end]


def freeze_script() -> str:
    """同一个 FREEZE 常量，提取出来在审计前注入：离屏/无渲染时动画时间轴不推进，
    不冻结的话审计会把"进场第一帧"当成最终状态，误报一堆"元素看不见"。"""
    text = SOURCE.read_text(encoding="utf-8")
    start = text.index('private const val FREEZE = """') + len('private const val FREEZE = """')
    end = text.index('""".trimIndent()', start) if '""".trimIndent()' in text[start:start + 4000] else text.index('"""', start)
    return text[start:end]


PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
html,body{{margin:0;padding:0}}iframe{{display:block;width:{w}px;height:{h}px;border:0}}
</style></head><body>
<iframe id="f" src="{src}"></iframe>
<pre id="out">pending</pre>
<script>
const freeze = {freeze};
const src = {script};
document.getElementById('f').addEventListener('load', () => {{
  const win = document.getElementById('f').contentWindow;
  try {{ win.eval(freeze); }} catch (e) {{}}
  let result;
  try {{ result = win.eval({audit}); }}
  catch (e) {{ result = JSON.stringify({{ error: String(e) }}); }}
  document.getElementById('out').textContent = result;
}});
</script></body></html>
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("page")
    ap.add_argument("--width", type=int, default=390)
    ap.add_argument("--height", type=int, default=844)
    ap.add_argument("--browser")
    ap.add_argument("--json", action="store_true", help="print the raw report")
    args = ap.parse_args()

    page = Path(args.page).resolve()
    if not page.is_file():
        raise SystemExit(f"not a file: {page}")
    if page.name == "__audit_harness.html":
        raise SystemExit("refusing to audit the harness itself")

    script = audit_script()
    (page.parent / "__audit_harness.html").write_text(
        PAGE.format(w=args.width, h=args.height, src=page.name,
                    freeze=json.dumps(freeze_script()), script=json.dumps(script), audit=json.dumps(script)),
        encoding="utf-8")
    browser = find_browser(args.browser)
    # 必须走 http：file:// 下的 iframe 是不透明来源，外层读不到内层文档。
    port = free_port()
    serve(page.parent, port)
    out = subprocess.run(
        [browser, "--headless=new", "--disable-gpu", "--no-first-run", "--disable-extensions",
         "--dump-dom", "--virtual-time-budget=3000",
         f"http://127.0.0.1:{port}/__audit_harness.html"],
        capture_output=True, timeout=90).stdout.decode("utf-8", errors="replace")
    match = re.search(r'<pre id="out">(.*?)</pre>', out, re.S)
    if not match:
        sys.stderr.write(out[:2000])
        raise SystemExit("audit produced no output (page may be blocked from file://)")
    raw = match.group(1).replace("&quot;", '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    try:
        report = json.loads(raw)
    except json.JSONDecodeError:
        print(raw[:2000])
        return 1
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    print(f"page      : {page}")
    print(f"viewport  : {report.get('layout', {}).get('viewport')}")
    print(f"passed    : {report.get('passed')}  genericTemplate={report.get('genericTemplate')}")
    print(f"problems  : {report.get('layoutProblems')}")
    print(f"contrast  : checked={report.get('contrast', {}).get('checked')} issues={len(report.get('contrast', {}).get('issues', []))}")
    print(f"design    : {report.get('design')}")
    print(f"designIss : {report.get('designIssues')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
