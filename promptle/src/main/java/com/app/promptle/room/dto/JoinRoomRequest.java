package com.app.promptle.room.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinRoomRequest(@NotBlank String alias, @NotBlank String avatarId) {}
