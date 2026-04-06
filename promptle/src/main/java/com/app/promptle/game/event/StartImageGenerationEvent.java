package com.app.promptle.game.event;

/**
 * Internal Spring application event published after the GENERATING phase change
 * has been committed. Triggers async image generation in a separate transaction.
 * <p>
 * Separated from {@link PhaseChangedApplicationEvent} so that the phase-change
 * broadcast to clients is always sent <em>before</em> image generation begins
 * (Spring processes after-commit synchronizations in event-publication order).
 */
public record StartImageGenerationEvent(String roomCode) {}
