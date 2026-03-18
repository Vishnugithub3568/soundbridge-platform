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
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
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
}
