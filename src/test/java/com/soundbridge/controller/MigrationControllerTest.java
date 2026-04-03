package com.soundbridge.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.soundbridge.dto.MigrationJobResponse;
import com.soundbridge.dto.MigrationReportResponse;
import com.soundbridge.dto.MigrationTrackResponse;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.TrackMatchStatus;
import com.soundbridge.service.MigrationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MigrationController.class)
@SuppressWarnings({"null", "deprecation"})
class MigrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MigrationService migrationService;

    @Test
    void postMigrateReturnsAccepted() throws Exception {
        UUID jobId = UUID.randomUUID();
        MigrationJobResponse response = new MigrationJobResponse(
            jobId,
            "https://open.spotify.com/playlist/abc",
            "YOUTUBE_MUSIC",
            null,
            null,
            JobStatus.QUEUED,
            0,
            0,
            0,
            Instant.now(),
            Instant.now()
        );

        when(migrationService.startMigration(anyString(), nullable(String.class), nullable(String.class))).thenReturn(response);

        mockMvc.perform(post("/migrate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spotifyPlaylistUrl\":\"https://open.spotify.com/playlist/abc\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void getJobTracksAndReportWork() throws Exception {
        UUID jobId = UUID.randomUUID();

        when(migrationService.getJob(eq(jobId))).thenReturn(new MigrationJobResponse(
            jobId,
            "https://open.spotify.com/playlist/abc",
            "YOUTUBE_MUSIC",
            null,
            null,
            JobStatus.RUNNING,
            6,
            2,
            1,
            Instant.now(),
            Instant.now()
        ));

        when(migrationService.getTracks(eq(jobId))).thenReturn(List.of(
            new MigrationTrackResponse(
                1L,
                "Dreams",
                "Fleetwood Mac",
                "Rumours",
                "abc123",
                "https://music.youtube.com/watch?v=abc123",
                "Dreams (Official Video)",
                "https://img.youtube.com/vi/abc123/mqdefault.jpg",
                TrackMatchStatus.MATCHED,
                0.91,
                null
            )
        ));

        when(migrationService.getReport(eq(jobId))).thenReturn(new MigrationReportResponse(
            jobId,
            JobStatus.RUNNING,
            6,
            2,
            1,
            33.3,
            List.of()
        ));

        mockMvc.perform(get("/migrate/{jobId}", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(get("/migrate/{jobId}/tracks", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].targetTrackTitle").value("Dreams (Official Video)"));

        mockMvc.perform(get("/migrate/{jobId}/report", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalTracks").value(6));
    }
}
