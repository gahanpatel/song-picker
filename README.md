AI Music Matcher

A full-stack web application that analyzes uploaded images using AI to determine their mood and atmosphere, then recommends matching songs from your Spotify playlists based on musical characteristics.

## Features

- **AI-Powered Image Analysis**: Uses OpenAI Vision API to analyze image mood, atmosphere, and emotional tone
- **Personalized Music Recommendations**: Recommends songs specifically from user-provided Spotify playlists
- **Intelligent Audio Matching**: Leverages AcousticBrainz/MusicBrainz APIs to match songs based on audio features (energy, valence, danceability)
- **Hybrid Fallback System**: Gracefully degrades from audio feature analysis to keyword-based matching when data unavailable
- **Real-Time Processing**: Instant image upload with live preview and analysis results
- **Spotify Integration**: Direct links to recommended songs with embedded audio previews

## Tech Stack

### Backend
- **Java** with **Spring Boot** (RESTful API)
- **Spring Web** for HTTP handling
- **RestTemplate** for external API integration
- **Maven** for dependency management

### Frontend
- **React** with modern Hooks (useState, useEffect)
- **JavaScript ES6+**
- **CSS3** for styling

### External APIs
- **OpenAI Vision API** (GPT-4o) - Image mood analysis
- **Spotify Web API** - Playlist data and track information
- **AcousticBrainz/MusicBrainz** - Audio feature extraction

## Architecture

The application uses a hybrid matching algorithm:

1. **Image Upload** → OpenAI analyzes mood/atmosphere
2. **Playlist Parsing** → Extracts tracks from user's Spotify playlist
3. **Audio Feature Matching** → Attempts to get precise audio data from AcousticBrainz
4. **Keyword Fallback** → Uses intelligent keyword matching if audio features unavailable
5. **Results** → Returns top 5 best-matching songs with Spotify links
