import { motion } from 'framer-motion';

function Stat({ label, value }) {
  return (
    <div className="stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function JobSummaryCard({ job, report }) {
  if (!job) {
    return null;
  }

  return (
    <motion.section
      className="panel"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35 }}
    >
      <div className="panelHeader">
        <h2>Migration Job</h2>
        <span className={`chip chip-${job.status.toLowerCase()}`}>{job.status}</span>
      </div>
      <p className="muted">{job.sourcePlaylistUrl}</p>
      <div className="statsGrid">
        <Stat label="Total Tracks" value={job.totalTracks} />
        <Stat label="Matched" value={job.matchedTracks} />
        <Stat label="Failed" value={job.failedTracks} />
        <Stat label="Match Rate" value={report ? `${report.matchRate.toFixed(1)}%` : '-'} />
      </div>
    </motion.section>
  );
}

export default JobSummaryCard;
