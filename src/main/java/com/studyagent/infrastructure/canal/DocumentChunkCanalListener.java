package com.studyagent.infrastructure.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.studyagent.common.config.CanalProperties;
import com.studyagent.modules.knowledge.application.DocumentChunkIndexSyncService;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "study-agent.canal", name = "enabled", havingValue = "true")
public class DocumentChunkCanalListener {

    private static final String DOCUMENT_CHUNKS_TABLE = "document_chunks";
    private static final String DOCUMENTS_TABLE = "documents";

    private final CanalProperties properties;
    private final DocumentChunkIndexSyncService documentChunkIndexSyncService;
    private volatile boolean running;
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        worker = new Thread(this::listen, "document-chunk-canal-listener");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void listen() {
        while (running) {
            CanalConnector connector = CanalConnectors.newSingleConnector(
                    new InetSocketAddress(properties.host(), properties.port()),
                    properties.destination(),
                    properties.username(),
                    properties.password()
            );
            try {
                connector.connect();
                connector.subscribe(properties.subscribeRegex());
                connector.rollback();
                while (running) {
                    Message message = connector.getWithoutAck(properties.batchSize());
                    long batchId = message.getId();
                    if (batchId == -1 || message.getEntries().isEmpty()) {
                        sleepQuietly();
                        continue;
                    }
                    try {
                        handleEntries(message.getEntries());
                        connector.ack(batchId);
                    } catch (Exception ex) {
                        connector.rollback(batchId);
                        log.warn("处理 Canal binlog 失败，已回滚 batch: {}", batchId, ex);
                        sleepQuietly();
                    }
                }
            } catch (Exception ex) {
                if (running) {
                    log.warn("Canal 连接或监听异常，将稍后重试", ex);
                }
                sleepQuietly();
            } finally {
                connector.disconnect();
            }
        }
    }

    private void handleEntries(List<CanalEntry.Entry> entries) throws Exception {
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();
            if (eventType != CanalEntry.EventType.INSERT && eventType != CanalEntry.EventType.UPDATE) {
                continue;
            }
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                handleRow(entry.getHeader().getTableName(), eventType, rowData);
            }
        }
    }

    private void handleRow(String tableName, CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        if (DOCUMENTS_TABLE.equals(tableName)) {
            handleDocumentRow(eventType, rowData);
            return;
        }
        if (!DOCUMENT_CHUNKS_TABLE.equals(tableName)) {
            return;
        }
        List<CanalEntry.Column> columns = eventType == CanalEntry.EventType.DELETE
                ? rowData.getBeforeColumnsList()
                : rowData.getAfterColumnsList();
        Long chunkId = longColumn(columns, "id");
        String esDocId = stringColumn(columns, "es_doc_id");
        if (chunkId == null || hasText(esDocId)) {
            return;
        }
        documentChunkIndexSyncService.syncChunk(chunkId);
    }

    private void handleDocumentRow(CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        if (eventType != CanalEntry.EventType.INSERT && eventType != CanalEntry.EventType.UPDATE) {
            return;
        }
        List<CanalEntry.Column> columns = rowData.getAfterColumnsList();
        Long documentId = longColumn(columns, "id");
        String parseStatus = stringColumn(columns, "parse_status");
        String indexStatus = stringColumn(columns, "index_status");
        if (documentId == null
                || !"PARSED".equals(parseStatus)
                || (!"INDEXING".equals(indexStatus) && !"FAILED".equals(indexStatus))) {
            return;
        }
        documentChunkIndexSyncService.syncMissingChunks(documentId);
    }

    private Long longColumn(List<CanalEntry.Column> columns, String name) {
        return columns.stream()
                .filter(column -> Objects.equals(column.getName(), name))
                .findFirst()
                .map(CanalEntry.Column::getValue)
                .filter(this::hasText)
                .map(Long::valueOf)
                .orElse(null);
    }

    private String stringColumn(List<CanalEntry.Column> columns, String name) {
        return columns.stream()
                .filter(column -> Objects.equals(column.getName(), name))
                .findFirst()
                .map(CanalEntry.Column::getValue)
                .orElse(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(properties.emptySleepMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
