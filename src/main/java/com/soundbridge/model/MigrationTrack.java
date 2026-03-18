package com.soundbridge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "migration_tracks")
public class MigrationTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private MigrationJob job;

    @Column(name = "source_track_name", nullable = false)
    private String sourceTrackName;

    @Column(name = "source_artist_name", nullable = false)
    private String sourceArtistName;

    @Column(name = "source_album_name")
    private String sourceAlbumName;

    @Column(name = "target_track_id")
    private String targetTrackId;

    @Column(name = "target_track_url")
    private String targetTrackUrl;

    @Column(name = "target_track_title")
    private String targetTrackTitle;

    @Column(name = "target_thumbnail_url")
    private String targetThumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false)
    private TrackMatchStatus matchStatus;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "failure_reason")
    private String failureReason;

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

    public MigrationJob getJob() {
        return job;
    }

    public void setJob(MigrationJob job) {
        this.job = job;
    }

    public String getSourceTrackName() {
        return sourceTrackName;
    }

    public void setSourceTrackName(String sourceTrackName) {
        this.sourceTrackName = sourceTrackName;
    }

    public String getSourceArtistName() {
        return sourceArtistName;
    }

    public void setSourceArtistName(String sourceArtistName) {
        this.sourceArtistName = sourceArtistName;
    }

    public String getSourceAlbumName() {
        return sourceAlbumName;
    }

    public void setSourceAlbumName(String sourceAlbumName) {
        this.sourceAlbumName = sourceAlbumName;
    }

    public String getTargetTrackId() {
        return targetTrackId;
    }

    public void setTargetTrackId(String targetTrackId) {
        this.targetTrackId = targetTrackId;
    }

    public String getTargetTrackUrl() {
        return targetTrackUrl;
    }

    public void setTargetTrackUrl(String targetTrackUrl) {
        this.targetTrackUrl = targetTrackUrl;
    }

    public String getTargetTrackTitle() {
        return targetTrackTitle;
    }

    public void setTargetTrackTitle(String targetTrackTitle) {
        this.targetTrackTitle = targetTrackTitle;
    }

    public String getTargetThumbnailUrl() {
        return targetThumbnailUrl;
    }

    public void setTargetThumbnailUrl(String targetThumbnailUrl) {
        this.targetThumbnailUrl = targetThumbnailUrl;
    }

    public TrackMatchStatus getMatchStatus() {
        return matchStatus;
    }

    public void setMatchStatus(TrackMatchStatus matchStatus) {
        this.matchStatus = matchStatus;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
