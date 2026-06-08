# 🎵 Song Picker

Song Picker turns an image into a soundtrack. Upload a photo and it figures out the
mood of the scene, then hands you five real songs that match the feeling — each one
linked straight to Spotify so you can play it.

## What it does

You give it a picture. Behind the scenes, **OpenAI's GPT-4o Vision** "looks" at the
image and reads its emotional tone — is it calm and nostalgic, bright and energetic,
moody and dark? From that read it picks **five songs** that fit, and **Spotify** turns
those picks into playable links.

You can use it two ways:

- **Just an image** — GPT suggests songs from scratch that match the mood, and they're
  looked up on Spotify so you can open and play them.
- **An image + your own Spotify playlist** — instead of suggesting new songs, it scans
  the whole playlist and chooses the five tracks already in it that best fit the image.

Along with the songs, you get a short written description of the mood and atmosphere
GPT saw in the photo, so you understand *why* it chose what it did.

## The idea

Music recommendation usually starts from other music — "people who liked this also
liked that." Song Picker starts from a feeling instead. A sunset on a beach, a rainy
city street, a crowded concert — each has a vibe, and the goal is to match songs to
that vibe rather than to a genre or a listening history.

## Built with

- **Frontend:** React — a simple upload-and-results interface
- **Backend:** Spring Boot (Java)
- **AI:** OpenAI GPT-4o for reading the image and choosing songs
- **Music:** Spotify Web API for resolving picks into playable tracks
