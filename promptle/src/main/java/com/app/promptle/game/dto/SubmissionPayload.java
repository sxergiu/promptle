package com.app.promptle.game.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmissionPayload(@NotBlank String text) {}
