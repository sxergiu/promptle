package com.app.promptle.game.dto;

import com.app.promptle.game.model.GamePhase;
import com.app.promptle.room.dto.PlayerDto;

import java.util.List;

public record GameStateSnapshot(
        GamePhase phase,
        int currentRound,
        int totalRounds,
        long timerSeconds,
        long serverTimestamp,
        String imageUrl,
        boolean hasSubmitted,
        int submittedCount,
        List<PlayerDto> players,
        String hostId
) {}
