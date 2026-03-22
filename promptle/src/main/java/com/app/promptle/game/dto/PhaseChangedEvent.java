package com.app.promptle.game.dto;

import com.app.promptle.game.model.GamePhase;

/**
 * WebSocket outbound event — fully implemented in Chunk 8.
 */
public record PhaseChangedEvent(
        GamePhase phase,
        int round,
        int totalRounds,
        long timerSeconds,
        long serverTimestamp
) {}
