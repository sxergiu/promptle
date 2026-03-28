package com.app.promptle.game.repository;

import com.app.promptle.game.model.Chain;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChainRepository extends JpaRepository<Chain, UUID> {

    List<Chain> findByRoom(Room room);

    Optional<Chain> findByRoomAndOriginPlayer(Room room, Player player);

    void deleteAllByRoom(Room room);
}
