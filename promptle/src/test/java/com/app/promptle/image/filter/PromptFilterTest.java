package com.app.promptle.image.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PromptFilterTest {

    private PromptFilter filter;

    @BeforeEach
    void setUp() throws IOException {
        filter = new PromptFilter(new BlockedWords());
    }

    @Test
    void sanitize_blockedWord_replaced() {
        assertEquals("a woolly cat", filter.sanitize("a naked cat"));
    }

    @Test
    void sanitize_caseInsensitive() {
        assertEquals("a woolly cat", filter.sanitize("a NAKED cat"));
    }

    @Test
    void sanitize_multipleWords() {
        assertEquals("woolly syrupy scene", filter.sanitize("naked bloody scene"));
    }

    @Test
    void sanitize_noBlockedWords_unchanged() {
        assertEquals("a happy dog", filter.sanitize("a happy dog"));
    }

    @Test
    void sanitize_partialMatch_ass_notReplaced() {
        // "assassin" contains "ass" but word boundary prevents false positive
        assertEquals("assassin", filter.sanitize("assassin"));
    }

    @Test
    void sanitize_partialMatch_kill_notReplaced() {
        // "skill" contains "kill" but word boundary prevents false positive
        assertEquals("skill", filter.sanitize("skill"));
    }

    @Test
    void sanitize_null_returnsNull() {
        assertNull(filter.sanitize(null));
    }

    @Test
    void sanitize_blank_returnsBlank() {
        assertEquals("   ", filter.sanitize("   "));
    }

    @Test
    void sanitize_creativeReplacement_fullSentence() {
        assertEquals("a woolly syrupy mannequin", filter.sanitize("a naked bloody corpse"));
    }

    @Test
    void containsBlockedWord_true() {
        assertTrue(filter.containsBlockedWord("draw me a nude"));
    }

    @Test
    void containsBlockedWord_false() {
        assertFalse(filter.containsBlockedWord("draw me a cat"));
    }

    @Test
    void containsBlockedWord_null_returnsFalse() {
        assertFalse(filter.containsBlockedWord(null));
    }

    @Test
    void containsBlockedWord_blank_returnsFalse() {
        assertFalse(filter.containsBlockedWord("   "));
    }
}
