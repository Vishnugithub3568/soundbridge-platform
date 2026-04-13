import { useMemo, useState } from 'react';

function getThumbnail(track) {
  if (track.targetThumbnailUrl) {
    return track.targetThumbnailUrl;
  }
  if (track.youtubeVideoId || track.targetTrackId) {
    const videoId = track.youtubeVideoId || track.targetTrackId;
    return `https://img.youtube.com/vi/${videoId}/mqdefault.jpg`;
  }
  return null;
}

function getVideoId(track) {
  return track.youtubeVideoId || track.targetTrackId || null;
}

function getStatusColor(status) {
  switch (status) {
    case 'MATCHED':
      return 'badge-matched';
    case 'PARTIAL':
      return 'badge-partial';
    case 'NOT_FOUND':
      return 'badge-neutral';
    case 'FAILED':
      return 'badge-failed';
    default:
      return 'badge-neutral';
  }
}

function classifyReason(reason) {
  const value = String(reason || '').trim();
  if (!value) {
    return { type: 'none', label: '' };
  }

  if (value.startsWith('SAFE_FALLBACK:')) {
    return {
      type: 'fallback',
      label: value.replace('SAFE_FALLBACK:', '').trim() || 'Reliable fallback used'
    };
  }

  if (value.startsWith('LOW_CONFIDENCE_FALLBACK:')) {
    return {
      type: 'fallback',
      label: value.replace('LOW_CONFIDENCE_FALLBACK:', '').trim() || 'Low-confidence candidate accepted'
    };
  }

  if (value.startsWith('PARTIAL:')) {
    return {
      type: 'warning',
      label: value.replace('PARTIAL:', '').trim() || 'Reviewable match with export limitations'
    };
  }

  if (value.startsWith('FAILED:')) {
    return {
      type: 'error',
      label: value.replace('FAILED:', '').trim() || value
    };
  }

  return { type: 'info', label: value };
}

function issueCategoryLabel(category) {
  const normalized = String(category || '').trim().toUpperCase();
  switch (normalized) {
    case 'QUOTA':
      return 'Quota';
    case 'PERMISSION':
      return 'Permission';
    case 'TOKEN':
      return 'Token';
    case 'TRANSIENT':
      return 'Transient';
    case 'NO_MATCH':
      return 'No Match';
    case 'PARTIAL':
      return 'Partial';
    case 'UNKNOWN':
      return 'Unknown';
    default:
      return '';
  }
}

function ScoreVisualization({ score }) {
  if (score === null || score === undefined) return <span className="text-slate-500">-</span>;
  
  const percentage = Math.round(score * 100);
  const isGood = percentage >= 65;
  
  return (
    <div className="flex items-center gap-2">
      <div className="h-2 w-20 overflow-hidden rounded-full bg-white/10">
        <div 
          className={`h-full transition-all ${isGood ? 'bg-emerald-400 shadow-[0_0_18px_rgba(52,211,153,0.45)]' : 'bg-amber-400 shadow-[0_0_18px_rgba(251,191,36,0.45)]'}`}
          style={{ width: `${percentage}%` }}
        />
      </div>
      <span className={`rounded-full px-2 py-0.5 text-[11px] font-bold ${isGood ? 'bg-emerald-500/15 text-emerald-300' : 'bg-amber-500/15 text-amber-300'}`}>
        {percentage}%
      </span>
    </div>
  );
}

function TrackTable({ tracks }) {
  const [previewTrack, setPreviewTrack] = useState(null);
  const [sortConfig, setSortConfig] = useState({ key: 'sourceTrackName', direction: 'asc' });

  const previewVideoId = useMemo(() => {
    if (!previewTrack) {
      return null;
    }
    return getVideoId(previewTrack);
  }, [previewTrack]);

  const sortedTracks = useMemo(() => {
    const sorted = [...(tracks || [])];
    sorted.sort((a, b) => {
      const aVal = a[sortConfig.key];
      const bVal = b[sortConfig.key];
      
      if (typeof aVal === 'number' && typeof bVal === 'number') {
        return sortConfig.direction === 'asc' ? aVal - bVal : bVal - aVal;
      }
      
      const aStr = String(aVal || '').toLowerCase();
      const bStr = String(bVal || '').toLowerCase();
      return sortConfig.direction === 'asc' 
        ? aStr.localeCompare(bStr) 
        : bStr.localeCompare(aStr);
    });
    return sorted;
  }, [tracks, sortConfig]);

  const handleSort = (key) => {
    setSortConfig(prevConfig => ({
      key,
      direction: prevConfig.key === key && prevConfig.direction === 'asc' ? 'desc' : 'asc'
    }));
  };

  const SortableHeader = ({ label, sortKey }) => (
    <th className="px-2 py-2 pr-2">
      <button
        type="button"
        onClick={() => handleSort(sortKey)}
        className="inline-flex w-full select-none items-center gap-1 rounded-md px-1 py-1 text-left text-xs uppercase tracking-[0.2em] text-slate-400 transition hover:bg-white/8 hover:text-slate-200"
      >
        {label}
        <span className="text-xs">
          {sortConfig.key === sortKey ? (sortConfig.direction === 'asc' ? '↑' : '↓') : ''}
        </span>
      </button>
    </th>
  );

  if (!tracks?.length) {
    return (
      <section className="glass-card glass-card-hover p-5">
        <h2 className="text-xl font-black text-white">Tracks</h2>
        <p className="mt-2 text-sm text-slate-300">No tracks processed yet. Start a migration to see results here.</p>
      </section>
    );
  }

  return (
    <section className="glass-card glass-card-hover p-5 md:p-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-black text-white md:text-2xl">Track Mapping</h2>
          <p className="mt-1 text-sm text-slate-400">Glowing match cards, preview support, and score badges.</p>
        </div>
        <p className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-semibold uppercase tracking-[0.22em] text-slate-300">{tracks.length} tracks shown</p>
      </div>

      <div className="mt-4 overflow-x-auto">
        <table className="w-full min-w-[980px] border-collapse text-sm text-slate-200">
          <thead>
            <tr className="border-b border-white/10 text-left text-xs uppercase tracking-[0.2em] text-slate-400">
              <SortableHeader label="Source Track" sortKey="sourceTrackName" />
              <SortableHeader label="Artist" sortKey="sourceArtistName" />
              <SortableHeader label="Status" sortKey="matchStatus" />
              <SortableHeader label="Match Score" sortKey="matchScore" />
              <th className="py-2 pr-2">Destination Result</th>
              <th className="py-2 pr-2">Details</th>
            </tr>
          </thead>
          <tbody>
            {sortedTracks.map((track) => {
              const thumbnail = getThumbnail(track);
              const statusColor = getStatusColor(track.matchStatus);
              const reasonMeta = classifyReason(track.failureReason);
              const issueLabel = issueCategoryLabel(track.issueCategory);
              return (
                <tr key={track.id} className="border-b border-white/5 align-top transition hover:bg-white/5">
                  <td className="py-4 pr-2 font-semibold max-w-xs truncate text-white">{track.sourceTrackName}</td>
                  <td className="py-4 pr-2 max-w-xs truncate text-slate-400">{track.sourceArtistName}</td>
                  <td className="py-3 pr-2">
                    <span className={`badge-status ${statusColor}`}>
                      {track.matchStatus}
                    </span>
                  </td>
                  <td className="py-4 pr-2">
                    <ScoreVisualization score={track.matchScore || track.confidenceScore} />
                  </td>
                  <td className="py-4 pr-2">
                    {track.targetTrackUrl ? (
                      <div className="flex items-start gap-3">
                        {thumbnail ? (
                          <button
                            type="button"
                            onClick={() => setPreviewTrack(track)}
                            className="group relative flex-shrink-0 overflow-hidden rounded-2xl border border-white/10"
                            title="Preview video"
                          >
                            <img
                              src={thumbnail}
                              alt={track.targetTrackTitle || 'YouTube thumbnail'}
                              className="h-14 w-24 object-cover transition duration-300 group-hover:scale-105 group-hover:brightness-90"
                            />
                            <span className="absolute inset-0 flex items-center justify-center bg-black/45 text-xs font-bold text-white opacity-0 transition group-hover:opacity-100">
                              ▶
                            </span>
                          </button>
                        ) : null}
                        <div className="space-y-1">
                          <p className="max-w-[220px] text-xs font-semibold text-white line-clamp-2">
                            {track.youtubeTitle || track.targetTrackTitle || 'Matched track'}
                          </p>
                          <a
                            href={track.targetTrackUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-block text-xs font-bold text-cyan-300 underline-offset-4 transition hover:text-cyan-200 hover:underline"
                          >
                            Open destination →
                          </a>
                        </div>
                      </div>
                    ) : (
                      <span className="text-xs text-slate-500">No match found</span>
                    )}
                  </td>
                  <td className="py-4 pr-2 text-xs max-w-xs text-slate-300">
                    {issueLabel ? (
                      <span className="mb-1 inline-flex items-center rounded-full border border-violet-300/20 bg-violet-400/10 px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-violet-200">
                        {issueLabel}
                      </span>
                    ) : null}
                    {reasonMeta.type === 'fallback' ? (
                      <span className="inline-flex items-center rounded-full border border-sky-400/20 bg-sky-400/10 px-2 py-1 font-semibold text-sky-200">
                        Fallback used: {reasonMeta.label}
                      </span>
                    ) : reasonMeta.type === 'warning' ? (
                      <span className="inline-flex items-center rounded-full border border-amber-400/20 bg-amber-400/10 px-2 py-1 font-semibold text-amber-200">
                        Review Only: {reasonMeta.label}
                      </span>
                    ) : reasonMeta.type === 'error' ? (
                      <span className="font-medium text-rose-300">{reasonMeta.label}</span>
                    ) : reasonMeta.type === 'info' ? (
                      <span className="font-medium text-slate-400">{reasonMeta.label}</span>
                    ) : (
                      <span className="text-emerald-300">✓ Matched</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {previewVideoId ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 p-4 backdrop-blur-xl" onClick={() => setPreviewTrack(null)}>
          <div
            className="w-full max-w-3xl rounded-[28px] border border-white/10 bg-slate-950 p-3 shadow-[0_30px_120px_rgba(2,6,23,0.8)]"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="mb-2 flex items-center justify-between">
              <p className="truncate pr-4 text-sm font-semibold text-white">
                {previewTrack?.youtubeTitle || previewTrack?.targetTrackTitle || 'YouTube Preview'}
              </p>
              <button
                type="button"
                onClick={() => setPreviewTrack(null)}
                className="glow-button-secondary px-3 py-1 text-xs"
              >
                Close
              </button>
            </div>
            <div className="aspect-video w-full overflow-hidden rounded-lg">
              <iframe
                className="h-full w-full"
                src={`https://www.youtube.com/embed/${previewVideoId}`}
                title="YouTube video preview"
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                allowFullScreen
              />
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}

export default TrackTable;
