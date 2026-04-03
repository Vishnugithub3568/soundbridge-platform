package com.soundbridge.service;

import com.soundbridge.dto.MigrationJobResponse;
import com.soundbridge.dto.MigrationReportResponse;
import com.soundbridge.dto.MigrationTrackResponse;
import com.soundbridge.exception.MigrationException;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationService {

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final MigrationAsyncProcessor migrationAsyncProcessor;
    private final ApplicationContext applicationContext;
    private final GoogleOAuthService googleOAuthService;

    public MigrationService(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        MigrationAsyncProcessor migrationAsyncProcessor,
        ApplicationContext applicationContext,
        GoogleOAuthService googleOAuthService
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.migrationAsyncProcessor = migrationAsyncProcessor;
        this.applicationContext = applicationContext;
        this.googleOAuthService = googleOAuthService;
    }

    public MigrationJobResponse startMigration(String spotifyPlaylistUrl, String spotifyAccessToken, String googleAccessToken) {
        String normalizedUrl = Objects.requireNonNullElse(spotifyPlaylistUrl, "").trim();
        if (normalizedUrl.isEmpty()) {
            throw new MigrationException("Spotify playlist URL is required", "MISSING_PLAYLIST_URL", 400);
        }

        String normalizedToken = Objects.requireNonNullElse(spotifyAccessToken, "").trim();
        String normalizedGoogleToken = Objects.requireNonNullElse(googleAccessToken, "").trim();

        if (!normalizedGoogleToken.isEmpty() && !googleOAuthService.hasYouTubeWriteScope(normalizedGoogleToken)) {
            throw new MigrationException(
                "Google token is missing YouTube permission. Reconnect Google and approve YouTube access, then retry.",
                "MISSING_YOUTUBE_SCOPE",
                400
            );
        }

        MigrationJob job = new MigrationJob();
        job.setSourcePlaylistUrl(normalizedUrl);
        job.setSpotifyAccessToken(normalizedToken.isEmpty() ? null : normalizedToken);
        job.setGoogleAccessToken(normalizedGoogleToken.isEmpty() ? null : normalizedGoogleToken);
        job.setTargetPlatform("YOUTUBE_MUSIC");
        job.setStatus(JobStatus.QUEUED);
        job.setTotalTracks(0);
        job.setMatchedTracks(0);
        job.setFailedTracks(0);
        job = jobRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        applicationContext.getBean(MigrationService.class).processMigrationAsync(jobId);
        return MigrationJobResponse.from(job);
    }

    public MigrationJobResponse retryFailedTracks(UUID jobId) {
        MigrationJob job = getJobEntity(jobId);
        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED) {
            throw new MigrationException("Migration job is already in progress", "JOB_IN_PROGRESS", 409);
        }

        if (job.getFailedTracks() <= 0) {
            return MigrationJobResponse.from(job);
        }

        job.setStatus(JobStatus.QUEUED);
        jobRepository.saveAndFlush(job);
        applicationContext.getBean(MigrationService.class).retryFailedTracksAsync(jobId);
        return MigrationJobResponse.from(job);
    }

    @Async("migrationTaskExecutor")
    public void processMigrationAsync(UUID jobId) {
        migrationAsyncProcessor.processMigration(jobId);
    }

    @Async("migrationTaskExecutor")
    public void retryFailedTracksAsync(UUID jobId) {
        migrationAsyncProcessor.retryFailedTracks(jobId);
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

        return new MigrationReportResponse(
            job.getId(),
            job.getStatus(),
            job.getTotalTracks(),
            job.getMatchedTracks(),
            job.getFailedTracks(),
            matchRate,
            tracks
        );
    }

    private MigrationJob getJobEntity(UUID jobId) {
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new MigrationException("Migration job not found", "JOB_NOT_FOUND", 404));
    }

    private void ensureJobExists(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new MigrationException("Migration job not found", "JOB_NOT_FOUND", 404);
        }
    }
}
