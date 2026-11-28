# Development Progress & Engineering Log

## 📌 Overview
This document tracks the end-to-end debugging, feature additions, CI/CD pipeline, architecture notes, and UI adjustments performed across the `cloudstream-plugins` repository, including **TorrServe**, **ExternalPlayers**, **SkipTorrentWarning**, **PopupBlocker**, **H2RxDMovies**, **MoviesDrive**, **AllInOneLiveTV**, and **SystemFont**.

---

## 🏗️ Architecture & Deployment Flow

CloudStream extensions are distributed via Git branches:
1. **`master` branch**: Source code for all providers (`TorrServe`, `ExternalPlayers`, `SkipTorrentWarning`, `PopupBlocker`, `H2RxDMovies`, `AllInOneLiveTV`, `MoviesDrive`, `SystemFont`).
2. **`builds` branch**: Distribution branch containing compiled `.cs3` Dex binaries, `plugins.json`, and `repo.json`.
3. **Local Storage / Sideloading**: Direct `.cs3` files can also be copied directly to `/storage/emulated/0/Cloudstream3/plugins/` for instantaneous on-device testing.
4. **CloudStream Client**: Fetches `https://raw.githubusercontent.com/deep12323/cloudstream-plugins/builds/repo.json`, which points to `plugins.json?v=XX`.

```mermaid
flowchart LR
    A["Developer Code (master)"] --> B["Gradle Build (:make)"]
    B --> C[".cs3 DEX Binaries in builds/"]
    B --> G["Local Device (/storage/emulated/0/Cloudstream3/plugins/)"]
    C --> D["GitHub Actions Workflow"]
    D -->|"Force Push (orphan/sync)"| E["Distribution Branch (origin/builds)"]
    E -->|"raw.githubusercontent.com"| F["CloudStream App Client"]
```

---

## 📦 Verified Active Plugins in Repository

| Plugin Name | Internal ID | Version | Description |
|---|---|---|---|
| **TorrServe** | `TorrServe` | **`7`** | BitTorrent streaming engine |
| **External Players** | `ExternalPlayers` | **`4`** | External video players integration |
| **Skip Torrent Warning**| `SkipTorrentWarning` | `3` | Bypasses torrent confirmation dialog & displays live stats |
| **Popup Blocker** | `PopupBlocker` | **`3`** | Universal shield blocking donation popups, announcement modals, and dialogs across all extensions (AllForU, CNCVerse, Raghav, Phisher Repo, etc.) |
| **H2RxDMovies** | `H2RxDMovies` | **`5`** | 25-row catalogue streaming movies & TV series via HubCloud |
| **Allinone Live TV** | `AllInOneLiveTV` | **`1`** | Live TV & Sports channels (JioTV Plus, SonyLiv, Sports TV, Fancode) |
| **MoviesDrive** | `MoviesDrive` | **`10`** | Stream links true resolution & size detection, eliminated duplicate 1080p badges & greedy HTML matching |
| **DesireMovies** | `DesireMovies` | **`2`** | Stream links true resolution & size detection, eliminated duplicate 1080p badges & greedy HTML matching |
| **System Font** | `SystemFont` | **`13`** | Safe Pre-Inflation Shield, PreDraw Gate & Native Opaque BottomSheet Protection |
| **AudioBookBay** | `AudioBookBay` | **`3`** | Stream unabridged audiobooks from AudioBookBay via TorrServer & BitTorrent |

---

## 🛠️ Issues Encountered & Solutions Implemented

### 1. H2RxDMovies: Catalogue Redesign & Vertical Posters
* **Requirements**:
  * Create a 25-row organized catalogue similar to Perverzija in CXXX, with vertical movie posters (2:3 aspect ratio), paginated search, and native download support.
* **Changes Made**:
  1. **Added `hasDownloadSupport = true`**: Enables direct offline video downloading.
  2. **25 Categorized Sections** in `mainPage`:
     * **Feeds**: `Home`, `Featured`, `Latest Movies`, `Latest TV Series`, `Top Rated Movies`, `Top Rated TV Series`
     * **OTT Platforms / Studios**: `Netflix`, `Amazon Prime`, `JioHotstar`, `Zee5`, `SonyLIV`, `Apple TV+`
     * **Languages**: `Hindi`, `Tamil`, `Telugu`, `Malayalam`, `English`, `Korean (K-Drama)`
     * **Genres**: `Action`, `Thriller & Crime`, `Comedy`, `Romance`, `Horror`, `Sci-Fi & Fantasy`, `Animation`
  3. **Card Aspect Ratio**: Configured `isHorizontalImages = false` and prioritized portrait posters (`poster ?: backdrop`).
  4. **Paginated Search**: Implemented `override suspend fun search(query: String, page: Int): SearchResponseList?` via `/api/v1/search?q=...&page=...`.
  5. **Version**: Bumped to **v4**.

---

### 2. MoviesDrive: Initial Implementation & Catalogue Loading (v1 & v2)
* **Problem**: MoviesDrive addon was installed but showed empty/blank content and failed to load anything.
* **Root Causes & Solutions**:
  1. **HTML Parser Mismatch**: `parsePosterCard` looked for `<a href>` inside `<div class="poster-card">`, but MoviesDrive structures cards as `<a href="..."><div class="poster-card">...</div></a>`. Updated link extraction to inspect the parent tag.
  2. **Fast Search Integration**: Added Typesense fast search (`/search.php?q=...&page=...`) with fallback to WordPress REST API (`/wp-json/wp/v2/posts`) and HTML search.
  3. **Modular Settings**: Created `MoviesDrivePreferences` and `MoviesDriveSettingsFragment` allowing users to toggle and customize visible categories.

---

### 3. MoviesDrive: All Links Not Showing & Link Resolution Fixes (v3)
* **Problem**: Movie and series stream options were either missing qualities (only single resolution shown) or failed to resolve links due to timeouts.
* **Root Causes & Solutions**:
  1. **TV Series Quality Loss**: Concurrently fetch all quality archives per season in parallel and merge all download options into each episode.
  2. **HubCloud Crawler Hang**: Restricted regex pattern to `(?:pixel|gpdl)\.hubcloud` and `?id=`.
  3. **Stream Labeling**: Stream link names now display full server details, quality, codec, and size (e.g. `HubCloud FSL • 1080p MULTI H.264 [8.96 GB]`, `HubCloud 10Gbps • 4k 2160p MULTI SDR [22.9GB]`).

---

### 4. MoviesDrive: Stream Link Sorting by Decreasing File Size (v4)
* **Problem**: Stream links were emitted non-deterministically based on network arrival times.
* **Root Causes & Solutions**:
  1. **Byte-Accurate Size Parsing**: Created `MoviesDriveHubCloud.parseSizeToBytes(str, fallbackQuality)` supporting `TB`, `GB`, `MB`, `KB`, `B` (including `GiB` and `MiB`).
  2. **HubCloud Page Size Scraping**: Enhanced `resolveHubCloud` to extract `<i id="size">` directly from HubCloud HTML if not already parsed.
  3. **Multi-Level Descending Comparator**: In `loadLinks`:
     * **Primary**: Exact file size in bytes (`compareByDescending { it.sizeBytes }`).
     * **Secondary**: Video quality resolution (`thenByDescending { it.link.quality }`).
     * **Tertiary**: Server priority (`thenByDescending { it.serverPriority }`: FSL Direct R2 [4] > 10Gbps Google CDN [3] > Fast Stream [2] > PixelDrain [1]).

---

### 5. MoviesDrive: Domain Update, Search Recovery, GDFlix Extraction & Pixeldrain Bypass (v5)
* **Problem**: 
  1. Search stopped functioning completely across MoviesDrive with connection resets.
  2. Several stream links failed to load, threw 404 playback errors, or crashed video players.
  3. PixelDrain links were bandwidth-capped or failing without bypass support.
* **Root Causes & Solutions**:
  1. **Active Domain Migration**: Migrated `mainUrl` from deprecated `new3.moviesdrive.cyou` (connection reset) to active `new4.moviesdrive.cyou`.
  2. **Search Recovery & Mirror Fallback**:
     * Primary: High-speed Typesense JSON search (`/search.php?q=...&page=...`).
     * Secondary: WordPress REST API (`/wp-json/wp/v2/posts?search=...`).
     * Tertiary: Active mirror search via `moviesdrives.bid` if `mainUrl` encounters network blocks.
     * Fourth: HTML search fallback.
  3. **Telegram Link Filtering**: Fixed regex in `HubCloudExtractor` that previously matched `hubcloud.mov/tg/go?id=...` ("Download From Telegram") and emitted Telegram channel invite URLs as `10Gbps` video streams, causing player crashes. Added strict validation ensuring only legitimate video streams (`googleusercontent.com`, `drive.google.com`, `.mkv`, `.mp4`) are emitted.
  4. **Pixeldrain Bypass Support**: Added integration for Pixeldrain bypass proxy via `https://cdn.pixeldrain.eu.cc/$fileId?download` (referencing GreasyFork script 551766), bypassing daily download/bandwidth limits and streaming directly as `PixelDrain Fast (Bypass)`. Filtered out recurring dummy fake IDs (such as `negn6f`) that return 404.
  5. **Native GDFlix & only-apis Extractor Support**:
     * Implemented `resolveGDFlix` and registered `MoviesDriveGDFlix` extractor API (`gdflix.vip`, `new10.gdflix.io`, `only-apis.com`).
     * Extracts direct Cloudflare R2 storage streams (`fastcdn-dl.pages.dev/?url=...` and `.r2.dev`), Instant 10Gbps Google CDN streams (`workers.dev`, `instadl.rest`), and PixelDrain with bypass.
     * Updated `loadLinks` to extract both HubCloud and GDFlix links concurrently from `mdrive` archives.
  6. **TV Series Episode Parsing Enhancement**: Expanded episode regex to `(?:^|\b)(?:EP|Episode|E)[\s.-]*0*(\d+)\b` to correctly parse `E01`, `EP-01`, `EP.01`, etc. across all season archives.
  7. **Version Bump**: Bumped to **v5**.

---

### 6. MoviesDrive: Clutter Text Removal, Stream Link Cleanup & Dual-Size Fix (v6)
* **Problem**:
  1. Cluttered post titles with raw OTT tags, quality labels, and audio tags (e.g. `Dhindora (Season 2) 2021 [Hindi] NETFLIX Series 480p 720p 1080p 2160p 4K – WEB-DL`).
  2. Episode names cluttered with resolution lists (e.g. `Episode 1 (4K, 2160p, 1080p, 720p, 480p)` or raw archive labels).
  3. Storyline/Plot displaying "No Plot Found" due to mismatched HTML tags (`<h5>` after `Storyline`).
  4. Zip packs in specials displaying duplicated sizes and wrong quality (e.g. `[ZIP PACK] Zip [870MB] 480p. [870MB]`).
  5. Stream links displaying duplicate file sizes, wrong resolutions at the end, and clutter (e.g. `HubCloud 10Gbps 1080p 10BIT HEVC [1.99 GB] [396.53 MB] 480p`).
  6. Duplicate links extracted multiple times due to nested element selection in archives.
* **Solutions**:
  1. **Advanced Title Cleaner**: `cleanTitle` strips OTT platform tags, video codec/resolution labels, audio brackets, site names, and release years while cleanly preserving Season info (e.g., `Dhindora (Season 2)`).
  2. **Robust Storyline Extraction**: Targets sibling elements (`h3 ~ h5`, `h3 ~ p`, etc.) and strips decorative symbols, ensuring plot summaries are captured reliably.
  3. **Clean Episode Names**: Simplified episode labels to standard `Episode $epNum`.
  4. **Clean Zip Pack Names**: Formatted cleanly as `📦 [ZIP PACK] • $quality [$size]` without duplicate `Zip` or carry-over sizes.
  5. **Stream Link Title Formatter**: Created `formatLinkTitle` in `HubCloudExtractor` to produce uniform `<Server> • <Resolution> • <Codec> • [<Size>]` titles with exact single file size and accurate `qualityInt`. Stripped HTML comments and prevented duplicate links.
  6. **Version Bump**: Bumped to **v6** in both `MoviesDrive/build.gradle.kts` and `.github/workflows/build.yml`.

---

### 7. MoviesDrive: Auto Domain Updates & Health Recovery (v7)
* **Goal**: Enable fully autonomous domain rotation and health verification so MoviesDrive never breaks when the website changes subdomains or domains (e.g. `new4` -> `new5.moviesdrive.cyou` or mirrors).
* **Architecture & Solutions**:
  1. **Remote Cloud Config (`moviesdrive_domain.txt`)**:
     * Plugin queries `https://raw.githubusercontent.com/deep12323/cloudstream-plugins/master/moviesdrive_domain.txt` on failure, allowing instant worldwide redirection to any newly active domain without requiring plugin releases.
  2. **Autonomous Domain Probing & Verification**:
     * `verifyDomain`: Validates endpoint responsiveness and response signatures against MoviesDrive WordPress REST API (`/wp-json/`) and home HTML.
     * `resolveActiveDomain`: If current domain drops, sequentially probes:
       - GitHub remote config.
       - Rotating subdomains: `new4`, `new5`, `new6`, `new7`, `new8`, `new3`, `new2.moviesdrive.cyou`.
       - Official mirrors & landing domains: `moviesdrives.mov`, `moviedrive.org`, `moviesdrives.cfd`.
  3. **Persistent Preference Cache**: Working domain is stored in `SharedPreferences` (`active_domain`) so subsequent launches use the verified working domain directly without rediscovery delays.
  4. **Transparent Dynamic Retries**: If `getMainPage`, `search`, or `load` encounter an HTTP or connection failure, they automatically discover the new active domain and seamlessly re-execute the request.
  5. **Legacy Bookmark Rewrite**: Any saved or bookmarked URLs containing legacy domains (`new3`, `new4`, etc.) are dynamically rewritten to the active `mainUrl` during `load()`, preserving user bookmarks indefinitely.
  6. **Version Bump**: Bumped to **v7** across all manifests, workflow definitions, and compilation targets.

---

### 8. MoviesDrive: Minimalistic Metadata & Audio Language Tag Support (v8)
* **Problem**:
  1. Plot contained repeated movie titles at the beginning (e.g. `Buddha 2: The Endless Journey (2014) 2500 years ago...`) and trailing screenshot/download link headers (`Screen-Shots :- ... Full Movie HD >>>>>> -:`).
  2. Tags and cast contained huge clutter text: outer `<div>` selections caused subsequent page lines to leak into `Genre:` and `Stars:`, leading to `Country: Japan`, `Original Language: Japa`, actors, and plot text being displayed as genre chips, and `Language: [Hindi...` attached to the last actor.
  3. No clear audio language tag was displayed to inform users which audio streams are available.
* **Architecture & Solutions**:
  1. **Clean Audio Language Tag**:
     * Extracts audio language directly from HTML info block (`Language: ...`) or title brackets (`[Hindi (DD2.0) & English]`, `{Hindi-Japanese}`, etc.).
     * Strips `/ ESubs` subtitle tags, unescapes entities, and formats as a prominent `Audio: <Language>` chip (e.g. `Audio: Hindi (DD2.0) & English`).
  2. **Minimalistic Plot Cleaning**:
     * Adjacent sibling targeting (`h3:contains(Storyline) + p`, `+ div`, `+ h5`) eliminates container leakage.
     * Strips leading repeated title and release year.
     * Cuts off any trailing screenshot headings, download link headers, or ASCII arrows (`>>>>>>`).
  3. **Isolated Genre & Cast Extraction**:
     * Stops extraction before adjacent field delimiters (`Country:`, `Language:`, `Stars:`, `Quality:`, `Director:`, `Storyline:`), keeping genres strictly to film genres (`Animation`, `Drama`, `History`) and actors strictly to valid cast names.
  4. **Version Bump**: Bumped to **v8** across `MoviesDrive/build.gradle.kts`, `.github/workflows/build.yml`, and `agent.md`.

---

### 9. MoviesDrive: Multi-Episode Archive Fix & Season-Labeled Zip Packs (v9)
* **Problem**:
  1. For TV series (e.g. *Reacher*, *Dhindora*), only 1 episode was appearing because an outer container `<div>` in the single-episode archive page matched first, assigning all episodes' download links to `Episode 1` and skipping subsequent episodes.
  2. In multi-season series, zip packs in the "No Season" tab lacked season labels (e.g. `1. [ZIP PACK] 4K [42.8GB]`), leaving users unable to identify which season each archive belonged to.
* **Architecture & Solutions**:
  1. **Section-Based Episode Extraction**:
     * Splits archive HTML by `<hr>` boundaries and targets leaf elements (`h1..h6, p`), preventing outer `<div>` elements from swallowing descendant episode links.
     * Extracts all individual episodes (Episode 1 through N) with full multi-server link merging and quality deduplication.
  2. **Season-Labeled Zip Packs**:
     * Automatically formats zip pack names with their corresponding season:  
       `📦 [ZIP PACK] • Season $seasonNum • $quality [$size]` (e.g. `📦 [ZIP PACK] • Season 4 • 4K [42.8GB]`).
     * Sorts zip packs hierarchically by Season (descending) and file size/resolution.
  3. **Version Bump**: Bumped to **v9** in `MoviesDrive/build.gradle.kts`, `.github/workflows/build.yml`, and `agent.md`.

---

### 6. ExternalPlayers: Custom & Manual External Players Manager + mpvEx & Defaults (v3 & v4)
* **Feature (v3)**: Add setting in External Players to manually add external players, keep default players (MPV Infinity, mpvEx, 1DM) added by default, and allow removing any players (both built-in defaults and custom ones).
* **Metadata Cleanup (v4)**: Stripped clutter from the repository display name and description. Changed title to cleanly read `External Players` and description to `External video players integration`.
* **Architecture & Features**:
  1. **Built-in Defaults**: Out-of-the-box support for MPV Infinity (`app.infinity.mpvz`), mpvEx (`app.marlboroadvance.mpvex` / `app.adityanarvekar.mpvex`), and 1DM (`idm.internet.download.manager.plus`).
  2. **Total Removal & Management Power**: Every player (built-in or custom) has an instant toggle and a deletion button with confirmation. When deleted, it is removed from preferences and `VideoClickActionHolder.allVideoClickActions` immediately.
  3. **Manual Player Addition**: Input dialog allowing users to enter custom Player Display Name, Package Name (Application ID), and optional Activity Name.
  4. **1-Tap Installed Video App Scanner**: Device scanner querying `Intent.ACTION_VIEW` (`video/*`) and checking popular media players (VLC, MX Player, Just Player, Next Player, Nova Video Player, mpv-android, BubbleUPnP, KMPlayer) for 1-tap addition without manual package typing.
  5. **Dynamic 20-Slot Architecture (`CustomPlayerSlot0..19`)**: Ensures each custom player has a distinct qualified Kotlin class name, providing unique `uniqueId()` required by CloudStream's `getByUniqueId()` preference mapping.
  6. **Universal Intent Extras**: Forwards subtitles (multi-format array + single URI), title/filename, resume playback positions, User-Agent, Referer, custom headers (bundle and string array), and URI read permissions.
  7. **Instant Live Synchronization**: Updates `VideoClickActionHolder.allVideoClickActions` upon adding, deleting, or toggling players without needing an app restart. Includes "↺ Reset to Defaults" button.

---

### 6. PopupBlocker: Universal Donation & Popup Shield Across All Wiki Repositories (v2 & v3)
* **Problem**: Donation popups were appearing in extensions from Raghav repo, Phisher repo, and AllForU repo.
* **5-Layer Defense Architecture in v3**:
  1. **`WindowManagerGlobal` Hook (`ShieldViewsList`)**: Replaces `WindowManagerGlobal.mViews` with `ShieldViewsList` to synchronously hide any window containing `AnnouncerCard` or ad/donation keywords.
  2. **Activity Window Callback Delegation (`ShieldWindowCallback`)**: Wraps `activity.window.callback` to trigger an instant sweep when dialogs take focus.
  3. **`ViewTreeObserver.OnGlobalLayoutListener`**: Monitors decor view changes in real time.
  4. **Target Class Neutralization (`com.allforu.Announcer`)**: Disarms `Announcer` before runnable execution.
  5. **Guardian Loop & SharedPreferences Immunization**: Continuous loop immunizes `cncverse_donation`, `raghav_donation_prefs`, `netnaija_donation_prefs`, `phisher_donation_prefs`.

---

### 7. SystemFont: Deep Jetpack Compose Multiplatform Zero-Glitch Engine (v10 & v11)
* **Problem**: In v10, users observed a momentary font jump / visual glitch during screen navigation, scrolling, and opening movie details. Reverse-engineering revealed that Jetpack Compose Multiplatform in CloudStream 4 creates separate `FontFamilyResolverImpl` instances per view with `AndroidFontResolveInterceptor`, and `FontListFontFamilyTypefaceAdapter` asynchronously loaded `productsans_*.ttf` asset files before resolving fallback fonts, triggering recompositions.
* **Architecture & Zero-Glitch Engine in v11**:
  1. **Direct `PlatformResolveInterceptor` Dynamic Proxy**:
     * Installed a dynamic proxy on `PlatformResolveInterceptor.Companion.Default` and across all `FontFamilyResolverImpl.platformResolveInterceptor` fields.
     * Intercepts `interceptFontFamily(fontFamily) -> FontFamily.Default` or `FontFamily.SansSerif` synchronously in 0ms, collapsing any `FontListFontFamily` directly into the system font family before `FontListFontFamilyTypefaceAdapter` or `AsyncTypefaceCache` can query asset files or start background coroutines.
  2. **`AndroidComposeView` Resolver Injection (`ZeroGlitchFontResolverProxy`)**:
     * Traverses `AndroidComposeView.Companion.composeViews` and window decor views.
     * Reflectively wraps `AndroidComposeView.fontFamilyResolver` (`MutableState<FontFamily.Resolver>`) with a dynamic proxy implementing `FontFamily.Resolver`.
     * `resolve` queries (`mo3408resolveDPcqOEQ`) return pre-computed `TypefaceResult.Immutable` instances wrapping `cachedNormal`, `cachedBold`, `cachedItalic`, and `cachedBoldItalic` in 0ms without touching disk or assets.
  3. **Optimized `ComposeFontInterceptorLruCache`**:
     * Intercepts `GlobalTypefaceRequestCache.resultCache`.
     * Cached reflection constructors (`TypefaceResult$Immutable(Object, boolean)`) and field handles (`trWeightField`, `trStyleField`) eliminate reflective scanning overhead on cache misses.
  4. **`GlobalAsyncTypefaceCache` Eviction**:
     * Evicts stale asset font loading entries so background asset loaders never finish and trigger UI recompositions.
  5. **Clean Non-Thrashing View Observers**:
     * Replaced continuous invalidations with targeted view hooking, ensuring zero frame drops or visual flickers during rapid navigation.
  6. **AndroidX `TypefaceCompat.sTypefaceCache` & `Typeface.sSystemFontMap`**:
     * Maintains prefilled AppCompat font caches (1024 capacity) and JVM system font aliases for complete coverage across all traditional Android Views and dialogs.

---

### 8. SystemFont: Pre-Inflation Shield & Zero-Glitch Frame-0 Gate (v12)
* **Problem**: User reported: *"glitch means i am able to see text changing in system font"*. When navigating between Home, Search, Movie Details, and Settings, the text initially rendered in Google Sans / Product Sans for 50-100ms and then visibly flipped to the system font (MiSans).
* **Root Cause**:
  1. CloudStream's core UI (`HomeFragment`, `ResultFragment`, `SearchFragment`, `SettingsFragment`, and RecyclerView items) is built with XML layouts where themes set `android:textViewStyle` to `@style/AppTextViewStyle` (`android:fontFamily="@font/google_sans"`).
  2. During XML layout inflation, `TextView` constructors load `productsans` natively from assets and render the initial frame *before* `OnGlobalLayoutListener` or lifecycle observers fire.
  3. When `applyToViewTree` ran after inflation and layout, it modified `view.typeface` while the view was already visible on screen, causing the user to watch the text snap/change from Google Sans to MiSans.
* **Architecture & Solutions in v12**:
  1. **Pre-Inflation Shield (`LayoutInflater.Factory2` Interceptor)**:
     * Reflectively hooks `activity.layoutInflater` via `ZeroGlitchLayoutFactory` wrapping `AppCompatDelegateImpl`.
     * The microsecond `onCreateView(parent, name, context, attrs)` creates any `TextView`, `Button`, or `EditText`, the target system typeface is assigned *before* the view is added to the hierarchy, measured, laid out, or drawn.
     * All views are born with the system font from Frame 0.
  2. **PreDraw Gate (`ViewTreeObserver.OnPreDrawListener`)**:
     * Installed on Window decor views to execute immediately before the GPU draws the display buffer.
     * Acts as an absolute fail-safe ensuring 100% of text views on screen have the system font before any pixels are rendered to the user.
  3. **Immediate Hierarchy Attachment (`ViewGroup.OnHierarchyChangeListener`)**:
     * Attached across all decor views and ViewGroups, instantly intercepting dynamically attached child views and `AndroidComposeView` instances at the moment of attachment.
  4. **Pre-Composition Compose Injection**:
     * Hooks `AndroidComposeView.fontFamilyResolver` State before the first composition pass runs, preventing Compose from ever rendering an initial frame with Product Sans or triggering recomposition passes.

---

### 9. SystemFont: Opaque BottomSheet & Native Dialog Preservation (v13)
* **Problem**: After v12, users reported that while the font changing glitch was completely fixed, opening the homepage provider selector (`HomeFragment.selectHomepage`) caused the bottom sheet dialog to render completely transparent, with the provider list floating directly on top of the movie posters.
* **Root Cause**:
  1. In v12, `attachHierarchyListener` called `group.setOnHierarchyChangeListener` on every ViewGroup, including `CoordinatorLayout` and `design_bottom_sheet`. This overwrote `CoordinatorLayout.HierarchyChangeListener`, which is responsible for initializing `BottomSheetBehavior` and applying the bottom sheet's opaque `MaterialShapeDrawable` background.
  2. `tryFallbackCreateView` in `ZeroGlitchLayoutFactory` intercepted container creation using the Activity's context rather than letting `LayoutInflater` inflate views with the dialog's `ContextThemeWrapper` (`AppBottomSheetDialogTheme`).
* **Solutions in v13**:
  1. **Removed `setOnHierarchyChangeListener` Hijacking**: Completely eliminated ViewGroup hierarchy listener replacement, preserving `CoordinatorLayout` and `BottomSheetBehavior` internal lifecycles and background shape drawables intact.
  2. **Pure `Factory2` Delegation**: `ZeroGlitchLayoutFactory` now only intercepts `TextView` instances and returns `null` for all layout containers and dialog frames, allowing Android's `LayoutInflater` to natively resolve dialog themes, background drawables, and corner elevations with 100% opacity.
  3. **Retained Frame-0 Font Shield**: All `TextView`, `Button`, and `EditText` instances in both the main window and bottom sheet dialogs continue to be intercepted at creation, maintaining zero font jumps.

---

### 10. TorrServe: Declutter Text & Clean Modern Look (v7)
* **Goal**: Remove verbose text clutter, emoji prefixes, and repetitive labels across TorrServe to establish a sleek, minimal, and clean UI.
* **Changes Made**:
  1. **Metadata & Repository**:
     * Cleaned description in `build.gradle.kts` and `.github/workflows/build.yml` to `"BitTorrent streaming engine"` (stripped long list of features and buzzwords).
     * Unified provider display name to `"TorrServe"` in `TorrServeProvider.kt`.
  2. **Stream Link & Load Responses**:
     * Extracted clean filename for stream links (`name = fileName`) rather than redundant `"$name: $fileName"` which previously duplicated the provider name alongside the source badge.
     * Cleaned movie/series load response plot to `"BitTorrent stream via TorrServer"`.
  3. **Settings Dialog**:
     * Cleaned header: `"TorrServe"` title with concise `"BitTorrent Streaming Configuration"` subtitle.
     * Compact status indicator (`Online: $url` / `Standby`).
     * Clean profile labels (`Keep Alive`, `Fast Stream`, `TV / Low RAM`, `4K Cinema`, `Default`) without emoji clutter.
     * Clean 1-line description tags for Buffer Size, Preload, Read-Ahead, Disconnect Timeout, and Trackers.
     * Stripped emojis (`⭐`, `🛡️`, `✏️`, `🔄`) from preset chips and buttons (`Custom`, `30s`, `1m`, `2m`, `5m`, `10m`, `Re-inject Trackers`).
     * Clean toggle labels: `Disable Seeding` and `Disk Cache`.
     * Concise action buttons (`Cancel` and `Save`) and minimal toast (`Settings saved`).
  4. **Version**: Bumped to **v7**.

---

### 11. DesireMovies: New Provider Implementation (v1)
* **Goal**: Build a complete, standalone CloudStream plugin for DesireMovies (`https://desiremovies.my/` landing hub and `https://1desiremovies.xyz/` active WordPress site) supporting movies, series, zip packs, auto-domain migration, and high-speed multi-hoster stream extractors.
* **Features & Implementation**:
  1. **Dual-Domain & Auto-Domain Recovery**:
     * Primary active domain: `https://1desiremovies.xyz`.
     * Official landing hub: `https://desiremovies.my/`. If active domain changes, the provider automatically scrapes the landing page for new mirrors, verifies `/wp-json/`, and updates the persistent domain configuration in `DesireMoviesPreferences`.
     * GitHub Remote Config fallback: Probes `desiremovies_domain.txt` on the repository master branch for instant remote domain updates without app updates.
     * Sequential probing fallback: Probes `1desiremovies.xyz` through `5desiremovies.xyz` and alternate TLDs (`.beauty`, `.org`, `.trade`, `.vip`).
  2. **Clean Minimalistic Metadata**:
     * Clean Title: Strips site branding (`Desiremovies`, `.my`, `.xyz`), codec buzzwords (`4K`, `1080p`, `720p`, `HEVC`, `x264`, `x265`, `WEB-HDRip`, `BluRay`), OTT tags (`Netflix`, `Hotstar`, `Amazon`, `Zee5`), and standalone years.
     * Prominent Audio Language Tag: Extracts exact audio tracks (`Language : ...` or title brackets) and tags the item with `Audio: <Languages>` (e.g., `Audio: Hindi, Telugu`) and formats it at the very top of the synopsis: `🔊 Audio: Hindi, Telugu`.
     * Clutter-Free Plot: Strips screenshot links, telegram promos, and repeated title lines.
     * Extracts IMDb score, genre tags, and cast members.
  3. **TV Series & Zip Pack Handling**:
     * Scans `.entry-content` elements sequentially to detect Season numbers (`Season 1`, `Season 2`), Episode ranges (`EP 1 TO 4`, `EP 5 TO 8`) and single episodes (`EP 76`).
     * Associates qualities and file sizes with each episode.
     * Detects Zip packs and allocates them under Season 0 / Specials with clear labels: `📦 [ZIP PACK] • Season $seasonNum • $quality [$size]`.
  4. **Multi-Server Stream Extraction & Descending Size Sorting**:
     * Resolves gateway links (`gyanigurus.online/view/...`) into direct lockers: HubCloud (`hubcloud.mov`), GDFlix (`gdflix.vip` / `new10.gdflix.io`), and HubDrive (`hubdrive.blog`).
     * Extracts direct streams:
       * **HubCloud FSL** (Cloudflare R2 Direct Video)
       * **HubCloud 10Gbps** (Google CDN high-speed streams via gpdl/pixel)
       * **HubCloud Stream** (HBPlay player base64)
       * **PixelDrain Fast** (Unlimited speed via `cdn.pixeldrain.eu.cc` bypass)
       * **PixelDrain** (Direct API fallback)
       * **GDFlix R2** (Direct Cloudflare Storage)
       * **GDFlix 10Gbps** (Google CDN via workers.dev)
     * All stream options are strictly sorted by **decreasing file size**, then quality (4K > 1080p > 720p > 480p), then server priority.
  5. **Fast Search & Paginated Catalogue**:
     * Primary search: High-speed WordPress REST API (`/wp-json/wp/v2/posts?search=...&page=...&per_page=20`).
     * Secondary search: Clean HTML search route (`/search/{query}/page/{page}/`), cleanly bypassing WAF bot blocks on `?s=`.
     * 13 Home catalogue categories (Latest, Bollywood, South Hindi, Hollywood Hindi/English, Web Series, Korean Hindi, Punjabi, etc.) configurable and toggleable via `DesireMoviesSettingsFragment`.
  6. **Version**: Initial release **v1**.

---

### 10. Resolution Bug Fix & Redundant Quality Badge Elimination (MoviesDrive v10 & DesireMovies v2)
* **Problem**:
  In the stream mirror selection sheet, every single link was displayed as `1080p` both in the text and with a duplicate `1080p` badge at the end of the line, regardless of whether the file was 480p, 720p, or 1080p:
  ```text
  GDFlix 10Gbps 1080p [8.94 GB] 1080p
  HubCloud 10Gbps 1080p [1.28 GB] 1080p
  GDFlix 10Gbps 1080p HEVC [722.52 MB] 1080p
  GDFlix 10Gbps 1080p [486.46 MB] 1080p
  ```
* **Root Causes & Solutions**:
  1. **Top-Level `div` Traversal in `load()`**:
     In `MoviesDriveProvider.kt`, `contentElem.select("h1, h2, h3, h4, h5, h6, p, div")` started by visiting outer `div` containers (`<div class="app-container">`) containing the post's full text (`... 480p | 720p | 1080p ...`), locking `currentHeadingQuality = "1080p"` and matching all download anchors inside that outer div before specific child `<h5>` headings could be evaluated.
     * **Fix**: Removed `div` from the element selector, restricted heading quality updates strictly to elements containing a single resolution (`singleQualities.size == 1`), and added fallback anchor scanning to guarantee no links are missed.
  2. **Resolution Determination Hierarchy in `formatLinkTitle`**:
     Previously, `val combined = "$qualityStr $filenameOrTitle"` prioritized `qualityStr` first, so any mistakenly inherited `1080p` shadowed the true resolution in the filename.
     * **Fix**: Extracted resolution directly from the exact video filename (`[A-Za-z0-9._-]+\.(?:mkv|mp4)`) first, falling back to `qualityStr` only when it contains a single resolution token.
  3. **File Size Cross-Check & Fallback**:
     Added size-based validation: small files (< 600 MB) are guaranteed to be 480p (correcting false 1080p labels), files under 1.1 GB are capped at 720p, and unknown resolutions are automatically inferred from file size thresholds.
  4. **Eliminated Redundant Resolution Badges**:
     CloudStream natively renders a quality badge at the end of each link row via `link.quality`. Embedding resolution text in `link.name` caused it to display twice (`1080p [8.94 GB] 1080p`).
     * **Fix**: Formatted `link.name` cleanly as `"$server • [$sizeClean]"` (or `"$server • $codec [$sizeClean]"` when codec like HEVC/10Bit is present). CloudStream's badge provides the clean, non-redundant resolution tag.
  5. **GDFlix Filename Regex**:
     Replaced greedy multiline fallback `Regex("""/([^/]+\.(?:mkv|mp4))""")` with precise token match `Regex("""(?i)(?:Name\s*:\s*|Download\s+|title>GDFlix\s*\|\s*)([A-Za-z0-9._-]+\.(?:mkv|mp4))""")`.
  6. **Versions**: Bumped **MoviesDrive to v10** and **DesireMovies to v2**.

---

### 11. AudioBookBay: Audiobook Provider with Multi-Chapter TorrServer Streaming (v1)
* **Features**:
  1. **27 Curated Categories & Tags**: Browse Latest Audiobooks, Bestsellers, Sci-Fi, Fantasy, Thriller, Mystery, Crime, Action, Adventure, Horror, LitRPG, Light Novel, Romance, Classics, Full Cast, History, Biographies, Business, Self-Help, Science, Computer & Tech, and Language feeds (`English`, `Spanish`, `French`, `German`), with pagination.
  2. **Paginated Search & Quick Search**: Queries `/?s=...` and `/page/<page>/?s=...` cleanly.
  3. **Rich Metadata Extraction**: Parses Title, Cover Art, Release Year, Author, Narrator (as cast credits), Format, Abridged/Unabridged status, File Size, Language, Categories, and Synopsis.
  4. **Info Hash -> Magnet & Multi-Chapter Streaming**: Constructs tracker-enriched magnet links from AudioBookBay's Info Hash and tracker table, resolves multi-file audiobooks into naturally sorted chapters via TorrServer (`TvSeriesLoadResponse`) or falls back cleanly to HTML chapter parsing and `ExtractorLinkType.MAGNET`.
  5. **Configurable Domain Mirror**: Settings dialog allows overriding or resetting the active AudioBookBay domain (default `https://audiobookbay.lu`).
  6. **Magnet URL Fix (v2)**: Passed `fix = false` to `newEpisode(...)` so CloudStream's `fixUrl()` no longer prepends `https://audiobookbay.lu/` to `magnet:?...` chapter links, and normalized `loadLinks()` inputs so magnet links always start from `magnet:`.
  7. **Single Link Mode (v3)**: Always return a single `MovieLoadResponse` (`TvType.AudioBook`) with no multi-episode splitting, and emit only the single `Magnet (BitTorrent)` link directly in `loadLinks()` without TorrServer polling/delays.
  8. **Version**: Updated to **v3**.

### 12. H2RxDMovies: Link Resolution, Retry & Extractor Fixes (v5)
* **Problem**: Several H2RxDMovies links failed to load or emitted dead 404 streams (including `gpdl.hubcloud.*` links, `pixel.hubcloud.*` redirects, dummy PixelDrain ID `negn6f`, transient API/host timeouts, and multi-season pack episode collisions).
* **Solutions**:
  1. **HubCloud Extractor Hardening**:
     * Added transient network retry (`retryGet`) for API and HubCloud requests.
     * Added Jsoup fallback for Step 1 gateway extraction (`a[href*='hubcloud.php'], a#download, a.btn-primary, a.btn-success`) and direct-page fallback when no intermediate gateway is present.
     * Added `gpdl*.hubcloud.*` and `?id=` 10Gbps link matching with `Referer` propagation, `isSuccessful` validation, `"Unable to Get Download Link"` rejection, and `cryptonewz.online` / `link=` / `url=` extraction.
     * Filtered dummy PixelDrain IDs (`negn6f`, length `< 6`) and added `cdn.pixeldrain.eu.cc` bypass alongside `.r2.dev` and `pages.dev/?url=` support.
  2. **Provider & Plugin Improvements**:
     * Added `hubcloud_url` -> `download_url` fallback on failure, GDFlix support, and registered HubCloud mirror domains (`hubcloud.mov`, `hubcloud.one`, `hubcloud.cx`, `hubcloud.foo`, `hubcloud.fans`).
     * Fixed Season 0 pack episode index collisions across multi-season series and sorted emitted links by descending file size, resolution, and server priority.
  3. **Version**: Bumped to **v5** in `H2RxDMovies/build.gradle.kts`, `.github/workflows/build.yml`, and `agent.md`.

---

## 🔄 How to Release Future Updates
Whenever updating provider code:
1. Bump `version = X` in `<Provider>/build.gradle.kts`.
2. Update the corresponding `version: X` and URL version query in `.github/workflows/build.yml`.
3. Push to `master`. GitHub Actions will automatically compile `.cs3` files and deploy them to the `builds` branch.
