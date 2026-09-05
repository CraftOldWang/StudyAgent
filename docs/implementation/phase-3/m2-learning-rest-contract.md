# M2 learning REST contract

All responses use `ApiResponse<T>`. Mutation responses include a backend-generated `traceId`.

## Endpoints

- `POST /api/learning/sessions` with `{knowledgeBaseId: string, learningGoal: string}` returns `{traceId, session}`.
- `GET /api/learning/sessions/{id}` restores the persisted session, plan, focus quiz/result and cards without regeneration.
- `POST /api/learning/sessions/{id}/explain` returns `{traceId, answer, session}`.
- `POST /api/learning/sessions/{id}/messages` with `{message: string}` returns `{traceId, answer, session}`. It is allowed in `EXPLAINING` and `QUIZZING`; the latter stays `QUIZZING`.
- `POST /api/learning/sessions/{id}/quiz` returns the five-question quiz without answers.
- `POST /api/learning/sessions/{id}/quiz/submit` with `{answers: [string x5]}` returns score and per-question feedback. There is no pass threshold.
- `POST /api/learning/sessions/{id}/cards` returns exactly three persisted cards and the updated session.
- `GET /api/learning/traces/{traceId}` returns the ordered product trace projection. M2 has no trace UI.

Mutation response bodies inside `ApiResponse.data` are:

```text
Created          = {traceId, session}
LearningTurn     = {traceId, answer, session}
QuizGenerated    = {traceId, quiz, session}
QuizResult       = {traceId, quizId, score, feedback, session}
CardsGenerated   = {traceId, knowledgePointId, cards, session}
```

`LearningTurnResponse` deliberately uses `answer`; there is no separate `explanation` field.
For `/explain`, `answer` is the generated explanation. For `/messages`, it is the answer to the user's question.

## DTOs

`LearningSessionResponse`:

```text
id, learningGoal, knowledgeBaseId, status, errorMessage,
activeKnowledgePoint, plan, currentQuiz, cards
```

`status` is `ACTIVE` or `COMPLETED`. `plan` contains ordered knowledge points with
`id, sequenceNo, topic, subtopics, estimatedMinutes, status, explanation, errorMessage`.
When the session is complete, the focus artifacts belong to the last knowledge point.
Nullable recovery fields (`errorMessage`, `activeKnowledgePoint`, `currentQuiz`, point `explanation`/`errorMessage`,
quiz `score`/`feedback`, and card `sourceChunkId`) are emitted explicitly as JSON `null` rather than omitted.

`QuizResponse` contains `quizId, knowledgePointId, questions, score, feedback`.
Each public question only contains `questionIndex, question, options, sourceChunkId`; correct answers are exposed only after submission in feedback.
`questionIndex` is zero-based and corresponds to the position in the five-element submission `answers` array.

`CardResponse` contains `id, front, back, sourceChunkId`. `sourceChunkId` is `string | null`:
only a verified real chunk ID is stored; absence of source is represented by `null`, never a fabricated ID.

`TraceEventResponse` contains `sequenceNo, stage, eventType, summary, status, createdAt`.

All Java `Long`/`long` identifiers are serialized as JSON strings by the shared Jackson configuration.
This includes session, knowledge-base, knowledge-point, quiz and card IDs; clients must not coerce them to JavaScript numbers.
Business counters such as `sequenceNo`, `questionIndex` and `score` remain JSON numbers.
