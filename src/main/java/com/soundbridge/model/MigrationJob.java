package com.soundbridge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "migration_jobs")
public class MigrationJob {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_playlist_url", nullable = false)
    private String sourcePlaylistUrl;

    @Column(name = "spotify_access_token", length = 4096)
    private String spotifyAccessToken;

    @Column(name = "google_access_token", length = 4096)
    private String googleAccessToken;

    @Column(name = "target_playlist_id", length = 512)
    private String targetPlaylistId;

    @Column(name = "target_playlist_url", length = 2048)
    private String targetPlaylistUrl;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "target_platform", nullable = false)
    private String targetPlatform = "YOUTUBE_MUSIC";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status = JobStatus.QUEUED;

    @Column(name = "total_tracks", nullable = false)
    private int totalTracks;

    @Column(name = "matched_tracks", nullable = false)
    private int matchedTracks;

    @Column(name = "failed_tracks", nullable = false)
    private int failedTracks;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = JobStatus.QUEUED;
        }
        if (targetPlatform == null || targetPlatform.isBlank()) {
            targetPlatform = "YOUTUBE_MUSIC";
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSourcePlaylistUrl() {
        return sourcePlaylistUrl;
    }

    public void setSourcePlaylistUrl(String sourcePlaylistUrl) {
        this.sourcePlaylistUrl = sourcePlaylistUrl;
    }

    public String getSpotifyAccessToken() {
        return spotifyAccessToken;
    }

    public void setSpotifyAccessToken(String spotifyAccessToken) {
        this.spotifyAccessToken = spotifyAccessToken;
    }

    public String getTargetPlatform() {
        return targetPlatform;
    }

    public void setTargetPlatform(String targetPlatform) {
        this.targetPlatform = targetPlatform;
    }

    public String getGoogleAccessToken() {
        return googleAccessToken;
    }

    public void setGoogleAccessToken(String googleAccessToken) {
        this.googleAccessToken = googleAccessToken;
    }

    public String getTargetPlaylistId() {
        return targetPlaylistId;
    }

    public void setTargetPlaylistId(String targetPlaylistId) {
        this.targetPlaylistId = targetPlaylistId;
    }

    public String getTargetPlaylistUrl() {
        return targetPlaylistUrl;
    }

    public void setTargetPlaylistUrl(String targetPlaylistUrl) {
        this.targetPlaylistUrl = targetPlaylistUrl;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public int getTotalTracks() {
        return totalTracks;
    }

    public void setTotalTracks(int totalTracks) {
        this.totalTracks = totalTracks;
    }

    public int getMatchedTracks() {
        return matchedTracks;
    }

    public void setMatchedTracks(int matchedTracks) {
        this.matchedTracks = matchedTracks;
    }

    public int getFailedTracks() {
        return failedTracks;
    }

    public void setFailedTracks(int failedTracks) {
        this.failedTracks = failedTracks;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
