#!/usr/bin/env python3
"""对历史运行重新判定。

断言或判定逻辑改动后，不必重跑设备就能检查历史运行在新判定下的结论。做法是在
`/test/runs` 里按用例找到历史运行，构造一份「运行前」快照（把当次产出的项目从快照里去掉，
让隔离性断言仍然成立），再调用 `/test/cases/{id}/judge`。

只使用 Python 标准库，复用 `run_eval.py` 的 bridge 客户端；不需要模型网络。

用法：
  python tools/eval/rejudge.py --run <runId> --case <caseId>
  python tools/eval/rejudge.py --completed        # 每条有已完成运行的用例都重新判定
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_eval


def snapshot_without(baseline, project_id):
    snapshot = json.loads(json.dumps(baseline))
    if project_id:
        snapshot.get("projects", {}).pop(project_id, None)
        snapshot.get("digests", {}).pop(project_id, None)
    return snapshot


def produced_project(bridge, run_id):
    artifact = bridge.get("/test/runs/{}".format(run_id))
    results = [json.loads(event["message"]) for event in artifact.get("events", []) if event.get("kind") == "app_result"]
    return results[-1].get("projectId") if results else None


def rejudge(bridge, baseline, case_id, run_id):
    judgment = bridge.post(
        "/test/cases/{}/judge".format(case_id),
        {"runId": run_id, "baseline": snapshot_without(baseline, produced_project(bridge, run_id))},
    )
    print("{} {} {}".format(case_id, "通过" if judgment.get("passed") else "失败", run_id))
    for failure in judgment.get("failures", []):
        print("    [{}] {}".format(failure.get("type"), failure.get("reason")))
    return bool(judgment.get("passed"))


def main():
    parser = argparse.ArgumentParser(description="对历史运行重新判定")
    parser.add_argument("--repo-root", default=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    parser.add_argument("--adb")
    parser.add_argument("--device")
    parser.add_argument("--host-port", type=int, default=18765)
    parser.add_argument("--device-port", type=int, default=run_eval.DEFAULT_DEVICE_PORT)
    parser.add_argument("--token")
    parser.add_argument("--case")
    parser.add_argument("--run")
    parser.add_argument("--completed", action="store_true", help="每条有已完成运行的用例都重新判定")
    args = parser.parse_args()
    if not args.completed and not (args.case and args.run):
        parser.error("需要 --run 与 --case，或使用 --completed。")

    repo_root = os.path.abspath(args.repo_root)
    bridge = run_eval.Bridge(run_eval.detect_adb(args.adb, run_eval.read_sdk_dir(repo_root)), args.device, args.host_port, args.device_port)
    bridge.forward()
    bridge.load_token(args.token)
    bridge.wait_until_ready(60)
    baseline = bridge.get("/test/baseline")

    if not args.completed:
        return 0 if rejudge(bridge, baseline, args.case, args.run) else 1

    latest = {}
    for run in bridge.get("/test/runs").get("runs", []):
        case_id = run.get("testCaseId")
        if case_id and run.get("status") == "completed":
            if case_id not in latest or run.get("startedAt", 0) > latest[case_id].get("startedAt", 0):
                latest[case_id] = run
    print("有已完成运行的历史用例：{} 条".format(len(latest)))
    passed = 0
    for case_id, run in sorted(latest.items()):
        passed += 1 if rejudge(bridge, baseline, case_id, run["id"]) else 0
    print("重新判定通过：{} / {}".format(passed, len(latest)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
