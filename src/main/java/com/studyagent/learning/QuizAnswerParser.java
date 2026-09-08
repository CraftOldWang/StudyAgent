package com.studyagent.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Grade only explicitly numbered choices; an incomplete or ambiguous message stays conversational. */
public final class QuizAnswerParser {
    private static final Pattern CHOICE = Pattern.compile("(?<![0-9])([1-5])\\s*[.、:：)）-]?\\s*([A-Da-dＡ-Ｄａ-ｄ])(?![A-Za-z])");
    private QuizAnswerParser() { }

    public static Optional<List<Integer>> parse(String message) {
        if (message == null) { return Optional.empty(); }
        var matcher = CHOICE.matcher(message);
        Integer[] answers = new Integer[5];
        int count = 0;
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            String normalized = java.text.Normalizer.normalize(matcher.group(2), java.text.Normalizer.Form.NFKC).toUpperCase(java.util.Locale.ROOT);
            int answer = normalized.charAt(0) - 'A';
            if (answers[index] != null) { return Optional.empty(); }
            answers[index] = answer; count++;
        }
        if (count != 5) { return Optional.empty(); }
        List<Integer> result = new ArrayList<>(5);
        for (Integer answer : answers) { result.add(answer); }
        return Optional.of(List.copyOf(result));
    }
}
