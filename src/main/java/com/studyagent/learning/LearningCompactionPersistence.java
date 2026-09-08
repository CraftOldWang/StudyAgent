package com.studyagent.learning;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.mapper.LearningCompactionMapper;
import com.studyagent.model.LearningCompaction;
import com.studyagent.model.LearningTurn;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningCompactionPersistence {
    private final LearningCompactionMapper summaries;
    private final LearningTurnPersistence turns;

    public LearningCompaction find(LearningTurn turn, String kind, String hash) {
        return summaries.selectOne(Wrappers.<LearningCompaction>query().eq("user_id", turn.getUserId())
                .eq("turn_id", turn.getId()).eq("kind", kind).eq("input_hash", hash));
    }

    @Transactional
    public LearningCompaction save(LearningTurn turn, String kind, String hash, Long pointId, String text) {
        turns.renew(turn);
        LearningCompaction existing = find(turn, kind, hash);
        if (existing != null) { return existing; }
        LearningCompaction summary = new LearningCompaction();
        summary.setUserId(turn.getUserId()); summary.setSessionId(turn.getSessionId()); summary.setTurnId(turn.getId());
        summary.setKnowledgePointId(pointId); summary.setKind(kind); summary.setInputHash(hash);
        summary.setSummaryText(text); summary.setTraceId(turn.getTraceId()); summary.setCreatedAt(LocalDateTime.now());
        summaries.insert(summary);
        return summary;
    }
}
