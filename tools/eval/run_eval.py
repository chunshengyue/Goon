#!/usr/bin/env python3
"""Goon Agent 评测驱动。

一条命令完成：adb forward -> 等待 bridge -> 记录环境与基线 -> 逐条提交用例 ->
轮询到终态 -> 请求 bridge 判定 -> 汇总输出 JSON 与 Markdown 报告。

只使用 Python 标准库；判定在设备端 bridge 内完成，本脚本只负责调度与汇总。
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

DEFAULT_DEVICE_PORT = 8765
TERMINAL_STATUSES = {"completed", "failed", "cancelled"}

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


class BridgeError(RuntimeError):
    pass


def run_command(args, timeout=60):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise BridgeError("命令失败：{}（{}）".format(" ".join(args), detail))
    return result.stdout.decode("utf-8", "replace").strip()


class Bridge:
    def __init__(self, adb, device, host_port, device_port):
        self.adb = adb
        self.device = device
        self.host_port = host_port
        self.device_port = device_port
        self.token = ""
        self.base_url = "http://127.0.0.1:{}".format(host_port)

    def adb_command(self, *args, **kwargs):
        command = [self.adb]
        if self.device:
            command += ["-s", self.device]
        command += list(args)
        return run_command(command, **kwargs)

    def forward(self):
        self.adb_command("forward", "tcp:{}".format(self.host_port), "tcp:{}".format(self.device_port))

    def load_token(self, explicit=None):
        if explicit:
            self.token = explicit
        else:
            self.token = self.adb_command("shell", "run-as com.example.goon cat files/debug_bridge_token").strip()
        if not self.token:
            raise BridgeError("没有取到 debug bridge token。")

    def request(self, method, path, payload=None, timeout=60):
        headers = {"x-goon-test-token": self.token}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise BridgeError("{} {} 返回 {}：{}".format(method, path, error.code, body[:400]))
        except Exception as error:
            raise BridgeError("{} {} 失败：{}".format(method, path, error))
        return json.loads(body) if body.strip() else {}

    def get(self, path, timeout=60):
        return self.request("GET", path, None, timeout)

    def post(self, path, payload=None, timeout=120):
        return self.request("POST", path, payload if payload is not None else {}, timeout)

    def wait_until_ready(self, timeout_seconds, interval=2.0):
        deadline = time.time() + timeout_seconds
        last_error = ""
        while time.time() < deadline:
            try:
                health = self.get("/test/health", timeout=10)
                if health.get("status") == "ok":
                    return health
                last_error = str(health)
            except BridgeError as error:
                last_error = str(error)
            time.sleep(interval)
        raise BridgeError("bridge 在 {} 秒内未就绪：{}".format(timeout_seconds, last_error))

    def keep_awake(self, enable):
        """设备熄屏后会冻结应用线程，bridge 表现为无响应但进程仍存活；评测期间必须防止休眠。"""
        try:
            self.adb_command("shell", "svc power stayon " + ("true" if enable else "false"))
        except Exception:
            pass
        try:
            self.adb_command("shell", "input keyevent KEYCODE_WAKEUP")
        except Exception:
            pass

    def is_screen_on(self):
        try:
            state = self.adb_command("shell", "dumpsys power")
        except Exception:
            return True
        for line in state.splitlines():
            if "mWakefulness=" in line:
                return "Awake" in line
        return True

    def wait_until_ready_silent(self, timeout_seconds, interval=2.0):
        try:
            self.wait_until_ready(timeout_seconds, interval)
            return True
        except BridgeError:
            return False


def detect_adb(explicit, sdk_dir):
    if explicit:
        return explicit
    candidates = []
    if sdk_dir:
        candidates.append(os.path.join(sdk_dir, "platform-tools", "adb.exe"))
        candidates.append(os.path.join(sdk_dir, "platform-tools", "adb"))
    if os.environ.get("ANDROID_HOME"):
        candidates.append(os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb"))
    candidates.append("adb")
    for candidate in candidates:
        try:
            run_command([candidate, "version"], timeout=20)
            return candidate
        except Exception:
            continue
    raise BridgeError("找不到 adb，请用 --adb 指定路径。")


def read_sdk_dir(repo_root):
    local_properties = os.path.join(repo_root, "local.properties")
    if not os.path.isfile(local_properties):
        return None
    with open(local_properties, "r", encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("sdk.dir="):
                return line.split("=", 1)[1].strip().replace("\\\\", "\\").replace("\\:", ":")
    return None


def git_value(repo_root, args):
    try:
        return run_command(["git", "-C", repo_root] + args, timeout=20)
    except Exception:
        return None


def collect_environment(bridge, repo_root):
    environment = {
        "capturedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "repoCommit": git_value(repo_root, ["rev-parse", "HEAD"]),
        "repoDirty": bool(git_value(repo_root, ["status", "--porcelain"])),
        "adb": bridge.adb,
        "device": bridge.device or "default",
        "hostPort": bridge.host_port,
    }
    for key, prop in (("androidRelease", "ro.build.version.release"), ("androidSdk", "ro.build.version.sdk"), ("deviceModel", "ro.product.model")):
        try:
            environment[key] = bridge.adb_command("shell", "getprop " + prop).strip()
        except Exception:
            environment[key] = None
    try:
        environment["apkVersion"] = bridge.adb_command("shell", "dumpsys package com.example.goon").split("versionName=")[1].split()[0]
    except Exception:
        environment["apkVersion"] = None
    return environment


def poll_run(bridge, run_id, timeout_seconds, interval):
    deadline = time.time() + timeout_seconds
    last = {}
    while time.time() < deadline:
        runs = bridge.get("/test/runs").get("runs", [])
        current = next((item for item in runs if item.get("id") == run_id), None)
        if current is None:
            raise BridgeError("运行 {} 不在 /test/runs 里，可能已被清理。".format(run_id))
        last = current
        if current.get("status") in TERMINAL_STATUSES:
            return current
        time.sleep(interval)
    raise BridgeError("运行 {} 在 {} 秒内没有结束，最后状态 {}".format(run_id, timeout_seconds, last.get("status")))


def select_cases(cases, suite, case_ids, categories, include_pending):
    selected = []
    for case in cases:
        if suite and case.get("suite") != suite:
            continue
        if case_ids and case.get("id") not in case_ids:
            continue
        if categories and case.get("category") not in categories:
            continue
        if not include_pending and case.get("status") == "pending":
            continue
        selected.append(case)
    return selected


def ensure_idle(bridge, timeout=180.0, interval=3.0):
    """等待上一轮 Agent 运行结束；未结束就先取消。

    一个卡住的运行会让同会话的后续用例全部以「本会话已有运行中的任务」秒失败，
    把单点超时放大成整套连挂，因此每条用例开跑前都必须确认会话空闲。
    """
    deadline = time.time() + timeout
    cancelled = False
    # 僵尸行不该阻塞整轮：进程被杀/启动即失败会留下一条永远 running 的记录，只看 status
    # 就会让之后每条用例都被判成「会话被占用」（实测整轮 6 条用例全被跳过）。
    # 这里按时长兜底：超过阈值还没结束的运行视为僵尸，忽略它并继续，同时把这件事打出来。
    while time.time() < deadline:
        runs = bridge.get("/test/runs").get("runs", [])
        # 「会话是否被占用」必须以**进程里是否真的还有这个运行对象**为准（/test/runs 的 active 字段）。
        # 只看 status 会把僵尸行误判成占用——进程被杀、启动即失败都会留下一条永远 running 的记录，
        # 实测让整轮 6 条用例全部被跳过，白烧一轮时间和 token。
        active = [item for item in runs if item.get("status") == "running" and item.get("active")]
        stale = [item for item in runs if item.get("status") == "running" and not item.get("active")]
        if stale:
            print("  注意：忽略 {} 条僵尸运行（进程里已无对应运行对象）：{}".format(
                len(stale), ", ".join((item.get("id") or "")[:8] for item in stale[:3])))
        if not active:
            return cancelled, True
        if not cancelled:
            try:
                bridge.post("/test/agent-control", {"action": "cancel"}, timeout=30)
                cancelled = True
            except BridgeError:
                pass
        time.sleep(interval)
    return cancelled, False


def run_setup_turn(bridge, case, run_timeout, interval, record):
    """连续修改类用例先准备目标项目；前置轮失败时不再判定，避免把前置失败记成产品缺陷。"""
    prompt = case.get("setupPrompt")
    if not prompt:
        return True
    submission = bridge.post("/test/runs", {"prompt": prompt, "createNew": True})
    run_id = submission.get("runId")
    record["setupRunId"] = run_id
    if not run_id:
        record["failures"] = [{"type": "setup", "reason": "前置轮没有拿到 runId：{}".format(submission)}]
        return False
    try:
        run = poll_run(bridge, run_id, run_timeout, interval)
    except BridgeError as error:
        record["failures"] = [{"type": "setup", "reason": "前置轮未结束：{}".format(error)}]
        return False
    record["setupRunStatus"] = run.get("status")
    if run.get("status") != "completed":
        record["failures"] = [{"type": "setup", "reason": "前置轮状态为 {}：{}".format(run.get("status"), run.get("message"))}]
        return False
    return True


def request_cancel(bridge, run_id, cancel_after, interval, record):
    """只在运行仍活跃时取消。运行在取消前就结束时，中断根本没有被验证，交给调用方记为待定。"""
    deadline = time.time() + float(cancel_after)
    while True:
        runs = bridge.get("/test/runs").get("runs", [])
        current = next((item for item in runs if item.get("id") == run_id), None)
        if current is None:
            record["cancelSkipped"] = "运行不在 /test/runs 里，无法确认状态"
            return False
        if current.get("status") in TERMINAL_STATUSES:
            record["cancelSkipped"] = "运行在取消前已经结束：{}（{}）".format(current.get("status"), current.get("message"))
            return False
        remaining = deadline - time.time()
        if remaining <= 0:
            break
        time.sleep(min(interval, remaining))
    record["cancelRequested"] = True
    bridge.post("/test/agent-control", {"action": "cancel"})
    return True


def evaluate_case(bridge, case, baseline, run_timeout, interval):
    record = {"caseId": case["id"], "title": case.get("title", ""), "suite": case.get("suite"), "category": case.get("category")}
    started = time.time()
    bridge.keep_awake(True)
    scenario = case.get("scenario") or {}
    fault = scenario.get("providerFault")
    if fault:
        bridge.post("/test/provider-fault", {"mode": fault})
    try:
        if not run_setup_turn(bridge, case, run_timeout, interval, record):
            record["passed"] = False
            record.setdefault("failures", [])
            return record
        # 判定「修改是否真的发生」要用本用例执行前的项目内容；隔离性仍用整条用例开始前的快照。
        judge_baseline = bridge.get("/test/baseline") if case.get("setupPrompt") else baseline
        submission = bridge.post("/test/cases/{}/run".format(case["id"]), {})
        run_id = submission.get("runId")
        record["runId"] = run_id
        if not run_id:
            record["passed"] = False
            record["failures"] = [{"type": "submission", "reason": "bridge 没有返回 runId：{}".format(submission)}]
            return record
        if scenario.get("cancelAfterSeconds") and not request_cancel(bridge, run_id, scenario["cancelAfterSeconds"], interval, record):
            reason = record.pop("cancelSkipped", "运行在取消前已经结束，本次没有真正验证中断。")
            record["judgment"] = {"pending": True}
            record["failures"] = [{"type": "scenario", "reason": reason}]
            record["passed"] = False
            record["elapsedSeconds"] = round(time.time() - started, 1)
            return record
        try:
            run = poll_run(bridge, run_id, run_timeout, interval)
            record["runStatus"] = run.get("status")
            record["model"] = run.get("model")
            record["modelAttempts"] = run.get("modelAttempts")
            record["promptTokens"] = run.get("promptTokens")
            record["completionTokens"] = run.get("completionTokens")
            record["cachedTokens"] = run.get("cachedTokens")
            record["reasoningTokens"] = run.get("reasoningTokens")
            prompt_total = run.get("promptTokens") or 0
            cached_total = run.get("cachedTokens") or 0
            record["cacheHitRate"] = round(cached_total / prompt_total, 4) if prompt_total > 0 else None
            record["runMessage"] = run.get("message")
        except BridgeError as error:
            record["runStatus"] = "bridge_error"
            record["runMessage"] = str(error)
            # 熄屏冻结会让轮询失败；唤醒后重试一次，避免把设备休眠记成产品缺陷。
            if not bridge.is_screen_on():
                trace = wake_and_retry(bridge, run_id, run_timeout, interval)
                if trace is not None:
                    run = trace
                    record["runStatus"] = run.get("status")
                    record["model"] = run.get("model")
                    record["modelAttempts"] = run.get("modelAttempts")
                    record["promptTokens"] = run.get("promptTokens")
                    record["completionTokens"] = run.get("completionTokens")
                    record["runMessage"] = run.get("message")
                    record["recoveredAfterWake"] = True
        judgment = bridge.post(
            "/test/cases/{}/judge".format(case["id"]),
            {"runId": run_id, "baseline": baseline, "judgeBaseline": judge_baseline},
        )
        record["judgment"] = judgment
        record["passed"] = bool(judgment.get("passed"))
        record["failures"] = judgment.get("failures", []) or record.get("failures", [])
        record["artifact"] = judgment.get("artifactEndpoint")
        record["elapsedSeconds"] = round(time.time() - started, 1)
        return record
    finally:
        if fault:
            try:
                bridge.post("/test/provider-fault", {"mode": ""})
            except BridgeError:
                pass


def wake_and_retry(bridge, run_id, run_timeout, interval):
    bridge.keep_awake(True)
    if not bridge.wait_until_ready_silent(60):
        return None
    try:
        return poll_run(bridge, run_id, run_timeout, interval)
    except BridgeError:
        return None


def summarize(environment, records, skipped):
    """Assemble the round summary. Cost data is separated from pass/fail on purpose:
    a run can be correct and still be expensive, and only the cached split says which."""
    evaluated = [item for item in records if not item.get("judgment", {}).get("pending")]
    pending_runtime = [item for item in records if item.get("judgment", {}).get("pending")]
    passed = [item for item in evaluated if item.get("passed")]
    by_suite = {}
    for item in evaluated:
        bucket = by_suite.setdefault(item.get("suite") or "unknown", {"total": 0, "passed": 0})
        bucket["total"] += 1
        bucket["passed"] += 1 if item.get("passed") else 0
    for bucket in by_suite.values():
        bucket["passRate"] = round(bucket["passed"] / bucket["total"], 4) if bucket["total"] else None
    return {
        "environment": environment,
        "models": sorted({item["model"] for item in records if item.get("model")}),
        "totals": {
            "cases": len(records),
            "evaluated": len(evaluated),
            "passed": len(passed),
            "failed": len(evaluated) - len(passed),
            "taskSuccessRate": round(len(passed) / len(evaluated), 4) if evaluated else None,
            "pendingSkipped": len(skipped),
            "pendingRuntime": len(pending_runtime),
        },
        "cost": build_cost_summary(records),
        "bySuite": by_suite,
        "stability": build_stability_summary(evaluated),
        "skipped": skipped,
        "pendingRuntime": [item["caseId"] for item in pending_runtime],
        "results": records,
    }

def build_stability_summary(evaluated):
    """pass@k 与 pass^k。

    - pass@k：同一用例跑 k 次，至少成功一次的比例（能力上限：能不能做到）。
    - pass^k：k 次**全部**成功的用例比例（稳定性：能不能每次都做到）。
    两个一起报才诚实：pass@k 高而 pass^k 低，说明这个 Agent 会做但不可靠。
    """
    by_case = {}
    for item in evaluated:
        by_case.setdefault(item["caseId"], []).append(bool(item.get("passed")))
    multi = {case: results for case, results in by_case.items() if len(results) > 1}
    if not multi:
        return {"repeats": 1, "cases": len(by_case),
                "passAt1": round(sum(1 for results in by_case.values() if results[0]) / len(by_case), 4) if by_case else None}
    k = min(len(results) for results in multi.values())
    return {
        "repeats": k,
        "cases": len(multi),
        "passAt1": round(sum(1 for results in multi.values() if results[0]) / len(multi), 4),
        "passAtK": round(sum(1 for results in multi.values() if any(results)) / len(multi), 4),
        "passPowK": round(sum(1 for results in multi.values() if all(results)) / len(multi), 4),
        "perCase": {case: [1 if value else 0 for value in results] for case, results in multi.items()},
    }

def build_cost_summary(records):
    """Aggregate token usage across the round.

    promptTokens includes cache hits, so reporting it alone overstates cost: only the
    uncached part is billed at the full rate. Both numbers must travel together.
    """
    # Only runs that reported cached_tokens take part: older runs lack the field and would
    # be counted as zero hits, which dilutes the rate into a meaningless number.
    runs = [item for item in records if item.get("promptTokens") and item.get("cachedTokens") is not None]
    prompt_total = sum(item.get("promptTokens") or 0 for item in runs)
    cached_total = sum(item.get("cachedTokens") or 0 for item in runs)
    summary = {
        "runsWithCacheData": len(runs),
        "promptTokens": prompt_total,
        "promptTokensUncached": max(prompt_total - cached_total, 0),
        "cachedTokens": cached_total,
        "completionTokens": sum(item.get("completionTokens") or 0 for item in runs),
        "reasoningTokens": sum(item.get("reasoningTokens") or 0 for item in runs),
        "modelAttempts": sum(item.get("modelAttempts") or 0 for item in runs),
        "cacheHitRate": round(cached_total / prompt_total, 4) if prompt_total > 0 else None,
    }
    return summary


def render_markdown(report, comparison=None):
    environment = report["environment"]
    totals = report["totals"]
    lines = ["# 评测报告", ""]
    lines.append("- 时间：{}".format(environment.get("capturedAt")))
    lines.append("- 模型：{}".format("、".join(report.get("models") or ["未知"])))
    lines.append("- 设备：{} / Android {} (SDK {})".format(environment.get("deviceModel"), environment.get("androidRelease"), environment.get("androidSdk")))
    lines.append("- 提交：{}（工作区{}改动）".format((environment.get("repoCommit") or "未知")[:12], "有" if environment.get("repoDirty") else "无"))
    lines.append("- 用例集：{} 条（可判定 {}，待定 {}：未运行 {} / 场景未生效 {}）".format(
        totals["cases"], totals["evaluated"], totals["pendingSkipped"] + totals.get("pendingRuntime", 0), totals["pendingSkipped"], totals.get("pendingRuntime", 0)))
    lines.append("")
    lines.append("## 结果")
    lines.append("")
    lines.append("- 任务成功率（判定口径）：{} / {} = **{}**".format(totals["passed"], totals["evaluated"], totals["taskSuccessRate"]))
    for suite, bucket in sorted(report["bySuite"].items()):
        lines.append("- {} 套件：{} / {} = {}".format(suite, bucket["passed"], bucket["total"], bucket["passRate"]))
    stability = report.get("stability") or {}
    if stability.get("passAtK") is not None:
        lines.append("- 稳定性：pass@1 {} / pass@{} {} / pass^{} {}（{} 条用例 × {} 次）".format(
            stability.get("passAt1"), stability.get("repeats"), stability.get("passAtK"),
            stability.get("repeats"), stability.get("passPowK"),
            stability.get("cases"), stability.get("repeats")))
        for case, results in sorted((stability.get("perCase") or {}).items()):
            lines.append("  - {}：{}".format(case, "".join("✓" if value else "✗" for value in results)))
    cost = report.get("cost") or {}
    if cost.get("promptTokens"):
        lines.append("")
        lines.append("## 成本与缓存")
        lines.append("")
        lines.append("- 参与统计的运行：{} 次（只统计上报过 cached_tokens 的运行）".format(cost.get("runsWithCacheData")))
        lines.append("- 模型调用：{} 次".format(cost.get("modelAttempts")))
        lines.append("- 输入 token：{}（其中命中缓存 {}，未命中 {}）".format(cost.get("promptTokens"), cost.get("cachedTokens"), cost.get("promptTokensUncached")))
        lines.append("- 缓存命中率：**{}**".format(cost.get("cacheHitRate")))
        lines.append("- 输出 token：{}（其中推理 {}）".format(cost.get("completionTokens"), cost.get("reasoningTokens")))
        lines.append("")
        lines.append("> 只有未命中的输入按全价计费。命中率塌陷说明历史前缀被改写（例如压缩），此时原始 token 数下降也可能更贵。")
    lines.append("")
    lines.append("## 用例明细")
    lines.append("")
    lines.append("| 用例 | 套件 | 结果 | 耗时 | 运行状态 | 失败断言 |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for item in report["results"]:
        if item.get("judgment", {}).get("pending"):
            verdict = "待定"
        else:
            verdict = "通过" if item.get("passed") else "失败"
        reasons = "；".join("{}: {}".format(failure.get("type"), failure.get("reason")) for failure in item.get("failures", []))
        hit = item.get("cacheHitRate")
        lines.append("| {} | {} | {} | {}s | {}（缓存命中 {}） | {} |".format(item["caseId"], item.get("suite"), verdict, item.get("elapsedSeconds", "-"), item.get("runStatus", "-"), "未知" if hit is None else hit, reasons or "-"))
    failures = [item for item in report["results"] if item.get("failures")]
    if failures:
        lines.append("")
        lines.append("## 需要关注的用例")
        for item in failures:
            lines.append("")
            lines.append("### {}".format(item["caseId"]))
            lines.append("- artifact：`GET /test/runs/{}`".format(item.get("runId")))
            for failure in item["failures"]:
                lines.append("- [{}] {}".format(failure.get("type"), failure.get("reason")))
    if comparison:
        lines.append("")
        lines.append("## 与上一基线对比")
        lines.append("")
        lines.append("- 上一基线：{}".format(comparison.get("baselineLabel", "未知")))
        lines.append("- 新增失败：{}".format("、".join(comparison.get("newlyFailed") or ["无"])))
        lines.append("- 新通过：{}".format("、".join(comparison.get("newlyPassed") or ["无"])))
        for suite, delta in sorted((comparison.get("suiteDeltas") or {}).items()):
            lines.append("- {} 成功率变化：{}".format(suite, delta))
    lines.append("")
    return "\n".join(lines)


def compare_with_baseline(report, previous):
    previous_results = {item["caseId"]: item for item in previous.get("results", [])}
    newly_failed, newly_passed = [], []
    for item in report["results"]:
        if item.get("judgment", {}).get("pending") or item["caseId"] not in previous_results:
            continue
        was_passing = previous_results[item["caseId"]].get("passed")
        if was_passing and not item.get("passed"):
            newly_failed.append(item["caseId"])
        elif not was_passing and item.get("passed"):
            newly_passed.append(item["caseId"])
    suite_deltas = {}
    for suite, bucket in report["bySuite"].items():
        before = previous.get("bySuite", {}).get(suite, {}).get("passRate")
        if before is not None and bucket["passRate"] is not None:
            suite_deltas[suite] = "{:+.4f}（{} -> {}）".format(bucket["passRate"] - before, before, bucket["passRate"])
    return {
        "baselineLabel": previous.get("environment", {}).get("capturedAt"),
        "newlyFailed": newly_failed,
        "newlyPassed": newly_passed,
        "suiteDeltas": suite_deltas,
    }


def load_previous_baseline(out_dir, explicit):
    path = explicit
    if not path and os.path.isdir(out_dir):
        candidates = sorted(name for name in os.listdir(out_dir) if name.startswith("eval-") and name.endswith(".json"))
        path = os.path.join(out_dir, candidates[-1]) if candidates else None
    if not path or not os.path.isfile(path):
        return None, None
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle), path


def main():
    parser = argparse.ArgumentParser(description="运行 Goon Agent 评测并输出可对比报告。")
    parser.add_argument("--repo-root", default=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    parser.add_argument("--adb")
    parser.add_argument("--device", help="adb 序列号；默认使用唯一连接的设备")
    parser.add_argument("--host-port", type=int, default=18765, help="宿主端口；8765 可能被本机其他程序占用")
    parser.add_argument("--device-port", type=int, default=DEFAULT_DEVICE_PORT)
    parser.add_argument("--token", help="显式指定 bridge token，默认从设备读取")
    parser.add_argument("--suite", choices=["legacy", "web"])
    parser.add_argument("--case", action="append", dest="cases", help="只跑指定用例，可重复")
    parser.add_argument("--category", action="append", dest="categories")
    parser.add_argument("--include-pending", action="store_true", help="同时运行尚未定断言的用例（记为待定）")
    parser.add_argument("--repeat", type=int, default=1,
                        help="同一批用例重复跑几次。>1 时报告里会给出 pass@k（k 次里至少一次通过）"
                             "与 pass^k（k 次全过）。稳定性只有重复跑才测得出来。")
    # 默认略高于 Agent 自身的 15 分钟总时长预算，让应用先按自己的预算收尾，
    # 避免客户端先超时导致运行悬挂、拖垮后续用例。
    parser.add_argument("--run-timeout", type=float, default=1020.0)
    parser.add_argument("--poll-interval", type=float, default=5.0)
    parser.add_argument("--ready-timeout", type=float, default=60.0)
    parser.add_argument("--out", default=os.path.join("tmp", "eval_reports"))
    parser.add_argument("--archive", help="把本轮报告另存到仓库目录（例如 docs/eval_baselines），用于跨版本基线对比")
    parser.add_argument("--baseline", help="指定对比用的历史报告 JSON")
    parser.add_argument("--list", action="store_true", help="只列出用例，不运行")
    parser.add_argument("--no-stay-awake", action="store_true", help="不做防休眠处理（默认会唤醒设备并保持常亮）")
    args = parser.parse_args()

    repo_root = os.path.abspath(args.repo_root)
    adb = detect_adb(args.adb, read_sdk_dir(repo_root))
    bridge = Bridge(adb, args.device, args.host_port, args.device_port)
    bridge.forward()
    bridge.load_token(args.token)
    stay_awake = not args.no_stay_awake
    if stay_awake:
        bridge.keep_awake(True)
    health = bridge.wait_until_ready(args.ready_timeout)
    print("bridge 就绪：{}".format(health))

    cases = bridge.get("/test/cases").get("cases", [])
    selected = select_cases(cases, args.suite, set(args.cases or []), set(args.categories or []), args.include_pending)
    if args.list:
        for case in selected:
            print("{}\t{}\t{}\t{}".format(case["suite"], case["id"], case.get("status"), case.get("title")))
        print("共 {} 条".format(len(selected)))
        return 0
    if not selected:
        print("没有匹配的用例。", file=sys.stderr)
        return 2

    environment = collect_environment(bridge, repo_root)
    environment["stayAwake"] = stay_awake
    print("设备 {} 上开始运行 {} 条用例".format(environment.get("device"), len(selected)))

    records, skipped = [], []
    # --repeat N：同一批用例重复跑 N 次，用于算 pass@k / pass^k。
    # pass@1 会掩盖不稳定性——一个 Agent 偶尔碰巧做对一次就能在 pass@1 上好看，
    # 所以稳定性必须靠重复跑同一批用例来测（2026 主流做法，见 docs/resume_playbook.md）。
    attempts = max(1, int(getattr(args, "repeat", 1)))
    plan = [(case, attempt) for attempt in range(1, attempts + 1) for case in selected]
    for case, attempt in plan:
        if case.get("status") == "pending":
            skipped.append(case["id"])
            print("- {} 跳过（待定）".format(case["id"]))
            continue
        if attempts > 1:
            print("- {} 第 {}/{} 次".format(case["id"], attempt, attempts), flush=True)
        bridge.keep_awake(True)
        cancelled, idle = ensure_idle(bridge)
        if not idle:
            records.append({"caseId": case["id"], "title": case.get("title", ""), "suite": case.get("suite"), "category": case.get("category"),
                            "passed": False, "runStatus": "blocked",
                            "failures": [{"type": "setup", "reason": "上一轮运行未能结束，会话一直处于占用状态。"}]})
            print("- {} 跳过（会话被占用）".format(case["id"]))
            continue
        if cancelled:
            print("- {} 前取消了未结束的上一轮运行".format(case["id"]))
        print("- {} 运行中…".format(case["id"]), flush=True)
        # 隔离性基线必须逐条用例采集，否则同一轮里前一条用例新建的项目会被算成越权写入。
        baseline_snapshot = bridge.get("/test/baseline")
        record = evaluate_case(bridge, case, baseline_snapshot, args.run_timeout, args.poll_interval)
        record["attempt"] = attempt
        records.append(record)
        if record.get("judgment", {}).get("pending"):
            verdict = "待定"
        else:
            verdict = "通过" if record.get("passed") else "失败"
        print("  {} {}s".format(verdict, record.get("elapsedSeconds", "-")))
        for failure in record.get("failures", []):
            print("    [{}] {}".format(failure.get("type"), failure.get("reason")))

    report = summarize(environment, records, skipped)
    out_dir = args.out if os.path.isabs(args.out) else os.path.join(repo_root, args.out)
    os.makedirs(out_dir, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = os.path.join(out_dir, "eval-{}.json".format(stamp))
    md_path = os.path.join(out_dir, "eval-{}.md".format(stamp))
    previous, previous_path = load_previous_baseline(out_dir, args.baseline)
    comparison = compare_with_baseline(report, previous) if previous else None
    report["comparison"] = comparison
    with open(json_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    with open(md_path, "w", encoding="utf-8") as handle:
        handle.write(render_markdown(report, comparison))

    # tmp/ 不进版本管理；基线要能跨版本对比，就必须另存一份到仓库里。
    if args.archive:
        archive_dir = args.archive if os.path.isabs(args.archive) else os.path.join(repo_root, args.archive)
        os.makedirs(archive_dir, exist_ok=True)
        for source, suffix in ((json_path, "json"), (md_path, "md")):
            shutil.copyfile(source, os.path.join(archive_dir, "eval-{}.{}".format(stamp, suffix)))
        print("基线存档：{}".format(archive_dir))

    totals = report["totals"]
    print("")
    print("任务成功率：{} / {} = {}".format(totals["passed"], totals["evaluated"], totals["taskSuccessRate"]))
    if comparison:
        print("对比基线 {}：新增失败 {}，新通过 {}".format(previous_path, comparison["newlyFailed"] or "无", comparison["newlyPassed"] or "无"))
    print("报告：{}".format(json_path))
    print("报告：{}".format(md_path))
    if stay_awake:
        bridge.keep_awake(False)
    return 1 if totals["failed"] else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BridgeError as error:
        print("评测中断：{}".format(error), file=sys.stderr)
        sys.exit(3)
