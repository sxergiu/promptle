package com.app.promptle.game.model;

import com.app.promptle.room.model.Player;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "chain_entries")
public class ChainEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chain_id")
    private Chain chain;

    private int round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = true)
    private Player author;

    @Column(nullable = false)
    private String text;

    private String imageUrl;

    private boolean isPlaceholder = false;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Chain getChain() {
        return chain;
    }

    public void setChain(Chain chain) {
        this.chain = chain;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public Player getAuthor() {
        return author;
    }

    public void setAuthor(Player author) {
        this.author = author;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public boolean isPlaceholder() {
        return isPlaceholder;
    }

    public void setPlaceholder(boolean placeholder) {
        isPlaceholder = placeholder;
    }
}
