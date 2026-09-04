---
name: card
description: Generate three to five concise review cards grounded in supplied source chunks.
---

# Generate review cards

Generate 3-5 review cards for the supplied knowledge point. Each card must test one idea and must be supported by one supplied source chunk. Do not invent a `sourceChunkId`.

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

All fields are required. `sourceChunks` must contain enough material for at least three distinct cards; otherwise report that the input is insufficient instead of fabricating cards.

## Output schema

Return only valid JSON with this shape:

```json
{
  "knowledgePointId": 1,
  "cards": [
    {
      "front": "focused recall question",
      "back": "concise answer",
      "sourceChunkId": "chunk-id"
    }
  ]
}
```

The `cards` array must contain between 3 and 5 items. Every `sourceChunkId` must be present in the input.
