package com.soundbridge.controller;

import com.soundbridge.dto.CreateMigrationRequest;
import com.soundbridge.dto.MigrationJobResponse;
import com.soundbridge.dto.MigrationReportResponse;
import com.soundbridge.dto.MigrationTrackResponse;
import com.soundbridge.service.MigrationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class MigrationController {

    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/migrate")
    public ResponseEntity<MigrationJobResponse> migrate(@Valid @RequestBody CreateMigrationRequest request) {
        MigrationJobResponse response = migrationService.startMigration(request.getSpotifyPlaylistUrl());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/migrate/{jobId}")
    public MigrationJobResponse getMigrationJob(@PathVariable UUID jobId) {
        return migrationService.getJob(jobId);
    }

    @GetMapping("/migrate/{jobId}/tracks")
    public List<MigrationTrackResponse> getMigrationTracks(@PathVariable UUID jobId) {
        return migrationService.getTracks(jobId);
    }

    @GetMapping("/migrate/{jobId}/report")
    public MigrationReportResponse getMigrationReport(@PathVariable UUID jobId) {
        return migrationService.getReport(jobId);
    }
}
