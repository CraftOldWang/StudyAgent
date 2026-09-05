---
name: explain
description: Explain one knowledge point clearly and cite only verified source chunk ids.
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

Return a concise, readable explanation in Markdown. Include the core idea, one concrete example,
and a short recap. Cite source chunk ids inline only when they were present in the tool input.
If the knowledge base is insufficient, say so explicitly instead of filling the gap with outside facts.
