function TrackTable({ tracks }) {
  if (!tracks?.length) {
    return (
      <section className="panel">
        <h2>Tracks</h2>
        <p className="muted">No tracks processed yet.</p>
      </section>
    );
  }

  return (
    <section className="panel">
      <h2>Track Mapping</h2>
      <div className="tableWrap">
        <table>
          <thead>
            <tr>
              <th>Source</th>
              <th>Artist</th>
              <th>Status</th>
              <th>Confidence</th>
              <th>Target</th>
              <th>Reason</th>
            </tr>
          </thead>
          <tbody>
            {tracks.map((track) => (
              <tr key={track.id}>
                <td>{track.sourceTrackName}</td>
                <td>{track.sourceArtistName}</td>
                <td>{track.matchStatus}</td>
                <td>{track.confidenceScore ? `${(track.confidenceScore * 100).toFixed(0)}%` : '-'}</td>
                <td>
                  {track.targetTrackUrl ? (
                    <a href={track.targetTrackUrl} target="_blank" rel="noreferrer">
                      Open
                    </a>
                  ) : (
                    '-'
                  )}
                </td>
                <td>{track.failureReason || '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export default TrackTable;
