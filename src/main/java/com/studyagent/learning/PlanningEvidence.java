package com.studyagent.learning;

import java.util.ArrayList;
import java.util.List;

/** Select verbatim spans by number; the model need not transcribe formulas to cite them. */
public final class PlanningEvidence {
    private PlanningEvidence() { }

    public static List<Excerpt> excerpts(String content) {
        List<Excerpt> result = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + 400, content.length());
            if (end < content.length()) {
                int boundary = content.lastIndexOf('\n', end - 1);
                if (boundary >= start + 160) { end = boundary + 1; }
                if (Character.isHighSurrogate(content.charAt(end - 1))) { end--; }
            }
            String text = content.substring(start, end).trim();
            if (!text.isEmpty()) { result.add(new Excerpt(result.size() + 1, text)); }
            start = end;
        }
        return List.copyOf(result);
    }

    public record Excerpt(int excerptNo, String text) { }
}
