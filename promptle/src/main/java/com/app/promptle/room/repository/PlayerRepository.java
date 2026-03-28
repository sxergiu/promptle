package com.app.promptle.room.repository;

import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<Player, UUID> {

    Optional<Player> findByToken(UUID token);

    List<Player> findByRoom(Room room);

    List<Player> findByRoomAndConnectedTrue(Room room);

    List<Player> findByRoomAndConnectedFalse(Room room);
}
