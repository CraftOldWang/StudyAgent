"""Generate source-grounded drafts via the app's counted eval model; deterministic evidence checks follow."""
import argparse
import hashlib
import json
import re
import sys
import time
import unicodedata
from pathlib import Path
import requests


def normalize(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text)).casefold()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, default=Path(".eval/corpus-production"))
    parser.add_argument("--only", nargs="+")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(Path("eval/rag/corpus-v1.json").read_text(encoding="utf-8"))
    system = Path("eval/rag/gold-draft-system-v2.txt").read_text(encoding="utf-8")
    session = requests.Session()
    session.headers["X-User-Id"] = "1"
    for source in manifest["files"]:
        if args.only and source["id"] not in args.only:
            continue
        output = args.run_dir / (source["id"] + ".json")
        if output.exists():
            continue
        original = (args.source_dir / (source["id"] + ".txt")).read_text(encoding="utf-8")
        blocks = []
        for paragraph in re.split(r"\n\s*\n", original):
            value = " ".join(paragraph.split())
            if not value:
                continue
            pieces = re.split(r"(?<=[。！？.!?;；])\s+", value) if len(value) > 400 else [value]
            blocks.extend(piece for piece in pieces if piece.strip())
        text = "\n".join(f"[B{i:04}] {block}" for i, block in enumerate(blocks, 1))
        count = 5 if source["course"] != "compiler" else (4 if source["id"] in ("compiler-01", "compiler-02") else 3)
        prompt = f"请生成 {count} 道不同知识点的问题。来源编号：{source['id']}。资料正文如下：\n<source>\n{text}\n</source>"
        request = {"purpose": "gold-draft", "promptVersion": "gold-draft-v2", "systemPrompt": system,
                   "prompt": prompt, "maxTokens": 3000}
        start = time.perf_counter()
        response = session.post("http://localhost:8080/api/eval/completions", json=request, timeout=180)
        raw = {"sourceId": source["id"], "sourceSha256": source["sha256"], "status": response.status_code,
               "elapsedMillis": (time.perf_counter() - start) * 1000, "traceId": response.headers.get("X-Trace-Id"),
               "systemPromptSha256": hashlib.sha256(system.encode()).hexdigest(),
               "sourceTextSha256": hashlib.sha256(text.encode()).hexdigest(), "response": response.json()}
        output.write_text(json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        response.raise_for_status()
        if raw["response"]["code"] != 0:
            raise RuntimeError("Model generation failed; inspect the recorded response")
        result = json.loads(raw["response"]["data"]["text"])
        questions = result["questions"]
        for q in questions:
            first = int(q["evidenceStart"].removeprefix("B"))
            last = int(q["evidenceEnd"].removeprefix("B"))
            if not 1 <= first <= last <= len(blocks):
                raise ValueError("Model returned an invalid evidence block range")
            q["quote"] = " ".join(blocks[first - 1:last])
            q["evidenceLiteralMatch"] = normalize(q["quote"]) in normalize(original)
            q["sourceId"] = source["id"]
            q["sourceSha256"] = source["sha256"]
        result["expectedCount"] = count
        result["countMatches"] = len(questions) == count
        result["allEvidenceLiteralMatches"] = all(q["evidenceLiteralMatch"] for q in questions)
        (args.run_dir / (source["id"] + ".draft.json")).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"source": source["id"], "questions": len(questions),
                          "literalEvidenceMatches": sum(q["evidenceLiteralMatch"] for q in questions)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
