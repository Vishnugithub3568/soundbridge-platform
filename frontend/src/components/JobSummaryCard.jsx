import { motion } from 'framer-motion';

function statusClass(statusLabel) {
  switch (statusLabel) {
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

function displayStatus(status) {
  if (status === 'QUEUED') {
    return 'STARTED';
  }
  return status;
}

function Stat({ label, value }) {
  return (
    <div className="stat-card h-full min-h-[104px]">
      <p className="text-[11px] uppercase tracking-[0.2em] leading-4 text-slate-400">{label}</p>
      <p className="mt-2 text-2xl font-black text-white">{value}</p>
    </div>
  );
}

function JobSummaryCard({ job, progressPercent, loading, report, reliabilityStats }) {
  if (!job) {
    return null;
  }

  const statusLabel = displayStatus(job.status);
  const safeProgress = Math.max(0, Math.min(100, Number(progressPercent || 0)));
  const sourcePlaylistLabel = String(job.sourcePlaylistUrl || '').trim() || 'Source playlist link unavailable';

  return (
    <motion.section
      className="glass-card glass-card-hover p-5 md:p-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35 }}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.28em] text-cyan-300/80">Migration Job</p>
          <h2 className="mt-2 text-2xl font-black tracking-tight text-white md:text-3xl">Track migration in progress</h2>
          <p className="mt-2 break-all font-mono text-xs text-slate-300/80">{sourcePlaylistLabel}</p>
        </div>
        <motion.span
          className={`badge-status ${statusClass(statusLabel)}`}
          animate={statusLabel === 'RUNNING' ? { opacity: [0.72, 1, 0.72] } : { opacity: 1 }}
          transition={statusLabel === 'RUNNING' ? { duration: 1.4, repeat: Infinity } : {}}
        >
          {statusLabel}
        </motion.span>
      </div>

      <div className="mt-4">
        <div className="mb-2 flex items-center justify-between text-sm text-slate-300">
          <span>Migration Progress</span>
          <span className="font-semibold text-white">{safeProgress}%</span>
        </div>
        <div className="relative h-3 overflow-hidden rounded-full bg-white/10 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)]">
          <motion.div
            className="h-full rounded-full bg-gradient-to-r from-cyan-400 via-sky-500 to-fuchsia-500 shadow-[0_0_24px_rgba(56,189,248,0.45)]"
            initial={{ width: 0 }}
            animate={{ width: `${safeProgress}%` }}
            transition={{ duration: 0.5 }}
          />
          {loading ? <div className="absolute inset-0 w-1/3 animate-shimmer bg-white/20" /> : null}
        </div>
      </div>

      <div className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-3">
        <Stat label="Total" value={job.totalTracks} />
        <Stat label="Matched" value={job.matchedTracks} />
        <Stat label="Failed" value={job.failedTracks} />
        <Stat label="Fallbacks" value={reliabilityStats?.fallbackUsed ?? 0} />
        <Stat label="Match Rate" value={report ? `${report.matchRate.toFixed(1)}%` : '-'} />
      </div>

      {loading ? (
        <div className="mt-4 flex items-center gap-3 rounded-2xl border border-cyan-300/10 bg-cyan-400/10 px-4 py-3 text-sm text-cyan-100">
          <span className="pulse-dot animate-glow-pulse" />
          <span>Processing tracks in the background with live progress updates.</span>
        </div>
      ) : null}
    </motion.section>
  );
}

export default JobSummaryCard;
