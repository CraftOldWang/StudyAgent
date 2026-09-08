package com.studyagent.learning;

import com.studyagent.algo.chunk.JtokkitTokenCounter;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;

/** Selects whole messages; a tool exchange always remains in the same turn. */
public final class LearningCompactionPolicy {
    private LearningCompactionPolicy() { }

    public static List<Msg> point(List<Msg> messages, Long pointId) {
        return messages.stream().filter(m -> LearningContextMessages.belongsTo(m, pointId)).toList();
    }

    public static Selection threshold(List<Msg> messages, Long pointId, Long turnId, int threshold) {
        List<Msg> latest = messages.stream().filter(m -> m.getMetadata() != null
                && turnId.toString().equals(m.getMetadata().get(LearningContextMessages.TURN))).toList();
        // A single large tool turn must not keep the context above budget indefinitely.
        List<Msg> keep = tokens(latest) < threshold / 2 ? latest : List.of();
        List<Msg> prefix = messages.stream().filter(m -> !keep.contains(m)).toList();
        return new Selection(prefix.stream().filter(m -> !LearningContextMessages.belongsTo(m, pointId)).toList(),
                point(prefix, pointId), keep);
    }

    public static List<Msg> replacePoint(List<Msg> messages, Long pointId, Msg summary) {
        List<Msg> result = new ArrayList<>();
        boolean inserted = false;
        for (Msg message : messages) {
            if (LearningContextMessages.belongsTo(message, pointId)) {
                if (!inserted) { result.add(summary); inserted = true; }
            } else { result.add(message); }
        }
        return List.copyOf(result);
    }

    public static int tokens(List<Msg> messages) {
        return new JtokkitTokenCounter().count(LearningContextMessages.stateJson("budget", "budget", messages));
    }
    public record Selection(List<Msg> older, List<Msg> current, List<Msg> keep) { }
}
