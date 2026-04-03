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

### Endpoints

- `GET /health`
- `POST /migrate`
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
