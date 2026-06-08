# 🎵 Song Picker

Upload an image and get back music that matches its mood. Song Picker analyzes a
photo with **OpenAI GPT-4o Vision**, infers the emotional tone, and recommends
five real songs — resolved to playable links via the **Spotify** API.

You can either let GPT pick songs from scratch, or point it at one of your own
Spotify playlists and have it choose the five tracks that best fit the image.

---

## How it works

1. You upload an image (and, optionally, a Spotify playlist URL) in the React UI.
2. The backend sends the image to **GPT-4o Vision**, which returns:
   - an **explanation** of the image's mood and atmosphere,
   - a **mood profile** (`energy`, `valence`, `danceability`, `acousticness`, `tempo`),
   - **5 suggested songs** (title + artist) that match the image.
3. Recommendations are resolved into playable Spotify tracks two ways:
   - **No playlist** → each of GPT's 5 suggestions is looked up on Spotify search to
     get a real URL/preview. If GPT returns no songs, the mood profile is mapped to
     keyword searches instead.
   - **With a playlist** → all playlist tracks are fetched, then handed to
     **GPT-4o-mini** along with the mood text + profile, which returns the indices of
     the 5 best matches.
4. The UI renders the analysis text and a card per track ("Open in Spotify", plus an
   audio player when a preview is available).

> **Note:** Spotify's `/audio-features` endpoint is **not** used — it was deprecated
> for new apps. Song selection is fully GPT-driven; Spotify is only used to resolve
> and play the picks.

---

## Tech stack

| Layer    | Tech                                                        |
|----------|-------------------------------------------------------------|
| Backend  | Spring Boot 3 (Web + WebFlux/WebClient), Java 17, Maven     |
| Frontend | React 19, Create React App (`react-scripts`)                |
| AI       | OpenAI GPT-4o (vision) + GPT-4o-mini (playlist ranking)     |
| Music    | Spotify Web API (Client Credentials flow)                   |

---

## Getting started

### Prerequisites

- **Java 17+**
- **Node.js 18+** and npm
- API credentials:
  - an **OpenAI** API key
  - a **Spotify** app `client id` + `client secret` (from the
    [Spotify Developer Dashboard](https://developer.spotify.com/dashboard))

### Configuration

Add your credentials to [`src/main/resources/application.properties`](src/main/resources/application.properties):

```properties
openai.api.key=YOUR_OPENAI_KEY
spotify.client.id=YOUR_SPOTIFY_CLIENT_ID
spotify.client.secret=YOUR_SPOTIFY_CLIENT_SECRET

# uploads are capped at 10 MB by default
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

> ⚠️ This file holds secrets — don't commit real keys to a public repo.

### Run the backend (from repo root)

```bash
./mvnw spring-boot:run        # starts on http://127.0.0.1:8080
```

### Run the frontend (from `frontend/`)

```bash
npm install
npm start                     # dev server on http://localhost:3000
```

Open **http://localhost:3000**, upload an image, and pick your songs.

---

## API

Base path: `/api/image` (CORS allows `http://localhost:3000`).

### `POST /api/image/analyze`

Multipart request that analyzes an image and returns matching songs.

**Form fields**

| Field         | Type   | Required | Description                                  |
|---------------|--------|----------|----------------------------------------------|
| `image`       | file   | yes      | The image to analyze                         |
| `playlistUrl` | string | no       | A Spotify playlist URL to pick tracks from   |

**Response**

```json
{
  "analysis": "A warm, golden-hour beach scene with a calm, nostalgic mood...",
  "spotify_tracks": [
    {
      "name": "Song Title",
      "artist": "Artist Name",
      "url": "https://open.spotify.com/track/...",
      "preview_url": "https://p.scdn.co/mp3-preview/..."
    }
  ]
}
```

> `preview_url` is frequently `null` (always `null` for playlist tracks), so the inline
> audio player may not render — but **"Open in Spotify" always works**.

### `GET /api/image/test`

Lightweight health-check endpoint.

---

## Common commands

```bash
# Backend (repo root)
./mvnw spring-boot:run                       # run
./mvnw clean package                         # build JAR
./mvnw test                                  # run all tests
./mvnw test -Dtest=ClassName#methodName      # run a single test

# Frontend (frontend/)
npm start                                    # dev server
npm run build                                # production build
npm test                                     # run tests
```

---

## Graceful degradation

Every external call has a fallback, so the app keeps working even when an API is down:

- **OpenAI vision fails** → mock analysis derived from the image filename.
- **No `suggested_songs` from GPT** → mood-keyword Spotify search.
- **Playlist GPT ranking fails** → first 5 playlist tracks.
- **Bad playlist URL / Spotify failure** → mood-keyword search, then a hardcoded mock track list.

---

## Project layout

```
song-picker/
├── src/main/java/com/gahan/song/picker/
│   ├── controller/ImageController.java   # REST endpoint + CORS
│   ├── service/OpenAIService.java        # GPT-4o vision call (+ mock fallback)
│   └── service/SpotifyService.java       # OAuth, search, GPT playlist ranking
├── src/main/resources/application.properties
├── frontend/
│   └── src/App.js                        # single-component React UI
├── pom.xml
└── CLAUDE.md                             # contributor / architecture notes
```
