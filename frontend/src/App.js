import React, {useState, useEffect} from 'react';
import './App.css';

function App() {
    const [selectedFile, setSelectedFile] = useState(null);
    const [analysis, setAnalysis] = useState('');
    const [spotifyTracks, setSpotifyTracks] = useState([]);
    const [playlistUrl, setPlaylistUrl] = useState('');
    const [loading, setLoading] = useState(false);

    const handleFileSelect = (event) => {
        setSelectedFile(event.target.files[0]);
        setAnalysis('');
        setSpotifyTracks([]);
    };

    const handleUpload = async () => {
        if (!selectedFile) {
            alert('Please select an image first');
            return;
        }

        setLoading(true);
        const formData = new FormData();
        formData.append('image', selectedFile);

        if (playlistUrl && playlistUrl.trim() !== '') {
            formData.append('playlistUrl', playlistUrl.trim());
        }

        try {
            const response = await fetch('http://127.0.0.1:8080/api/image/analyze', {
                method: 'POST',
                body: formData
            });

            const result = await response.json();
            setAnalysis(result.analysis);
            setSpotifyTracks(result.spotify_tracks || []);
        } catch (error) {
            console.error('Error:', error);
            setAnalysis('Error analyzing image. Please try again.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="App">
            <header className="App-header">
                <h1>Song Picker</h1>
                <p>Upload an image and get music recommendations based on its mood!</p>

                <div className="upload-section">
                    <input
                        type="file"
                        accept="image/*"
                        onChange={handleFileSelect}
                        className="file-input"
                    />

                    <input
                        type="url"
                        placeholder="Paste Spotify playlist link (optional)"
                        value={playlistUrl}
                        onChange={(e) => setPlaylistUrl(e.target.value)}
                        className="playlist-input"
                    />

                    {selectedFile && (
                        <div className="file-preview">
                            <p>Selected: {selectedFile.name}</p>
                            <img
                                src={URL.createObjectURL(selectedFile)}
                                alt="Preview"
                                className="image-preview"
                            />
                        </div>
                    )}

                    <button
                        onClick={handleUpload}
                        disabled={!selectedFile || loading}
                        className="upload-button"
                    >
                        {loading ? 'Analyzing...' : 'Get Music Recommendations'}
                    </button>
                </div>

                {analysis && (
                    <div className="analysis-result">
                        <h3>AI Analysis:</h3>
                        <p>{analysis}</p>
                    </div>
                )}

                {spotifyTracks.length > 0 && (
                    <div className="spotify-results">
                        <h3>Recommended Tracks:</h3>
                        {spotifyTracks.map((track, index) => (
                            <div key={index} className="spotify-track">
                                <div className="track-info">
                                    <strong>{track.name}</strong> by {track.artist}
                                </div>
                                <div className="track-links">
                                    {track.preview_url && (
                                        <audio controls>
                                            <source src={track.preview_url} type="audio/mpeg"/>
                                        </audio>
                                    )}
                                    {track.spotify_url && (
                                        <a href={track.spotify_url} target="_blank"
                                           rel="noopener noreferrer">
                                            Open in Spotify
                                        </a>
                                    )}
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </header>
        </div>
    );
}

export default App;