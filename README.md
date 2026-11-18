# Cloudstream Plugins Repo

A curated collection of high-performance, native [CloudStream 3](https://github.com/recloudstream/cloudstream) plugins and enhancement tools.

## 🔌 Available Plugins

### 1. ⚡ TorrServe Provider & Engine Tuner
Native BitTorrent streaming engine and buffer management utility integrated directly into Cloudstream.
* **Instant BitTorrent Streaming**: Search or paste any magnet link, `.torrent` file, or info hash to stream video content immediately over local HTTP without waiting for full downloads.
* **🚀 40+ High-Speed Streaming Trackers**: Automatically injects 40 Tier-1 public BitTorrent trackers into every magnet link and syncs daily tracker lists into TorrServer (`RetrackersMode = 1`). Drastically accelerates peer/seeder discovery, speeds up initial chunk buffering, and prevents stream stalling.
* **⚡ 0% Preload (Instant Start)**: Break free from standard CloudStream 50% pre-buffering! Set preload buffer down to `0%` to start video playback immediately as the first chunks arrive.
* **⏩ Forward Read-Ahead Customization**: Tune the forward read-ahead window (up to 95–100%) to cushion against network spikes and ensure seamless seeking.
* **💾 Memory & Disk Cache Modes**: Adjust cache from 32MB up to 1GB+; choose between RAM for ultra-fast seeking or Disk storage to safeguard low-memory devices (Android TV / FireStick).
* **🚫 Seeding Control (Leech Only)**: Toggle off background uploading to conserve mobile data, reduce battery consumption, and prevent bandwidth saturation.
* **🤖 Auto-Applying Settings Daemon**: Custom buffer size, preload %, lookahead %, and upload rules persist permanently across app restarts and are auto-applied on cold boot and stream initialization.
* **Multi-Episode & Subtitles**: Automatically unpacks multi-file torrents into episodes and extracts external/embedded subtitles on the fly.

### 2. 🎬 External Players (MPV Infinity, mpvEx & 1DM)
External player integrations: MPV Infinity (`is.xyz.mpv`), mpvEx (`com.husseinala.mpv`), and 1DM Video Downloader & Player (`idm.internet.download.manager` / `idm.internet.download.manager.plus`) in CloudStream's Preferred video player settings.
* Seamlessly forward direct video streams, torrent streams, and m3u8 playlists to your preferred advanced external video player with subtitle and audio track preservation.

### 3. ⏩ Skip Torrent Warning
A lightweight utility plugin that bypasses the *"Stream Torrent"* confirmation dialog and shows stream stats in player controls.
* **⚡ Zero Interruption**: Enjoy instant 1-click torrent streaming on mobile and Android TV without annoying confirmation popups.
* **📊 Player Stats Card**: Automatically expands download speed, file size, and active connection stats inside player controls.
* **🎛️ Minimal Settings**: Toggle warning bypass and player stats with clean, instant toggles.

### 4. 🛡️ Popup Blocker
Universal shield plugin that completely eliminates intrusive donation prompts, monthly mission popups, and ad banners across all CloudStream extensions (AllForU, CNCVerse, Raghav, Phisher Repo, etc.).
* **🛡️ Zero Popups**: Automatically intercepts and dismisses modal dialogs and announcement popups before they render.
* **⚡ Proactive Date Immunization**: Pre-authorizes donation session dates in SharedPreferences so extensions skip network stats calls and donation checks entirely.
* **🚫 Ad Redirect Shield**: Blocks secondary sponsor/ad redirects (`omg10.com`, etc.).

### 5. 🍿 MoviesDrive
Stream movies & TV series from MoviesDrive with customizable category catalogue and HubCloud high-speed extraction.
* **Categorized Catalogue**: Bollywood, Hollywood, Dual Audio, Web Series, and 4K Ultra HD.
* **HubCloud Integration**: Direct high-speed R2 and Google CDN stream resolution with smart sorting by decreasing file size.

### 6. 🎥 H2RxDMovies
Stream movies & TV series from H2RxDMovies with HubCloud direct R2 & Google CDN streams.
* Fast search and instant link resolution.

### 7. 📺 Allinone Live TV
Live TV & Sports streaming channels integrated directly into CloudStream.
* Live streams from JioTV Plus, SonyLiv, Sports TV, and Fancode.

### 8. 🧠 NextUp: AI-Powered Recommendation Engine
AI curation and discovery extension that analyzes your local CloudStream bookmarks/favorites and Simkl watch ratings, generates hyper-personalized recommendations via Google Gemini (supporting both Gemini Flash and custom-tuned AI Studio models), and resolves content opening through CloudStream's **"Open with"** ecosystem.
* **Simkl Meta-Engine**: Simkl IDs, WebP posters, backdrops, community ratings, and full episode listings.
* **Gemini AI Engine**: Creative recommendations with strict deduplication and refresh handling.
* **1-Tap Dataset Exporter**: Export `.jsonl` fine-tuning datasets formatted for Google AI Studio.

---

## ⚙️ Installation & Setup

1. **Install the Plugin Repo**: Open CloudStream > **Settings** > **Extensions** > **Add Repository**, and paste this URL:
   ```text
   https://raw.githubusercontent.com/deep12323/cloudstream-plugins/builds/repo.json
   ```
2. **Download Plugins**: Browse the repository list and install the desired plugins.
