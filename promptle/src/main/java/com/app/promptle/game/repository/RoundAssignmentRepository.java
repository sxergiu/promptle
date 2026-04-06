package com.app.promptle.game.repository;

import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.RoundAssignment;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoundAssignmentRepository extends JpaRepository<RoundAssignment, UUID> {

    Optional<RoundAssignment> findByRoomAndRoundAndPlayer(Room room, int round, Player player);

    Optional<RoundAssignment> findByRoomAndRoundAndChain(Room room, int round, Chain chain);

    List<RoundAssignment> findByRoom(Room room);

    void deleteAllByRoom(Room room);
}
