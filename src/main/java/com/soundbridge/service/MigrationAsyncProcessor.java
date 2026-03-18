package com.soundbridge.service;

import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.client.YouTubeClient;
import com.soundbridge.client.YouTubeMatch;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.MigrationTrack;
import com.soundbridge.model.TrackMatchStatus;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class MigrationAsyncProcessor {

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final SpotifyClient spotifyClient;
    private final YouTubeClient youTubeClient;

    public MigrationAsyncProcessor(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        SpotifyClient spotifyClient,
        YouTubeClient youTubeClient
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.spotifyClient = spotifyClient;
        this.youTubeClient = youTubeClient;
    }

    @Async("migrationTaskExecutor")
    public void processMigration(UUID jobId) {
        MigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        try {
            job.setStatus(JobStatus.RUNNING);
            jobRepository.saveAndFlush(job);

            List<SpotifyTrack> sourceTracks = spotifyClient.fetchPlaylistTracks(job.getSourcePlaylistUrl());
            if (sourceTracks == null) {
                sourceTracks = List.of();
            }
            job.setTotalTracks(sourceTracks.size());
            job.setMatchedTracks(0);
            job.setFailedTracks(0);
            jobRepository.saveAndFlush(job);

            int matched = 0;
            int failed = 0;

            for (SpotifyTrack sourceTrack : sourceTracks) {
                if (sourceTrack == null) {
                    failed++;
                    job.setFailedTracks(failed);
                    jobRepository.saveAndFlush(job);
                    continue;
                }

                YouTubeMatch match = youTubeClient.matchTrack(sourceTrack);
                MigrationTrack migrationTrack = new MigrationTrack();
                migrationTrack.setJob(job);
                migrationTrack.setSourceTrackName(Objects.requireNonNullElse(sourceTrack.name(), "Unknown Track"));
                migrationTrack.setSourceArtistName(Objects.requireNonNullElse(sourceTrack.artist(), "Unknown Artist"));
                migrationTrack.setSourceAlbumName(sourceTrack.album());

                if (match != null) {
                    migrationTrack.setConfidenceScore(match.confidenceScore());

                    if (match.matched()) {
                        migrationTrack.setTargetTrackId(match.targetTrackId());
                        migrationTrack.setTargetTrackUrl(match.targetTrackUrl());
                        migrationTrack.setTargetTrackTitle(match.targetTrackTitle());
                        migrationTrack.setTargetThumbnailUrl(match.targetThumbnailUrl());
                        migrationTrack.setMatchStatus(match.partial() ? TrackMatchStatus.PARTIAL : TrackMatchStatus.MATCHED);
                        migrationTrack.setFailureReason(match.failureReason());
                        matched++;
                    } else {
                        migrationTrack.setTargetTrackTitle(match.targetTrackTitle());
                        migrationTrack.setTargetThumbnailUrl(match.targetThumbnailUrl());
                        migrationTrack.setMatchStatus(TrackMatchStatus.NOT_FOUND);
                        migrationTrack.setFailureReason(
                            Objects.requireNonNullElse(match.failureReason(), "No reliable YouTube Music match")
                        );
                        failed++;
                    }
                } else {
                    migrationTrack.setMatchStatus(TrackMatchStatus.NOT_FOUND);
                    migrationTrack.setFailureReason("Matcher returned no response");
                    failed++;
                }

                trackRepository.save(migrationTrack);

                job.setMatchedTracks(matched);
                job.setFailedTracks(failed);
                jobRepository.saveAndFlush(job);

                try {
                    Thread.sleep(150);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Migration processing interrupted", interruptedException);
                }
            }

            job.setStatus(JobStatus.COMPLETED);
            jobRepository.saveAndFlush(job);
        } catch (Exception ex) {
            job.setStatus(JobStatus.FAILED);
            jobRepository.saveAndFlush(job);
        }
    }
}
