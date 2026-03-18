package com.soundbridge.dto;

import com.soundbridge.model.MigrationTrack;
import com.soundbridge.model.TrackMatchStatus;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MigrationTrackResponse {
    Long id;
    String sourceTrackName;
    String sourceArtistName;
    String sourceAlbumName;
    String targetTrackId;
    String targetTrackUrl;
    TrackMatchStatus matchStatus;
    Double confidenceScore;
    String failureReason;

    public static MigrationTrackResponse from(MigrationTrack track) {
        return MigrationTrackResponse.builder()
            .id(track.getId())
            .sourceTrackName(track.getSourceTrackName())
            .sourceArtistName(track.getSourceArtistName())
            .sourceAlbumName(track.getSourceAlbumName())
            .targetTrackId(track.getTargetTrackId())
            .targetTrackUrl(track.getTargetTrackUrl())
            .matchStatus(track.getMatchStatus())
            .confidenceScore(track.getConfidenceScore())
            .failureReason(track.getFailureReason())
            .build();
    }
}
