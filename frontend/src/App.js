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

    useEffect(() => {
        const handlePaste = (e) => {
            const items = e.clipboardData?.items;
            if (!items) return;
            for (const item of items) {
                if (item.type.startsWith('image/')) {
                    handleFile(item.getAsFile());
                    break;
                }
            }
        };
        document.addEventListener('paste', handlePaste);
        return () => document.removeEventListener('paste', handlePaste);
    }, []);

    const handleFile = (file) => {
        if (!file) return;
        // Some browsers report an empty/non-image MIME type for valid images
        // (e.g. HEIC, or when the OS doesn't map the extension). Trust the
        // filename extension as a fallback so the file still gets accepted.
        const looksLikeImage =
            file.type.startsWith('image/') ||
            /\.(png|jpe?g|gif|webp|bmp|heic|heif|avif|svg|tiff?)$/i.test(file.name || '');
        if (!looksLikeImage) {
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
                {/* ── Left panel: upload + analysis ── */}
                <div className="left-panel">
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
                                    <svg className="drop-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                                        <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
                                        <polyline points="17 8 12 3 7 8" />
                                        <line x1="12" y1="3" x2="12" y2="15" />
                                    </svg>
                                    <p>Drag &amp; drop an image here</p>
                                    <span className="drop-hint">click to browse · or paste (⌘V)</span>
                                </div>
                            )}
                            <input
                                type="file"
                                className="file-overlay"
                                onClick={(e) => { e.target.value = null; }}
                                onChange={(e) => handleFile(e.target.files[0])}
                            />
                        </div>

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
                                    Reset
                                </button>
                            )}
                        </div>
                    </div>

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
                </div>

                {/* ── Right panel: tracks ── */}
                <div className="right-panel">
                    {spotifyTracks.length > 0 && (
                        <section className="result-card">
                            <h2 className="card-label">Recommended Tracks</h2>
                            <ol className="track-list">
                                {spotifyTracks.map((track, i) => (
                                    <li key={track.spotify_url || track.name || i} className="track-item">
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
                                                        <SpotifyIcon />
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
            </main>
        </div>
    );
}

function SpotifyIcon() {
    return (
        <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" style={{ flexShrink: 0 }}>
            <circle cx="12" cy="12" r="12" fill="currentColor" opacity="0" />
            <path d="M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z" />
        </svg>
    );
}

export default App;
