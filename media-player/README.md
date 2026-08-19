# Playwave 🎵

A media player web app with a Spotify-style design language — black app frame,
rounded dark panels, green accent, card grids, bottom player bar — plus a
**cloud source**: stream your own music from a home server such as a
**ZimaBoard 2**.

No build step, no dependencies. Plain HTML/CSS/JS and one zero-dependency
Node server.

```
media-player/
├── index.html            the app
├── css/styles.css
├── js/app.js
├── server/zima-server.js music server for the ZimaBoard (Node 18+, no deps)
└── android/              WebView wrapper + build script producing a test APK
    └── playwave-debug.apk   prebuilt, ready to sideload
```

## Quick start (everything on the ZimaBoard)

The easiest setup: the ZimaBoard hosts both the app and your music, so any
device on your network just opens one URL.

1. Copy this `media-player/` folder onto the ZimaBoard (e.g. with `scp` or a
   USB stick). Make sure Node.js 18+ is installed (`sudo apt install nodejs`
   on the stock Debian/CasaOS image, or install the official build).
2. Start the server, pointing it at your music folder:

   ```bash
   node server/zima-server.js /DATA/Media/Music 8090
   ```

3. From any device on your network open `http://<zimaboard-ip>:8090`
   (find the IP in CasaOS or with `ip addr`). The app auto-detects that its
   own host serves music and connects immediately.

### Keep it running after reboot (systemd)

```ini
# /etc/systemd/system/playwave.service
[Unit]
Description=Playwave music server
After=network.target

[Service]
ExecStart=/usr/bin/node /opt/playwave/server/zima-server.js /DATA/Media/Music 8090
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now playwave
```

## Alternative: app anywhere, music on the server

You can also open `index.html` from any host (or just double-click it) and
connect to the ZimaBoard remotely:

1. Run `zima-server.js` on the ZimaBoard as above.
2. In the app, click **Connect server** (sidebar or the cloud badge, top
   right) and enter `http://<zimaboard-ip>:8090`.

The server sends permissive CORS headers, so cross-origin streaming works.
One caveat: if you host the app over **https**, browsers block requests to a
plain-http LAN server (mixed content) — serve the app over http, open it from
disk, or put the ZimaBoard behind https (e.g. Tailscale/Caddy) in that case.

The last-used server address is remembered and reconnected on the next visit.

## How songs are read

`zima-server.js` recursively scans the music folder for
`.mp3 .m4a .aac .flac .ogg .opus .wav .webm` files:

- Files named `Artist - Title.mp3` get proper artist/title fields.
- The containing folder name becomes the album.
- Streaming supports HTTP Range requests, so seeking is instant.
- The folder is rescanned on every `/api/songs` call — drop new files in and
  refresh, no restart needed.

### No Node on the server?

Any static file host works too (nginx, CasaOS file server…): put your audio
files next to a `songs.json` manifest and connect to that URL — the app falls
back to `GET <server>/songs.json`:

```json
[
  { "id": "1", "title": "Sunrise", "artist": "Kalte", "album": "Dawn", "url": "/music/Kalte - Sunrise.mp3" }
]
```

## Player features

- Home, Search, and Library (all-songs playlist) views
- Play/pause, previous/next, shuffle, repeat off/all/one, seek, volume/mute
- Liked songs (persisted locally), playing-row equalizer animation
- Local files source: add songs straight from the device you're browsing on
- Media-keys / lock-screen integration via the Media Session API
- <kbd>Space</kbd> toggles playback

## Android test APK

`android/playwave-debug.apk` is a prebuilt, sideloadable test build: the web
app wrapped in a full-screen WebView (package `com.playwave.app`, minSdk 24 =
Android 7.0+, ~41 KB).

**Install:** copy the APK to your phone (download from this repo, or via USB /
your cloud drive), open it, and allow "install unknown apps" when prompted.
It's signed with a throwaway debug key, so Play Protect will note it's an
unknown developer — that's expected for a sideloaded test build.

**Use:** tap the cloud badge (top right) and enter your server address, e.g.
`http://192.168.1.50:8090`. The address is remembered. Cleartext (plain http)
traffic to your LAN server is explicitly allowed by the app.

## No ZimaBoard? Three ways to use it anyway

1. **Demo songs** — the app ships with four bundled demo tracks. On the empty
   home screen tap **Play demo songs**; nothing else is needed.
2. **Local files** — tap **＋** / "Add files" and pick audio straight from
   your phone (or from the browser's device when using the web version).
3. **Any computer is the "server"** — the ZimaBoard is just the machine that
   runs `server/zima-server.js`; your PC or laptop works identically:

   ```bash
   # Windows (PowerShell), macOS or Linux — Node 18+ installed:
   node server/zima-server.js "C:\Users\you\Music" 8090   # or ~/Music
   ```

   Then connect the phone app to `http://<your-pc-ip>:8090` (find the IP with
   `ipconfig` / `ip addr`; phone and PC must be on the same Wi-Fi). When you
   get a ZimaBoard later, move the same command there — nothing else changes.

**Rebuild it yourself** — no Android SDK or Gradle needed, just a JDK (11+)
and curl:

```bash
cd android
./build-apk.sh        # fetches apktool/dx/apksig once, outputs playwave-debug.apk
```

The script compiles `src/MainActivity.java` against a stub android.jar from
Maven Central, dexes it with `dx`, packages manifest + icons + web app with
apktool, and signs with the v2 scheme via `apksig`. A fresh debug keystore is
generated on first build — if you rebuild on another machine, the signature
changes and Android will ask you to uninstall the old copy first.

## Local demo (no ZimaBoard handy)

```bash
cd media-player
mkdir music        # drop a few audio files in here
node server/zima-server.js ./music 8090
# open http://localhost:8090
```
