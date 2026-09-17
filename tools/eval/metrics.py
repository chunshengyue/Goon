"""Aggregate the metrics a resume (and an interview) actually asks for.

Where the numbers come from: the debug bridge, not a new model run.
  /test/runs     -> every run: status, tokens, cache, model ms, attempts
  /test/cases    -> the judged case set (ids/suites/categories)
  /test/summary  -> pre-computed p50/p95 of model time + cache hit rate

Two modes:
  --history-only   aggregate everything already recorded (zero model cost)
  (default)        same, plus per-case stability when a case was run more than once

Usage:
  python tools/eval/metrics.py --history-only --out tmp/eval_history.md
  python tools/eval/metrics.py --out docs/eval_report_final.md
"""
import argparse
import importlib.util
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

_spec = importlib.util.spec_from_file_location("run_eval", os.path.join(os.path.dirname(os.path.abspath(__file__)), "run_eval.py"))
run_eval = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(run_eval)


def percentile(values, ratio):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(ratio * len(ordered)))]


def aggregate(runs):
    """Group runs by test case (or 'manual' for hand-run tasks) and compute the table."""
    buckets = {}
    for run in runs:
        key = run.get("testCaseId") or "manual"
        buckets.setdefault(key, []).append(run)
    rows = []
    for key, group in sorted(buckets.items()):
        terminal = [r for r in group if r.get("status") in ("completed", "failed", "cancelled")]
        completed = [r for r in group if r.get("status") == "completed"]
        with_cache = [r for r in group if r.get("cachedTokens") is not None and r.get("promptTokens")]
        prompt = sum(r.get("promptTokens") or 0 for r in with_cache)
        cached = sum(r.get("cachedTokens") or 0 for r in with_cache)
        rows.append({
            "case": key,
            "runs": len(group),
            "terminal": len(terminal),
            "completed": len(completed),
            "successRate": round(len(completed) / len(terminal), 4) if terminal else None,
            "medianAttempts": statistics.median([r.get("modelAttempts") or 0 for r in group]) if group else None,
            "medianPromptTokens": statistics.median([r.get("promptTokens") or 0 for r in with_cache]) if with_cache else None,
            "cacheHitRate": round(cached / prompt, 4) if prompt else None,
            "uncachedTokens": max(prompt - cached, 0) if prompt else None,
            "p50ModelMs": percentile([r.get("modelMs") or 0 for r in group if r.get("modelMs")], 0.5),
            "p95ModelMs": percentile([r.get("modelMs") or 0 for r in group if r.get("modelMs")], 0.95),
        })
    return rows


def stability(rows):
    """A case with >=2 runs gives pass@k / pass^k straight from history (no new runs needed)."""
    multi = [row for row in rows if row["runs"] >= 2 and row["case"] != "manual"]
    if not multi:
        return None
    return {
        "cases": len(multi),
        "passAtK": round(sum(1 for row in multi if row["completed"] > 0) / len(multi), 4),
        "passPowK": round(sum(1 for row in multi if row["completed"] == row["terminal"]) / len(multi), 4),
    }


def render(rows, summary, groups, stab, history_only):
    out = ["# Agent 指标汇总", ""]
    out.append("数据来源：bridge `/test/runs`（{0} 条运行）+ `/test/summary`。{1}".format(
        sum(row["runs"] for row in rows), "仅历史，无新增模型调用。" if history_only else ""))
    out.append("")
    out.append("## 总览")
    out.append("")
    out.append("- 运行总数 {}；用例运行 {}；已完成 {}；失败 {}".format(
        summary.get("runs"), summary.get("caseRuns"), summary.get("completed"), summary.get("failed")))
    out.append("- 终态成功率 **{}**；latest-case 成功率 {}".format(
        summary.get("terminalSuccessRate"), summary.get("latestCaseSuccessRate")))
    out.append("- 模型耗时 P50 {} ms / P95 {} ms".format(summary.get("modelMsP50"), summary.get("modelMsP95")))
    out.append("- 输入 token {}，其中命中缓存 {}（命中率 **{}**），全价 {}；输出 {}；推理 {}".format(
        summary.get("promptTokens"), summary.get("cachedTokens"), summary.get("cacheHitRate"),
        summary.get("promptTokensUncached"), summary.get("completionTokens"), summary.get("reasoningTokens")))
    if stab:
        out.append("- 历史稳定性：{} 条用例跑过多次 → pass@k **{}** / pass^k **{}**".format(
            stab["cases"], stab["passAtK"], stab["passPowK"]))
    out.append("")
    out.append("## 分类（按用例 / manual 为手工任务）")
    out.append("")
    out.append("| 用例 | 运行 | 完成 | 成功率 | 中位轮次 | 中位输入token | 缓存命中 | 模型P50/P95(ms) |")
    out.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        out.append("| {} | {} | {} | {} | {} | {} | {} | {} / {} |".format(
            row["case"], row["runs"], row["completed"], row["successRate"], row["medianAttempts"],
            row["medianPromptTokens"], row["cacheHitRate"], row["p50ModelMs"], row["p95ModelMs"]))
    if groups:
        out.append("")
        out.append("## 套件")
        out.append("")
        for name, bucket in sorted(groups.items()):
            out.append("- {}：{} / {} = {}".format(name, bucket.get("passed"), bucket.get("total"), bucket.get("passRate")))
    return "\n".join(out) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    parser.add_argument("--adb")
    parser.add_argument("--device")
    parser.add_argument("--host-port", type=int, default=18765)
    parser.add_argument("--device-port", type=int, default=run_eval.DEFAULT_DEVICE_PORT)
    parser.add_argument("--token")
    parser.add_argument("--history-only", action="store_true", help="只汇总历史运行，不触发任何新运行")
    parser.add_argument("--out", help="输出 markdown 路径；不传则打印到 stdout")
    args = parser.parse_args()

    sdk_dir = run_eval.read_sdk_dir(args.repo_root)
    adb = run_eval.detect_adb(args.adb, sdk_dir)
    if not adb:
        print("找不到 adb：需要真机才能取指标。装好 platform-tools 或用 --adb 指定路径。", file=sys.stderr)
        return 2
    bridge = run_eval.Bridge(adb, args.device, args.host_port, args.device_port)
    try:
        bridge.forward()
        bridge.load_token(args.token)
        summary = bridge.get("/test/summary")
        runs = bridge.get("/test/runs").get("runs", [])
    except Exception as error:  # noqa: BLE001 - 设备/桥不通时要给一句人话，而不是栈
        print("取指标失败：{}。检查设备是否在线、bridge 是否已 forward、token 是否可读。".format(error), file=sys.stderr)
        return 3

    rows = aggregate(runs)
    stab = stability(rows)
    text = render(rows, summary, summary.get("bySuite"), stab, args.history_only)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)
        print("写入 {}".format(args.out))
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
