---
name: explain
description: Explain one knowledge point from supplied source chunks and return the required JSON contract.
---

# Explain a knowledge point

Use this skill only to explain the supplied knowledge point. Base factual claims on the supplied source chunks. Do not invent a source or a `sourceChunkId`; if the sources are insufficient, state that explicitly in the explanation.

## Input schema

```json
{
  "knowledgePointId": 1,
  "topic": "knowledge point topic",
  "learnerGoal": "what the learner wants to understand",
  "sourceChunks": [
    {
      "sourceChunkId": "chunk-id",
      "content": "source text"
    }
  ]
}
```

All fields are required. `sourceChunks` must contain at least one item.

## Output schema

Return only valid JSON with this shape:

```json
{
  "knowledgePointId": 1,
  "explanation": "clear explanation for the learner goal",
  "keyPoints": ["important point"],
  "examples": ["example grounded in the explanation"],
  "sourceChunkIds": ["chunk-id"]
}
```

`sourceChunkIds` must contain only identifiers present in the input. Keep the explanation focused on the requested topic and learner goal.
