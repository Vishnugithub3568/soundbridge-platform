import { useEffect, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
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

function MigrationPage() {
  const [playlistUrl, setPlaylistUrl] = useState('');
  const [spotifyAccessToken, setSpotifyAccessToken] = useState(() => readCachedSpotifyToken());
  const [googleAccessToken, setGoogleAccessToken] = useState(() => readCachedGoogleToken().token);
  const [googleScope, setGoogleScope] = useState(() => readCachedGoogleToken().scope);
  const [googleUser, setGoogleUser] = useState(null);
  const [job, setJob] = useState(null);
  const [tracks, setTracks] = useState([]);
  const [report, setReport] = useState(null);
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
    return jobData;
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError('');

    if (!googleAccessToken || !googleAccessToken.trim()) {
      setError('Google login is required to export tracks into a YouTube Music playlist.');
      return;
    }

    setLoading(true);
    setTracks([]);
    setReport(null);

    try {
      const createdJob = await startMigration(playlistUrl.trim(), spotifyAccessToken, googleAccessToken);
      setJob(createdJob);
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

  return (
    <main className="mx-auto grid w-full max-w-6xl gap-5 px-4 py-8 md:px-6">
      <motion.header
        className="rounded-2xl border border-clay bg-white/80 p-6 shadow-panel"
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45 }}
      >
        <h1 className="text-4xl font-bold tracking-tight md:text-6xl">SoundBridge</h1>
        <p className="mt-2 max-w-3xl text-sm text-stone-600 md:text-base">
          Production-style migration pipeline from Spotify playlists to YouTube Music with async job processing and
          weighted match scoring.
        </p>
      </motion.header>

      <motion.form
        className="rounded-2xl border border-clay bg-white/80 p-5 shadow-panel"
        onSubmit={handleSubmit}
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, delay: 0.05 }}
      >
        <label htmlFor="playlistUrl" className="block text-sm font-bold uppercase tracking-wide text-stone-600">
          Spotify Playlist URL
        </label>
        <div className="mt-3 flex flex-col gap-3 md:flex-row">
          <input
            id="playlistUrl"
            name="playlistUrl"
            value={playlistUrl}
            onChange={(event) => setPlaylistUrl(event.target.value)}
            placeholder="https://open.spotify.com/playlist/..."
            className="w-full rounded-xl border border-clay bg-white px-4 py-3 font-mono text-sm focus:border-mint focus:outline-none"
            required
          />
          <button
            type="submit"
            disabled={!canSubmit}
            className="rounded-xl bg-gradient-to-r from-mint to-emerald-500 px-6 py-3 font-bold text-white transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {loading ? 'Migrating...' : 'Start Migration'}
          </button>
        </div>

        <div className="mt-4 rounded-xl border border-clay bg-stone-50/70 p-4">
          <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
            <div>
              <p className="text-sm font-bold uppercase tracking-wide text-stone-600">Spotify Login</p>
              <p className="mt-1 text-xs text-stone-500">
                Use OAuth login for private playlists with scopes
                {' '}<span className="font-semibold">playlist-read-private</span>
                {' '}and{' '}<span className="font-semibold">playlist-read-collaborative</span>.
              </p>
              <p className="mt-2 text-xs font-semibold text-stone-700">
                Status: {spotifyAccessToken ? 'Connected' : 'Not connected'}
              </p>
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={beginSpotifyLogin}
                disabled={spotifyAuthLoading}
                className="rounded-xl border border-stone-300 bg-white px-4 py-2 text-sm font-bold text-stone-700 transition hover:bg-stone-100 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {spotifyAuthLoading ? 'Connecting...' : (spotifyAccessToken ? 'Reconnect Spotify' : 'Login with Spotify')}
              </button>
              {spotifyAccessToken ? (
                <button
                  type="button"
                  onClick={disconnectSpotify}
                  className="rounded-xl border border-red-300 bg-red-50 px-4 py-2 text-sm font-bold text-red-700 transition hover:bg-red-100"
                >
                  Disconnect
                </button>
              ) : null}
            </div>
          </div>
        </div>

        <div className="mt-4 rounded-xl border border-clay bg-stone-50/70 p-4">
          <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
            <div>
              <p className="text-sm font-bold uppercase tracking-wide text-stone-600">Google Login</p>
              <p className="mt-1 text-xs text-stone-500">
                Use OAuth login with YouTube write access to create playlists and add matched tracks automatically.
              </p>
              <p className="mt-2 text-xs font-semibold text-stone-700">
                Status: {googleAccessToken ? `Connected as ${googleUser?.email || 'User'}` : 'Not connected'}
              </p>
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={beginGoogleLogin}
                disabled={googleAuthLoading}
                className="rounded-xl border border-stone-300 bg-white px-4 py-2 text-sm font-bold text-stone-700 transition hover:bg-stone-100 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {googleAuthLoading ? 'Connecting...' : (googleAccessToken ? 'Reconnect Google' : 'Login with Google')}
              </button>
              {googleAccessToken ? (
                <button
                  type="button"
                  onClick={disconnectGoogle}
                  className="rounded-xl border border-red-300 bg-red-50 px-4 py-2 text-sm font-bold text-red-700 transition hover:bg-red-100"
                >
                  Disconnect
                </button>
              ) : null}
            </div>
          </div>
          <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900">
            If Google shows <span className="font-semibold">access_denied</span>, your account must be added as a
            <span className="font-semibold"> Test user</span> in Google Cloud Console or the OAuth app must be
            published. Use the same Google account you added there before reconnecting.
          </div>
        </div>
          <div className="mt-4 flex items-center gap-2 text-sm text-stone-600">
            <span className="h-3 w-3 animate-pulse rounded-full bg-mint" />
            <span>Running async migration job in background...</span>
          </div>
      </motion.form>

      {error ? (
        <motion.section
          className="rounded-2xl border border-red-300 bg-red-50 p-4 text-sm text-red-700 shadow-panel"
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
        >
          {error}
        </motion.section>
      ) : null}

      <JobSummaryCard
        job={job}
        progressPercent={progressPercent}
        loading={loading}
        report={report}
        reliabilityStats={reliabilityStats}
      />

      <motion.section
        className="rounded-2xl border border-clay bg-white/80 p-4 shadow-panel"
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
      >
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-3">
            <label className="inline-flex items-center gap-2 text-sm font-semibold text-stone-700">
              <input
                type="checkbox"
                checked={showFailedOnly}
                onChange={(event) => setShowFailedOnly(event.target.checked)}
                className="h-4 w-4 rounded border-clay text-mint focus:ring-mint"
              />
              Show only failed tracks
            </label>
            {showFailedOnly && tracks && (
              <span className="text-xs font-bold text-stone-500 bg-stone-100 px-2 py-1 rounded-full">
                {tracks.filter(t => t.matchStatus === 'FAILED').length} failed
              </span>
            )}
          </div>

          {canRetryFailed && (
            <button
              type="button"
              disabled={!canRetryFailed}
              onClick={handleRetryFailed}
              className="rounded-xl border-2 border-amber-400 bg-gradient-to-r from-amber-50 to-orange-50 px-4 py-2 text-sm font-bold text-amber-700 transition hover:bg-amber-100 hover:border-amber-500 disabled:cursor-not-allowed disabled:opacity-50 flex items-center gap-2"
            >
              <span>🔄</span>
              Retry Failed Tracks ({job?.failedTracks || 0})
            </button>
          )}
        </div>
      </motion.section>

      <TrackTable tracks={visibleTracks} />
    </main>
  );
}

export default MigrationPage;
