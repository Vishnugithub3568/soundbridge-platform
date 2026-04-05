package com.soundbridge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "track_match_cache")
public class TrackMatchCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "track_title", nullable = false, length = 512)
    private String trackTitle;

    @Column(name = "artist", nullable = false, length = 512)
    private String artist;

    @Column(name = "normalized_track_title", nullable = false, length = 512)
    private String normalizedTrackTitle;

    @Column(name = "normalized_artist", nullable = false, length = 512)
    private String normalizedArtist;

    @Column(name = "youtube_video_id", nullable = false, length = 512)
    private String youtubeVideoId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTrackTitle() {
        return trackTitle;
    }

    public void setTrackTitle(String trackTitle) {
        this.trackTitle = trackTitle;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getNormalizedTrackTitle() {
        return normalizedTrackTitle;
    }

    public void setNormalizedTrackTitle(String normalizedTrackTitle) {
        this.normalizedTrackTitle = normalizedTrackTitle;
    }

    public String getNormalizedArtist() {
        return normalizedArtist;
    }

    public void setNormalizedArtist(String normalizedArtist) {
        this.normalizedArtist = normalizedArtist;
    }

    public String getYouTubeVideoId() {
        return youtubeVideoId;
    }

    public void setYouTubeVideoId(String youtubeVideoId) {
        this.youtubeVideoId = youtubeVideoId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
