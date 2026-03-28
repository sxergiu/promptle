package com.app.promptle.game.event;

import com.app.promptle.game.model.GamePhase;

/**
 * Internal Spring application event — fully implemented in Chunk 8.
 */
public record PhaseChangedApplicationEvent(
        String roomCode,
        GamePhase phase,
        int round,
        int totalRounds,
        long timerSeconds,
        long serverTimestamp
) {}
