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

function TrackTable({ tracks }) {
  const [previewTrack, setPreviewTrack] = useState(null);

  const previewVideoId = useMemo(() => {
    if (!previewTrack) {
      return null;
    }
    return getVideoId(previewTrack);
  }, [previewTrack]);

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
            <tr className="border-b border-clay text-left text-xs uppercase tracking-wide text-stone-500">
              <th className="py-2 pr-2">Source</th>
              <th className="py-2 pr-2">Artist</th>
              <th className="py-2 pr-2">Status</th>
              <th className="py-2 pr-2">Score</th>
              <th className="py-2 pr-2">YouTube</th>
              <th className="py-2 pr-2">Reason</th>
            </tr>
          </thead>
          <tbody>
            {tracks.map((track) => {
              const thumbnail = getThumbnail(track);
              return (
                <tr key={track.id} className="border-b border-clay/70 align-top">
                  <td className="py-3 pr-2 font-medium">{track.sourceTrackName}</td>
                  <td className="py-3 pr-2">{track.sourceArtistName}</td>
                  <td className="py-3 pr-2">
                    <span className="rounded-full border border-stone-300 bg-stone-100 px-2 py-1 text-xs font-semibold">
                      {track.matchStatus}
                    </span>
                  </td>
                  <td className="py-3 pr-2">
                    {track.confidenceScore ? `${(track.confidenceScore * 100).toFixed(0)}%` : '-'}
                  </td>
                  <td className="py-3 pr-2">
                    {track.targetTrackUrl ? (
                      <div className="flex items-start gap-2">
                        {thumbnail ? (
                          <button
                            type="button"
                            onClick={() => setPreviewTrack(track)}
                            className="group relative overflow-hidden rounded-md border border-clay"
                            title="Preview video"
                          >
                            <img
                              src={thumbnail}
                              alt={track.targetTrackTitle || 'YouTube thumbnail'}
                              className="h-14 w-24 object-cover transition duration-200 group-hover:brightness-90"
                            />
                            <span className="absolute inset-0 flex items-center justify-center bg-black/35 text-xs font-bold text-white opacity-0 transition group-hover:opacity-100">
                              Preview
                            </span>
                          </button>
                        ) : null}
                        <div className="space-y-1">
                          <p className="max-w-[220px] text-xs font-semibold text-stone-700">
                            {track.youtubeTitle || track.targetTrackTitle || 'Matched track'}
                          </p>
                          <a
                            href={track.targetTrackUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="text-xs font-bold text-mint underline-offset-2 hover:underline"
                          >
                            Open on YouTube Music
                          </a>
                        </div>
                      </div>
                    ) : (
                      '-'
                    )}
                  </td>
                  <td className="py-3 pr-2 text-stone-600">{track.failureReason || '-'}</td>
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
