package com.app.promptle.game.model;

import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "chains", uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "origin_player_id"}))
public class Chain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_player_id")
    private Player originPlayer;

    @Column(name = "style")
    private String style;

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

    public Player getOriginPlayer() {
        return originPlayer;
    }

    public void setOriginPlayer(Player originPlayer) {
        this.originPlayer = originPlayer;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }
}
