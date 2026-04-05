# Phase 7 Step 1 - End-to-End Manual Test Matrix Report

Date: 2026-04-05
Environment: Local backend at http://localhost:9000 (Spring Boot), local frontend build
Scope: BUILD_PHASE_PRD.md Phase 7 Step 1 checklist (7 scenarios)

## Summary
- Total scenarios: 7
- Passed: 3
- Failed: 4
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
- Status: FAIL
- Evidence: POST /migrate returned 500 due to DB FK violation when creating migration job:
  - migration_jobs.user_id references users.id, but test user ID did not exist.
  - Error: Referential integrity constraint violation on FK_MIGRATION_JOBS_USER.
- Notes: Retry path could not be validated because job creation failed first.

6. OAuth reconnect after token expiry
- Status: PASS
- Evidence: Preflight with invalid/expired token returns reconnect guidance ("Reconnect Google...").
- Notes: User guidance for token recovery is functioning.

7. CORS behavior from Vercel domain only
- Status: FAIL
- Evidence: OPTIONS preflight to /health for both origins returned 403 and no Access-Control-Allow-Origin header:
  - https://soundbridge-platform.vercel.app
  - https://evil.example.com
- Notes: Current local runtime does not confirm allowlist behavior for Vercel-only access.

## Raw Evidence Snippets
- Smoke baseline remained healthy earlier in session: /health, /diagnose, /migrate/history, /migrate/preflight passed.
- Dashboard overview endpoint is healthy and returns telemetry counters.
- Migration creation failure payload:
  - "Referential integrity constraint violation ... FK_MIGRATION_JOBS_USER ..."

## Exit Criteria Impact
Phase 7 Step 1 is not fully complete for release readiness because scenarios 1, 3, 5, and 7 did not pass in this environment.

## Recommended Next Actions
1. Use real OAuth tokens (Google + Spotify) and rerun scenarios 1 and 3.
2. Ensure user row exists before POST /migrate for scenario 5, then rerun retry validation.
3. Verify CORS allowlist in deployed Render environment (or local config parity) and rerun scenario 7 using browser-like preflight checks.
4. Re-run this full checklist after fixes and update this report with final pass state.
