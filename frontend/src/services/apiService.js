import axios from 'axios';

function isLocalRuntimeHost() {
  const host = window.location.hostname;
  return host === 'localhost' || host === '127.0.0.1';
}

function isVercelRuntimeHost() {
  return window.location.hostname.endsWith('.vercel.app');
}

function isLoopbackUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1';
  } catch {
    return false;
  }
}

function resolveApiBaseUrl() {
  const renderProductionApi = 'https://soundbridge-platform.onrender.com';
  const configured = String(import.meta.env.VITE_API_URL ?? '').trim();
  if (!configured) {
    if (!isLocalRuntimeHost() && isVercelRuntimeHost()) {
      return renderProductionApi;
    }
    return '/api';
  }

  // Ignore localhost API URLs in production deployments.
  if (!isLocalRuntimeHost() && isLoopbackUrl(configured)) {
    if (isVercelRuntimeHost()) {
      return renderProductionApi;
    }
    return '/api';
  }

  return configured;
}

const api = axios.create({
  baseURL: resolveApiBaseUrl()
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (!error?.response) {
      const networkError = new Error(
        'Cannot reach backend API. Set VITE_API_URL to your deployed backend URL and allow your frontend origin in CORS_ALLOWED_ORIGINS.'
      );
      networkError.cause = error;
      return Promise.reject(networkError);
    }

    return Promise.reject(error);
  }
);

export async function startMigration(
  sourcePlaylistUrl,
  spotifyAccessToken,
  googleAccessToken,
  direction = 'SPOTIFY_TO_YOUTUBE',
  userId = '',
  strictMode = false
) {
  const payload = {
    sourcePlaylistUrl,
    direction,
    strictMode: Boolean(strictMode)
  };
  if (spotifyAccessToken && spotifyAccessToken.trim()) {
    payload.spotifyAccessToken = spotifyAccessToken.trim();
  }
  if (googleAccessToken && googleAccessToken.trim()) {
    payload.googleAccessToken = googleAccessToken.trim();
  }
  if (userId && String(userId).trim()) {
    payload.userId = String(userId).trim();
  }

  const response = await api.post('/migrate', payload);
  return response.data;
}

export async function preflightMigration(sourcePlaylistUrl, spotifyAccessToken, googleAccessToken, direction = 'SPOTIFY_TO_YOUTUBE') {
  const payload = {
    sourcePlaylistUrl,
    direction
  };

  if (spotifyAccessToken && spotifyAccessToken.trim()) {
    payload.spotifyAccessToken = spotifyAccessToken.trim();
  }
  if (googleAccessToken && googleAccessToken.trim()) {
    payload.googleAccessToken = googleAccessToken.trim();
  }

  const response = await api.post('/migrate/preflight', payload);
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

export async function getMigrationHistory(userId, limit = 20) {
  const normalizedUserId = String(userId || '').trim();
  if (!normalizedUserId) {
    return [];
  }

  const response = await api.get('/migrate/history', {
    params: {
      userId: normalizedUserId,
      limit
    }
  });
  return Array.isArray(response.data) ? response.data : [];
}

export async function getDashboardOverview(userId, email = '') {
  const response = await api.get('/dashboard/overview', {
    params: {
      userId: String(userId || '').trim(),
      email: String(email || '').trim()
    }
  });
  return response.data;
}

export async function getDashboardLibrary(userId) {
  const response = await api.get('/dashboard/library', {
    params: {
      userId: String(userId || '').trim()
    }
  });
  return Array.isArray(response.data) ? response.data : [];
}

export async function getDashboardServicesStatus() {
  const response = await api.get('/dashboard/services-status');
  return Array.isArray(response.data) ? response.data : [];
}

export async function getDashboardNavigation() {
  const response = await api.get('/dashboard/navigation');
  return Array.isArray(response.data) ? response.data : [];
}

export async function exchangeGoogleCode(code, redirectUri, codeVerifier) {
  const response = await api.post('/auth/google/token', {
    code,
    redirectUri,
    codeVerifier
  });
  return response.data;
}

export async function syncUserProfile(userId, email, displayName = '') {
  const response = await api.post('/auth/sync-user', {
    userId,
    email,
    displayName
  });
  return response.data;
}
