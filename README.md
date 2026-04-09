# SoundBridge Platform

SoundBridge migrates playlists between Spotify and YouTube Music using a Spring Boot backend and a React/Vite frontend.

## Runtime Architecture

- Backend: Spring Boot API on port `9000`
- Frontend: Vite app on port `5173`
- Local database: H2 (auto-initialized from `src/main/resources/schema.sql`)
- Optional production database: Supabase/Postgres (`supabase-schema.sql` and `supabase-migration-2026-04-05-fk-hardening.sql`)

## Local Development

### 1. Backend

```bash
mvn spring-boot:run
```

Backend URL: `http://localhost:9000`

### 2. Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend URL: `http://localhost:5173`

## Build Commands

### Backend build

```bash
mvn clean package -DskipTests
```

### Frontend production build

```bash
cd frontend
npm ci
npm run build
```

## Required Environment Variables

### Backend (Render or local)

- `SPOTIFY_CLIENT_ID`
- `SPOTIFY_CLIENT_SECRET`
- `YOUTUBE_API_KEY`
- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `CORS_ALLOWED_ORIGINS`

### Backend recommended match/reliability tuning

- `SPOTIFY_SAFE_FALLBACK_ENABLED=false`
- `YOUTUBE_MATCH_THRESHOLD=0.45`
- `YOUTUBE_RETRY_MATCH_THRESHOLD=0.40`
- `YOUTUBE_MATCH_STRICT_THRESHOLD=0.72`
- `YOUTUBE_MATCH_TITLE_WEIGHT=0.62`
- `YOUTUBE_MATCH_ARTIST_WEIGHT=0.38`
- `YOUTUBE_MATCH_INDICATOR_MISMATCH_PENALTY=0.18`
- `YOUTUBE_MATCH_INDICATOR_MATCH_BONUS=0.04`
- `YOUTUBE_MATCH_ARTIST_TITLE_CROSS_WEIGHT=0.55`

### Frontend (Vercel)

- `VITE_API_URL=https://<your-backend-domain>`
- `VITE_SPOTIFY_CLIENT_ID=<your-spotify-app-client-id>`
- `VITE_SPOTIFY_REDIRECT_URI=https://<your-vercel-app>.vercel.app`
- `VITE_GOOGLE_CLIENT_ID=<your-google-client-id>`
- `VITE_GOOGLE_REDIRECT_URI=https://<your-vercel-app>.vercel.app/auth/google/callback`

## OAuth Requirements

### Spotify

- Private/collaborative source playlists need user token scopes:
  - `playlist-read-private`
  - `playlist-read-collaborative`
- Spotify destination playlist creation requires user login and valid token.

### Google / YouTube

- Required scope for write operations: `https://www.googleapis.com/auth/youtube.force-ssl`
- If Google shows `access_denied` in testing mode, add your account as a Test User in OAuth consent screen.

## Deployment

### Backend on Render

1. Deploy from this repository.
2. Set all backend environment variables listed above.
3. Ensure `CORS_ALLOWED_ORIGINS` includes your Vercel domain(s).
4. Verify health endpoint: `GET /health`.

### Frontend on Vercel

1. Import repository and set root directory to `frontend`.
2. Build command: `npm run build`
3. Output directory: `dist`
4. Set all frontend environment variables listed above.

## API Endpoints

- `GET /health`
- `GET /diagnose`
- `POST /migrate`
- `POST /migrate/preflight`
- `GET /migrate/history?userId=<uuid>&limit=20`
- `GET /migrate/{jobId}`
- `GET /migrate/{jobId}/tracks`
- `GET /migrate/{jobId}/report`

## Validation Checklist

### Local validation

1. Backend compile: `mvn -DskipTests compile`
2. Frontend build: `cd frontend && npm run build`

### Post-deploy smoke check (PowerShell)

```powershell
./scripts/deploy-smoke-check.ps1 -BaseUrl "https://your-backend-domain" -UserId "11111111-1111-1111-1111-111111111111"
```

Checks:
- `GET /health`
- `GET /diagnose`
- `GET /migrate/history`
- `POST /migrate/preflight`

### Phase 7 authenticated E2E validation (PowerShell)

Use this when you have real Spotify and Google tokens and want one command to validate both migration directions.

```powershell
./scripts/phase7-e2e-validate.ps1 `
  -BaseUrl "https://your-backend-domain" `
  -SpotifyAccessToken "<spotify_access_token>" `
  -GoogleAccessToken "<google_access_token_with_youtube_force_ssl_scope>" `
  -SpotifySourcePlaylistUrl "https://open.spotify.com/playlist/<playlist_id>" `
  -YouTubeSourcePlaylistUrl "https://music.youtube.com/playlist?list=<playlist_id>"
```

Optional full migration run (starts jobs and polls until terminal status):

```powershell
./scripts/phase7-e2e-validate.ps1 `
  -BaseUrl "https://your-backend-domain" `
  -SpotifyAccessToken "<spotify_access_token>" `
  -GoogleAccessToken "<google_access_token_with_youtube_force_ssl_scope>" `
  -RunMigration
```
