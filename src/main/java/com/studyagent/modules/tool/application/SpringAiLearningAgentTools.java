package com.studyagent.modules.tool.application;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.rag.domain.RagReference;
import com.studyagent.modules.rag.domain.RagSearchResult;
import com.studyagent.modules.review.interfaces.ReviewCardResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 暴露给 Spring AI 的学习 Agent 工具集合。
 *
 * <p>这个类只负责把模型可见的参数转换成受控工具调用；真正的权限校验、审计和业务执行仍在
 * {@link KnowledgeSearchTool} 与 {@link ReviewCardWriteTool} 中完成。这样既能使用 Spring AI 的
 * tool calling，又不会把资源范围交给模型自由填写。</p>
 */
@Component
@RequiredArgsConstructor
public class SpringAiLearningAgentTools {

    private final KnowledgeSearchTool knowledgeSearchTool;
    private final ReviewCardWriteTool reviewCardWriteTool;
    private final LearningAgentToolContextResolver contextResolver;

    /**
     * 在当前学习会话允许的知识库范围内检索资料。
     */
    @Tool(name = KnowledgeSearchTool.TOOL_NAME, description = """
            在当前学习会话已授权的知识库中检索资料。适用于需要查找教材、笔记、文档片段或引用依据的问题。
            只需要传入用户要检索的问题，用户身份、会话和知识库范围由服务端上下文提供。
            """)
    public KnowledgeSearchToolResult searchKnowledge(
            @ToolParam(description = "要在知识库中检索的学习问题或关键词。") String question,
            ToolContext toolContext
    ) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("检索问题不能为空");
        }
        LearningAgentToolContext context = contextResolver.require(toolContext);
        RagSearchResult result = knowledgeSearchTool.search(
                context.agentRunId(),
                context.sessionId(),
                context.userId(),
                context.allowedKnowledgeBaseIds(),
                question
        );
        return KnowledgeSearchToolResult.from(result);
    }

    /**
     * 将模型生成的复习卡草稿写入当前用户的复习卡系统。
     */
    @Tool(name = ReviewCardWriteTool.TOOL_NAME, description = """
            为当前学习会话写入复习卡。适用于用户要求生成复习卡、记忆卡、闪卡或需要沉淀长期复习内容时。
            卡片必须来自当前会话允许的知识库资料；如果不确定 knowledgeBaseId，可以留空由服务端使用当前会话范围。
            """)
    public ReviewCardWriteToolResult writeReviewCards(
            @ToolParam(description = "要写入的复习卡草稿列表，建议 1 到 5 张。") List<ReviewCardDraftArgument> drafts,
            ToolContext toolContext
    ) {
        if (drafts == null || drafts.isEmpty()) {
            throw new BusinessException("复习卡草稿不能为空");
        }
        LearningAgentToolContext context = contextResolver.require(toolContext);
        List<ReviewCardWriteTool.CardDraft> cardDrafts = drafts.stream()
                .map(ReviewCardDraftArgument::toCardDraft)
                .toList();
        List<ReviewCardResponse> cards = reviewCardWriteTool.writeCards(
                context.agentRunId(),
                context.sessionId(),
                context.userId(),
                context.allowedKnowledgeBaseIds(),
                cardDrafts
        );
        return ReviewCardWriteToolResult.from(cards);
    }

    /**
     * RAG 工具返回给模型的轻量结果，避免把不必要的内部字段暴露给模型。
     */
    public record KnowledgeSearchToolResult(
            String question,
            int hitCount,
            List<KnowledgeReferenceResult> references
    ) {

        private static KnowledgeSearchToolResult from(RagSearchResult result) {
            return new KnowledgeSearchToolResult(
                    result.question(),
                    result.references().size(),
                    result.references().stream()
                            .map(KnowledgeReferenceResult::from)
                            .toList()
            );
        }
    }

    /**
     * 模型可阅读的引用片段信息，保留回答所需的引用标识和正文。
     */
    public record KnowledgeReferenceResult(
            String chunkId,
            Long documentId,
            Long knowledgeBaseId,
            String documentTitle,
            String content,
            String retrievalSource,
            double score
    ) {

        private static KnowledgeReferenceResult from(RagReference reference) {
            return new KnowledgeReferenceResult(
                    reference.chunkId(),
                    reference.documentId(),
                    reference.knowledgeBaseId(),
                    reference.documentTitle(),
                    reference.content(),
                    reference.retrievalSource(),
                    reference.score()
            );
        }
    }

    /**
     * 模型提交的复习卡草稿参数。
     */
    public record ReviewCardDraftArgument(
            @ToolParam(required = false, description = "卡片来源知识库 ID；不确定时可以留空。")
            Long knowledgeBaseId,
            @ToolParam(required = false, description = "卡片来源文档 ID；没有明确来源时可以留空。")
            Long documentId,
            @ToolParam(description = "复习卡正面，通常是一个需要回忆的问题。")
            String front,
            @ToolParam(description = "复习卡背面，给出准确答案、解释或助记提示。")
            String back,
            @ToolParam(required = false, description = "卡片标签，例如 agent、java、exam。")
            List<String> tags,
            @ToolParam(required = false, description = "来源消息 ID；通常由服务端决定，不确定时留空。")
            Long sourceMessageId,
            @ToolParam(required = false, description = "来源 chunk ID 列表，用于追溯引用依据。")
            List<Long> sourceChunkIds
    ) {

        private ReviewCardWriteTool.CardDraft toCardDraft() {
            if (front == null || front.isBlank()) {
                throw new BusinessException("复习卡正面不能为空");
            }
            if (back == null || back.isBlank()) {
                throw new BusinessException("复习卡背面不能为空");
            }
            return new ReviewCardWriteTool.CardDraft(
                    knowledgeBaseId,
                    documentId,
                    front,
                    back,
                    tags == null ? List.of() : tags,
                    sourceMessageId,
                    sourceChunkIds == null ? List.of() : sourceChunkIds
            );
        }
    }

    /**
     * 写卡工具返回给模型的执行摘要。
     */
    public record ReviewCardWriteToolResult(
            int cardCount,
            List<ReviewCardWriteResultItem> cards
    ) {

        private static ReviewCardWriteToolResult from(List<ReviewCardResponse> cards) {
            return new ReviewCardWriteToolResult(
                    cards.size(),
                    cards.stream()
                            .map(ReviewCardWriteResultItem::from)
                            .toList()
            );
        }
    }

    /**
     * 单张已写入复习卡的摘要信息。
     */
    public record ReviewCardWriteResultItem(
            Long cardId,
            Long knowledgeBaseId,
            Long documentId,
            String front,
            String status,
            String cardState
    ) {

        private static ReviewCardWriteResultItem from(ReviewCardResponse card) {
            return new ReviewCardWriteResultItem(
                    card.id(),
                    card.knowledgeBaseId(),
                    card.documentId(),
                    card.front(),
                    card.status(),
                    card.cardState()
            );
        }
    }
}
