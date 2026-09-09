"""Create immutable user messages from a reviewed plan and a separate quality rubric."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--planning-run", type=Path, required=True)
parser.add_argument("--course", choices=["algorithms", "os"], required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
source = json.loads((args.planning_run / "state.json").read_text(encoding="utf-8"))
assert source["lastView"]["status"] == "SUCCEEDED"
tasks = source["lastView"]["result"]["tasks"]
assert len(tasks) == 5
rubric_path = Path("eval/planning/compaction-quality-v1.json")
rubric = json.loads(rubric_path.read_text(encoding="utf-8"))["courses"][args.course]
expected_topic = "稳定匹配" if args.course == "algorithms" else "进程"
assert expected_topic in tasks[0]["topic"], "Review the first-point probe against the actual teaching plan"
points = []
for i, task in enumerate(tasks):
    messages = {
        "explain": "请从当前知识点开始，结合资料讲清规则、一个例子和一个容易误解的地方。",
        "quiz": "我准备好了，请针对当前知识点给我五道选择题进行测验。",
        "grade": "现在完整提交五题答案：1.A 2.A 3.A 4.A 5.A。",
        "cards": "请结合本次答题反馈生成当前知识点的三张复习卡，并完成这个知识点。"}
    if i == 0:
        messages["question"] = (f"我给这次误区起个个人标记“{rubric['personalMarker']}”：我以为“{rubric['originalMisconception']}”。"
                                f"请根据课件纠正。另记一个未解决问题：{rubric['unresolvedQuestion']}？此问题暂不展开。"
                                "请用200字以内回复，暂不出题或进入下一点。")
    elif i in [2, 4]:
        messages["question"] = ("请只根据前面的学习记录，回忆我在第一个知识点设置的个人标记、原来的误区、"
                                "纠正后的规则，以及当时留下的未解决问题。某项记录已经没有就明确说不知道，不要猜。"
                                "请用150字以内回复，暂不出题或进入下一点。")
    points.append({"topic": task["topic"], "messages": messages})
result = {"version": "compaction-user-script-v1", "course": args.course, "planningRunId": source["runId"],
          "rubricSha256": hashlib.sha256(rubric_path.read_bytes()).hexdigest(), "points": points}
raw = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
args.output.parent.mkdir(parents=True, exist_ok=True)
if args.output.exists():
    assert args.output.read_bytes() == raw, "Never overwrite a frozen experiment script"
else:
    args.output.write_bytes(raw)
print(json.dumps({"course": args.course, "turns": sum(len(p["messages"]) for p in points), "sha256": hashlib.sha256(raw).hexdigest()}))
