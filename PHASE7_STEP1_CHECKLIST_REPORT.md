# Phase 7 Step 1 - End-to-End Manual Test Matrix Report

Date: 2026-04-05
Environment: Local backend at http://localhost:9000 (Spring Boot), local frontend build
Scope: BUILD_PHASE_PRD.md Phase 7 Step 1 checklist (7 scenarios)

## Summary
- Total scenarios: 7
- Passed: 4
- Failed: 3
- Overall result: PARTIAL PASS (environment blockers remain before release readiness)

## Checklist Results

1. Spotify -> YouTube migration with public playlist
- Status: FAIL
- Evidence: Preflight validates URL but returns readyToStart=false with blocker "Google login is required to create playlists in YouTube Music."
- Notes: Valid Google OAuth token with YouTube write scope was not available.

2. Spotify -> YouTube migration with private playlist (OAuth required)
- Status: PASS
- Evidence: Preflight response includes Google required blocker plus Spotify connect recommendation.
- Notes: OAuth guidance path works as expected.

3. YouTube -> Spotify migration with OAuth
- Status: FAIL
- Evidence: Preflight requires both Google login and Spotify login.
- Notes: End-to-end transfer could not proceed without both valid OAuth tokens.

4. Preflight blocking checks when tokens/permissions are missing
- Status: PASS
- Evidence: Preflight with invalid Google token returns blocker for missing YouTube write permission.
- Notes: Blocking behavior matches expected safeguard.

5. Retry flow on failed/partial/not-found tracks
- Status: PASS (after re-run)
- Evidence:
  - Seeded user via `POST /auth/sync-user` for `11111111-1111-1111-1111-111111111111`.
  - Re-run created job `4d751aaf-2f29-4c3a-a69c-6b5e6d021172`.
  - Retryable tracks detected: `1`.
  - `POST /migrate/{jobId}/retry-failed` returned status `QUEUED`.
- Notes: Original FK blocker is resolved when the user row exists before migration creation.

6. OAuth reconnect after token expiry
- Status: PASS
- Evidence: Preflight with invalid/expired token returns reconnect guidance ("Reconnect Google...").
- Notes: User guidance for token recovery is functioning.

7. CORS behavior from Vercel domain only
- Status: FAIL
- Evidence:
  - Focused verification run against deployed Render backend: `https://soundbridge-platform.onrender.com/health`.
  - Actual active Vercel domain identified as `https://soundbridge.vercel.app` (200 on root check).
  - OPTIONS preflight from `https://soundbridge.vercel.app` returned `403` with empty `Access-Control-Allow-Origin`.
  - OPTIONS preflight from `https://evil.example.com` also returned `403` with empty `Access-Control-Allow-Origin`.
- Notes: Deployed CORS policy currently blocks both tested origins, including the active Vercel domain.

## Raw Evidence Snippets
- Smoke baseline remained healthy earlier in session: /health, /diagnose, /migrate/history, /migrate/preflight passed.
- Dashboard overview endpoint is healthy and returns telemetry counters.
- Initial migration creation failure payload (before user seed):
  - "Referential integrity constraint violation ... FK_MIGRATION_JOBS_USER ..."
- Scenario 5 rerun evidence:
  - `jobId=4d751aaf-2f29-4c3a-a69c-6b5e6d021172`
  - `retryableTracks=1`
  - `retryResponseStatus=QUEUED`
- Deployed CORS rerun evidence:
  - Backend: `https://soundbridge-platform.onrender.com/health`
  - Origin `https://soundbridge.vercel.app`: `403`, `ACAO=`
  - Origin `https://evil.example.com`: `403`, `ACAO=`

## Exit Criteria Impact
Phase 7 Step 1 is not fully complete for release readiness because scenarios 1, 3, and 7 did not pass.

## Recommended Next Actions
1. Use real OAuth tokens (Google + Spotify) and rerun scenarios 1 and 3.
2. Update deployed `CORS_ALLOWED_ORIGINS` to include `https://soundbridge.vercel.app`, redeploy Render backend, and rerun scenario 7.
3. Re-run this full checklist after env fixes and update this report with final pass state.
