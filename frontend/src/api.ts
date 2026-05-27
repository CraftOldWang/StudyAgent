import type {
  ApiResponse,
  EntityId,
  KnowledgeBase,
  KnowledgeDocument,
  LearningSessionResponse,
  QuizAnswer,
  QuizQuestion,
  RagAnswer,
  RagSearchResult,
  ReviewCard,
  ReviewSubmitResponse,
  UploadResult
} from "./types";

async function parseApiResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get("content-type") ?? "";
  const payload = contentType.includes("application/json")
    ? ((await response.json()) as ApiResponse<T>)
    : null;

  if (!response.ok) {
    throw new Error(payload?.message ?? `HTTP ${response.status}`);
  }
  if (!payload) {
    throw new Error("后端没有返回 JSON");
  }
  if (payload.code !== 0) {
    throw new Error(payload.message || "请求失败");
  }
  return payload.data;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(url, {
    ...init,
    headers
  });
  return parseApiResponse<T>(response);
}

export const api = {
  listKnowledgeBases() {
    return request<KnowledgeBase[]>("/api/knowledge-bases");
  },
  createKnowledgeBase(payload: { name: string; description?: string }) {
    return request<KnowledgeBase>("/api/knowledge-bases", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  updateKnowledgeBase(
    knowledgeBaseId: EntityId,
    payload: { name?: string; description?: string; status?: string }
  ) {
    return request<KnowledgeBase>(`/api/knowledge-bases/${knowledgeBaseId}`, {
      method: "PATCH",
      body: JSON.stringify(payload)
    });
  },
  deleteKnowledgeBase(knowledgeBaseId: EntityId) {
    return request<void>(`/api/knowledge-bases/${knowledgeBaseId}`, {
      method: "DELETE"
    });
  },
  listDocuments(knowledgeBaseId: EntityId) {
    return request<KnowledgeDocument[]>(`/api/knowledge-bases/${knowledgeBaseId}/documents`);
  },
  uploadFile(knowledgeBaseId: EntityId, file: File) {
    const formData = new FormData();
    formData.set("knowledgeBaseId", String(knowledgeBaseId));
    formData.set("file", file);
    return request<UploadResult>("/api/files/upload", {
      method: "POST",
      body: formData
    });
  },
  ragAnswer(knowledgeBaseId: EntityId, question: string) {
    return request<RagAnswer>("/api/chat/rag", {
      method: "POST",
      body: JSON.stringify({ knowledgeBaseId, question })
    });
  },
  ragSearch(knowledgeBaseIds: EntityId[], question: string) {
    return request<RagSearchResult>("/api/chat/rag/search", {
      method: "POST",
      body: JSON.stringify({ knowledgeBaseIds, question })
    });
  },
  createLearningSession(message: string, knowledgeBaseIds: EntityId[]) {
    return request<LearningSessionResponse>("/api/learning/sessions", {
      method: "POST",
      body: JSON.stringify({ message, knowledgeBaseIds })
    });
  },
  listReviewCards(status?: string) {
    const query = status ? `?status=${encodeURIComponent(status)}` : "";
    return request<ReviewCard[]>(`/api/review/cards${query}`);
  },
  dueReviewCards(limit = 20) {
    return request<ReviewCard[]>(`/api/review/cards/due?limit=${limit}`);
  },
  createReviewCard(payload: {
    knowledgeBaseId?: EntityId;
    documentId?: EntityId;
    sessionId?: EntityId;
    front: string;
    back: string;
    tags?: string[];
    sourceMessageId?: EntityId;
    sourceChunkIds?: EntityId[];
  }) {
    return request<ReviewCard>("/api/review/cards", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  updateReviewCard(
    cardId: EntityId,
    payload: { front?: string; back?: string; tags?: string[]; status?: string }
  ) {
    return request<ReviewCard>(`/api/review/cards/${cardId}`, {
      method: "PATCH",
      body: JSON.stringify(payload)
    });
  },
  deleteReviewCard(cardId: EntityId) {
    return request<void>(`/api/review/cards/${cardId}`, {
      method: "DELETE"
    });
  },
  submitReview(cardId: EntityId, rating: "AGAIN" | "HARD" | "GOOD" | "EASY") {
    return request<ReviewSubmitResponse>(`/api/review/cards/${cardId}/reviews`, {
      method: "POST",
      body: JSON.stringify({ rating })
    });
  },
  listQuizQuestions(knowledgeBaseId?: EntityId, limit = 50) {
    const params = new URLSearchParams({ limit: String(limit) });
    if (knowledgeBaseId) {
      params.set("knowledgeBaseId", knowledgeBaseId);
    }
    return request<QuizQuestion[]>(`/api/quizzes/questions?${params.toString()}`);
  },
  answerQuizQuestion(questionId: EntityId, userAnswer: string) {
    return request<QuizAnswer>(`/api/quizzes/questions/${questionId}/answers`, {
      method: "POST",
      body: JSON.stringify({ userAnswer })
    });
  }
};

export async function streamLearningAgent(
  sessionId: EntityId,
  message: string,
  onEvent: (eventName: string, data: unknown) => void
): Promise<void> {
  const response = await fetch(`/api/learning/sessions/${sessionId}/agent/stream`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream"
    },
    body: JSON.stringify({ message })
  });

  if (!response.ok || !response.body) {
    const text = await response.text();
    throw new Error(text || `SSE 请求失败: HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() ?? "";
    for (const part of parts) {
      const parsed = parseSseBlock(part);
      if (parsed) {
        onEvent(parsed.event, parsed.data);
      }
    }
  }

  const finalBlock = parseSseBlock(buffer);
  if (finalBlock) {
    onEvent(finalBlock.event, finalBlock.data);
  }
}

function parseSseBlock(block: string): { event: string; data: unknown } | null {
  if (!block.trim()) {
    return null;
  }
  let event = "message";
  const dataLines: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith("event:")) {
      event = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).trimStart());
    }
  }
  const dataText = dataLines.join("\n");
  if (!dataText) {
    return { event, data: null };
  }
  try {
    return { event, data: JSON.parse(dataText) };
  } catch {
    return { event, data: dataText };
  }
}
