package com.app.promptle.image.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class PromptFilter {

    private static final Logger log = LoggerFactory.getLogger(PromptFilter.class);

    private final Map<String, String> replacements;
    private final Pattern pattern;

    public PromptFilter(BlockedWords blockedWords) {
        this.replacements = blockedWords.getWords();
        String joined = replacements.keySet().stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        this.pattern = Pattern.compile("\\b(" + joined + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Returns the prompt with all blocked words replaced by their creative substitutes.
     * If no blocked words are found, returns the original string unchanged.
     */
    public String sanitize(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }
        Matcher matcher = pattern.matcher(prompt);
        if (!matcher.find()) {
            return prompt;
        }
        log.warn("Blocked word detected in prompt, sanitizing");
        matcher.reset();
        return matcher.replaceAll(mr ->
                replacements.getOrDefault(mr.group().toLowerCase(), "thing")
        );
    }

    /** Returns true if the prompt contains at least one blocked word. */
    public boolean containsBlockedWord(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        return pattern.matcher(prompt).find();
    }
}
