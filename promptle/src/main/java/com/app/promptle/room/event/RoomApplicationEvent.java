package com.app.promptle.room.event;

import com.app.promptle.room.dto.RoomEvent;

public record RoomApplicationEvent(String roomCode, RoomEvent payload) {}
