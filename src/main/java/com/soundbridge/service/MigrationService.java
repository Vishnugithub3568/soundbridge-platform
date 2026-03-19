package com.soundbridge.service;

import com.soundbridge.dto.MigrationJobResponse;
import com.soundbridge.dto.MigrationReportResponse;
import com.soundbridge.dto.MigrationTrackResponse;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MigrationService {

    private final MigrationJobRepository jobRepository;
    private final MigrationTrackRepository trackRepository;
    private final MigrationAsyncProcessor migrationAsyncProcessor;
    private final ApplicationContext applicationContext;

    public MigrationService(
        MigrationJobRepository jobRepository,
        MigrationTrackRepository trackRepository,
        MigrationAsyncProcessor migrationAsyncProcessor,
        ApplicationContext applicationContext
    ) {
        this.jobRepository = jobRepository;
        this.trackRepository = trackRepository;
        this.migrationAsyncProcessor = migrationAsyncProcessor;
        this.applicationContext = applicationContext;
    }

    public MigrationJobResponse startMigration(String spotifyPlaylistUrl) {
        String normalizedUrl = Objects.requireNonNullElse(spotifyPlaylistUrl, "").trim();
        if (normalizedUrl.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Spotify playlist URL is required");
        }

        MigrationJob job = new MigrationJob();
        job.setSourcePlaylistUrl(normalizedUrl);
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

    @Async("migrationTaskExecutor")
    public void processMigrationAsync(UUID jobId) {
        migrationAsyncProcessor.processMigration(jobId);
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
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Migration job not found"));
    }

    private void ensureJobExists(UUID jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Migration job not found");
        }
    }
}
