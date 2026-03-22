package com.app.promptle.room.dto;

import com.app.promptle.game.model.GamePhase;

import java.util.List;

public record RoomStateResponse(
        String roomCode,
        GamePhase phase,
        int currentRound,
        int totalRounds,
        List<PlayerDto> players,
        String hostId
) {}
