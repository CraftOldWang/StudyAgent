import {
  Archive,
  BookOpen,
  Bot,
  Brain,
  CheckCircle2,
  Gauge,
  FileText,
  FolderPlus,
  Loader2,
  MessageSquareText,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Send,
  Trash2,
  Upload,
  X
} from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { api, streamLearningAgent } from "./api";
import type {
  AgentEvent,
  ChatMessage,
  EntityId,
  KnowledgeBase,
  KnowledgeDocument,
  PerformancePipelineComparison,
  PerformancePipelineStage,
  PerformanceMultipartPartResponse,
  PerformanceUploadComparison,
  PerformanceUploadStage,
  QuizQuestion,
  RagReference,
  ReviewCard,
  ViewKey
} from "./types";

const viewMeta: Record<ViewKey, { eyebrow: string; title: string }> = {
  knowledge: {
    eyebrow: "知识库管理",
    title: "资料、文档状态和知识库范围"
  },
  agent: {
    eyebrow: "Agent 对话",
    title: "状态化学习流程与工具事件"
  },
  rag: {
    eyebrow: "RAG 测试",
    title: "只验证检索、引用和基于资料回答"
  },
  performance: {
    eyebrow: "性能测试",
    title: "RustFS 上传与 RocketMQ 解耦耗时对比"
  },
  review: {
    eyebrow: "复习系统",
    title: "复习卡 CRUD 与 FSRS 复习"
  }
};

const navItems: Array<{ key: ViewKey; label: string; icon: typeof BookOpen }> = [
  { key: "knowledge", label: "知识库", icon: BookOpen },
  { key: "performance", label: "性能", icon: Gauge },
  { key: "agent", label: "Agent", icon: Bot },
  { key: "rag", label: "RAG 测试", icon: Search },
  { key: "review", label: "复习", icon: Brain }
];

let messageSeed = 1;
let eventSeed = 1;

function App() {
  const [view, setView] = useState<ViewKey>("knowledge");
  const [loading, setLoading] = useState(false);
  const [toast, setToast] = useState("");
  const [error, setError] = useState("");
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedKbId, setSelectedKbId] = useState<EntityId | null>(null);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [editingKb, setEditingKb] = useState<KnowledgeBase | null>(null);
  const [showKbForm, setShowKbForm] = useState(false);
  const [kbName, setKbName] = useState("");
  const [kbDescription, setKbDescription] = useState("");
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [performanceFile, setPerformanceFile] = useState<File | null>(null);
  const [performanceKbId, setPerformanceKbId] = useState<EntityId | null>(null);
  const [performanceChunkSizeMb, setPerformanceChunkSizeMb] = useState(8);
  const [performanceChunkConcurrency, setPerformanceChunkConcurrency] = useState(4);
  const [performanceWaitSeconds, setPerformanceWaitSeconds] = useState(300);
  const [performanceTriggerIndex, setPerformanceTriggerIndex] = useState(false);
  const [uploadComparison, setUploadComparison] = useState<PerformanceUploadComparison | null>(null);
  const [pipelineComparison, setPipelineComparison] = useState<PerformancePipelineComparison | null>(null);
  const [performanceRunning, setPerformanceRunning] = useState<"upload" | "pipeline" | null>(null);
  const [performanceProgress, setPerformanceProgress] = useState("");

  const [agentKbIds, setAgentKbIds] = useState<EntityId[]>([]);
  const [agentSessionId, setAgentSessionId] = useState<EntityId | null>(null);
  const [agentMessages, setAgentMessages] = useState<ChatMessage[]>([]);
  const [agentEvents, setAgentEvents] = useState<AgentEvent[]>([]);
  const [agentInput, setAgentInput] = useState("");
  const [agentStreaming, setAgentStreaming] = useState(false);
  const [agentDraft, setAgentDraft] = useState("");
  const [agentQuizQuestions, setAgentQuizQuestions] = useState<QuizQuestion[]>([]);
  const [quizAnswers, setQuizAnswers] = useState<Record<string, string>>({});

  const [ragKbId, setRagKbId] = useState<EntityId | null>(null);
  const [ragQuestion, setRagQuestion] = useState("");
  const [ragMode, setRagMode] = useState<"answer" | "search">("answer");
  const [ragAnswer, setRagAnswer] = useState("");
  const [ragReferences, setRagReferences] = useState<RagReference[]>([]);
  const [ragRunning, setRagRunning] = useState(false);

  const [reviewCards, setReviewCards] = useState<ReviewCard[]>([]);
  const [reviewFilter, setReviewFilter] = useState("");
  const [showCardForm, setShowCardForm] = useState(false);
  const [editingCard, setEditingCard] = useState<ReviewCard | null>(null);
  const [cardKbId, setCardKbId] = useState<EntityId | null>(null);
  const [cardFront, setCardFront] = useState("");
  const [cardBack, setCardBack] = useState("");
  const [cardTags, setCardTags] = useState("");
  const [dueCards, setDueCards] = useState<ReviewCard[]>([]);
  const [reviewIndex, setReviewIndex] = useState(0);
  const [quizHistory, setQuizHistory] = useState<QuizQuestion[]>([]);
  const [quizHistoryKbId, setQuizHistoryKbId] = useState<EntityId | "">("");

  const meta = viewMeta[view];
  const selectedKb = useMemo(
    () => knowledgeBases.find((kb) => kb.id === selectedKbId) ?? null,
    [knowledgeBases, selectedKbId]
  );
  const currentDueCard = dueCards[reviewIndex] ?? null;

  useEffect(() => {
    void refreshAll();
  }, []);

  useEffect(() => {
    if (selectedKbId) {
      void loadDocuments(selectedKbId);
    } else {
      setDocuments([]);
    }
  }, [selectedKbId]);

  useEffect(() => {
    if (!toast) {
      return;
    }
    const timer = window.setTimeout(() => setToast(""), 3200);
    return () => window.clearTimeout(timer);
  }, [toast]);

  async function runTask<T>(task: () => Promise<T>, successMessage?: string): Promise<T | undefined> {
    setLoading(true);
    setError("");
    try {
      const result = await task();
      if (successMessage) {
        setToast(successMessage);
      }
      return result;
    } catch (err) {
      const message = err instanceof Error ? err.message : "操作失败";
      setError(message);
      setToast(message);
      return undefined;
    } finally {
      setLoading(false);
    }
  }

  async function refreshAll() {
    await runTask(async () => {
      const [kbs, cards, quizzes] = await Promise.all([
        api.listKnowledgeBases(),
        api.listReviewCards(reviewFilter || undefined),
        api.listQuizQuestions()
      ]);
      setKnowledgeBases(kbs);
      setReviewCards(cards);
      setQuizHistory(quizzes);
      const firstKbId = kbs[0]?.id ?? null;
      const nextSelected = selectedKbId && kbs.some((kb) => kb.id === selectedKbId) ? selectedKbId : firstKbId;
      setSelectedKbId(nextSelected);
      setRagKbId((current) => current ?? nextSelected);
      setCardKbId((current) => current ?? nextSelected);
      setPerformanceKbId((current) => current ?? nextSelected);
      setAgentKbIds((current) => (current.length ? current : nextSelected ? [nextSelected] : []));
    });
  }

  async function loadKnowledgeBases() {
    const kbs = await api.listKnowledgeBases();
    setKnowledgeBases(kbs);
    if (!selectedKbId || !kbs.some((kb) => kb.id === selectedKbId)) {
      setSelectedKbId(kbs[0]?.id ?? null);
    }
  }

  async function loadDocuments(knowledgeBaseId: EntityId) {
    const docs = await runTask(() => api.listDocuments(knowledgeBaseId));
    if (docs) {
      setDocuments(docs);
    }
  }

  function openCreateKb() {
    setEditingKb(null);
    setKbName("");
    setKbDescription("");
    setShowKbForm(true);
  }

  function openEditKb(kb: KnowledgeBase) {
    setEditingKb(kb);
    setKbName(kb.name);
    setKbDescription(kb.description ?? "");
    setShowKbForm(true);
  }

  async function submitKnowledgeBase(event: FormEvent) {
    event.preventDefault();
    const name = kbName.trim();
    if (!name) {
      setToast("知识库名称不能为空");
      return;
    }
    const saved = await runTask(async () => {
      if (editingKb) {
        return api.updateKnowledgeBase(editingKb.id, {
          name,
          description: kbDescription.trim()
        });
      }
      return api.createKnowledgeBase({ name, description: kbDescription.trim() });
    }, editingKb ? "知识库已更新" : "知识库已创建");
    if (saved) {
      setShowKbForm(false);
      setEditingKb(null);
      await loadKnowledgeBases();
      setSelectedKbId(saved.id);
    }
  }

  async function archiveKnowledgeBase(kb: KnowledgeBase) {
    await runTask(async () => {
      await api.updateKnowledgeBase(kb.id, { status: "ARCHIVED" });
      await loadKnowledgeBases();
    }, "知识库已归档");
  }

  async function deleteKnowledgeBase(kb: KnowledgeBase) {
    const ok = window.confirm(`确认停用知识库「${kb.name}」吗？文档与索引不会在这里清理。`);
    if (!ok) {
      return;
    }
    await runTask(async () => {
      await api.deleteKnowledgeBase(kb.id);
      await loadKnowledgeBases();
    }, "知识库已停用");
  }

  async function uploadFile(event: FormEvent) {
    event.preventDefault();
    if (!selectedKbId) {
      setToast("请先选择知识库");
      return;
    }
    if (!selectedFile) {
      setToast("请选择资料文件");
      return;
    }
    const result = await runTask(() => api.uploadFile(selectedKbId, selectedFile), "文件已提交处理");
    if (result) {
      setSelectedFile(null);
      await loadDocuments(selectedKbId);
    }
  }

  async function runUploadPerformance(event: FormEvent) {
    event.preventDefault();
    if (!performanceKbId) {
      setToast("请先选择知识库");
      return;
    }
    if (!performanceFile) {
      setToast("请选择测试文件");
      return;
    }
    setPerformanceRunning("upload");
    setPerformanceProgress("前端单请求直传上传中");
    setError("");
    try {
      const normalizedChunkSizeMb = Math.max(5, performanceChunkSizeMb);
      if (normalizedChunkSizeMb !== performanceChunkSizeMb) {
        setPerformanceChunkSizeMb(normalizedChunkSizeMb);
      }
      const chunkSizeBytes = normalizedChunkSizeMb * 1024 * 1024;
      const chunkConcurrency = Math.max(1, performanceChunkConcurrency);

      const directStartedAt = performance.now();
      const directResponse = await api.uploadPerformanceDirect(
        performanceKbId,
        performanceFile,
        performanceTriggerIndex
      );
      const direct = {
        ...directResponse.direct,
        totalMillis: Math.round(performance.now() - directStartedAt)
      };

      setPerformanceProgress("初始化 S3 Multipart Upload");
      const multipartStartedAt = performance.now();
      const initResponse = await api.initPerformanceMultipart(performanceFile, chunkSizeBytes);
      let multipartCompleted = false;
      try {
        setPerformanceProgress(`前端并发分片上传中 0/${initResponse.totalChunks}`);
        const partUploadStartedAt = performance.now();
        const parts = await uploadMultipartPartsFromBrowser(
          performanceFile,
          initResponse.uploadId,
          initResponse.objectKey,
          initResponse.chunkSizeBytes,
          initResponse.totalChunks,
          chunkConcurrency,
          (finished, total) => setPerformanceProgress(`前端并发分片上传中 ${finished}/${total}`)
        );
        const browserUploadMillis = Math.round(performance.now() - partUploadStartedAt);

        setPerformanceProgress("RustFS complete multipart 中");
        const completeResponse = await api.completePerformanceMultipart({
          knowledgeBaseId: performanceKbId,
          filename: performanceFile.name,
          contentType: performanceFile.type || "application/octet-stream",
          fileSize: performanceFile.size,
          objectKey: initResponse.objectKey,
          uploadId: initResponse.uploadId,
          chunkSizeBytes: initResponse.chunkSizeBytes,
          totalChunks: initResponse.totalChunks,
          chunkConcurrency,
          browserUploadMillis,
          triggerIndex: performanceTriggerIndex,
          parts
        });
        multipartCompleted = true;
        const multipart = {
          ...completeResponse.multipart,
          uploadMillis: browserUploadMillis,
          totalMillis: Math.round(performance.now() - multipartStartedAt)
        };
        const result: PerformanceUploadComparison = {
          filename: performanceFile.name,
          contentType: performanceFile.type || "application/octet-stream",
          fileSize: performanceFile.size,
          chunkSizeBytes: initResponse.chunkSizeBytes,
          totalChunks: initResponse.totalChunks,
          chunkConcurrency,
          triggerIndex: performanceTriggerIndex,
          summary: "BROWSER_SIDE_COMPARISON：直传从浏览器单请求开始计时；S3 Multipart 从浏览器 slice 并发上传 chunk 开始计时。",
          direct,
          multipart
        };
        setUploadComparison(result);
      } finally {
        if (!multipartCompleted && initResponse.uploadId && initResponse.objectKey) {
          await api.abortPerformanceMultipart(initResponse.uploadId, initResponse.objectKey).catch(() => undefined);
        }
      }
      setToast("上传性能测试完成");
      await loadDocuments(performanceKbId);
    } catch (err) {
      const message = err instanceof Error ? err.message : "上传性能测试失败";
      setError(message);
      setToast(message);
    } finally {
      setPerformanceProgress("");
      setPerformanceRunning(null);
    }
  }

  async function uploadMultipartPartsFromBrowser(
    file: File,
    uploadId: string,
    objectKey: string,
    chunkSizeBytes: number,
    totalChunks: number,
    chunkConcurrency: number,
    onProgress: (finished: number, total: number) => void
  ) {
    const parts: Array<{ partNumber: number; eTag: string }> = [];
    let nextChunkIndex = 0;
    let finishedChunks = 0;
    const workerCount = Math.min(chunkConcurrency, totalChunks);

    async function worker() {
      while (true) {
        const chunkIndex = nextChunkIndex;
        nextChunkIndex += 1;
        if (chunkIndex >= totalChunks) {
          return;
        }
        const start = chunkIndex * chunkSizeBytes;
        const end = Math.min(file.size, start + chunkSizeBytes);
        const chunk = file.slice(start, end);
        const response: PerformanceMultipartPartResponse = await api.uploadPerformanceMultipartPart(
          uploadId,
          objectKey,
          chunkIndex + 1,
          chunk,
          file.name
        );
        parts[chunkIndex] = {
          partNumber: response.partNumber,
          eTag: response.eTag
        };
        finishedChunks += 1;
        onProgress(finishedChunks, totalChunks);
      }
    }

    await Promise.all(Array.from({ length: workerCount }, () => worker()));
    return parts;
  }

  async function runPipelinePerformance() {
    if (!performanceKbId) {
      setToast("请先选择知识库");
      return;
    }
    if (!performanceFile) {
      setToast("请选择测试文件");
      return;
    }
    setPerformanceRunning("pipeline");
    setError("");
    try {
      const result = await api.comparePipelinePerformance(
        performanceKbId,
        performanceFile,
        Math.max(1, performanceWaitSeconds)
      );
      setPipelineComparison(result);
      setToast("处理链路性能测试完成");
      await loadDocuments(performanceKbId);
    } catch (err) {
      const message = err instanceof Error ? err.message : "处理链路性能测试失败";
      setError(message);
      setToast(message);
    } finally {
      setPerformanceRunning(null);
    }
  }

  function toggleAgentKb(id: EntityId) {
    setAgentKbIds((current) =>
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id]
    );
  }

  function resetAgentSession() {
    setAgentSessionId(null);
    setAgentMessages([]);
    setAgentEvents([]);
    setAgentDraft("");
    setAgentQuizQuestions([]);
    setAgentInput("");
  }

  async function submitAgent(event: FormEvent) {
    event.preventDefault();
    const message = agentInput.trim();
    if (!message) {
      setToast("请输入学习问题");
      return;
    }
    if (agentKbIds.length === 0) {
      setToast("请选择至少一个知识库");
      return;
    }

    setAgentMessages((current) => [...current, { id: messageSeed++, role: "user", content: message }]);
    setAgentInput("");
    setAgentStreaming(true);
    setAgentDraft("");
    let streamedContent = "";

    try {
      const sessionId = agentSessionId ?? (await api.createLearningSession(message, agentKbIds)).sessionId;
      setAgentSessionId(sessionId);
      await streamLearningAgent(sessionId, message, (eventName, data) => {
        const appended = handleAgentEvent(eventName, data);
        if (appended) {
          streamedContent = streamedContent ? `${streamedContent}\n\n${appended}` : appended;
        }
      });
      setAgentMessages((current) => [
        ...current,
        {
          id: messageSeed++,
          role: "assistant",
          content: streamedContent.trim() || "当前阶段已完成，可继续发送消息推进下一阶段。"
        }
      ]);
      setAgentDraft("");
    } catch (err) {
      const messageText = err instanceof Error ? err.message : "Agent 运行失败";
      setAgentMessages((current) => [...current, { id: messageSeed++, role: "system", content: messageText }]);
      setToast(messageText);
    } finally {
      setAgentStreaming(false);
    }
  }

  function handleAgentEvent(eventName: string, data: unknown) {
    setAgentEvents((current) => [
      {
        id: eventSeed++,
        event: eventName,
        data,
        receivedAt: new Date().toLocaleTimeString()
      },
      ...current
    ]);

    const content = eventContent(eventName, data);
    const questions = eventQuizQuestions(eventName, data);
    if (questions.length > 0) {
      setAgentQuizQuestions(questions);
      void loadQuizHistory();
    }
    if (content) {
      setAgentDraft((current) => (current ? `${current}\n\n${content}` : content));
    }
    if (eventName === "error") {
      setAgentMessages((current) => [
        ...current,
        { id: messageSeed++, role: "system", content: eventContent(eventName, data) || "Agent 运行失败" }
      ]);
    }
    return content;
  }

  async function submitRag(event: FormEvent) {
    event.preventDefault();
    const question = ragQuestion.trim();
    if (!ragKbId) {
      setToast("请选择知识库");
      return;
    }
    if (!question) {
      setToast("请输入问题");
      return;
    }
    setRagRunning(true);
    setRagAnswer("运行中...");
    setRagReferences([]);
    try {
      if (ragMode === "answer") {
        const result = await api.ragAnswer(ragKbId, question);
        setRagAnswer(result.answer);
        setRagReferences(result.references);
      } else {
        const result = await api.ragSearch([ragKbId], question);
        setRagAnswer(`召回 ${result.references.length} 条引用。`);
        setRagReferences(result.references);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : "RAG 请求失败";
      setRagAnswer(message);
      setToast(message);
    } finally {
      setRagRunning(false);
    }
  }

  async function loadReviewCards(status = reviewFilter) {
    const cards = await runTask(() => api.listReviewCards(status || undefined));
    if (cards) {
      setReviewCards(cards);
    }
  }

  async function changeReviewFilter(status: string) {
    setReviewFilter(status);
    const cards = await runTask(() => api.listReviewCards(status || undefined));
    if (cards) {
      setReviewCards(cards);
    }
  }

  function openCreateCard() {
    setEditingCard(null);
    setCardFront("");
    setCardBack("");
    setCardTags("");
    setCardKbId(selectedKbId ?? knowledgeBases[0]?.id ?? null);
    setShowCardForm(true);
  }

  function openEditCard(card: ReviewCard) {
    setEditingCard(card);
    setCardFront(card.front);
    setCardBack(card.back);
    setCardTags(readTags(card).join(", "));
    setCardKbId(card.knowledgeBaseId ?? selectedKbId ?? knowledgeBases[0]?.id ?? null);
    setShowCardForm(true);
  }

  async function submitCard(event: FormEvent) {
    event.preventDefault();
    const front = cardFront.trim();
    const back = cardBack.trim();
    if (!front || !back) {
      setToast("正面和背面不能为空");
      return;
    }
    const tags = cardTags
      .split(",")
      .map((tag) => tag.trim())
      .filter(Boolean);
    const saved = await runTask(async () => {
      if (editingCard) {
        return api.updateReviewCard(editingCard.id, { front, back, tags });
      }
      return api.createReviewCard({
        knowledgeBaseId: cardKbId ?? undefined,
        front,
        back,
        tags
      });
    }, editingCard ? "复习卡已更新" : "复习卡已创建");
    if (saved) {
      setShowCardForm(false);
      setEditingCard(null);
      await loadReviewCards();
    }
  }

  async function suspendOrActivateCard(card: ReviewCard) {
    const nextStatus = card.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    await runTask(async () => {
      await api.updateReviewCard(card.id, { status: nextStatus });
      await loadReviewCards();
    }, nextStatus === "ACTIVE" ? "复习卡已恢复" : "复习卡已暂停");
  }

  async function deleteCard(card: ReviewCard) {
    const ok = window.confirm("确认删除这张复习卡吗？后端会将状态标记为 DELETED。");
    if (!ok) {
      return;
    }
    await runTask(async () => {
      await api.deleteReviewCard(card.id);
      await loadReviewCards();
    }, "复习卡已删除");
  }

  async function loadDueCards() {
    const cards = await runTask(() => api.dueReviewCards(20), "到期卡片已加载");
    if (cards) {
      setDueCards(cards);
      setReviewIndex(0);
    }
  }

  async function loadQuizHistory(knowledgeBaseId = quizHistoryKbId) {
    const questions = await runTask(() => api.listQuizQuestions(knowledgeBaseId || undefined));
    if (questions) {
      setQuizHistory(questions);
    }
  }

  async function changeQuizHistoryKb(knowledgeBaseId: EntityId | "") {
    setQuizHistoryKbId(knowledgeBaseId);
    const questions = await runTask(() => api.listQuizQuestions(knowledgeBaseId || undefined));
    if (questions) {
      setQuizHistory(questions);
    }
  }

  async function submitQuizAnswer(questionId: EntityId, answer?: string) {
    const userAnswer = (answer ?? quizAnswers[questionId] ?? "").trim();
    if (!userAnswer) {
      setToast("请先填写答案");
      return;
    }
    const result = await runTask(() => api.answerQuizQuestion(questionId, userAnswer), "答案已提交");
    if (result) {
      setQuizAnswers((current) => ({ ...current, [questionId]: "" }));
      setAgentQuizQuestions((current) =>
        current.map((question) =>
          question.id === questionId ? { ...question, answers: [result, ...question.answers] } : question
        )
      );
      await loadQuizHistory();
    }
  }

  async function submitReview(rating: "AGAIN" | "HARD" | "GOOD" | "EASY") {
    if (!currentDueCard) {
      return;
    }
    const result = await runTask(() => api.submitReview(currentDueCard.id, rating), `已提交 ${rating}`);
    if (result) {
      setDueCards((current) => current.filter((card) => card.id !== currentDueCard.id));
      setReviewIndex(0);
      await loadReviewCards();
    }
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brandMark">SA</div>
          <div>
            <strong>StudyAgent</strong>
            <span>学习备考工作台</span>
          </div>
        </div>
        <nav className="nav" aria-label="主导航">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button
                className={`navItem ${view === item.key ? "active" : ""}`}
                key={item.key}
                type="button"
                onClick={() => setView(item.key)}
                title={item.label}
              >
                <Icon size={18} />
                {item.label}
              </button>
            );
          })}
        </nav>
        <div className="sidebarFoot">
          <button className="ghostButton" type="button" onClick={() => void refreshAll()}>
            <RefreshCw size={16} />
            刷新数据
          </button>
          <div className="miniStatus">{loading ? "正在请求后端" : "Vite 代理 /api -> 8080"}</div>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <p className="eyebrow">{meta.eyebrow}</p>
            <h1>{meta.title}</h1>
          </div>
          <div className={`health ${error ? "danger" : ""}`}>
            {error ? "后端请求异常" : loading ? "同步中" : "已就绪"}
          </div>
        </header>

        {view === "knowledge" && (
          <KnowledgeView
            knowledgeBases={knowledgeBases}
            selectedKbId={selectedKbId}
            selectedKb={selectedKb}
            documents={documents}
            showKbForm={showKbForm}
            editingKb={editingKb}
            kbName={kbName}
            kbDescription={kbDescription}
            selectedFile={selectedFile}
            onSelectKb={setSelectedKbId}
            onOpenCreateKb={openCreateKb}
            onOpenEditKb={openEditKb}
            onArchiveKb={(kb) => void archiveKnowledgeBase(kb)}
            onDeleteKb={(kb) => void deleteKnowledgeBase(kb)}
            onSubmitKb={(event) => void submitKnowledgeBase(event)}
            onCancelKb={() => setShowKbForm(false)}
            onKbNameChange={setKbName}
            onKbDescriptionChange={setKbDescription}
            onFileChange={setSelectedFile}
            onUpload={(event) => void uploadFile(event)}
            onRefreshDocuments={() => selectedKbId && void loadDocuments(selectedKbId)}
          />
        )}

        {view === "agent" && (
          <AgentView
            knowledgeBases={knowledgeBases}
            selectedIds={agentKbIds}
            messages={agentMessages}
            events={agentEvents}
            input={agentInput}
            streaming={agentStreaming}
            draft={agentDraft}
            sessionId={agentSessionId}
            quizQuestions={agentQuizQuestions}
            quizAnswers={quizAnswers}
            onToggleKb={toggleAgentKb}
            onInputChange={setAgentInput}
            onQuizAnswerChange={(questionId, value) =>
              setQuizAnswers((current) => ({ ...current, [questionId]: value }))
            }
            onSubmitQuizAnswer={(questionId) => void submitQuizAnswer(questionId)}
            onSubmit={(event) => void submitAgent(event)}
            onReset={resetAgentSession}
          />
        )}

        {view === "performance" && (
          <PerformanceView
            knowledgeBases={knowledgeBases}
            selectedKbId={performanceKbId}
            selectedFile={performanceFile}
            chunkSizeMb={performanceChunkSizeMb}
            chunkConcurrency={performanceChunkConcurrency}
            waitSeconds={performanceWaitSeconds}
            triggerIndex={performanceTriggerIndex}
            running={performanceRunning}
            progress={performanceProgress}
            uploadResult={uploadComparison}
            pipelineResult={pipelineComparison}
            onKbChange={setPerformanceKbId}
            onFileChange={setPerformanceFile}
            onChunkSizeChange={setPerformanceChunkSizeMb}
            onChunkConcurrencyChange={setPerformanceChunkConcurrency}
            onWaitSecondsChange={setPerformanceWaitSeconds}
            onTriggerIndexChange={setPerformanceTriggerIndex}
            onRunUpload={runUploadPerformance}
            onRunPipeline={() => void runPipelinePerformance()}
          />
        )}

        {view === "rag" && (
          <RagView
            knowledgeBases={knowledgeBases}
            ragKbId={ragKbId}
            question={ragQuestion}
            mode={ragMode}
            answer={ragAnswer}
            references={ragReferences}
            running={ragRunning}
            onKbChange={setRagKbId}
            onQuestionChange={setRagQuestion}
            onModeChange={setRagMode}
            onSubmit={(event) => void submitRag(event)}
          />
        )}

        {view === "review" && (
          <ReviewView
            knowledgeBases={knowledgeBases}
            cards={reviewCards}
            filter={reviewFilter}
            showForm={showCardForm}
            editingCard={editingCard}
            cardKbId={cardKbId}
            cardFront={cardFront}
            cardBack={cardBack}
            cardTags={cardTags}
            dueCard={currentDueCard}
            dueCount={dueCards.length}
            quizHistory={quizHistory}
            quizHistoryKbId={quizHistoryKbId}
            onOpenCreate={openCreateCard}
            onOpenEdit={openEditCard}
            onCancel={() => setShowCardForm(false)}
            onKbChange={setCardKbId}
            onFrontChange={setCardFront}
            onBackChange={setCardBack}
            onTagsChange={setCardTags}
            onSubmit={(event) => void submitCard(event)}
            onFilterChange={(status) => void changeReviewFilter(status)}
            onToggleStatus={(card) => void suspendOrActivateCard(card)}
            onDelete={(card) => void deleteCard(card)}
            onLoadDue={() => void loadDueCards()}
            onSubmitReview={(rating) => void submitReview(rating)}
            onQuizHistoryKbChange={(knowledgeBaseId) => void changeQuizHistoryKb(knowledgeBaseId)}
            onRefreshQuizHistory={() => void loadQuizHistory()}
          />
        )}
      </main>

      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
    </div>
  );
}

interface KnowledgeViewProps {
  knowledgeBases: KnowledgeBase[];
  selectedKbId: EntityId | null;
  selectedKb: KnowledgeBase | null;
  documents: KnowledgeDocument[];
  showKbForm: boolean;
  editingKb: KnowledgeBase | null;
  kbName: string;
  kbDescription: string;
  selectedFile: File | null;
  onSelectKb: (id: EntityId) => void;
  onOpenCreateKb: () => void;
  onOpenEditKb: (kb: KnowledgeBase) => void;
  onArchiveKb: (kb: KnowledgeBase) => void;
  onDeleteKb: (kb: KnowledgeBase) => void;
  onSubmitKb: (event: FormEvent) => void;
  onCancelKb: () => void;
  onKbNameChange: (value: string) => void;
  onKbDescriptionChange: (value: string) => void;
  onFileChange: (file: File | null) => void;
  onUpload: (event: FormEvent) => void;
  onRefreshDocuments: () => void;
}

function KnowledgeView(props: KnowledgeViewProps) {
  return (
    <>
      <div className="grid knowledgeLayout">
        <section className="panel">
          <div className="panelHead">
            <div>
              <h2>知识库</h2>
              <p>创建、编辑、归档知识库，上传资料前先选定范围。</p>
            </div>
            <button className="primaryButton" type="button" onClick={props.onOpenCreateKb}>
              <FolderPlus size={16} />
              新建
            </button>
          </div>
          {props.showKbForm && (
            <form className="form compact" onSubmit={props.onSubmitKb}>
              <input
                value={props.kbName}
                onChange={(event) => props.onKbNameChange(event.target.value)}
                placeholder="知识库名称"
                maxLength={128}
                required
              />
              <textarea
                value={props.kbDescription}
                onChange={(event) => props.onKbDescriptionChange(event.target.value)}
                placeholder="描述"
                rows={3}
              />
              <div className="buttonRow">
                <button className="primaryButton" type="submit">
                  <CheckCircle2 size={16} />
                  {props.editingKb ? "保存" : "创建"}
                </button>
                <button className="ghostButton" type="button" onClick={props.onCancelKb}>
                  <X size={16} />
                  取消
                </button>
              </div>
            </form>
          )}
          <div className="list">
            {props.knowledgeBases.map((kb) => (
              <article className={`kbItem ${props.selectedKbId === kb.id ? "active" : ""}`} key={kb.id}>
                <button className="itemMain" type="button" onClick={() => props.onSelectKb(kb.id)}>
                  <div>
                    <strong>{kb.name}</strong>
                    <span>{kb.description || "暂无描述"}</span>
                  </div>
                  <StatusBadge status={kb.status} />
                </button>
                <div className="itemActions">
                  <button className="iconButton" type="button" title="编辑" onClick={() => props.onOpenEditKb(kb)}>
                    <Pencil size={16} />
                  </button>
                  <button className="iconButton" type="button" title="归档" onClick={() => props.onArchiveKb(kb)}>
                    <Archive size={16} />
                  </button>
                  <button className="iconButton danger" type="button" title="停用" onClick={() => props.onDeleteKb(kb)}>
                    <Trash2 size={16} />
                  </button>
                </div>
              </article>
            ))}
            {props.knowledgeBases.length === 0 && <EmptyState text="暂无知识库。" />}
          </div>
        </section>

        <section className="panel">
          <div className="panelHead">
            <div>
              <h2>上传资料</h2>
              <p>调用 `/api/files/upload`，后端会创建文档并发送索引消息。</p>
            </div>
          </div>
          <form className="form" onSubmit={props.onUpload}>
            <label>
              上传到
              <select
                value={props.selectedKbId ?? ""}
                onChange={(event) => props.onSelectKb(event.target.value)}
              >
                {props.knowledgeBases.map((kb) => (
                  <option value={kb.id} key={kb.id}>
                    {kb.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="fileDrop">
              <input
                type="file"
                accept=".pdf,.md,.markdown,.txt,.doc,.docx,.ppt,.pptx,application/pdf,text/markdown,text/plain"
                onChange={(event) => props.onFileChange(event.target.files?.[0] ?? null)}
              />
              <Upload size={26} />
              <strong>{props.selectedFile ? props.selectedFile.name : "选择 PDF、Markdown 或文档"}</strong>
              <span>{props.selectedFile ? formatBytes(props.selectedFile.size) : "支持后端解析器可识别的学习资料"}</span>
            </label>
            <button className="primaryButton" type="submit">
              <Upload size={16} />
              上传并入库
            </button>
          </form>
        </section>
      </div>

      <section className="panel documentsPanel">
        <div className="panelHead">
          <div>
            <h2>文档状态</h2>
            <p>{props.selectedKb ? `当前知识库：${props.selectedKb.name}` : "请选择知识库"}</p>
          </div>
          <button className="ghostButton" type="button" onClick={props.onRefreshDocuments}>
            <RefreshCw size={16} />
            刷新文档
          </button>
        </div>
        <DocumentTable documents={props.documents} />
      </section>
    </>
  );
}

function DocumentTable({ documents }: { documents: KnowledgeDocument[] }) {
  if (documents.length === 0) {
    return <EmptyState text="这个知识库还没有文档。" />;
  }
  return (
    <div className="tableWrap">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>标题</th>
            <th>解析</th>
            <th>索引</th>
            <th>错误</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          {documents.map((doc) => (
            <tr key={doc.id}>
              <td>{doc.id}</td>
              <td>{doc.title}</td>
              <td>
                <StatusBadge status={doc.parseStatus} />
              </td>
              <td>
                <StatusBadge status={doc.indexStatus} />
              </td>
              <td>{doc.errorMessage || "-"}</td>
              <td>{formatDate(doc.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface PerformanceViewProps {
  knowledgeBases: KnowledgeBase[];
  selectedKbId: EntityId | null;
  selectedFile: File | null;
  chunkSizeMb: number;
  chunkConcurrency: number;
  waitSeconds: number;
  triggerIndex: boolean;
  running: "upload" | "pipeline" | null;
  progress: string;
  uploadResult: PerformanceUploadComparison | null;
  pipelineResult: PerformancePipelineComparison | null;
  onKbChange: (id: EntityId) => void;
  onFileChange: (file: File | null) => void;
  onChunkSizeChange: (value: number) => void;
  onChunkConcurrencyChange: (value: number) => void;
  onWaitSecondsChange: (value: number) => void;
  onTriggerIndexChange: (value: boolean) => void;
  onRunUpload: (event: FormEvent) => void;
  onRunPipeline: () => void;
}

function PerformanceView(props: PerformanceViewProps) {
  const busy = props.running !== null;
  const runningTitle = props.running === "upload" ? "直传 vs 分片测试运行中" : "同步 vs RocketMQ 测试运行中";
  const runningDetail =
    props.running === "upload"
      ? "正在写入 RustFS 和 MySQL，后端日志会输出直传、S3 Multipart part 上传、complete 和入库耗时。"
      : "正在等待真实解析、embedding 和 ES 索引完成；RocketMQ 消费状态会持续写入后端日志。";
  return (
    <div className="performanceLayout">
      <section className="panel">
        <div className="panelHead">
          <div>
            <h2>测试输入</h2>
            <p>选择同一个文件后，分别运行上传对比和处理链路对比。</p>
          </div>
        </div>
        <form className="form performanceForm" onSubmit={props.onRunUpload}>
          <label>
            知识库
            <select value={props.selectedKbId ?? ""} onChange={(event) => props.onKbChange(event.target.value)}>
              {props.knowledgeBases.map((kb) => (
                <option value={kb.id} key={kb.id}>
                  {kb.name}
                </option>
              ))}
            </select>
          </label>
          <label className="fileDrop">
            <input
              type="file"
              accept=".pdf,.md,.markdown,.txt,.doc,.docx,.ppt,.pptx,application/pdf,text/markdown,text/plain"
              onChange={(event) => props.onFileChange(event.target.files?.[0] ?? null)}
              disabled={busy}
            />
            <Upload size={26} />
            <strong>{props.selectedFile ? props.selectedFile.name : "选择测试文件"}</strong>
            <span>{props.selectedFile ? formatBytes(props.selectedFile.size) : "会真实写入 RustFS、MySQL、RocketMQ、embedding 和 ES"}</span>
          </label>
          <div className="twoColumnForm">
            <label>
              分片大小 MB
              <input
                type="number"
                min={5}
                value={props.chunkSizeMb}
                onChange={(event) => props.onChunkSizeChange(Number(event.target.value))}
                disabled={busy}
              />
            </label>
            <label>
              分片并发数
              <input
                type="number"
                min={1}
                max={64}
                value={props.chunkConcurrency}
                onChange={(event) => props.onChunkConcurrencyChange(Number(event.target.value))}
                disabled={busy}
              />
            </label>
          </div>
          <div className="twoColumnForm">
            <label>
              MQ 等待秒数
              <input
                type="number"
                min={1}
                value={props.waitSeconds}
                onChange={(event) => props.onWaitSecondsChange(Number(event.target.value))}
                disabled={busy}
              />
            </label>
          </div>
          <label className="checkRow">
            <input
              type="checkbox"
              checked={props.triggerIndex}
              onChange={(event) => props.onTriggerIndexChange(event.target.checked)}
              disabled={busy}
            />
            <span>上传对比后也发送索引消息</span>
          </label>
          <div className="buttonRow">
            <button className="primaryButton" type="submit" disabled={busy}>
              {props.running === "upload" ? <Loader2 className="spin" size={16} /> : <Gauge size={16} />}
              直传 vs S3 分片
            </button>
            <button className="ghostButton" type="button" onClick={props.onRunPipeline} disabled={busy}>
              {props.running === "pipeline" ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
              同步 vs RocketMQ
            </button>
          </div>
          {props.running && (
            <div className="runningNotice" role="status">
              <Loader2 className="spin" size={16} />
              <div>
                <strong>{runningTitle}</strong>
                <span>{props.progress || runningDetail}</span>
              </div>
            </div>
          )}
        </form>
      </section>

      <section className="panel resultPanel">
        <h2>上传对比结果</h2>
        {props.uploadResult ? (
          <UploadPerformanceResult result={props.uploadResult} />
        ) : (
          <EmptyState text="运行直传 vs S3 Multipart 后显示 RustFS 上传耗时。" />
        )}
      </section>

      <section className="panel resultPanel fullWidthPanel">
        <h2>处理链路结果</h2>
        {props.pipelineResult ? (
          <PipelinePerformanceResult result={props.pipelineResult} />
        ) : (
          <EmptyState text="运行同步 vs RocketMQ 后显示真实 embedding 和 ES 索引耗时。" />
        )}
      </section>
    </div>
  );
}

function UploadPerformanceResult({ result }: { result: PerformanceUploadComparison }) {
  return (
    <div className="metricStack">
      <div className="badgeRow">
        <span className="badge">{formatBytes(result.fileSize)}</span>
        <span className="badge">chunk {formatBytes(result.chunkSizeBytes)}</span>
        <span className="badge">{result.totalChunks} chunks</span>
        <span className="badge">并发 {result.chunkConcurrency}</span>
        <span className="badge">{result.triggerIndex ? "已触发索引" : "仅上传入库"}</span>
      </div>
      <div className="metricGrid">
        <UploadStageCard title="单文件直传" stage={result.direct} />
        <UploadStageCard title="S3 Multipart" stage={result.multipart} />
      </div>
    </div>
  );
}

function UploadStageCard({ title, stage }: { title: string; stage: PerformanceUploadStage }) {
  return (
    <article className="metricCard">
      <div className="itemRow">
        <strong>{title}</strong>
        <StatusBadge status={stage.mode} />
      </div>
      <Metric label="总耗时" value={formatMillis(stage.totalMillis)} strong />
      <Metric label="上传耗时" value={formatMillis(stage.uploadMillis)} />
      <Metric label={stage.mode.includes("MULTIPART") ? "complete 耗时" : "合并耗时"} value={formatMillis(stage.mergeMillis)} />
      <Metric label="入库耗时" value={formatMillis(stage.databaseMillis)} />
      <div className="badgeRow">
        <span className="badge">file {stage.fileId}</span>
        <span className="badge">doc {stage.documentId}</span>
      </div>
    </article>
  );
}

function PipelinePerformanceResult({ result }: { result: PerformancePipelineComparison }) {
  return (
    <div className="metricStack">
      <div className="badgeRow">
        <span className="badge">{formatBytes(result.fileSize)}</span>
        <span className="badge">timeout {formatMillis(result.waitTimeoutMillis)}</span>
      </div>
      <div className="metricGrid">
        <PipelineStageCard title="传统同步处理" stage={result.synchronous} />
        <PipelineStageCard title="RocketMQ 解耦" stage={result.rocketMq} />
      </div>
    </div>
  );
}

function PipelineStageCard({ title, stage }: { title: string; stage: PerformancePipelineStage }) {
  return (
    <article className="metricCard">
      <div className="itemRow">
        <strong>{title}</strong>
        <StatusBadge status={stage.indexStatus} />
      </div>
      <Metric label="用户等待" value={formatMillis(stage.responseMillis)} strong />
      <Metric label="最终索引完成" value={formatMillis(stage.indexedMillis)} />
      <Metric label="对象上传" value={formatMillis(stage.uploadMillis)} />
      <Metric label="业务入库" value={formatMillis(stage.databaseMillis)} />
      <Metric label="消息发送" value={formatMillis(stage.messageMillis)} />
      <Metric label="同步处理" value={formatMillis(stage.processingMillis)} />
      <div className="badgeRow">
        <span className="badge">file {stage.fileId}</span>
        <span className="badge">doc {stage.documentId}</span>
        <span className="badge">chunks {stage.childChunkCount}</span>
        <span className="badge">{stage.parseStatus}</span>
      </div>
      {stage.errorMessage && <p className="errorText">{stage.errorMessage}</p>}
    </article>
  );
}

function Metric({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={`metric ${strong ? "strong" : ""}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

interface AgentViewProps {
  knowledgeBases: KnowledgeBase[];
  selectedIds: EntityId[];
  messages: ChatMessage[];
  events: AgentEvent[];
  input: string;
  streaming: boolean;
  draft: string;
  sessionId: EntityId | null;
  quizQuestions: QuizQuestion[];
  quizAnswers: Record<string, string>;
  onToggleKb: (id: EntityId) => void;
  onInputChange: (value: string) => void;
  onQuizAnswerChange: (questionId: EntityId, value: string) => void;
  onSubmitQuizAnswer: (questionId: EntityId) => void;
  onSubmit: (event: FormEvent) => void;
  onReset: () => void;
}

function AgentView(props: AgentViewProps) {
  return (
    <div className="chatLayout">
      <section className="panel chatPanel">
        <div className="panelHead">
          <div>
            <h2>Agent 对话</h2>
            <p>{props.sessionId ? `当前 sessionId: ${props.sessionId}` : "新消息会创建学习会话，每次对话推进一个阶段"}</p>
          </div>
          <button className="ghostButton" type="button" onClick={props.onReset}>
            <Plus size={16} />
            新会话
          </button>
        </div>
        <div className="messages">
          {props.messages.map((message) => (
            <div className={`message ${message.role}`} key={message.id}>
              {message.content}
            </div>
          ))}
          {props.streaming && (
            <div className="message assistant">
              <Loader2 className="spin" size={16} />
              {props.draft || "Agent 正在运行学习工作流..."}
            </div>
          )}
          {props.messages.length === 0 && !props.streaming && (
            <EmptyState text="发送学习目标后，Agent 会按阶段推进；你也可以随时提问、要求测验或请求生成复习卡。" />
          )}
          {props.quizQuestions.length > 0 && (
            <QuizQuestionCards
              questions={props.quizQuestions}
              answers={props.quizAnswers}
              onAnswerChange={props.onQuizAnswerChange}
              onSubmitAnswer={props.onSubmitQuizAnswer}
            />
          )}
        </div>
        <form className="chatForm" onSubmit={props.onSubmit}>
          <textarea
            rows={3}
            value={props.input}
            onChange={(event) => props.onInputChange(event.target.value)}
            placeholder={props.sessionId ? "可以继续、提问、要求测验，或让它生成复习卡" : "例如：根据资料讲讲 Java 线程池的核心参数"}
            disabled={props.streaming}
          />
          <button className="primaryButton" type="submit" disabled={props.streaming}>
            <Send size={16} />
            {props.sessionId ? "发送" : "开始"}
          </button>
        </form>
      </section>
      <aside className="panel sidePanel">
        <h2>知识库范围</h2>
        <div className="checkList">
          {props.knowledgeBases.map((kb) => (
            <label className="checkRow" key={kb.id}>
              <input
                type="checkbox"
                checked={props.selectedIds.includes(kb.id)}
                onChange={() => props.onToggleKb(kb.id)}
                disabled={props.streaming}
              />
              <span>{kb.name}</span>
            </label>
          ))}
        </div>
        <h2>运行事件</h2>
        <div className="eventList">
          {props.events.map((event) => (
            <article className="eventItem" key={event.id}>
              <div>
                <span className="eventName">{event.event}</span>
                <span className="eventTime">{event.receivedAt}</span>
              </div>
              <code>{shortJson(event.data)}</code>
            </article>
          ))}
          {props.events.length === 0 && <EmptyState text="SSE 事件会显示在这里。" />}
        </div>
      </aside>
    </div>
  );
}

interface RagViewProps {
  knowledgeBases: KnowledgeBase[];
  ragKbId: EntityId | null;
  question: string;
  mode: "answer" | "search";
  answer: string;
  references: RagReference[];
  running: boolean;
  onKbChange: (id: EntityId) => void;
  onQuestionChange: (value: string) => void;
  onModeChange: (mode: "answer" | "search") => void;
  onSubmit: (event: FormEvent) => void;
}

function RagView(props: RagViewProps) {
  return (
    <div className="ragLayout">
      <section className="panel">
        <div className="panelHead">
          <div>
            <h2>RAG 问答</h2>
            <p>问答调用 `/api/chat/rag`，召回测试调用 `/api/chat/rag/search`。</p>
          </div>
        </div>
        <form className="form" onSubmit={props.onSubmit}>
          <label>
            知识库
            <select value={props.ragKbId ?? ""} onChange={(event) => props.onKbChange(event.target.value)}>
              {props.knowledgeBases.map((kb) => (
                <option key={kb.id} value={kb.id}>
                  {kb.name}
                </option>
              ))}
            </select>
          </label>
          <textarea
            rows={6}
            value={props.question}
            onChange={(event) => props.onQuestionChange(event.target.value)}
            placeholder="输入一个只希望基于知识库回答的问题"
          />
          <div className="segmented" role="group" aria-label="RAG 模式">
            <label>
              <input
                type="radio"
                checked={props.mode === "answer"}
                onChange={() => props.onModeChange("answer")}
              />
              生成回答
            </label>
            <label>
              <input
                type="radio"
                checked={props.mode === "search"}
                onChange={() => props.onModeChange("search")}
              />
              只看召回
            </label>
          </div>
          <button className="primaryButton" type="submit" disabled={props.running}>
            {props.running ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
            运行 RAG
          </button>
        </form>
      </section>
      <section className="panel resultPanel">
        <h2>结果</h2>
        <div className="answerBox">{props.answer || "等待问题。"}</div>
        <h2>引用</h2>
        <ReferenceList references={props.references} />
      </section>
    </div>
  );
}

function ReferenceList({ references }: { references: RagReference[] }) {
  if (references.length === 0) {
    return <EmptyState text="暂无引用。" />;
  }
  return (
    <div className="referenceList">
      {references.map((reference, index) => (
        <article className="referenceItem" key={`${reference.chunkId}-${index}`}>
          <div className="badgeRow">
            <span className="badge">#{index + 1}</span>
            <span className="badge">doc {reference.documentId}</span>
            <span className="badge">chunk {reference.chunkId}</span>
            <span className="badge">{reference.retrievalSource}</span>
            <span className="badge">score {reference.score.toFixed(4)}</span>
          </div>
          <strong>{reference.documentTitle}</strong>
          <p>{reference.content}</p>
        </article>
      ))}
    </div>
  );
}

interface QuizQuestionCardsProps {
  questions: QuizQuestion[];
  answers: Record<string, string>;
  onAnswerChange: (questionId: EntityId, value: string) => void;
  onSubmitAnswer: (questionId: EntityId) => void;
}

function QuizQuestionCards(props: QuizQuestionCardsProps) {
  return (
    <div className="quizCards">
      <h2>测验题</h2>
      {props.questions.map((question, index) => (
        <article className="quizCard" key={question.id}>
          <div className="badgeRow">
            <span className="badge">第 {index + 1} 题</span>
            <span className="badge">{question.questionType}</span>
            {question.knowledgeBaseId && <span className="badge">KB {question.knowledgeBaseId}</span>}
          </div>
          <strong>{question.questionText}</strong>
          {question.options.length > 0 && (
            <div className="optionList">
              {question.options.map((option) => (
                <span className="badge" key={option}>
                  {option}
                </span>
              ))}
            </div>
          )}
          <textarea
            rows={3}
            value={props.answers[question.id] ?? ""}
            onChange={(event) => props.onAnswerChange(question.id, event.target.value)}
            placeholder="写下你的答案"
          />
          <div className="buttonRow">
            <button className="primaryButton" type="button" onClick={() => props.onSubmitAnswer(question.id)}>
              <CheckCircle2 size={16} />
              提交答案
            </button>
          </div>
          {question.answers[0] && (
            <div className="answerFeedback">
              <strong>最近评价：{question.answers[0].score ?? "-"} 分</strong>
              <p>{question.answers[0].evaluation}</p>
            </div>
          )}
          <details>
            <summary>查看标准答案</summary>
            <p>{question.correctAnswer}</p>
            {question.explanation && <p>{question.explanation}</p>}
          </details>
        </article>
      ))}
    </div>
  );
}

interface ReviewViewProps {
  knowledgeBases: KnowledgeBase[];
  cards: ReviewCard[];
  filter: string;
  showForm: boolean;
  editingCard: ReviewCard | null;
  cardKbId: EntityId | null;
  cardFront: string;
  cardBack: string;
  cardTags: string;
  dueCard: ReviewCard | null;
  dueCount: number;
  quizHistory: QuizQuestion[];
  quizHistoryKbId: EntityId | "";
  onOpenCreate: () => void;
  onOpenEdit: (card: ReviewCard) => void;
  onCancel: () => void;
  onKbChange: (id: EntityId) => void;
  onFrontChange: (value: string) => void;
  onBackChange: (value: string) => void;
  onTagsChange: (value: string) => void;
  onSubmit: (event: FormEvent) => void;
  onFilterChange: (status: string) => void;
  onToggleStatus: (card: ReviewCard) => void;
  onDelete: (card: ReviewCard) => void;
  onLoadDue: () => void;
  onSubmitReview: (rating: "AGAIN" | "HARD" | "GOOD" | "EASY") => void;
  onQuizHistoryKbChange: (knowledgeBaseId: EntityId | "") => void;
  onRefreshQuizHistory: () => void;
}

function ReviewView(props: ReviewViewProps) {
  return (
    <div className="reviewLayout">
      <section className="panel">
        <div className="panelHead">
          <div>
            <h2>复习卡 CRUD</h2>
            <p>手动维护卡片，也能查看 Agent 写入的复习卡。</p>
          </div>
          <button className="primaryButton" type="button" onClick={props.onOpenCreate}>
            <Plus size={16} />
            新建卡片
          </button>
        </div>
        {props.showForm && (
          <form className="form compact" onSubmit={props.onSubmit}>
            <label>
              关联知识库
              <select value={props.cardKbId ?? ""} onChange={(event) => props.onKbChange(event.target.value)}>
                {props.knowledgeBases.map((kb) => (
                  <option value={kb.id} key={kb.id}>
                    {kb.name}
                  </option>
                ))}
              </select>
            </label>
            <textarea
              rows={3}
              value={props.cardFront}
              onChange={(event) => props.onFrontChange(event.target.value)}
              placeholder="正面：问题、提示或概念"
              required
            />
            <textarea
              rows={4}
              value={props.cardBack}
              onChange={(event) => props.onBackChange(event.target.value)}
              placeholder="背面：答案、解释或例子"
              required
            />
            <input
              value={props.cardTags}
              onChange={(event) => props.onTagsChange(event.target.value)}
              placeholder="标签，用逗号分隔"
            />
            <div className="buttonRow">
              <button className="primaryButton" type="submit">
                <CheckCircle2 size={16} />
                {props.editingCard ? "保存卡片" : "创建卡片"}
              </button>
              <button className="ghostButton" type="button" onClick={props.onCancel}>
                <X size={16} />
                取消
              </button>
            </div>
          </form>
        )}
        <div className="filterRow">
          {[
            ["", "全部"],
            ["ACTIVE", "Active"],
            ["SUSPENDED", "Suspended"]
          ].map(([status, label]) => (
            <button
              className={`filterButton ${props.filter === status ? "active" : ""}`}
              type="button"
              key={status}
              onClick={() => props.onFilterChange(status)}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="cardList">
          {props.cards.map((card) => (
            <article className="reviewCard" key={card.id}>
              <div className="itemRow">
                <div>
                  <strong>{card.front}</strong>
                  <p>{card.back}</p>
                </div>
                <StatusBadge status={card.status} />
              </div>
              <div className="badgeRow">
                <span className="badge">due {formatDate(card.dueAt)}</span>
                <span className="badge">state {card.cardState}</span>
                <span className="badge">reps {card.reps}</span>
                {readTags(card).map((tag) => (
                  <span className="badge" key={tag}>
                    {tag}
                  </span>
                ))}
              </div>
              <div className="itemActions">
                <button className="iconButton" type="button" title="编辑" onClick={() => props.onOpenEdit(card)}>
                  <Pencil size={16} />
                </button>
                <button className="iconButton" type="button" title="暂停/恢复" onClick={() => props.onToggleStatus(card)}>
                  <Archive size={16} />
                </button>
                <button className="iconButton danger" type="button" title="删除" onClick={() => props.onDelete(card)}>
                  <Trash2 size={16} />
                </button>
              </div>
            </article>
          ))}
          {props.cards.length === 0 && <EmptyState text="暂无复习卡。" />}
        </div>
      </section>

      <section className="panel reviewSession">
        <div className="panelHead">
          <div>
            <h2>开始复习</h2>
            <p>{props.dueCount ? `队列中还有 ${props.dueCount} 张卡` : "加载到期卡后开始复习"}</p>
          </div>
          <button className="ghostButton" type="button" onClick={props.onLoadDue}>
            <RefreshCw size={16} />
            加载到期
          </button>
        </div>
        {props.dueCard ? (
          <div className="reviewBox">
            <div className="reviewFront">
              <strong>正面</strong>
              {props.dueCard.front}
            </div>
            <div className="reviewBack">
              <strong>背面</strong>
              {props.dueCard.back}
            </div>
            <div className="ratingRow">
              {(["AGAIN", "HARD", "GOOD", "EASY"] as const).map((rating) => (
                <button type="button" key={rating} onClick={() => props.onSubmitReview(rating)}>
                  {rating}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <EmptyState text="暂无到期卡片。" />
        )}

        <div className="quizHistory">
          <div className="panelHead">
            <div>
              <h2>历史题目</h2>
              <p>按知识库筛选 Agent 生成过的测验题和作答记录。</p>
            </div>
            <button className="ghostButton" type="button" onClick={props.onRefreshQuizHistory}>
              <RefreshCw size={16} />
              刷新
            </button>
          </div>
          <label>
            知识库筛选
            <select
              value={props.quizHistoryKbId}
              onChange={(event) => props.onQuizHistoryKbChange(event.target.value)}
            >
              <option value="">全部知识库</option>
              {props.knowledgeBases.map((kb) => (
                <option value={kb.id} key={kb.id}>
                  {kb.name}
                </option>
              ))}
            </select>
          </label>
          <div className="historyList">
            {props.quizHistory.map((question) => (
              <article className="historyItem" key={question.id}>
                <div className="badgeRow">
                  <span className="badge">{question.questionType}</span>
                  {question.knowledgeBaseId && <span className="badge">KB {question.knowledgeBaseId}</span>}
                  <span className="badge">{formatDate(question.createdAt)}</span>
                </div>
                <strong>{question.questionText}</strong>
                <p>{question.correctAnswer}</p>
                {question.answers[0] && (
                  <div className="answerFeedback">
                    <strong>最近作答：{question.answers[0].score ?? "-"} 分</strong>
                    <p>{question.answers[0].userAnswer}</p>
                    <p>{question.answers[0].evaluation}</p>
                  </div>
                )}
              </article>
            ))}
            {props.quizHistory.length === 0 && <EmptyState text="暂无历史题目。" />}
          </div>
        </div>
      </section>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const normalized = status.toUpperCase();
  const tone =
    normalized.includes("FAILED") || normalized.includes("DELETED")
      ? "danger"
      : normalized.includes("PENDING") || normalized.includes("UPLOADED") || normalized.includes("RUNNING")
        ? "warning"
        : "success";
  return <span className={`badge ${tone}`}>{status}</span>;
}

function EmptyState({ text }: { text: string }) {
  return (
    <div className="emptyState">
      <FileText size={20} />
      {text}
    </div>
  );
}

function eventContent(eventName: string, data: unknown): string {
  const payload = data as Record<string, unknown> | null;
  if (!payload || typeof payload !== "object") {
    return "";
  }
  if (eventName === "token.delta" && typeof payload.content === "string") {
    return `【${payload.stage ?? "内容"}】\n${payload.content}`;
  }
  if (eventName === "quiz.generated" && typeof payload.content === "string") {
    return `【即时测验】\n${payload.content}`;
  }
  if (eventName === "card.generated" && typeof payload.content === "string") {
    return `【复习卡】\n${payload.content}`;
  }
  if (eventName === "tool.completed") {
    return `工具完成：${payload.toolName ?? ""}，命中 ${payload.hitCount ?? payload.cardCount ?? "-"} 条`;
  }
  if (eventName === "tool.failed" || eventName === "error") {
    return `错误：${payload.message ?? "未知错误"}`;
  }
  if (eventName === "agent.stage.waiting") {
    return `阶段已完成：${payload.completedStage ?? "-"}\n下一阶段：${payload.nextStage ?? "-"}\n${payload.message ?? ""}`;
  }
  return "";
}

function eventQuizQuestions(eventName: string, data: unknown): QuizQuestion[] {
  if (eventName !== "quiz.generated" || !data || typeof data !== "object") {
    return [];
  }
  const payload = data as { questions?: unknown };
  return Array.isArray(payload.questions) ? (payload.questions as QuizQuestion[]) : [];
}

function readTags(card: ReviewCard): string[] {
  if (!card.tagsJson) {
    return [];
  }
  try {
    const parsed = JSON.parse(card.tagsJson);
    return Array.isArray(parsed) ? parsed.filter((item) => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function shortJson(value: unknown) {
  const text = typeof value === "string" ? value : JSON.stringify(value);
  if (!text) {
    return "";
  }
  return text.length > 180 ? `${text.slice(0, 180)}...` : text;
}

function formatDate(value?: string | null) {
  if (!value) {
    return "-";
  }
  return value.replace("T", " ").slice(0, 19);
}

function formatBytes(value: number) {
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatMillis(value: number) {
  if (value < 1000) {
    return `${value} ms`;
  }
  return `${(value / 1000).toFixed(2)} s`;
}

export default App;
