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

    public String getTargetPlatform() {
        return targetPlatform;
    }

    public void setTargetPlatform(String targetPlatform) {
        this.targetPlatform = targetPlatform;
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
