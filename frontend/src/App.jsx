import { useEffect } from 'react';
import MigrationPage from './pages/MigrationPage';

function App() {
  useEffect(() => {
    document.title = 'SoundBridge - Playlist Migration Dashboard';
  }, []);

  return <MigrationPage />;
}

export default App;
