package com.app.promptle.room.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRoomRequest(@NotBlank String alias, @NotBlank String avatarId) {}
