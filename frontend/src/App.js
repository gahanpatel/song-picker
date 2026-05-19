import React, { useState, useEffect } from 'react';
import './App.css';

function App() {
    const [selectedFile, setSelectedFile] = useState(null);
    const [previewUrl, setPreviewUrl] = useState(null);
    const [analysis, setAnalysis] = useState('');
    const [spotifyTracks, setSpotifyTracks] = useState([]);
    const [playlistUrl, setPlaylistUrl] = useState('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [dragging, setDragging] = useState(false);
    useEffect(() => {
        if (!selectedFile) { setPreviewUrl(null); return; }
        const url = URL.createObjectURL(selectedFile);
        setPreviewUrl(url);
        return () => URL.revokeObjectURL(url);
    }, [selectedFile]);

    const handleFile = (file) => {
        if (!file) return;
        if (!file.type.startsWith('image/')) {
            setError('Please select an image file.');
            return;
        }
        setSelectedFile(file);
        setAnalysis('');
        setSpotifyTracks([]);
        setError('');
    };

    const handleDrop = (e) => {
        e.preventDefault();
        setDragging(false);
        handleFile(e.dataTransfer.files[0]);
    };

    const handleUpload = async () => {
        if (!selectedFile) return;
        setLoading(true);
        setError('');

        const formData = new FormData();
        formData.append('image', selectedFile);
        if (playlistUrl.trim()) formData.append('playlistUrl', playlistUrl.trim());

        try {
            const response = await fetch('http://127.0.0.1:8080/api/image/analyze', {
                method: 'POST',
                body: formData,
            });
            if (!response.ok) throw new Error(`Server error ${response.status}`);
            const result = await response.json();
            setAnalysis(result.analysis);
            setSpotifyTracks(result.spotify_tracks || []);
        } catch (err) {
            setError('Failed to analyze image. Make sure the backend is running.');
        } finally {
            setLoading(false);
        }
    };

    const hasResults = analysis || spotifyTracks.length > 0;

    return (
        <div className="app">
            <header className="app-header">
                <h1 className="app-title">Song Picker</h1>
                <p className="app-subtitle">Drop an image, get music that matches its mood</p>
            </header>

            <main className="app-main">
                <div className="upload-card">
                    <div
                        className={`drop-zone${dragging ? ' dragging' : ''}${previewUrl ? ' has-preview' : ''}`}
                        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
                        onDragLeave={() => setDragging(false)}
                        onDrop={handleDrop}
                    >
                        {previewUrl ? (
                            <img src={previewUrl} alt="Preview" className="preview-img" />
                        ) : (
                            <div className="drop-prompt">
                                <svg className="drop-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                                    <rect x="3" y="3" width="18" height="18" rx="2" />
                                    <circle cx="8.5" cy="8.5" r="1.5" />
                                    <path d="M21 15l-5-5L5 21" />
                                </svg>
                                <p>Drag &amp; drop an image here</p>
                                <span className="drop-hint">or click to browse</span>
                            </div>
                        )}
                        <input
                            type="file"
                            accept="image/*"
                            onClick={(e) => { e.target.value = null; }}
                            onChange={(e) => handleFile(e.target.files[0])}
                            className="file-overlay"
                        />
                    </div>

                    {previewUrl && selectedFile && (
                        <p className="file-name">{selectedFile.name}</p>
                    )}

                    <input
                        type="url"
                        placeholder="Spotify playlist URL (optional)"
                        value={playlistUrl}
                        onChange={(e) => setPlaylistUrl(e.target.value)}
                        className="playlist-input"
                    />

                    {error && <p className="error-msg">{error}</p>}

                    <div className="btn-row">
                        <button
                            onClick={handleUpload}
                            disabled={!selectedFile || loading}
                            className="analyze-btn"
                        >
                            {loading && <span className="spinner" />}
                            {loading ? 'Analyzing…' : 'Get Recommendations'}
                        </button>
                        {hasResults && (
                            <button
                                className="reset-btn"
                                onClick={() => {
                                    setSelectedFile(null);
                                    setAnalysis('');
                                    setSpotifyTracks([]);
                                    setPlaylistUrl('');
                                    setError('');
                                }}
                            >
                                Try another image
                            </button>
                        )}
                    </div>
                </div>

                {hasResults && (
                    <div className="results-grid">
                        {analysis && (
                            <section className="result-card">
                                <h2 className="card-label">AI Analysis</h2>
                                <div className="analysis-body">
                                    {analysis.split('\n\n').map((para, i) => {
                                        const clean = para.replace(/\*\*/g, '');
                                        const colonIdx = clean.indexOf(':');
                                        if (colonIdx > 0 && colonIdx < 40) {
                                            return (
                                                <div key={i} className="analysis-section">
                                                    <span className="analysis-label">{clean.slice(0, colonIdx)}</span>
                                                    <span className="analysis-text">{clean.slice(colonIdx + 1).trim()}</span>
                                                </div>
                                            );
                                        }
                                        return <p key={i} className="analysis-para">{clean}</p>;
                                    })}
                                </div>
                            </section>
                        )}

                        {spotifyTracks.length > 0 && (
                            <section className="result-card">
                                <h2 className="card-label">Recommended Tracks</h2>
                                <ol className="track-list">
                                    {spotifyTracks.map((track, i) => (
                                        <li key={i} className="track-item">
                                            <span className="track-num">{i + 1}</span>
                                            <div className="track-meta">
                                                <span className="track-name">{track.name}</span>
                                                <span className="track-artist">{track.artist}</span>
                                                <div className="track-controls">
                                                    {track.preview_url && (
                                                        <audio controls>
                                                            <source src={track.preview_url} type="audio/mpeg" />
                                                        </audio>
                                                    )}
                                                    {track.spotify_url && (
                                                        <a href={track.spotify_url} target="_blank" rel="noopener noreferrer" className="spotify-btn">
                                                            Open in Spotify
                                                        </a>
                                                    )}
                                                </div>
                                            </div>
                                        </li>
                                    ))}
                                </ol>
                            </section>
                        )}
                    </div>
                )}
            </main>
        </div>
    );
}

export default App;
