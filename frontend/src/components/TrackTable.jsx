function getThumbnail(track) {
  if (track.targetThumbnailUrl) {
    return track.targetThumbnailUrl;
  }
  if (track.targetTrackId) {
    return `https://img.youtube.com/vi/${track.targetTrackId}/mqdefault.jpg`;
  }
  return null;
}

function TrackTable({ tracks }) {
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
      <h2 className="text-xl font-bold">Track Mapping</h2>

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
                          <img
                            src={thumbnail}
                            alt={track.targetTrackTitle || 'YouTube thumbnail'}
                            className="h-14 w-24 rounded-md border border-clay object-cover"
                          />
                        ) : null}
                        <div className="space-y-1">
                          <p className="max-w-[220px] text-xs font-semibold text-stone-700">
                            {track.targetTrackTitle || 'Matched track'}
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
    </section>
  );
}

export default TrackTable;
