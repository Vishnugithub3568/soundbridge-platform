package com.soundbridge.service;

import com.soundbridge.client.SpotifyClient;
import com.soundbridge.client.SpotifyTrack;
import com.soundbridge.client.YouTubeClient;
import com.soundbridge.client.YouTubeMatch;
import com.soundbridge.dto.MigrationJobResponse;
import com.soundbridge.dto.MigrationReportResponse;
import com.soundbridge.dto.MigrationTrackResponse;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.MigrationTrack;
import com.soundbridge.model.TrackMatchStatus;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MigrationService {

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final SpotifyClient spotifyClient;
    private final YouTubeClient youTubeClient;

    public MigrationService(
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

    @Transactional
    public MigrationJobResponse startMigration(String spotifyPlaylistUrl) {
        MigrationJob job = new MigrationJob();
        job.setSourcePlaylistUrl(spotifyPlaylistUrl);
        job.setTargetPlatform("YOUTUBE_MUSIC");
        job.setStatus(JobStatus.QUEUED);
        job = jobRepository.save(job);

        processMigration(job.getId());
        return MigrationJobResponse.from(jobRepository.findById(job.getId()).orElseThrow());
    }

    @Transactional
    public void processMigration(UUID jobId) {
        MigrationJob job = getJobEntity(jobId);
        job.setStatus(JobStatus.PROCESSING);
        job = jobRepository.save(job);

        List<SpotifyTrack> sourceTracks = spotifyClient.fetchPlaylistTracks(job.getSourcePlaylistUrl());
        job.setTotalTracks(sourceTracks.size());
        jobRepository.save(job);

        int matched = 0;
        int failed = 0;

        for (SpotifyTrack sourceTrack : sourceTracks) {
            YouTubeMatch match = youTubeClient.matchTrack(sourceTrack);

            MigrationTrack migrationTrack = new MigrationTrack();
            migrationTrack.setJob(job);
            migrationTrack.setSourceTrackName(sourceTrack.getName());
            migrationTrack.setSourceArtistName(sourceTrack.getArtist());
            migrationTrack.setSourceAlbumName(sourceTrack.getAlbum());
            migrationTrack.setConfidenceScore(match.getConfidenceScore());

            if (match.isMatched()) {
                migrationTrack.setTargetTrackId(match.getTargetTrackId());
                migrationTrack.setTargetTrackUrl(match.getTargetTrackUrl());
                migrationTrack.setMatchStatus(match.isPartial() ? TrackMatchStatus.PARTIAL : TrackMatchStatus.MATCHED);
                migrationTrack.setFailureReason(match.getFailureReason());
                matched++;
            } else {
                migrationTrack.setMatchStatus(TrackMatchStatus.NOT_FOUND);
                migrationTrack.setFailureReason(match.getFailureReason());
                failed++;
            }

            trackRepository.save(migrationTrack);
        }

        job.setMatchedTracks(matched);
        job.setFailedTracks(failed);
        job.setStatus(JobStatus.COMPLETED);
        jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public MigrationJobResponse getJob(UUID jobId) {
        return MigrationJobResponse.from(getJobEntity(jobId));
    }

    @Transactional(readOnly = true)
    public List<MigrationTrackResponse> getTracks(UUID jobId) {
        ensureJobExists(jobId);
        return trackRepository.findByJobIdOrderByIdAsc(jobId)
            .stream()
            .map(MigrationTrackResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public MigrationReportResponse getReport(UUID jobId) {
        MigrationJob job = getJobEntity(jobId);
        List<MigrationTrackResponse> tracks = getTracks(jobId);
        double matchRate = job.getTotalTracks() == 0
            ? 0.0
            : ((double) job.getMatchedTracks() / (double) job.getTotalTracks()) * 100.0;

        return MigrationReportResponse.builder()
            .jobId(job.getId())
            .status(job.getStatus())
            .totalTracks(job.getTotalTracks())
            .matchedTracks(job.getMatchedTracks())
            .failedTracks(job.getFailedTracks())
            .matchRate(matchRate)
            .tracks(tracks)
            .build();
    }

    private MigrationJob getJobEntity(UUID jobId) {
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Migration job not found"));
    }

    private void ensureJobExists(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Migration job not found");
        }
    }
}
