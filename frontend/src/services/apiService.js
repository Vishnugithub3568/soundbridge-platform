import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? '/api'
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (!error?.response) {
      const networkError = new Error(
        'Cannot reach backend API. Start the backend on http://localhost:9000 or configure VITE_API_URL to a reachable server.'
      );
      networkError.cause = error;
      return Promise.reject(networkError);
    }

    return Promise.reject(error);
  }
);

export async function startMigration(spotifyPlaylistUrl, spotifyAccessToken) {
  const payload = { spotifyPlaylistUrl };
  if (spotifyAccessToken && spotifyAccessToken.trim()) {
    payload.spotifyAccessToken = spotifyAccessToken.trim();
  }

  const response = await api.post('/migrate', payload);
  return response.data;
}

export async function getMigrationJob(jobId) {
  const response = await api.get(`/migrate/${jobId}`);
  return response.data;
}

export async function getMigrationTracks(jobId) {
  const response = await api.get(`/migrate/${jobId}/tracks`);
  return response.data;
}

export async function getMigrationReport(jobId) {
  const response = await api.get(`/migrate/${jobId}/report`);
  return response.data;
}

export async function retryFailedTracks(jobId) {
  const response = await api.post(`/migrate/${jobId}/retry-failed`);
  return response.data;
}
