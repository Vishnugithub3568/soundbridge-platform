import { useEffect, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import JobSummaryCard from '../components/JobSummaryCard';
import TrackTable from '../components/TrackTable';
import { getMigrationJob, getMigrationReport, getMigrationTracks, startMigration } from '../services/apiService';

const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED']);

function MigrationPage() {
  const [playlistUrl, setPlaylistUrl] = useState('');
  const [job, setJob] = useState(null);
  const [tracks, setTracks] = useState([]);
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const canSubmit = useMemo(() => playlistUrl.trim().length > 0 && !loading, [playlistUrl, loading]);

  const progressPercent = useMemo(() => {
    if (!job || !job.totalTracks) {
      return 0;
    }
    const processed = (job.matchedTracks || 0) + (job.failedTracks || 0);
    return Math.min(100, Math.round((processed / job.totalTracks) * 100));
  }, [job]);

  const refreshJobData = async (jobId) => {
    const [jobData, tracksData, reportData] = await Promise.all([
      getMigrationJob(jobId),
      getMigrationTracks(jobId),
      getMigrationReport(jobId)
    ]);
    setJob(jobData);
    setTracks(tracksData);
    setReport(reportData);
    return jobData;
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError('');
    setLoading(true);
    setTracks([]);
    setReport(null);

    try {
      const createdJob = await startMigration(playlistUrl.trim());
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

        {loading ? (
          <div className="mt-4 flex items-center gap-2 text-sm text-stone-600">
            <span className="h-3 w-3 animate-pulse rounded-full bg-mint" />
            <span>Running async migration job in background...</span>
          </div>
        ) : null}
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

      <JobSummaryCard job={job} progressPercent={progressPercent} loading={loading} report={report} />
      <TrackTable tracks={tracks} />
    </main>
  );
}

export default MigrationPage;
