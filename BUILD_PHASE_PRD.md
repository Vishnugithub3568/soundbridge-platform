# SoundBridge Build-Phase PRD (Temporary Working Document)

## Document Status
- Created: 2026-04-04
- Last updated: 2026-04-05
- Purpose: Continuity document for active build phase so implementation can resume even if chat history is lost.
- Lifecycle: Temporary.
- Delete after: Final completion + production validation + handoff.

## Important Temporary-File Note
This file exists only to accelerate development continuity.
After final completion and stable deployment verification, this file should be deleted.

## Product Vision
Build a Soundiiz-like migration experience for playlist transfer between Spotify and YouTube Music with:
- Reliable OAuth login flow
- Preflight validation before migration
- Async migration execution
- Track-level visibility, confidence scoring, and issue recovery
- Production-ready deploy flow on Render (backend) + Vercel (frontend)

## Current State (Already Completed)
### 2026-04-05 verification and DB hardening
- Added reusable Supabase hardening SQL script:
  - `supabase-migration-2026-04-05-fk-hardening.sql`
- Confirmed DB integrity checks in Supabase:
  - `null_ids = 0`
  - duplicate migration job IDs = none
  - `orphan_tracks = 0`
- Completed backend API smoke verification locally:
  - `GET /health` => 200
  - `GET /diagnose` => 200
  - `GET /migrate/history` => 200
  - `POST /migrate/preflight` => 200
- Added reusable smoke script:
  - `scripts/deploy-smoke-check.ps1`

### Deployment and env setup
- Frontend deployed on Vercel.
- Backend deployed on Render.
- Frontend uses deployed backend via VITE_API_URL.
- Backend CORS allows Vercel origin via CORS_ALLOWED_ORIGINS.
- OAuth redirect URIs configured for Vercel domain:
  - Spotify redirect URI uses Vercel domain root.
  - Google redirect URI uses Vercel callback path.

### Core migration flow
- Preflight endpoint available and active.
- Migration start endpoint active.
- Migration progress/status and track-level report active.
- Public playlist migration verified working with destination URL generated.

### Reliability/retry improvements implemented
- Retry now includes retryable statuses beyond hard failures:
  - FAILED
  - PARTIAL
  - NOT_FOUND
- Better backend error summarization for common causes:
  - API quota
  - OAuth scope/permission
  - expired/invalid token
  - temporary network/API issues
- Frontend retry UX now shows retryable issue breakdown and total retry count.

### Phase 3 implementation completed
- Backend now persists and serves user-scoped history:
  - Jobs store userId.
  - New endpoint: GET /migrate/history?userId=<uuid>&limit=<n>
- Frontend now syncs a user profile and loads history from backend first.
- Local history cache remains as fallback if history API is unavailable.

## In-Scope Roadmap (Next Work)

### Current Phase Snapshot (as of 2026-04-05)
- Phase 3: Completed
- Phase 4: Completed (2026-04-05)
- Phase 5: Completed (2026-04-05)
- Phase 6: In progress (Steps 1-3 completed)
- Phase 7: Pending

## Phase 3: Server-Side Job History (Priority: High)
Status: Completed on 2026-04-04

### Problem
Current history is local/browser-scoped, not account-scoped. Users lose continuity across devices/browsers.

### Goals
- Persist migration history on backend per user.
- Fetch history from backend on dashboard load.
- Keep UI filters and status summaries working.

### Implementation Steps
1. Add backend endpoint(s): list jobs with pagination and sort by updatedAt desc.
2. Associate jobs to user identity (if auth user model already available, use userId linkage).
3. Add frontend API call to load history from backend.
4. Keep local cache only as fallback when backend unavailable.
5. Add empty/loading/error states for history panel.

### Acceptance Criteria
- Same user can log in from another browser and see job history.
- History is not lost after cache clear.
- Dashboard still loads gracefully if history API fails.

## Phase 4: Retry Intelligence and Recovery UX (Priority: High)
Status: Completed on 2026-04-05

### Problem
Retry exists, but user guidance can still be improved for specific root causes.

### Goals
- Provide issue-category tags at track/job level.
- Show exact recommended action before retry.

### Implementation Steps
1. Add stable issue categories in backend response model (quota, permission, token, transient, no-match).
2. Map categories to user-facing actions in UI.
3. Show category counts in summary cards.
4. Add pre-retry checklist text based on dominant issue category.

### Acceptance Criteria
- User can identify why retry failed from UI without reading raw backend messages.
- Retry success rate improves for transient/token-related failures.

### Delivered (2026-04-05)
1. Added stable issue categories in backend response model for track-level retry diagnostics (`MigrationTrackResponse.issueCategory`).
2. Added backend recommended retry action per track (`MigrationTrackResponse.recommendedAction`).
3. Added job-level issue summary in migration report (`issueCategoryCounts`, `dominantIssueCategory`, `dominantIssueAction`).
4. Added category counts and dominant issue checklist guidance in transfer UI before retry.
5. Added per-track issue category tags in Track Mapping view.

## Phase 5: Match Quality Improvements (Priority: High)
Status: Completed on 2026-04-05

### Problem
Some fallback or low-confidence matches are acceptable but still noisy.

### Goals
- Improve ranking precision and reduce mismatches.

### Implementation Steps
1. Improve normalization rules for remix/live/cover/explicit/clean indicators.
2. Add weighted artist/title confidence tuning with configurable thresholds.
3. Add optional strict mode toggle in UI (favor precision over recall).
4. Track quality metrics per run:
   - confident matches
   - fallback matches
   - partial exports

### Acceptance Criteria
- Reduced obvious mismatches in sampled playlists.
- Improved confident-match ratio without major drop in completion rate.

### Delivered So Far (2026-04-05)
1. Improved candidate normalization by stripping noisy qualifiers and harmonizing text before similarity scoring.
2. Added indicator-aware matching logic for remix/live/cover/karaoke/explicit/clean/acoustic/instrumental.
3. Added configurable weighted scoring knobs via backend properties:
  - `youtube.match.title.weight`
  - `youtube.match.artist.weight`
  - `youtube.match.indicator.mismatch.penalty`
  - `youtube.match.indicator.match.bonus`
  - `youtube.match.artist.title.cross-weight`
4. Added score adjustments for indicator mismatch penalties and indicator-match bonuses.

### Remaining For Completion
None.

## Phase 6: Operational Hardening (Priority: Medium)
### Goals
- Increase production resilience and observability.

### Implementation Steps
1. Add deploy smoke checks script (health + migrate/preflight sanity). ✅ Completed on 2026-04-05 (`scripts/deploy-smoke-check.ps1`)
2. Add structured logs for key migration stages. ✅ Completed on 2026-04-05 (service + processor stage logs)
3. Add runtime safeguards for rate-limit heavy loops. ✅ Completed on 2026-04-05 (`MigrationAsyncProcessor` adaptive cooldown + configurable loop throttling)
4. Add basic dashboard telemetry counters (if desired).

### Acceptance Criteria
- Fast post-deploy confidence checks exist.
- Failures can be diagnosed quickly from logs.

## Phase 7: Release Readiness and Cleanup (Priority: Medium)
### Goals
- Prepare for stable production release and remove temporary scaffolding/docs.

### Implementation Steps
1. Final end-to-end manual test matrix execution.
2. Confirm OAuth, CORS, and env parity across preview/prod.
3. Consolidate README deployment steps.
4. Delete temporary build-phase docs/files (including this file).

### Acceptance Criteria
- Full checklist passes in production environment.
- Temporary files removed.

## Test Matrix (Run After Each Major Phase)
1. Spotify to YouTube migration with public playlist.
2. Spotify to YouTube migration with private playlist (OAuth required).
3. YouTube to Spotify migration with OAuth.
4. Preflight blocking checks when tokens/permissions are missing.
5. Retry flow on failed/partial/not-found tracks.
6. OAuth reconnect after token expiry.
7. CORS behavior from Vercel domain only.

## Deployment Checklist (Render + Vercel)
1. Backend deploy on Render.
2. Backend env verification:
   - SPOTIFY_CLIENT_ID
   - SPOTIFY_CLIENT_SECRET
   - YOUTUBE_API_KEY
   - GOOGLE_CLIENT_ID
   - GOOGLE_CLIENT_SECRET
   - CORS_ALLOWED_ORIGINS
3. Frontend deploy on Vercel.
4. Frontend env verification:
   - VITE_API_URL
   - VITE_SPOTIFY_CLIENT_ID
   - VITE_SPOTIFY_REDIRECT_URI
   - VITE_GOOGLE_CLIENT_ID
   - VITE_GOOGLE_REDIRECT_URI
5. OAuth provider redirect verification:
   - Spotify redirect URI exact match
   - Google callback URI exact match

## Risks and Mitigations
- Risk: OAuth scope mismatch in production.
  - Mitigation: explicit preflight blocker + reconnect guidance.
- Risk: YouTube quota exhaustion.
  - Mitigation: quota-specific error handling + retry guidance.
- Risk: cross-origin failures after domain changes.
  - Mitigation: CORS allowlist update checklist per deployment.
- Risk: false-positive matches.
  - Mitigation: stricter scoring rules + manual review indicators.

## Working Branch and Commit Convention
- Branch naming: feature/phase-<n>-<short-purpose>
- Commit format:
  - feat: for functional additions
  - fix: for bug or reliability fixes
  - chore: for non-functional maintenance

## Continuation Prompt (Copy/Paste to Resume Work)
Use this block in a new chat if context is lost:

"Continue SoundBridge implementation from BUILD_PHASE_PRD.md.
Current status: deploys are live, preflight and migration work, retry improvements are implemented.
Start with Phase 3 (server-side job history), then Phase 4.
For each phase: implement code changes, run backend/frontend builds, summarize what changed, and provide deploy/test steps." 

## Definition of Done (Final)
- All roadmap phases completed or explicitly deferred.
- Production deploy verified for both flows and retry scenarios.
- Documentation updated in main README.
- Temporary files removed:
  - BUILD_PHASE_PRD.md (this file)
  - Any temporary planning/checklist files created during build phase.
