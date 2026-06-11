package com.app.promptle.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinRoomRequest(@NotBlank @Size(max = 13) String alias, @NotBlank String avatarId) {}
