import json
import sys

sys.path.insert(0, r"D:\newapp\tools\eval")
import run_eval

repo_root = r"D:\newapp"
bridge = run_eval.Bridge(run_eval.detect_adb(None, run_eval.read_sdk_dir(repo_root)), None, 18765, run_eval.DEFAULT_DEVICE_PORT)
bridge.forward()
bridge.load_token(None)
bridge.wait_until_ready(60)

GENERIC = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>通用模板</title><style>
:root { --brand:#1478ff; --ink:#10243a; --line:#dbe8f5; }
* { box-sizing:border-box; }
body { margin:0; font-family:-apple-system,"PingFang SC",sans-serif; color:var(--ink); background:#eaf4ff; }
.app { padding:20px 16px; display:flex; flex-direction:column; gap:14px; }
.card { background:#fff; border:1px solid var(--line); border-radius:16px; box-shadow:0 10px 26px rgba(0,0,0,.10); padding:16px; }
h1 { font-size:25px; margin:0 0 6px; }
.primary { border:0; border-radius:16px; background:var(--brand); color:#fff; height:48px; width:100%; font-size:16px; }
</style></head><body><div class="app">
<h1 id="t">喝水记录</h1>
<div class="card"><p>今日累计</p><strong>0 毫升</strong></div>
<div class="card"><p>快捷添加</p></div>
<div class="card"><button class="primary" id="add">添加</button></div>
</div></body></html>"""

CRAFTED = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>纸墨记录</title><style>
:root { --ink:#75322d; --body:#84675a; --wine:#a6453c; --gold:#bb8847; --gold-soft:#dec18b;
  --paper:#fff8ea; --paper-deep:#f7ead2; --line:rgba(166,105,69,.32); }
* { box-sizing:border-box; }
body { margin:0; min-height:100vh; color:var(--ink); font-family:"Noto Serif SC","Songti SC",serif;
  background:
    radial-gradient(ellipse at 4% 22%, rgba(186,130,86,.14) 0 1px, transparent 2px),
    radial-gradient(ellipse at 80% 40%, rgba(186,130,86,.10) 0 1px, transparent 2px),
    linear-gradient(108deg, rgba(255,255,255,.45), transparent 42%), var(--paper-deep);
  background-size:17px 19px, 23px 29px, auto, auto; padding:22px 18px; }
.hero { position:relative; overflow:hidden; min-height:150px; padding:22px;
  border:1px solid var(--gold-soft); border-radius:2px; background:rgba(255,250,239,.76);
  box-shadow:0 0 0 4px rgba(174,125,45,.09), 0 4px 8px rgba(117,50,45,.13), inset 0 1px 0 rgba(255,255,255,.9); }
.hero::before, .hero::after { content:""; position:absolute; border:1px solid rgba(187,136,71,.22); border-radius:50%; }
.hero::before { width:150px; height:150px; right:-54px; top:-76px; }
.hero::after { width:112px; height:112px; right:-29px; top:-51px; }
h1 { position:relative; z-index:1; font-size:27px; letter-spacing:3px; margin:10px 0; }
.badge { position:relative; display:inline-block; padding:3px 6px; border:1.5px solid rgba(174,125,45,.9);
  border-radius:5px 2px 5px 2px; background:#fff8ea; color:#8d672e; transform:rotate(-6deg);
  box-shadow:0 2px 0 rgba(117,50,45,.16), 0 3px 5px rgba(117,50,45,.13), inset 0 1px 0 rgba(255,255,255,.9);
  text-shadow:0 1px 0 rgba(255,255,255,.72); font:700 10px Georgia,serif; }
.badge::before { content:""; position:absolute; inset:2px; border:1px solid currentColor;
  border-radius:3px 1px 3px 1px; opacity:.28; }
.metrics { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); margin-top:16px;
  border:1px solid var(--gold-soft); background:rgba(255,250,238,.68); border-radius:2px; }
.metric { padding:14px 11px; min-height:92px; }
.metric + .metric { border-left:1px solid var(--line); }
.metric strong { display:block; margin:9px 0 1px; font:700 27px Georgia,"Songti SC",serif; }
.action { margin-top:14px; border:0; border-bottom:1px solid var(--gold); background:var(--paper);
  color:#8d672e; padding:9px 16px; font-family:inherit; font-size:13px; letter-spacing:2px;
  clip-path:polygon(8px 0, 100% 0, calc(100% - 6px) 100%, 0 100%); }
</style></head><body>
<section class="hero"><h1 id="t">招募记录</h1><span class="badge">五星</span></section>
<div class="metrics"><div class="metric"><span>总抽数</span><strong>128</strong></div>
<div class="metric"><span>五星</span><strong>3</strong></div></div>
<button class="action" id="add">记录一次</button>
</body></html>"""

MANIFEST = {"id": "designlab", "name": "设计审计验证", "version": "0.1.0", "entry": "index.html",
            "permissions": ["storage"], "network": [], "runtime": "web", "runtimeVersion": 1}

STEPS = [{"action": "assertText", "selector": "#t", "text": ""}]


def preview(name, html):
    payload = {"action": "preview", "manifest": MANIFEST, "files": {"index.html": html},
               "steps": [{"action": "assertText", "selector": "#t", "text": "记录"}]}
    report = bridge.post("/test/web", payload)
    print("=== " + name)
    print("  passed=" + str(report.get("passed")) + "  genericTemplate=" + str(report.get("genericTemplate")))
    print("  layoutProblems=" + json.dumps(report.get("layoutProblems"), ensure_ascii=False))
    print("  designIssues=" + json.dumps(report.get("designIssues"), ensure_ascii=False))
    print("  design=" + json.dumps(report.get("design"), ensure_ascii=False))
    return report


generic = preview("通用模板页（应判失败）", GENERIC)
crafted = preview("工艺页（应通过）", CRAFTED)

problems = []
if generic.get("genericTemplate") is not True or generic.get("passed") is not False:
    problems.append("通用模板页没有被判为默认模板")
if crafted.get("genericTemplate") is not False:
    problems.append("工艺页被误判为默认模板")
if crafted.get("passed") is not True:
    problems.append("工艺页没有通过：" + json.dumps(crafted.get("designIssues"), ensure_ascii=False))

print("")
print("结论：" + ("真机设计审计行为符合预期" if not problems else "；".join(problems)))
sys.exit(0 if not problems else 1)
