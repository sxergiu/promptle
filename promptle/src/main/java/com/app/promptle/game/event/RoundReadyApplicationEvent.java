package com.app.promptle.game.event;

import java.util.Map;
import java.util.UUID;

/**
 * Internal Spring application event — fully implemented in Chunk 9.
 * Maps playerId -> imageUrl for per-player round-ready messages.
 */
public record RoundReadyApplicationEvent(String roomCode, int round, Map<UUID, String> playerImageUrls) {}
