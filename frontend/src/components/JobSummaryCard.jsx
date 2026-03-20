import { motion } from 'framer-motion';

function statusClass(statusLabel) {
  switch (statusLabel) {
    case 'COMPLETED':
      return 'status-completed';
    case 'FAILED':
      return 'status-failed';
    case 'RUNNING':
      return 'status-running';
    default:
      return 'status-started';
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
    <div className="rounded-xl border border-clay bg-white/80 p-3">
      <p className="text-xs uppercase tracking-wide text-stone-500">{label}</p>
      <p className="mt-1 text-2xl font-bold">{value}</p>
    </div>
  );
}

function JobSummaryCard({ job, progressPercent, loading, report, reliabilityStats }) {
  if (!job) {
    return null;
  }

  const statusLabel = displayStatus(job.status);

  return (
    <motion.section
      className="rounded-2xl border border-clay bg-gradient-to-br from-amber-50 to-orange-50 p-5 shadow-panel"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35 }}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-2xl font-bold">Migration Job</h2>
          <p className="mt-1 break-all font-mono text-xs text-stone-600">{job.sourcePlaylistUrl}</p>
        </div>
        <span className={`status-chip ${statusClass(statusLabel)}`}>{statusLabel}</span>
      </div>

      <div className="mt-4">
        <div className="mb-2 flex items-center justify-between text-sm text-stone-600">
          <span>Migration Progress</span>
          <span className="font-semibold text-stone-900">{progressPercent}%</span>
        </div>
        <div className="relative h-3 overflow-hidden rounded-full bg-stone-200">
          <motion.div
            className="h-full rounded-full bg-gradient-to-r from-mint to-emerald-400"
            initial={{ width: 0 }}
            animate={{ width: `${progressPercent}%` }}
            transition={{ duration: 0.5 }}
          />
          {loading ? <div className="absolute inset-0 w-1/3 animate-shimmer bg-white/30" /> : null}
        </div>
      </div>

      <div className="mt-4 grid gap-3 md:grid-cols-5">
        <Stat label="Total" value={job.totalTracks} />
        <Stat label="Matched" value={job.matchedTracks} />
        <Stat label="Failed" value={job.failedTracks} />
        <Stat label="Fallbacks" value={reliabilityStats?.fallbackUsed ?? 0} />
        <Stat label="Match Rate" value={report ? `${report.matchRate.toFixed(1)}%` : '-'} />
      </div>
    </motion.section>
  );
}

export default JobSummaryCard;
