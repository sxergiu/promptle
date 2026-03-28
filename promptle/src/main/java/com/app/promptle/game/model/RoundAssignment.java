package com.app.promptle.game.model;

import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "round_assignments", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"room_id", "round", "player_id"}),
        @UniqueConstraint(columnNames = {"room_id", "round", "chain_id"})
})
public class RoundAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    private int round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id")
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chain_id")
    private Chain chain;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public Chain getChain() {
        return chain;
    }

    public void setChain(Chain chain) {
        this.chain = chain;
    }
}
