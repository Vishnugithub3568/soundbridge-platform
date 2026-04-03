import { useEffect, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import Navbar from '../components/Navbar';
import JobSummaryCard from '../components/JobSummaryCard';
import TrackTable from '../components/TrackTable';
import {
  exchangeGoogleCode,
  getMigrationJob,
  getMigrationReport,
  getMigrationTracks,
  retryFailedTracks,
  startMigration
} from '../services/apiService';

const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED']);
const SPOTIFY_SCOPES = ['playlist-read-private', 'playlist-read-collaborative'];
const SPOTIFY_PKCE_VERIFIER_KEY = 'spotify_pkce_verifier';
const SPOTIFY_PKCE_STATE_KEY = 'spotify_pkce_state';
const SPOTIFY_TOKEN_CACHE_KEY = 'spotify_oauth_token';

const GOOGLE_SCOPES = [
  'openid',
  'profile',
  'email',
  'https://www.googleapis.com/auth/youtube.force-ssl'
];
const REQUIRED_YOUTUBE_SCOPE = 'https://www.googleapis.com/auth/youtube.force-ssl';
const GOOGLE_PKCE_VERIFIER_KEY = 'google_pkce_verifier';
const GOOGLE_PKCE_STATE_KEY = 'google_pkce_state';
const GOOGLE_TOKEN_CACHE_KEY = 'google_oauth_token';
const GOOGLE_ID_TOKEN_CACHE_KEY = 'google_oauth_id_token';
const GOOGLE_REQUIRED_SCOPE_MESSAGE =
  'Google token is missing YouTube permission. Reconnect Google and approve YouTube access.';
const THEME_CACHE_KEY = 'soundbridge_theme';
const JOB_HISTORY_CACHE_KEY = 'soundbridge_job_history';

function readThemePreference() {
  try {
    const stored = window.localStorage.getItem(THEME_CACHE_KEY);
    return stored === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

function readJobHistory() {
  try {
    const stored = window.localStorage.getItem(JOB_HISTORY_CACHE_KEY);
    if (!stored) {
      return [];
    }

    const parsed = JSON.parse(stored);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function formatHistoryDate(value) {
  if (!value) {
    return 'Just now';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return 'Just now';
  }

  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit'
  }).format(date);
}

function historyStatusClass(status) {
  switch (status) {
    case 'COMPLETED':
      return 'badge-matched';
    case 'FAILED':
      return 'badge-failed';
    case 'RUNNING':
      return 'badge-neutral';
    default:
      return 'badge-partial';
  }
}

function isFallbackReason(reason) {
  const value = String(reason || '').trim();
  return value.startsWith('SAFE_FALLBACK:') || value.startsWith('LOW_CONFIDENCE_FALLBACK:');
}

function isFailedReason(reason) {
  return String(reason || '').trim().startsWith('FAILED:');
}

function encodeBase64Url(bytes) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  let output = '';

  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i];
    const b = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const c = i + 2 < bytes.length ? bytes[i + 2] : 0;
    const chunk = (a << 16) | (b << 8) | c;

    output += chars[(chunk >> 18) & 63];
    output += chars[(chunk >> 12) & 63];
    output += i + 1 < bytes.length ? chars[(chunk >> 6) & 63] : '';
    output += i + 2 < bytes.length ? chars[chunk & 63] : '';
  }

  return output;
}

function randomString(length) {
  const bytes = new Uint8Array(length);
  window.crypto.getRandomValues(bytes);
  return encodeBase64Url(bytes);
}

async function sha256(input) {
  const encoder = new TextEncoder();
  const buffer = await window.crypto.subtle.digest('SHA-256', encoder.encode(input));
  return new Uint8Array(buffer);
}

function cacheSpotifyToken(token, expiresInSeconds) {
  const expiresAt = Date.now() + Math.max(0, Number(expiresInSeconds || 0)) * 1000;
  const payload = JSON.stringify({ token, expiresAt });
  window.localStorage.setItem(SPOTIFY_TOKEN_CACHE_KEY, payload);
}

function readCachedSpotifyToken() {
  try {
    const raw = window.localStorage.getItem(SPOTIFY_TOKEN_CACHE_KEY);
    if (!raw) {
      return '';
    }

    const parsed = JSON.parse(raw);
    if (!parsed?.token || !parsed?.expiresAt || parsed.expiresAt <= Date.now() + 60000) {
      window.localStorage.removeItem(SPOTIFY_TOKEN_CACHE_KEY);
      return '';
    }

    return parsed.token;
  } catch {
    window.localStorage.removeItem(SPOTIFY_TOKEN_CACHE_KEY);
    return '';
  }
}

function cacheGoogleToken(token, expiresInSeconds, idToken, scope) {
  const expiresAt = Date.now() + Math.max(0, Number(expiresInSeconds || 3600)) * 1000;
  const payload = JSON.stringify({ token, expiresAt, scope: String(scope || '').trim() });
  window.localStorage.setItem(GOOGLE_TOKEN_CACHE_KEY, payload);
  if (idToken) {
    window.localStorage.setItem(GOOGLE_ID_TOKEN_CACHE_KEY, idToken);
  }
}

function readCachedGoogleToken() {
  try {
    const raw = window.localStorage.getItem(GOOGLE_TOKEN_CACHE_KEY);
    if (!raw) {
      return { token: '', scope: '' };
    }

    const parsed = JSON.parse(raw);
    if (!parsed?.token || !parsed?.expiresAt || parsed.expiresAt <= Date.now() + 60000) {
      window.localStorage.removeItem(GOOGLE_TOKEN_CACHE_KEY);
      window.localStorage.removeItem(GOOGLE_ID_TOKEN_CACHE_KEY);
      return { token: '', scope: '' };
    }

    return {
      token: parsed.token,
      scope: String(parsed.scope || '').trim()
    };
  } catch {
    window.localStorage.removeItem(GOOGLE_TOKEN_CACHE_KEY);
    window.localStorage.removeItem(GOOGLE_ID_TOKEN_CACHE_KEY);
    return { token: '', scope: '' };
  }
}

function isLocalRuntimeHost() {
  const host = window.location.hostname;
  return host === 'localhost' || host === '127.0.0.1';
}

function isLoopbackUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1';
  } catch {
    return false;
  }
}

function resolveRedirectUri(configuredValue, fallbackValue) {
  const configured = String(configuredValue || '').trim();
  if (!configured) {
    return fallbackValue;
  }

  // Prevent production deployments from using localhost callback values by mistake.
  if (!isLocalRuntimeHost() && isLoopbackUrl(configured)) {
    return fallbackValue;
  }

  return configured;
}

function hasRequiredGoogleScope(scopeString) {
  const normalized = String(scopeString || '').trim();
  if (!normalized) {
    return false;
  }

  const scopes = new Set(normalized.split(/\s+/).filter(Boolean));
  return scopes.has(REQUIRED_YOUTUBE_SCOPE) || scopes.has('https://www.googleapis.com/auth/youtube');
}

function describeGoogleAuthError(authError) {
  const normalized = String(authError || '').trim();
  if (normalized === 'access_denied') {
    return 'Google login was blocked because this OAuth app is still in testing. Add your Google account as a Test user in Google Cloud Console, or publish the app, then try again.';
  }

  return `Google login failed: ${normalized || 'unknown_error'}`;
}

function validateSourcePlaylistUrl(rawUrl, direction) {
  const url = String(rawUrl || '').trim();
  if (!url) {
    return 'Playlist URL is required.';
  }

  const normalized = url.toLowerCase();

  if (direction === 'SPOTIFY_TO_YOUTUBE') {
    if (normalized.includes('/album/')) {
      return 'Spotify album URL detected. Please use a playlist URL: https://open.spotify.com/playlist/{id}';
    }
    if (!normalized.includes('open.spotify.com/playlist/')) {
      return 'Invalid Spotify playlist URL. Use format: https://open.spotify.com/playlist/{id}';
    }
    return '';
  }

  if (direction === 'YOUTUBE_TO_SPOTIFY') {
    const isYouTubePlaylist =
      (normalized.includes('music.youtube.com/playlist') || normalized.includes('youtube.com/playlist'))
      && normalized.includes('list=');

    if (!isYouTubePlaylist) {
      return 'Invalid YouTube Music playlist URL. Use format: https://music.youtube.com/playlist?list={id}';
    }
    return '';
  }

  return 'Invalid migration direction selected.';
}

function MigrationPage() {
  const [playlistUrl, setPlaylistUrl] = useState('');
  const [direction, setDirection] = useState('SPOTIFY_TO_YOUTUBE');
  const [theme, setTheme] = useState(() => readThemePreference());
  const [view, setView] = useState('dashboard');
  const [spotifyAccessToken, setSpotifyAccessToken] = useState(() => readCachedSpotifyToken());
  const [googleAccessToken, setGoogleAccessToken] = useState(() => readCachedGoogleToken().token);
  const [googleScope, setGoogleScope] = useState(() => readCachedGoogleToken().scope);
  const [googleUser, setGoogleUser] = useState(null);
  const [job, setJob] = useState(null);
  const [tracks, setTracks] = useState([]);
  const [report, setReport] = useState(null);
  const [jobHistory, setJobHistory] = useState(() => readJobHistory());
  const [loading, setLoading] = useState(false);
  const [spotifyAuthLoading, setSpotifyAuthLoading] = useState(false);
  const [googleAuthLoading, setGoogleAuthLoading] = useState(false);
  const [error, setError] = useState('');
  const [showFailedOnly, setShowFailedOnly] = useState(false);
  const spotifyClientId = String(import.meta.env.VITE_SPOTIFY_CLIENT_ID ?? '').trim();
  const spotifyRedirectUri = resolveRedirectUri(import.meta.env.VITE_SPOTIFY_REDIRECT_URI, window.location.origin);
  const googleClientId = String(import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '').trim();
  const googleRedirectUri = resolveRedirectUri(import.meta.env.VITE_GOOGLE_REDIRECT_URI, `${window.location.origin}/callback`);

  const canSubmit = useMemo(() => playlistUrl.trim().length > 0 && !loading, [playlistUrl, loading]);

  const progressPercent = useMemo(() => {
    if (!job || !job.totalTracks) {
      return 0;
    }
    const processed = (job.matchedTracks || 0) + (job.failedTracks || 0);
    return Math.min(100, Math.round((processed / job.totalTracks) * 100));
  }, [job]);

  const visibleTracks = useMemo(() => {
    if (!showFailedOnly) {
      return tracks;
    }
    return tracks.filter((track) => track.matchStatus === 'FAILED');
  }, [tracks, showFailedOnly]);

  const reliabilityStats = useMemo(() => {
    const fallbackUsed = tracks.filter((track) => isFallbackReason(track.failureReason)).length;
    const hardFailures = tracks.filter((track) => track.matchStatus === 'FAILED' || isFailedReason(track.failureReason)).length;
    const confidentMatches = tracks.filter(
      (track) => track.matchStatus === 'MATCHED' && !isFallbackReason(track.failureReason)
    ).length;

    return {
      fallbackUsed,
      hardFailures,
      confidentMatches
    };
  }, [tracks]);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark');
    document.documentElement.dataset.theme = theme;
    try {
      window.localStorage.setItem(THEME_CACHE_KEY, theme);
    } catch {
      // ignore storage failures
    }
  }, [theme]);

  useEffect(() => {
    try {
      window.localStorage.setItem(JOB_HISTORY_CACHE_KEY, JSON.stringify(jobHistory.slice(0, 12)));
    } catch {
      // ignore storage failures
    }
  }, [jobHistory]);

  const upsertHistoryEntry = (jobData, reportData, trackCount) => {
    if (!jobData?.id) {
      return;
    }

    const entry = {
      id: jobData.id,
      status: jobData.status,
      direction,
      sourcePlaylistUrl: jobData.sourcePlaylistUrl,
      targetPlatform: jobData.targetPlatform,
      targetPlaylistUrl: jobData.targetPlaylistUrl,
      totalTracks: jobData.totalTracks ?? trackCount ?? 0,
      matchedTracks: jobData.matchedTracks ?? 0,
      failedTracks: jobData.failedTracks ?? 0,
      matchRate: reportData?.matchRate ?? 0,
      updatedAt: jobData.updatedAt || new Date().toISOString()
    };

    setJobHistory((currentHistory) => {
      const nextHistory = [entry, ...currentHistory.filter((item) => item.id !== jobData.id)];
      return nextHistory.slice(0, 12);
    });
  };

  const refreshJobData = async (jobId) => {
    const [jobData, tracksData, reportData] = await Promise.all([
      getMigrationJob(jobId),
      getMigrationTracks(jobId),
      getMigrationReport(jobId)
    ]);
    setJob(jobData);
    setTracks(tracksData);
    setReport(reportData);
    setError('');
    upsertHistoryEntry(jobData, reportData, tracksData.length);
    return jobData;
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError('');

    const isSpotifyToYouTube = direction === 'SPOTIFY_TO_YOUTUBE';
    const isYouTubeToSpotify = direction === 'YOUTUBE_TO_SPOTIFY';
    const sourceUrlError = validateSourcePlaylistUrl(playlistUrl, direction);

    if (sourceUrlError) {
      setError(sourceUrlError);
      return;
    }

    if (isSpotifyToYouTube && (!googleAccessToken || !googleAccessToken.trim())) {
      setError('Google login is required to export tracks into a YouTube Music playlist.');
      return;
    }

    if (isYouTubeToSpotify && (!spotifyAccessToken || !spotifyAccessToken.trim())) {
      setError('Spotify login is required to create a playlist and add tracks.');
      return;
    }

    if (isYouTubeToSpotify && (!googleAccessToken || !googleAccessToken.trim())) {
      setError('Google login is required to access YouTube Music playlists.');
      return;
    }

    setLoading(true);
    setTracks([]);
    setReport(null);

    try {
      const createdJob = await startMigration(playlistUrl.trim(), spotifyAccessToken, googleAccessToken, direction);
      setJob(createdJob);
      upsertHistoryEntry(createdJob, null, 0);
      await refreshJobData(createdJob.id);
    } catch (submitError) {
      setError(submitError?.response?.data?.message || submitError.message || 'Failed to start migration');
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!job?.id) {
      return undefined;
    }

    if (TERMINAL_STATUSES.has(job.status)) {
      setLoading(false);
      return undefined;
    }

    const intervalId = window.setInterval(async () => {
      try {
        const latest = await refreshJobData(job.id);
        if (TERMINAL_STATUSES.has(latest.status)) {
          setLoading(false);
          window.clearInterval(intervalId);
        }
      } catch (pollError) {
        setError(pollError?.response?.data?.message || pollError.message || 'Polling failed');
        setLoading(false);
        window.clearInterval(intervalId);
      }
    }, 2000);

    return () => window.clearInterval(intervalId);
  }, [job?.id, job?.status]);

  useEffect(() => {
    if (job?.status === 'FAILED' && !error) {
      setError('Migration failed. Please review track-level errors and try again.');
    }
  }, [job?.status, error]);

  useEffect(() => {
    if (!googleAccessToken) {
      return;
    }

    if (hasRequiredGoogleScope(googleScope)) {
      return;
    }

    setGoogleAccessToken('');
    setGoogleScope('');
    setGoogleUser(null);
    window.localStorage.removeItem(GOOGLE_TOKEN_CACHE_KEY);
    window.localStorage.removeItem(GOOGLE_ID_TOKEN_CACHE_KEY);
    setError(GOOGLE_REQUIRED_SCOPE_MESSAGE);
  }, [googleAccessToken, googleScope]);

  const handleRetryFailed = async () => {
    if (!job?.id || loading) {
      return;
    }

    setError('');
    setLoading(true);

    try {
      const queuedJob = await retryFailedTracks(job.id);
      setJob(queuedJob);
      upsertHistoryEntry(queuedJob, report, tracks.length);
      await refreshJobData(job.id);
    } catch (retryError) {
      setError(retryError?.response?.data?.message || retryError.message || 'Failed to retry failed tracks');
      setLoading(false);
    }
  };

  const canRetryFailed = Boolean(job?.id) && !loading && (job?.failedTracks || 0) > 0;

  const beginSpotifyLogin = async () => {
    setError('');

    if (!spotifyClientId.trim()) {
      setError('Missing VITE_SPOTIFY_CLIENT_ID. Add it to frontend/.env or Vercel environment variables.');
      return;
    }

    try {
      setSpotifyAuthLoading(true);
      const verifier = randomString(64);
      const state = randomString(24);
      const challenge = encodeBase64Url(await sha256(verifier));

      window.localStorage.setItem(SPOTIFY_PKCE_VERIFIER_KEY, verifier);
      window.localStorage.setItem(SPOTIFY_PKCE_STATE_KEY, state);

      const authorizeUrl = new URL('https://accounts.spotify.com/authorize');
      authorizeUrl.searchParams.set('response_type', 'code');
      authorizeUrl.searchParams.set('client_id', spotifyClientId);
      authorizeUrl.searchParams.set('scope', SPOTIFY_SCOPES.join(' '));
      authorizeUrl.searchParams.set('redirect_uri', spotifyRedirectUri);
      authorizeUrl.searchParams.set('state', state);
      authorizeUrl.searchParams.set('code_challenge_method', 'S256');
      authorizeUrl.searchParams.set('code_challenge', challenge);

      window.location.assign(authorizeUrl.toString());
    } catch {
      setSpotifyAuthLoading(false);
      setError(`Failed to start Spotify login. Ensure this exact redirect URI is added in Spotify app settings: ${spotifyRedirectUri}`);
    }
  };

  const disconnectSpotify = () => {
    setSpotifyAccessToken('');
    window.localStorage.removeItem(SPOTIFY_TOKEN_CACHE_KEY);
    window.localStorage.removeItem(SPOTIFY_PKCE_VERIFIER_KEY);
    window.localStorage.removeItem(SPOTIFY_PKCE_STATE_KEY);
  };

  const beginGoogleLogin = async () => {
    setError('');

    if (!googleClientId.trim()) {
      setError('Missing VITE_GOOGLE_CLIENT_ID. Add it to frontend/.env or Vercel environment variables.');
      return;
    }

    try {
      setGoogleAuthLoading(true);
      const verifier = randomString(64);
      const state = randomString(24);
      const challenge = encodeBase64Url(await sha256(verifier));

      window.localStorage.setItem(GOOGLE_PKCE_VERIFIER_KEY, verifier);
      window.localStorage.setItem(GOOGLE_PKCE_STATE_KEY, state);

      const authorizeUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth');
      authorizeUrl.searchParams.set('response_type', 'code');
      authorizeUrl.searchParams.set('client_id', googleClientId);
      authorizeUrl.searchParams.set('scope', GOOGLE_SCOPES.join(' '));
      authorizeUrl.searchParams.set('redirect_uri', googleRedirectUri);
      authorizeUrl.searchParams.set('state', state);
      authorizeUrl.searchParams.set('code_challenge_method', 'S256');
      authorizeUrl.searchParams.set('code_challenge', challenge);
      authorizeUrl.searchParams.set('access_type', 'offline');
      authorizeUrl.searchParams.set('include_granted_scopes', 'true');
      authorizeUrl.searchParams.set('prompt', 'consent');

      window.location.assign(authorizeUrl.toString());
    } catch {
      setGoogleAuthLoading(false);
      setError(`Failed to start Google login. Ensure this exact redirect URI is added in Google Cloud Console: ${googleRedirectUri}`);
    }
  };

  const disconnectGoogle = () => {
    setGoogleAccessToken('');
    setGoogleScope('');
    setGoogleUser(null);
    window.localStorage.removeItem(GOOGLE_TOKEN_CACHE_KEY);
    window.localStorage.removeItem(GOOGLE_ID_TOKEN_CACHE_KEY);
    window.localStorage.removeItem(GOOGLE_PKCE_VERIFIER_KEY);
    window.localStorage.removeItem(GOOGLE_PKCE_STATE_KEY);
  };

  useEffect(() => {
    const hasPendingSpotifyAuth = Boolean(window.localStorage.getItem(SPOTIFY_PKCE_STATE_KEY));
    if (!hasPendingSpotifyAuth) {
      return;
    }

    const params = new URLSearchParams(window.location.search);
    const code = params.get('code');
    const state = params.get('state');
    const authError = params.get('error');

    if (!code && !authError) {
      return;
    }

    const clearQueryParams = () => {
      const nextUrl = `${window.location.pathname}${window.location.hash || ''}`;
      window.history.replaceState({}, document.title, nextUrl);
    };

    if (authError) {
      clearQueryParams();
      setError(`Spotify login failed: ${authError}`);
      setSpotifyAuthLoading(false);
      return;
    }

    const storedVerifier = window.localStorage.getItem(SPOTIFY_PKCE_VERIFIER_KEY);
    const storedState = window.localStorage.getItem(SPOTIFY_PKCE_STATE_KEY);

    if (!storedVerifier || !storedState || storedState !== state) {
      clearQueryParams();
      setError('Spotify login failed: invalid OAuth state. Please try again.');
      setSpotifyAuthLoading(false);
      return;
    }

    const exchangeCode = async () => {
      try {
        setSpotifyAuthLoading(true);
        const body = new URLSearchParams();
        body.set('grant_type', 'authorization_code');
        body.set('code', code);
        body.set('redirect_uri', spotifyRedirectUri);
        body.set('client_id', spotifyClientId);
        body.set('code_verifier', storedVerifier);

        const response = await fetch('https://accounts.spotify.com/api/token', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
          },
          body
        });

        if (!response.ok) {
          const details = await response.text();
          throw new Error(`Spotify token exchange failed (${response.status}): ${details || 'Unknown error'}`);
        }

        const tokenPayload = await response.json();
        if (!tokenPayload?.access_token) {
          throw new Error('Spotify token exchange response did not include access_token');
        }

        setSpotifyAccessToken(tokenPayload.access_token);
        cacheSpotifyToken(tokenPayload.access_token, tokenPayload.expires_in);
        setError('');
      } catch (exchangeError) {
        setError(exchangeError.message || 'Failed to complete Spotify login.');
      } finally {
        window.localStorage.removeItem(SPOTIFY_PKCE_VERIFIER_KEY);
        window.localStorage.removeItem(SPOTIFY_PKCE_STATE_KEY);
        clearQueryParams();
        setSpotifyAuthLoading(false);
      }
    };

    exchangeCode();
  }, [spotifyClientId, spotifyRedirectUri]);

  useEffect(() => {
    const hasPendingGoogleAuth = Boolean(window.localStorage.getItem(GOOGLE_PKCE_STATE_KEY));
    if (!hasPendingGoogleAuth) {
      return;
    }

    const params = new URLSearchParams(window.location.search);
    const code = params.get('code');
    const state = params.get('state');
    const authError = params.get('error');

    if (!code && !authError) {
      return;
    }

    const clearQueryParams = () => {
      const nextUrl = `${window.location.pathname}${window.location.hash || ''}`;
      window.history.replaceState({}, document.title, nextUrl);
    };

    if (authError) {
      clearQueryParams();
      setError(describeGoogleAuthError(authError));
      setGoogleAuthLoading(false);
      return;
    }

    const storedVerifier = window.localStorage.getItem(GOOGLE_PKCE_VERIFIER_KEY);
    const storedState = window.localStorage.getItem(GOOGLE_PKCE_STATE_KEY);

    if (!storedVerifier || !storedState || storedState !== state) {
      clearQueryParams();
      setError('Google login failed: invalid OAuth state. Please try again.');
      setGoogleAuthLoading(false);
      return;
    }

    const exchangeCode = async () => {
      try {
        setGoogleAuthLoading(true);
        const tokenPayload = await exchangeGoogleCode(code, googleRedirectUri, storedVerifier);
        if (!tokenPayload?.access_token) {
          throw new Error('Google token exchange response did not include access_token');
        }

        if (!hasRequiredGoogleScope(tokenPayload.scope)) {
          throw new Error(GOOGLE_REQUIRED_SCOPE_MESSAGE);
        }

        setGoogleAccessToken(tokenPayload.access_token);
        setGoogleScope(String(tokenPayload.scope || '').trim());
        cacheGoogleToken(tokenPayload.access_token, tokenPayload.expires_in, tokenPayload.id_token, tokenPayload.scope);

        // Decode ID token to get user info
        if (tokenPayload.id_token) {
          const parts = tokenPayload.id_token.split('.');
          if (parts.length === 3) {
            try {
              const decoded = JSON.parse(atob(parts[1]));
              setGoogleUser({
                email: decoded.email,
                name: decoded.name,
                picture: decoded.picture
              });
            } catch {
              // Silently fail, user is still authenticated
            }
          }
        }

        setError('');
      } catch (exchangeError) {
        setError(exchangeError.message || 'Failed to complete Google login.');
      } finally {
        window.localStorage.removeItem(GOOGLE_PKCE_VERIFIER_KEY);
        window.localStorage.removeItem(GOOGLE_PKCE_STATE_KEY);
        clearQueryParams();
        setGoogleAuthLoading(false);
      }
    };

    exchangeCode();
  }, [googleClientId, googleRedirectUri]);

  const historySection = (
    <motion.section
      className="glass-card glass-card-hover p-5 md:p-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35 }}
    >
      <div className="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.28em] text-cyan-300/80">Job History</p>
          <h2 className="mt-2 text-2xl font-black text-white">Recent migrations</h2>
          <p className="mt-1 text-sm text-slate-400">Stored locally so you can review recent runs without affecting the backend.</p>
        </div>
        <button type="button" onClick={() => setView('dashboard')} className="glow-button-secondary self-start">
          Back to dashboard
        </button>
      </div>

      <div className="mt-5 grid gap-4">
        {jobHistory.length ? jobHistory.map((entry) => (
          <article key={entry.id} className="history-row">
            <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={`badge-status ${historyStatusClass(entry.status)}`}>{entry.status}</span>
                  <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-400">
                    {entry.direction === 'YOUTUBE_TO_SPOTIFY' ? 'YouTube Music → Spotify' : 'Spotify → YouTube Music'}
                  </span>
                </div>
                <p className="mt-3 truncate font-mono text-xs text-slate-300">{entry.sourcePlaylistUrl}</p>
                <div className="mt-3 flex flex-wrap gap-3 text-sm text-slate-400">
                  <span>{entry.totalTracks} tracks</span>
                  <span>{entry.matchedTracks} matched</span>
                  <span>{entry.failedTracks} failed</span>
                  <span>{entry.matchRate?.toFixed ? `${entry.matchRate.toFixed(1)}% match` : `${entry.matchRate}% match`}</span>
                  <span>{formatHistoryDate(entry.updatedAt)}</span>
                </div>
              </div>
              {entry.targetPlaylistUrl ? (
                <a
                  href={entry.targetPlaylistUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="glow-button-secondary inline-flex items-center justify-center"
                >
                  Open destination
                </a>
              ) : null}
            </div>
          </article>
        )) : (
          <div className="rounded-2xl border border-white/10 bg-white/5 p-6 text-sm text-slate-400">
            No saved jobs yet. Run a migration from the dashboard to populate history.
          </div>
        )}
      </div>
    </motion.section>
  );

  const dashboardSection = (
    <div className="grid gap-6 xl:grid-cols-[1.2fr_0.8fr]">
      <motion.section
        className="glass-card glass-card-hover p-5 md:p-7"
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45 }}
      >
        <div className="mb-6 flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.28em] text-cyan-300/80">Playlist Migration</p>
            <h1 className="mt-2 text-4xl font-black tracking-tight md:text-6xl">
              <span className="gradient-heading">SoundBridge</span>
            </h1>
            <p className="mt-3 max-w-2xl text-sm text-slate-300 md:text-base">
              Modern SaaS dashboard for Spotify and YouTube Music migrations with async processing, scoring, and track review.
            </p>
          </div>
          <div className="rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-sm text-slate-300">
            <div className="flex items-center gap-2">
              <span className="pulse-dot animate-glow-pulse" />
              <span>{loading ? 'Migration active' : 'Ready to migrate'}</span>
            </div>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="grid gap-5">
          <div className="glass-card bg-white/5 p-4">
            <label className="block text-xs font-semibold uppercase tracking-[0.28em] text-slate-400">Migration Direction</label>
            <div className="mt-4 grid gap-3 md:grid-cols-2">
              <label className={`flex cursor-pointer items-center gap-3 rounded-2xl border p-4 transition ${direction === 'SPOTIFY_TO_YOUTUBE' ? 'border-cyan-300/30 bg-cyan-400/10 shadow-[0_0_28px_rgba(56,189,248,0.15)]' : 'border-white/10 bg-white/5 hover:bg-white/8'}`}>
                <input
                  type="radio"
                  name="direction"
                  value="SPOTIFY_TO_YOUTUBE"
                  checked={direction === 'SPOTIFY_TO_YOUTUBE'}
                  onChange={(event) => setDirection(event.target.value)}
                  className="h-4 w-4 accent-cyan-400"
                />
                <div>
                  <p className="font-semibold text-white">Spotify → YouTube Music</p>
                  <p className="text-xs text-slate-400">Best for exporting Spotify playlists into YouTube Music.</p>
                </div>
              </label>
              <label className={`flex cursor-pointer items-center gap-3 rounded-2xl border p-4 transition ${direction === 'YOUTUBE_TO_SPOTIFY' ? 'border-fuchsia-300/30 bg-fuchsia-400/10 shadow-[0_0_28px_rgba(217,70,239,0.15)]' : 'border-white/10 bg-white/5 hover:bg-white/8'}`}>
                <input
                  type="radio"
                  name="direction"
                  value="YOUTUBE_TO_SPOTIFY"
                  checked={direction === 'YOUTUBE_TO_SPOTIFY'}
                  onChange={(event) => setDirection(event.target.value)}
                  className="h-4 w-4 accent-fuchsia-400"
                />
                <div>
                  <p className="font-semibold text-white">YouTube Music → Spotify</p>
                  <p className="text-xs text-slate-400">Import a YouTube Music playlist into Spotify.</p>
                </div>
              </label>
            </div>
          </div>

          <div className="grid gap-4 md:grid-cols-[1fr_auto]">
            <div>
              <label htmlFor="playlistUrl" className="mb-2 block text-xs font-semibold uppercase tracking-[0.28em] text-slate-400">
                {direction === 'SPOTIFY_TO_YOUTUBE' ? 'Spotify' : 'YouTube Music'} Playlist URL
              </label>
              <input
                id="playlistUrl"
                name="playlistUrl"
                value={playlistUrl}
                onChange={(event) => setPlaylistUrl(event.target.value)}
                placeholder={
                  direction === 'SPOTIFY_TO_YOUTUBE'
                    ? 'https://open.spotify.com/playlist/...'
                    : 'https://music.youtube.com/playlist?list=...'
                }
                className="input-dark font-mono"
                required
              />
            </div>
            <div className="flex items-end">
              <button type="submit" disabled={!canSubmit} className="glow-button w-full md:w-auto">
                {loading ? 'Migrating...' : 'Start Migration'}
              </button>
            </div>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <div className="glass-card glass-card-hover p-5">
              <div className="flex flex-col gap-4">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-[0.28em] text-slate-400">Spotify Login</p>
                  <p className="mt-2 text-sm text-slate-300">
                    OAuth login for private playlists using <span className="font-semibold text-white">playlist-read-private</span> and{' '}
                    <span className="font-semibold text-white">playlist-read-collaborative</span>.
                  </p>
                  <p className="mt-3 text-sm text-slate-200">Status: {spotifyAccessToken ? 'Connected' : 'Not connected'}</p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={beginSpotifyLogin}
                    disabled={spotifyAuthLoading}
                    className="glow-button-secondary inline-flex min-w-[150px] flex-1 items-center justify-center"
                  >
                    {spotifyAuthLoading ? 'Connecting...' : spotifyAccessToken ? 'Reconnect Spotify' : 'Login with Spotify'}
                  </button>
                  {spotifyAccessToken ? (
                    <button
                      type="button"
                      onClick={disconnectSpotify}
                      className="glow-button-secondary inline-flex min-w-[120px] flex-1 items-center justify-center border-rose-400/20 text-rose-200 hover:border-rose-300/30 hover:bg-rose-500/10"
                    >
                      Disconnect
                    </button>
                  ) : null}
                </div>
              </div>
            </div>

            <div className="glass-card glass-card-hover p-5">
              <div className="flex flex-col gap-4">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-[0.28em] text-slate-400">Google Login</p>
                  <p className="mt-2 text-sm text-slate-300">
                    OAuth login with YouTube write access to create playlists and add matched tracks automatically.
                  </p>
                  <p className="mt-3 text-sm text-slate-200">
                    Status: {googleAccessToken ? `Connected as ${googleUser?.email || 'User'}` : 'Not connected'}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={beginGoogleLogin}
                    disabled={googleAuthLoading}
                    className="glow-button-secondary inline-flex min-w-[150px] flex-1 items-center justify-center"
                  >
                    {googleAuthLoading ? 'Connecting...' : googleAccessToken ? 'Reconnect Google' : 'Login with Google'}
                  </button>
                  {googleAccessToken ? (
                    <button
                      type="button"
                      onClick={disconnectGoogle}
                      className="glow-button-secondary inline-flex min-w-[120px] flex-1 items-center justify-center border-rose-400/20 text-rose-200 hover:border-rose-300/30 hover:bg-rose-500/10"
                    >
                      Disconnect
                    </button>
                  ) : null}
                </div>
              </div>
              <div className="mt-4 rounded-2xl border border-amber-400/15 bg-amber-400/10 px-4 py-3 text-xs leading-6 text-amber-100">
                If Google shows <span className="font-semibold">access_denied</span>, add your account as a Test user in Google Cloud Console or publish the app.
              </div>
            </div>
          </div>

          <div className="flex flex-col gap-3 rounded-2xl border border-white/10 bg-white/5 px-4 py-4 md:flex-row md:items-center md:justify-between">
            <div className="flex items-center gap-3 text-sm text-slate-300">
              <span className="animate-glow-pulse inline-flex h-3 w-3 rounded-full bg-cyan-400 shadow-[0_0_18px_rgba(34,211,238,0.5)]" />
              <span>Running async migration job in background.</span>
            </div>
            {canRetryFailed ? (
              <button
                type="button"
                disabled={!canRetryFailed}
                onClick={handleRetryFailed}
                className="glow-button-secondary border-amber-400/20 text-amber-100 hover:border-amber-300/30 hover:bg-amber-500/10"
              >
                Retry Failed Tracks ({job?.failedTracks || 0})
              </button>
            ) : null}
          </div>
        </form>
      </motion.section>

      <div className="grid gap-6">
        <JobSummaryCard job={job} progressPercent={progressPercent} loading={loading} report={report} reliabilityStats={reliabilityStats} />

        <motion.section
          className="glass-card glass-card-hover p-5 md:p-6"
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.3 }}
        >
          <div className="flex flex-col gap-3">
            <div className="flex items-center justify-between gap-3">
              <div>
                <h2 className="text-xl font-black text-white">Track filters</h2>
                <p className="mt-1 text-sm text-slate-400">Toggle failed tracks only to inspect the problem set.</p>
              </div>
              {showFailedOnly && tracks ? (
                <span className="badge-status badge-neutral">{tracks.filter((track) => track.matchStatus === 'FAILED').length} failed</span>
              ) : null}
            </div>
            <label className="inline-flex items-center gap-3 rounded-2xl border border-white/10 bg-white/5 px-4 py-3 text-sm text-slate-200">
              <input
                type="checkbox"
                checked={showFailedOnly}
                onChange={(event) => setShowFailedOnly(event.target.checked)}
                className="h-4 w-4 rounded border-white/20 bg-transparent accent-cyan-400"
              />
              Show only failed tracks
            </label>
          </div>
        </motion.section>
      </div>
    </div>
  );

  return (
    <main className="soft-grid mx-auto min-h-screen w-full max-w-7xl px-4 py-4 md:px-6 md:py-6">
      <Navbar
        currentView={view}
        onViewChange={setView}
        theme={theme}
        onToggleTheme={() => setTheme((currentTheme) => (currentTheme === 'dark' ? 'light' : 'dark'))}
        jobCount={jobHistory.length}
      />

      {error ? (
        <motion.section
          className="glass-card mb-6 border-rose-400/20 bg-rose-500/10 p-4 text-sm text-rose-100"
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
        >
          {error}
        </motion.section>
      ) : null}

      {view === 'history' ? historySection : dashboardSection}

      {view === 'dashboard' ? <TrackTable tracks={visibleTracks} /> : null}
    </main>
  );
}

export default MigrationPage;
