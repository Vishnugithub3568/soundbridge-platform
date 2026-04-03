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
      return 'bg-emerald-100 text-emerald-700 border-emerald-300';
    case 'PARTIAL':
      return 'bg-yellow-100 text-yellow-700 border-yellow-300';
    case 'NOT_FOUND':
      return 'bg-stone-100 text-stone-700 border-stone-300';
    case 'FAILED':
      return 'bg-red-100 text-red-700 border-red-300';
    default:
      return 'bg-stone-100 text-stone-700 border-stone-300';
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

function ScoreVisualization({ score }) {
  if (!score) return <span className="text-gray-500">-</span>;
  
  const percentage = Math.round(score * 100);
  const isGood = percentage >= 65;
  
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 overflow-hidden rounded-full bg-stone-200 h-2">
        <div 
          className={`h-full transition-all ${isGood ? 'bg-emerald-500' : 'bg-amber-500'}`}
          style={{ width: `${percentage}%` }}
        />
      </div>
      <span className={`text-xs font-bold ${isGood ? 'text-emerald-600' : 'text-amber-600'}`}>
        {percentage}%
      </span>
    </div>
  );
}

function TrackTable({ tracks }) {
  const [previewTrack, setPreviewTrack] = useState(null);
  const [sortConfig, setSortConfig] = useState({ key: 'id', direction: 'asc' });

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
    <th 
      className="py-2 pr-2 cursor-pointer hover:bg-stone-100 px-2 select-none"
      onClick={() => handleSort(sortKey)}
    >
      <div className="flex items-center gap-1">
        {label}
        <span className="text-xs">
          {sortConfig.key === sortKey ? (sortConfig.direction === 'asc' ? '↑' : '↓') : ''}
        </span>
      </div>
    </th>
  );

  if (!tracks?.length) {
    return (
      <section className="rounded-2xl border border-clay bg-white/80 p-5 shadow-panel">
        <h2 className="text-xl font-bold">Tracks</h2>
        <p className="mt-2 text-sm text-stone-600">No tracks processed yet.</p>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-clay bg-white/80 p-5 shadow-panel">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-xl font-bold">Track Mapping</h2>
        <p className="text-xs font-semibold uppercase tracking-wide text-stone-500">{tracks.length} tracks shown</p>
      </div>

      <div className="mt-4 overflow-x-auto">
        <table className="w-full min-w-[950px] border-collapse text-sm">
          <thead>
            <tr className="border-b border-clay text-left text-xs uppercase tracking-wide text-stone-500 bg-stone-50">
              <SortableHeader label="Source Track" sortKey="sourceTrackName" />
              <SortableHeader label="Artist" sortKey="sourceArtistName" />
              <SortableHeader label="Status" sortKey="matchStatus" />
              <SortableHeader label="Match Score" sortKey="matchScore" />
              <th className="py-2 pr-2">YouTube Result</th>
              <th className="py-2 pr-2">Details</th>
            </tr>
          </thead>
          <tbody>
            {sortedTracks.map((track) => {
              const thumbnail = getThumbnail(track);
              const statusColor = getStatusColor(track.matchStatus);
              const reasonMeta = classifyReason(track.failureReason);
              return (
                <tr key={track.id} className="border-b border-clay/70 align-top hover:bg-stone-50 transition">
                  <td className="py-3 pr-2 font-medium max-w-xs truncate">{track.sourceTrackName}</td>
                  <td className="py-3 pr-2 text-stone-600 max-w-xs truncate">{track.sourceArtistName}</td>
                  <td className="py-3 pr-2">
                    <span className={`rounded-full border ${statusColor} px-2 py-1 text-xs font-semibold inline-block`}>
                      {track.matchStatus}
                    </span>
                  </td>
                  <td className="py-3 pr-2">
                    <ScoreVisualization score={track.matchScore || track.confidenceScore} />
                  </td>
                  <td className="py-3 pr-2">
                    {track.targetTrackUrl ? (
                      <div className="flex items-start gap-2">
                        {thumbnail ? (
                          <button
                            type="button"
                            onClick={() => setPreviewTrack(track)}
                            className="group relative overflow-hidden rounded-md border border-clay flex-shrink-0"
                            title="Preview video"
                          >
                            <img
                              src={thumbnail}
                              alt={track.targetTrackTitle || 'YouTube thumbnail'}
                              className="h-14 w-24 object-cover transition duration-200 group-hover:brightness-90"
                            />
                            <span className="absolute inset-0 flex items-center justify-center bg-black/35 text-xs font-bold text-white opacity-0 transition group-hover:opacity-100">
                              ▶
                            </span>
                          </button>
                        ) : null}
                        <div className="space-y-1">
                          <p className="max-w-[220px] text-xs font-semibold text-stone-700 line-clamp-2">
                            {track.youtubeTitle || track.targetTrackTitle || 'Matched track'}
                          </p>
                          <a
                            href={track.targetTrackUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="text-xs font-bold text-mint underline-offset-2 hover:underline inline-block"
                          >
                            Open on YouTube Music →
                          </a>
                        </div>
                      </div>
                    ) : (
                      <span className="text-stone-400 text-xs">No match found</span>
                    )}
                  </td>
                  <td className="py-3 pr-2 text-xs text-stone-600 max-w-xs">
                    {reasonMeta.type === 'fallback' ? (
                      <span className="inline-flex items-center rounded-full border border-sky-300 bg-sky-50 px-2 py-1 font-semibold text-sky-700">
                        Reliable Fallback: {reasonMeta.label}
                      </span>
                    ) : reasonMeta.type === 'warning' ? (
                      <span className="inline-flex items-center rounded-full border border-amber-300 bg-amber-50 px-2 py-1 font-semibold text-amber-700">
                        Review Only: {reasonMeta.label}
                      </span>
                    ) : reasonMeta.type === 'error' ? (
                      <span className="font-medium text-red-600">{reasonMeta.label}</span>
                    ) : reasonMeta.type === 'info' ? (
                      <span className="font-medium text-stone-600">{reasonMeta.label}</span>
                    ) : (
                      <span className="text-emerald-600">✓ Matched</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {previewVideoId ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 p-4" onClick={() => setPreviewTrack(null)}>
          <div
            className="w-full max-w-3xl rounded-2xl border border-stone-700 bg-stone-950 p-3 shadow-2xl"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="mb-2 flex items-center justify-between">
              <p className="truncate pr-4 text-sm font-semibold text-stone-200">
                {previewTrack?.youtubeTitle || previewTrack?.targetTrackTitle || 'YouTube Preview'}
              </p>
              <button
                type="button"
                onClick={() => setPreviewTrack(null)}
                className="rounded-lg border border-stone-600 px-3 py-1 text-xs font-bold text-stone-200 hover:bg-stone-800"
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
