---
name: quiz
description: Generate exactly five source-grounded multiple-choice questions for one knowledge point.
---

# Generate a knowledge-point quiz

Generate exactly 5 multiple-choice questions from the supplied source chunks. Do not use outside facts or invent a `sourceChunkId`.

## Input schema

```json
{
  "knowledgePointId": 1,
  "topic": "knowledge point topic",
  "sourceChunks": [
    {
      "sourceChunkId": "chunk-id",
      "content": "source text"
    }
  ]
}
```

All fields are required. `sourceChunks` must contain enough material to support every question; otherwise report that the input is insufficient instead of fabricating questions.

## Output schema

Return only a valid JSON array with this shape:

```json
[{"question":"question text","options":["option A","option B","option C","option D"],
  "correctAnswer":"option A","explanation":"why the answer is correct","sourceChunkId":"chunk-id"}]
```

The array must contain exactly 5 items. Each item must have four distinct options, a `correctAnswer` exactly equal to one option, an explanation, and one `sourceChunkId` present in the input.
