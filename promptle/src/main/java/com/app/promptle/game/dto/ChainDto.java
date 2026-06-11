package com.app.promptle.game.dto;

import java.util.List;

// TODO(chain-style): add `String style` field here and populate it in
// GameService.endGuessingRound() chain-to-DTO mapping when the frontend needs per-chain style display.
public record ChainDto(List<ChainEntryDto> entries) {}
