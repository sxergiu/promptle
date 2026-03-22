package com.app.promptle.game.service;

import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.RoundAssignment;
import com.app.promptle.game.repository.RoundAssignmentRepository;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stub — fully implemented in a later chunk.
 * Resolves which chain a player is assigned to for a given round.
 */
@Service
public class RoundAssignmentService {

    private final RoundAssignmentRepository roundAssignmentRepository;

    public RoundAssignmentService(RoundAssignmentRepository roundAssignmentRepository) {
        this.roundAssignmentRepository = roundAssignmentRepository;
    }

    /**
     * Generates round assignments for all players and chains using a cyclic offset formula.
     * For round r (1-based), player at index i is assigned chain at index (i + r - 1) % n.
     */
    public void generateAssignments(Room room, List<Player> players, List<Chain> chains) {
        int n = players.size();
        int totalRounds = room.getTotalRounds();
        for (int round = 1; round <= totalRounds; round++) {
            for (int i = 0; i < n; i++) {
                int chainIndex = (i + round - 1) % n;
                RoundAssignment assignment = new RoundAssignment();
                assignment.setRoom(room);
                assignment.setPlayer(players.get(i));
                assignment.setChain(chains.get(chainIndex));
                assignment.setRound(round);
                roundAssignmentRepository.save(assignment);
            }
        }
    }

    /**
     * Returns the chain assigned to the given player for the given round.
     *
     * @param room   the current room
     * @param player the player whose assignment is being queried
     * @param round  the round number (1-based)
     * @return the assigned Chain
     */
    public Chain getAssignedChain(Room room, Player player, int round) {
        throw new UnsupportedOperationException("Not yet implemented — stub for Chunk 3 compilation");
    }
}
