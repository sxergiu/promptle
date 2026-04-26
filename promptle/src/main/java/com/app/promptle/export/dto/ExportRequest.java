package com.app.promptle.export.dto;

import com.app.promptle.game.dto.ChainDto;
import com.app.promptle.room.dto.PlayerDto;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ExportRequest(
        @NotNull ChainDto chain,
        @NotNull List<PlayerDto> players
) {}
