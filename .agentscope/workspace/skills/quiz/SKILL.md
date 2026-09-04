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

Return only valid JSON with this shape:

```json
{
  "knowledgePointId": 1,
  "questions": [
    {
      "question": "question text",
      "options": [
        {"key": "A", "text": "option text"},
        {"key": "B", "text": "option text"},
        {"key": "C", "text": "option text"},
        {"key": "D", "text": "option text"}
      ],
      "answer": "A",
      "explanation": "why the answer is correct",
      "sourceChunkId": "chunk-id"
    }
  ]
}
```

The `questions` array must contain exactly 5 items. Each item must have four distinct options, one answer matching an option key, an explanation, and one `sourceChunkId` present in the input.
