package com.app.promptle.room.dto;

import java.util.List;

/**
 * Re-exported from room.event package for tests that import from room.dto.
 * The canonical definition is in com.app.promptle.room.event.RoomEvent.
 */
public record RoomEvent(RoomEventType type, List<PlayerDto> players, String hostId) {}
