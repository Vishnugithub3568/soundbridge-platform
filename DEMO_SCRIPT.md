# SoundBridge Demo Script (2-3 Minutes)

## Scope Lock

Primary flow for this demo is only:

Spotify playlist URL -> YouTube migration

Do not branch into other providers or reverse directions during the live presentation.

## Demo Setup (Before You Share Screen)

1. Start backend: `mvn spring-boot:run`
2. Start frontend: `cd frontend && npm run dev`
3. Open app at `http://localhost:5173`
4. Keep one valid Spotify playlist URL ready in clipboard

## 2-3 Minute Live Script

### 0:00-0:20 | Problem and Promise

What to click:
1. Show landing/page header and migration form.

What to say:
"Music users are locked into platforms. SoundBridge migrates playlists quickly and reliably. In this demo, I will move a Spotify playlist to YouTube in one flow, then show transparent progress and match quality."

### 0:20-0:55 | Start the Migration

What to click:
1. In source URL input, paste Spotify playlist link.
2. Confirm target platform is YouTube.
3. Click Start Migration.

What to say:
"I provide a Spotify playlist URL and start migration. The backend creates an async job immediately, so the UI stays responsive while processing runs in the background."

Technical highlight (simple):
- Async job creation decouples request time from processing time.

### 0:55-1:35 | Progress and Reliability

What to click:
1. Open/keep job summary panel visible.
2. Point to status updates and counters.
3. If available, trigger retry for failed tracks.

What to say:
"You can see live status and counts as the job progresses. If any track fails to match on first pass, retry lets us recover without restarting the full migration."

Technical highlights (simple):
- Progress is polled and rendered live.
- Retry path handles transient failures or weak matches.

### 1:35-2:20 | Match Quality and Explainability

What to click:
1. Open track table.
2. Point to matched tracks and confidence score.
3. Show one lower-score row and explain fallback behavior.

What to say:
"Each track shows its best YouTube candidate and a confidence score. Scoring combines title, artist, and album similarity so matching is measurable, not a black box."

Technical highlights (simple):
- Weighted scoring on metadata similarity.
- Deterministic candidate selection with visible confidence.

### 2:20-2:50 | Close Strong

What to click:
1. Return to summary card with final totals.

What to say:
"This is the core value: a Spotify playlist moves to YouTube with async execution, observable progress, and resilient retry handling. The user gets control and transparency end to end."

## Core Interview Answers (Prep)

### Q1: Why async instead of synchronous migration?

"Playlist migration depends on multiple external API calls and can take time. Async keeps API latency low, prevents request timeouts, and gives users progress visibility while work continues in the background."

### Q2: How do you ensure match quality?

"We score multiple candidates using track metadata similarity, then pick the highest-confidence result. This keeps behavior predictable and allows us to show confidence in the UI."

### Q3: How do you handle failures?

"Failures are isolated per track and surfaced in the UI. Retry lets users recover failed items without rerunning successful ones, which improves reliability and user trust."

### Q4: What is your biggest engineering tradeoff?

"We favor transparent, deterministic matching over opaque heuristics. That may miss some edge cases, but it makes behavior debuggable and easier to improve safely."

### Q5: How would you scale this?

"Move job execution to a queue-backed worker model, add rate-limit aware batching, and persist richer match telemetry for better tuning and observability."
