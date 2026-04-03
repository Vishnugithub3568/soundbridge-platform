package com.soundbridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.soundbridge.exception.MigrationException;
import com.soundbridge.model.JobStatus;
import com.soundbridge.model.MigrationJob;
import com.soundbridge.repository.MigrationJobRepository;
import com.soundbridge.repository.MigrationTrackRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class MigrationServiceTest {

    @Mock
    private MigrationJobRepository jobRepository;

    @Mock
    private MigrationTrackRepository trackRepository;

    @Mock
    private MigrationAsyncProcessor migrationAsyncProcessor;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private GoogleOAuthService googleOAuthService;

    private MigrationService migrationService;

    @BeforeEach
    void setUp() {
        migrationService = new MigrationService(
            jobRepository,
            trackRepository,
            migrationAsyncProcessor,
            applicationContext,
            googleOAuthService
        );
    }

    @Test
    void startMigrationDispatchesBackgroundWork() {
        MigrationJob saved = new MigrationJob();
        saved.setId(UUID.randomUUID());
        saved.setStatus(JobStatus.QUEUED);
        saved.setSourcePlaylistUrl("https://open.spotify.com/playlist/abc");

        when(jobRepository.saveAndFlush(any(MigrationJob.class))).thenReturn(saved);
        when(applicationContext.getBean(MigrationService.class)).thenReturn(migrationService);
        doNothing().when(migrationAsyncProcessor).processMigration(any(UUID.class));

        var response = migrationService.startMigration("https://open.spotify.com/playlist/abc", null, null);

        assertEquals(JobStatus.QUEUED, response.status());
        verify(migrationAsyncProcessor).processMigration(saved.getId());
    }

    @Test
    void startMigrationRejectsBlankUrl() {
        assertThrows(MigrationException.class, () -> migrationService.startMigration("  ", null, null));
    }

    @Test
    void startMigrationRejectsGoogleTokenWithoutYouTubeScope() {
        when(googleOAuthService.hasYouTubeWriteScope("bad-token")).thenReturn(false);

        MigrationException ex = assertThrows(
            MigrationException.class,
            () -> migrationService.startMigration("https://open.spotify.com/playlist/abc", null, "bad-token")
        );

        assertEquals("MISSING_YOUTUBE_SCOPE", ex.getErrorCode());
    }
}
