package com.studyagent.learning;

public final class KnowledgePointLifecycle {

    public KnowledgePointStatus advance(KnowledgePointStatus current, KnowledgePointStatus target) {
        if (current == null || target == null) {
            throw new IllegalArgumentException("Knowledge point states must not be null");
        }

        KnowledgePointStatus expected = switch (current) {
            case NEW -> KnowledgePointStatus.EXPLAINING;
            case EXPLAINING -> KnowledgePointStatus.QUIZZING;
            case QUIZZING -> KnowledgePointStatus.CARD_GENERATING;
            case CARD_GENERATING -> KnowledgePointStatus.COMPLETED;
            case COMPLETED -> throw new IllegalStateException("COMPLETED is a terminal knowledge point state");
        };

        if (target != expected) {
            throw new IllegalStateException(
                    "Knowledge point state can only advance from " + current + " to " + expected + ", not " + target);
        }
        return target;
    }
}
