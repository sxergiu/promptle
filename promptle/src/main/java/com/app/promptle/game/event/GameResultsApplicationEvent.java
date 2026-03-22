package com.app.promptle.game.event;

import com.app.promptle.game.dto.GameResultsEvent;

/**
 * Internal Spring application event carrying game results.
 * Fully implemented in Chunk 12.
 */
public record GameResultsApplicationEvent(String roomCode, GameResultsEvent payload) {}
