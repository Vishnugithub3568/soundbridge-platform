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

  return (
    <main className="layout">
      <motion.header
        className="hero"
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45 }}
      >
        <h1>SoundBridge</h1>
        <p>Migrate Spotify playlists to YouTube Music with deterministic mock matching.</p>
      </motion.header>

      <motion.form
        className="panel formPanel"
        onSubmit={handleSubmit}
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, delay: 0.05 }}
      >
        <label htmlFor="playlistUrl">Spotify Playlist URL</label>
        <div className="formRow">
          <input
            id="playlistUrl"
            name="playlistUrl"
            value={playlistUrl}
            onChange={(event) => setPlaylistUrl(event.target.value)}
            placeholder="https://open.spotify.com/playlist/..."
            required
          />
          <button type="submit" disabled={!canSubmit}>
            {loading ? 'Running...' : 'Start Migration'}
          </button>
        </div>
        {error ? <p className="errorText">{error}</p> : null}
      </motion.form>

      <JobSummaryCard job={job} report={report} />
      <TrackTable tracks={tracks} />
    </main>
  );
}

export default MigrationPage;
