package com.app.promptle.game.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.RoundAssignment;
import com.app.promptle.game.repository.RoundAssignmentRepository;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Resolves which chain a player is assigned to for a given round.
 * Uses a cyclic Latin-square formula to guarantee no self-assignment and no collisions.
 */
@Service
@Transactional
public class RoundAssignmentService {

    private final RoundAssignmentRepository roundAssignmentRepository;

    public RoundAssignmentService(RoundAssignmentRepository roundAssignmentRepository) {
        this.roundAssignmentRepository = roundAssignmentRepository;
    }

    /**
     * Generates round assignments for all players and chains using a cyclic offset formula.
     * Round 1 is the PROMPTING round — no assignment is needed for it.
     * For each guessing round r (r = 2..N), player at index i is assigned chain at index (i + (r-1)) % N.
     */
    public void generateAssignments(Room room, List<Player> players, List<Chain> chains) {
        int n = players.size();
        int totalRounds = room.getTotalRounds();
        for (int round = 2; round <= totalRounds; round++) {
            for (int i = 0; i < n; i++) {
                int chainIndex = (i + (round - 1)) % n;
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
     * @throws GameException if no assignment exists for the given combination
     */
    public Chain getAssignedChain(Room room, Player player, int round) {
        return roundAssignmentRepository
                .findByRoomAndRoundAndPlayer(room, round, player)
                .map(RoundAssignment::getChain)
                .orElseThrow(() -> new GameException("No assignment found"));
    }
}
