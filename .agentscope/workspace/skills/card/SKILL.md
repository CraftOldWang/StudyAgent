---
name: card
description: Generate exactly three concise Anki-style review cards without inventing sources.
---

# Generate review cards

Generate exactly 3 review cards for the supplied knowledge point. Each card must test one idea. Do not invent a `sourceChunkId`.

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

When supplied source chunks support a card, preserve the real `sourceChunkId`. If no verified source supports it, use `null` rather than fabricating an identifier.

## Output schema

Return only a valid JSON array with this shape:

```json
[{"front":"focused recall question","back":"concise answer","sourceChunkId":"chunk-id or null"}]
```

The array must contain exactly 3 items. Every non-null `sourceChunkId` must be present in the input.
