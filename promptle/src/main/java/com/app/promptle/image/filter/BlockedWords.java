package com.app.promptle.image.filter;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class BlockedWords {

    private final Map<String, String> words;

    public BlockedWords() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("blocked-words.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
            this.words = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("="))
                    .collect(Collectors.toUnmodifiableMap(
                            line -> line.substring(0, line.indexOf('=')).toLowerCase(),
                            line -> line.substring(line.indexOf('=') + 1)
                    ));
        }
    }

    public Map<String, String> getWords() {
        return words;
    }
}
