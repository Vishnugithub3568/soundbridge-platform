# SoundBridge Platform

SoundBridge helps users migrate music from Spotify to YouTube Music with a fast, reliable Spring Boot + React experience.

End-to-end scaffold for Spotify to YouTube Music migration flow with a Spring Boot backend and React/Vite frontend.

## Backend

### Run

```bash
mvn spring-boot:run
```

Backend URL: `http://localhost:9000`

### Build

```bash
mvn clean package -DskipTests
```

### Smoke Check (PowerShell)

Run a quick backend verification after restart/deploy:

```powershell
./scripts/deploy-smoke-check.ps1
```

Optional parameters:

```powershell
./scripts/deploy-smoke-check.ps1 -BaseUrl "https://your-backend-domain" -UserId "11111111-1111-1111-1111-111111111111"
```

### Endpoints

- `GET /health`
- `POST /migrate`
- `POST /migrate/preflight`
- `GET /migrate/history?userId=<uuid>&limit=20`
- `GET /migrate/{jobId}`
- `GET /migrate/{jobId}/tracks`
- `GET /migrate/{jobId}/report`

## Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend URL: `http://localhost:5173`

### Production Build

```bash
cd frontend
npm ci
npm run build
```

## Database

- Default local runtime uses H2 in-memory DB with startup schema initialization (`src/main/resources/schema.sql`).
- For Supabase/Postgres, set datasource env vars from `.env.example` and run SQL from `supabase-schema.sql`.

## Deployment Notes

- Required secrets: `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `YOUTUBE_API_KEY`
- Reliability config:
	- `SPOTIFY_SAFE_FALLBACK_ENABLED=false` (default)
	- `YOUTUBE_MATCH_THRESHOLD=0.45`
	- `YOUTUBE_RETRY_MATCH_THRESHOLD=0.40`
   - `YOUTUBE_MATCH_STRICT_THRESHOLD=0.72`
   - `YOUTUBE_MATCH_TITLE_WEIGHT=0.62`
   - `YOUTUBE_MATCH_ARTIST_WEIGHT=0.38`
   - `YOUTUBE_MATCH_INDICATOR_MISMATCH_PENALTY=0.18`
   - `YOUTUBE_MATCH_INDICATOR_MATCH_BONUS=0.04`
   - `YOUTUBE_MATCH_ARTIST_TITLE_CROSS_WEIGHT=0.55`

- Google OAuth note:
   - If Google shows `access_denied` and says the app has not completed verification, add your Google account as a Test user in the Google Cloud OAuth consent screen or publish the app.
   - The migration flow requires YouTube write scope: `https://www.googleapis.com/auth/youtube.force-ssl`.

### Google Cloud Console Steps

1. Open Google Cloud Console and select the project used by this app.
2. Go to APIs & Services, then OAuth consent screen.
3. If the app is in Testing, add your Gmail address under Test users.
4. Confirm the authorized redirect URI matches the frontend callback URL.
5. Reconnect Google in the app and approve the YouTube permission prompt again.

## Private Spotify Playlists

- Public playlists can migrate with backend app credentials (`SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET`).
- Private or collaborative playlists require a Spotify user access token with scopes:
  - `playlist-read-private`
  - `playlist-read-collaborative`
- Configure frontend env with `VITE_SPOTIFY_CLIENT_ID`.
- Configure frontend env with `VITE_SPOTIFY_REDIRECT_URI` that exactly matches Spotify app settings.
- In the UI, use `Login with Spotify` to connect via Authorization Code + PKCE.
- After login, the access token is forwarded automatically during migration requests.

## Vercel Frontend Deployment

1. Import the repository in Vercel.
2. Set the root directory to `frontend`.
3. Keep defaults:
   - Build command: `npm run build`
   - Output directory: `dist`
4. Add environment variable in Vercel:
   - `VITE_API_URL=https://<your-backend-domain>`
   - `VITE_SPOTIFY_CLIENT_ID=<your-spotify-app-client-id>`
   - `VITE_SPOTIFY_REDIRECT_URI=https://<your-vercel-app>.vercel.app`

Backend must allow your Vercel origin through CORS:

- `CORS_ALLOWED_ORIGINS=https://<your-vercel-app>.vercel.app`
- For previews, you can provide multiple comma-separated origins.
