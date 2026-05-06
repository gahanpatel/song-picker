# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Song Picker is a full-stack application that analyzes images via OpenAI Vision (GPT-4o) and recommends music by matching the image's mood/emotional tone against Spotify tracks. Spotify's `/audio-features` endpoint is used for accurate mood matching when a playlist is provided.

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
3. `OpenAIService` encodes the image as base64 and calls GPT-4o Vision, which returns a mood/atmosphere description.
4. `SpotifyService` uses that description two ways:
   - **No playlist**: extracts search terms and searches Spotify directly.
   - **With playlist**: fetches all tracks from the playlist URL, scores each track, and returns the top 5.
5. Scoring is hybrid: `SpotifyService` calls `GET /v1/audio-features/{id}` for each track to get energy, valence, and danceability; if that fails, keyword regex scoring is used as fallback.
6. Response JSON `{ analysis: string, spotify_tracks: Track[] }` is rendered in the frontend.

### Key Backend Classes
- [ImageController.java](src/main/java/com/gahan/song/picker/controller/ImageController.java) — single REST endpoint, CORS configured for localhost:3000
- [OpenAIService.java](src/main/java/com/gahan/song/picker/service/OpenAIService.java) — GPT-4o vision call; falls back to filename-heuristic mock on failure
- [SpotifyService.java](src/main/java/com/gahan/song/picker/service/SpotifyService.java) — Client Credentials OAuth, track search, hybrid mood scoring

### Frontend
- [frontend/src/App.js](frontend/src/App.js) — single-component React app; handles upload, displays analysis text and track cards with audio preview

## Configuration

API credentials are stored in [src/main/resources/application.properties](src/main/resources/application.properties):
- `openai.api.key`
- `spotify.client.id` / `spotify.client.secret`

Max upload size is 10 MB (configurable in the same file).

## Fallback Behavior

All three external API calls have explicit fallbacks so the app degrades gracefully:
- OpenAI failure → mock analysis derived from the image filename
- Spotify failure → mock track list
- Spotify audio features failure → regex keyword scoring against the AI description
