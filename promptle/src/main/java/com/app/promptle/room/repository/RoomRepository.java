package com.app.promptle.room.repository;

import com.app.promptle.room.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByRoomCode(String roomCode);
}
