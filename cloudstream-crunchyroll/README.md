# Crunchyroll CloudStream Extension

A CloudStream 3 extension that provides access to Crunchyroll anime content.

## Features

- Browse anime by category (Recently Added, Simulcast, Popular, Updated, Featured)
- Search for anime series and movies
- Load full series with seasons and episodes
- Stream HLS video (adaptive quality)
- Subtitles (all available Crunchyroll subtitle tracks)
- Anime movie support
- Login with your Crunchyroll account for premium content

## Building

### Prerequisites

1. Clone the [CloudStream extensions template](https://github.com/recloudstream/cloudstream-extensions) or add this as a module in that repo.
2. Android SDK installed (API 26+)
3. Gradle 7.5+

### Steps

```bash
# From the root of the extensions repo, or place this directory as a module:
./gradlew :CrunchyrollProvider:make
```

The built `.cs3` plugin file will appear in `CrunchyrollProvider/build/`.

### Alternatively — standalone build

```bash
cd cloudstream-crunchyroll
# Ensure gradle wrapper is set up (see below)
./gradlew make
```

## Installation

1. Build the extension (or download a pre-built `.cs3` from the releases).
2. In CloudStream, go to **Settings → Extensions → Add extension** and point it to the `.cs3` file, or host the `builds/` folder and add its URL as an extension repo.

## Login (Premium Content)

Crunchyroll requires a subscription for simulcast and many titles. To authenticate:

1. In CloudStream, go to **Settings → Account** for this extension.
2. Enter your Crunchyroll email and password.
3. The extension handles token refresh automatically.

Anonymous access works for free-tier content only.

## How It Works

The extension uses Crunchyroll's **Beta API** (`beta-api.crunchyroll.com`) — the same API the official Crunchyroll web app uses. Key flows:

| Step | Endpoint |
|------|----------|
| Auth (anonymous) | `POST /auth/v1/token` with `grant_type=client_id` |
| Auth (login) | `POST /auth/v1/token` with `grant_type=password` |
| Browse | `GET /content/v2/discover/browse` |
| Search | `GET /content/v2/discover/search` |
| Series detail | `GET /content/v2/cms/series/{id}` |
| Seasons | `GET /content/v2/cms/series/{id}/seasons` |
| Episodes | `GET /content/v2/cms/seasons/{id}/episodes` |
| CMS token | `GET /index/v2` |
| Streams | `GET /cms/v2/{bucket}/videos/{id}/streams` |

## File Structure

```
cloudstream-crunchyroll/
├── build.gradle.kts                          # Plugin metadata and CloudStream config
├── README.md
└── src/main/
    ├── AndroidManifest.xml
    └── kotlin/com/crunchyroll/
        ├── CrunchyrollPlugin.kt              # Plugin entry point (registers provider)
        └── CrunchyrollProvider.kt            # Main provider: browse, search, load, links
```

## Notes

- Crunchyroll geo-restricts some content — streams will fail for unavailable regions.
- Hardsub streams are exposed separately so you can pick soft-sub + external subtitle tracks.
- Token refresh is handled automatically; re-login is only needed if the refresh token expires.
