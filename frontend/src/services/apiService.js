import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? 'http://localhost:9000'
});

export async function startMigration(spotifyPlaylistUrl) {
  const response = await api.post('/migrate', { spotifyPlaylistUrl });
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
