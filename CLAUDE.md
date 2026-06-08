# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Song Picker is a full-stack application that analyzes images via OpenAI Vision (GPT-4o) and recommends music that matches the image's mood/emotional tone. Song selection is GPT-driven: GPT-4o suggests real songs for the image, and Spotify is used to resolve those suggestions into playable links (URL + preview). Spotify's `/audio-features` endpoint is **not** used — it was removed when Spotify deprecated it for new apps.

## Commands

### Backend (Spring Boot — run from repo root)
```bash
./mvnw spring-boot:run      # Start backend on http://127.0.0.1:8080
./mvnw clean package        # Build JAR
./mvnw test                 # Run all tests
./mvnw test -Dtest=ClassName#methodName  # Run a single test
```

### Frontend (React — run from `frontend/`)
```bash
npm install     # Install dependencies
npm start       # Dev server on http://localhost:3000
npm run build   # Production build
npm test        # Run tests
```

## Architecture

### Request Flow
1. User uploads an image (and optionally a Spotify playlist URL) via the React frontend.
2. `POST /api/image/analyze` in `ImageController` receives the multipart request.
3. `OpenAIService` encodes the image as base64 and calls GPT-4o Vision. GPT returns JSON with: an `explanation` (mood/atmosphere text), a mood profile (`energy`, `valence`, `danceability`, `acousticness`, `tempo`), and `suggested_songs` — exactly 5 real songs (title + artist) that match the image.
4. `SpotifyService` turns those into results two ways:
   - **No playlist**: looks up each of GPT's 5 suggested songs on Spotify search (top hit each) to get real URLs/previews. If GPT returned no songs, it falls back to mapping the mood profile to keyword search terms (`extractSearchTerms`) and searching Spotify for 5 tracks.
   - **With playlist**: fetches all tracks from the playlist URL (paginated, cached), then sends the full track list + mood text to **GPT-4o-mini**, which returns the indices of the 5 best matches (`selectFromPlaylistWithGPT`). Falls back to the first 5 tracks.
5. The mood profile is now only used as fallback search keywords; there is no Spotify audio-features scoring.
6. Response JSON `{ analysis: string, spotify_tracks: Track[] }` is rendered in the frontend. Note: `preview_url` is often `null` (always `null` for playlist tracks), so the audio player frequently won't render, but "Open in Spotify" always works.

### Key Backend Classes
- [ImageController.java](src/main/java/com/gahan/song/picker/controller/ImageController.java) — single REST endpoint, CORS configured for localhost:3000
- [OpenAIService.java](src/main/java/com/gahan/song/picker/service/OpenAIService.java) — GPT-4o vision call; falls back to filename-heuristic mock on failure
- [SpotifyService.java](src/main/java/com/gahan/song/picker/service/SpotifyService.java) — Client Credentials OAuth; resolves GPT's suggested songs via Spotify search, GPT-ranks playlist tracks; mood-keyword search fallback

### Frontend
- [frontend/src/App.js](frontend/src/App.js) — single-component React app; handles upload, displays analysis text and track cards with audio preview

## Configuration

API credentials are stored in [src/main/resources/application.properties](src/main/resources/application.properties):
- `openai.api.key`
- `spotify.client.id` / `spotify.client.secret`

Max upload size is 10 MB (configurable in the same file).

## Fallback Behavior

All external API calls have explicit fallbacks so the app degrades gracefully:
- OpenAI vision failure → mock analysis derived from the image filename (`generateMockAnalysis`)
- No `suggested_songs` from GPT → mood-keyword Spotify search (`extractSearchTerms`)
- Playlist GPT ranking failure → first 5 playlist tracks
- Invalid/unparseable playlist URL, or Spotify failure → falls back to `findRecommendations`, then to a hardcoded mock track list (`getMockSpotifyTracks`)
