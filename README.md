# SoundBridge Platform

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

## Database

- Default local runtime uses H2 in-memory DB with startup schema initialization (`src/main/resources/schema.sql`).
- For Supabase/Postgres, set datasource env vars from `.env.example` and run SQL from `supabase-schema.sql`.
