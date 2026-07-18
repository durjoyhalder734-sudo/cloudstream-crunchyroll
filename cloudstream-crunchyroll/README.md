# Crunchyroll CloudStream Extension

A [CloudStream 3](https://github.com/recloudstream/cloudstream) extension that provides Crunchyroll anime content — browse, search, episodes, streams, and subtitles.

## Features

- Browse by category: Recently Added, Simulcast, Popular, Updated, Featured
- Full text search
- Series detail with all seasons and episodes
- HLS adaptive streaming (soft-subs + optional hardsub variants)
- All available subtitle tracks
- Anime movie detection
- Account login for premium content (token auto-refreshes)

## Repository structure

```
cloudstream-crunchyroll/
├── build.gradle.kts                   ← root Gradle build
├── settings.gradle.kts                ← includes :CrunchyrollProvider
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── CrunchyrollProvider/
    ├── build.gradle.kts               ← extension metadata
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/crunchyroll/
            ├── CrunchyrollPlugin.kt   ← plugin entry point
            └── CrunchyrollProvider.kt ← browse / search / load / streams
```

## Requirements

- JDK 11 or later
- Android SDK (API 26+) — set `ANDROID_HOME` or `ANDROID_SDK_ROOT`
- Internet access (downloads Gradle and dependencies on first build)

## Build

```bash
git clone <this-repo>
cd cloudstream-crunchyroll

# Unix / macOS
chmod +x gradlew
./gradlew :CrunchyrollProvider:make

# Windows
gradlew.bat :CrunchyrollProvider:make
```

The compiled extension is written to:

```
CrunchyrollProvider/build/CrunchyrollProvider.cs3
```

## Install in CloudStream

### Option A — sideload the file

1. Copy `CrunchyrollProvider.cs3` to your Android device.
2. In CloudStream: **Settings → Extensions → Add from file** and select the `.cs3`.

### Option B — self-host a repository

1. Run `./gradlew makePluginsJson` to generate a `plugins.json` manifest alongside the `.cs3` file in `builds/`.
2. Host the `builds/` folder on any static HTTP server (GitHub Pages, Cloudflare Pages, etc.).
3. In CloudStream: **Settings → Extensions → Add repository** → paste the URL.

## Login (premium content)

Crunchyroll requires a subscription for simulcast and many series. To authenticate:

1. In CloudStream, long-press the Crunchyroll extension → **Accounts**.
2. Enter your Crunchyroll email and password.
3. The extension fetches an access token and refresh token automatically; re-login is only needed if the refresh token expires (~30 days of inactivity).

Anonymous access is limited to free-tier content.

## How it works

The extension uses Crunchyroll's **Beta API** (`beta-api.crunchyroll.com`) — the same endpoints the official web app uses.

| Step | Method | Endpoint |
|---|---|---|
| Anonymous token | POST | `/auth/v1/token` (`grant_type=client_id`) |
| Login | POST | `/auth/v1/token` (`grant_type=password`) |
| Token refresh | POST | `/auth/v1/token` (`grant_type=refresh_token`) |
| Browse | GET | `/content/v2/discover/browse` |
| Search | GET | `/content/v2/discover/search` |
| Series detail | GET | `/content/v2/cms/series/{id}` |
| Seasons | GET | `/content/v2/cms/series/{id}/seasons` |
| Episodes | GET | `/content/v2/cms/seasons/{id}/episodes` |
| CMS signing keys | GET | `/index/v2` |
| Streams + subs | GET | `/cms/v2/{bucket}/videos/{id}/streams` |

Streams are served as HLS manifests (`.m3u8`). Crunchyroll signs each stream URL with a short-lived CloudFront policy token fetched from `/index/v2`.

## Notes

- Geo-restriction: Crunchyroll restricts some titles by region. Streams will return an error for unavailable titles — this is enforced server-side.
- Hardsub variants are exposed as separate links so you can select the soft-sub + external subtitle workflow.
- If a build fails with `SDK not found`, ensure `ANDROID_HOME` points to your Android SDK directory.
