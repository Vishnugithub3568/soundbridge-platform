package com.soundbridge.controller;

import com.soundbridge.dto.DashboardLibraryItemResponse;
import com.soundbridge.dto.DashboardOverviewResponse;
import com.soundbridge.dto.DashboardServiceStatusResponse;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.model.User;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.UserRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final MigrationJobRepository migrationJobRepository;
    private final UserRepository userRepository;

    public DashboardController(MigrationJobRepository migrationJobRepository, UserRepository userRepository) {
        this.migrationJobRepository = migrationJobRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/overview")
    public ResponseEntity<DashboardOverviewResponse> getOverview(
        @RequestParam UUID userId,
        @RequestParam(required = false) String email
    ) {
        User user = userRepository.findById(userId)
            .orElseGet(() -> new User(userId, safeEmail(email), "SoundBridge User"));

        List<MigrationJob> jobs = migrationJobRepository.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, 20));

        int completedJobs = (int) jobs.stream().filter(job -> job.getStatus() == JobStatus.COMPLETED).count();
        int failedJobs = (int) jobs.stream().filter(job -> job.getStatus() == JobStatus.FAILED).count();
        int runningJobs = (int) jobs.stream().filter(job -> job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED).count();
        int totalTracks = jobs.stream().mapToInt(MigrationJob::getTotalTracks).sum();

        List<DashboardServiceStatusResponse> services = List.of(
            new DashboardServiceStatusResponse("Spotify", true, "Connected", "OAuth active"),
            new DashboardServiceStatusResponse("YouTube Music", true, "Connected", "OAuth active")
        );

        DashboardOverviewResponse response = new DashboardOverviewResponse(
            userId,
            Objects.requireNonNullElse(user.getDisplayName(), "SoundBridge User"),
            user.getEmail(),
            jobs.size(),
            completedJobs,
            failedJobs,
            runningJobs,
            totalTracks,
            services.size(),
            List.of("Transfer", "Synchronize", "Generate", "Share"),
            services
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/library")
    public List<DashboardLibraryItemResponse> getLibrary(@RequestParam UUID userId) {
        return migrationJobRepository.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, 50))
            .stream()
            .map(job -> new DashboardLibraryItemResponse(
                job.getId(),
                buildTitle(job),
                sourcePlatform(job),
                job.getTargetPlatform(),
                job.getTotalTracks(),
                job.getStatus().name(),
                job.getUpdatedAt(),
                job.getSourcePlaylistUrl(),
                job.getTargetPlaylistUrl()
            ))
            .toList();
    }

    @GetMapping("/services-status")
    public List<DashboardServiceStatusResponse> getServicesStatus() {
        return List.of(
            new DashboardServiceStatusResponse("Spotify", true, "Connected", "OAuth active"),
            new DashboardServiceStatusResponse("YouTube Music", true, "Connected", "OAuth active"),
            new DashboardServiceStatusResponse("SoundCloud", false, "Coming Soon", "Not integrated yet")
        );
    }

    @GetMapping("/navigation")
    public List<String> getNavigation() {
        return List.of("Home", "Plans", "Terms of Service", "Help", "Services Status");
    }

    private String safeEmail(String email) {
        String value = Objects.requireNonNullElse(email, "").trim();
        return value.isEmpty() ? "guest@soundbridge.local" : value;
    }

    private String buildTitle(MigrationJob job) {
        String source = Objects.requireNonNullElse(job.getSourcePlaylistUrl(), "SoundBridge Migration");
        if (source.length() > 48) {
            return source.substring(0, 45) + "...";
        }
        return source;
    }

    private String sourcePlatform(MigrationJob job) {
        return "SPOTIFY".equalsIgnoreCase(job.getTargetPlatform()) ? "YouTube Music" : "Spotify";
    }
}