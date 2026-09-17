"""在真实沙箱里跑一遍能力探测页：零模型调用，只发一次 preview。

用法：python tools/probe_web_capabilities/run_probe.py

用途：沙箱的任何改动（CSP、WebView 设置、manifest 校验、体积上限）都会改变小程序
实际能用的能力。这份探测把「能用什么」从推断变成事实，成本是一次 WebView 渲染。

注意：探测只能证明**对象是否存在**，不能证明**功能能不能用**。相机、定位、剪贴板、
文件选择这些对象都存在，但宿主一律 deny（见 app.js 注释与 PRD 21.6），不要据此写代码。
"""

import json
import os
import subprocess
import urllib.request

ADB = r"C:\Users\17525\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV = "8d8f8d92"
HERE = os.path.dirname(os.path.abspath(__file__))
PROBE = HERE

token = subprocess.run(
    [ADB, "-s", DEV, "shell", "run-as com.example.goon cat files/debug_bridge_token"],
    capture_output=True, text=True).stdout.strip()


def read(name):
    with open(os.path.join(PROBE, name), "r", encoding="utf-8") as handle:
        return handle.read()


body = {
    "action": "preview",
    "manifest": {
        "id": "probe-capabilities",
        "name": "能力探测",
        "version": "0.1.0",
        "entry": "index.html",
        "permissions": ["storage"],
        "network": [],
        "runtime": "web",
        "runtimeVersion": 1,
    },
    "files": {
        "index.html": read("index.html"),
        "style.css": read("style.css"),
        "app.js": read("app.js"),
        "data.json": read("data.json"),
    },
    "steps": [{"action": "assertVisible", "selector": "#app"}],
}

payload = json.dumps(body).encode("utf-8")
req = urllib.request.Request("http://127.0.0.1:18765/test/web", data=payload,
                             headers={"x-goon-test-token": token, "Content-Type": "application/json"})
report = json.load(urllib.request.urlopen(req, timeout=90))

print("passed =", report.get("passed"))
print("errors =", json.dumps(report.get("errors"), ensure_ascii=False))
print("designIssues =", json.dumps(report.get("designIssues"), ensure_ascii=False))
title = report.get("title") or ""
print("\n探测结果：")
try:
    data = json.loads(title)
    for key in sorted(data):
        print("  %-12s %s" % (key, data[key]))
except Exception:
    print("  (title 不是 JSON) ", title[:600])
