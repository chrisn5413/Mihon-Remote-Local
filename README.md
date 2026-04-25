# Mihon Remote Library Extension

A [Mihon](https://mihon.app) source extension that lets you browse and read manga stored in Google Drive — directly within Mihon, with no server software or backend required.

> **Status:** Core reading and browsing is working. Covers, CBZ chapters, folder-based chapters, and persistent library state are all functional.

---

## Overview

This extension treats Google Drive as a dumb file store. It scans your Drive folder once to build a local index, then uses that index for all browsing — no network required after the initial scan. Opening a chapter downloads its pages to a local cache and serves them over a loopback HTTP server to Mihon's reader.

**This extension is read-only.** It uses a read-only OAuth scope and never modifies, uploads to, or deletes anything from your Drive.

---

## How It Works

### 1. Library Scanning

When you add a library, the extension calls the Google Drive Files API once to fetch all files and folders up to two levels deep under your root folder:

```
Root folder
  └─ Series folder       ← Level 1 (each becomes a manga)
       └─ Chapter folder  ← Level 2 (folder-based chapters)
       └─ Chapter.cbz     ← Level 2 (archive-based chapters)
```

The full result is returned by Drive in a small number of paginated API calls. The extension builds an in-memory parent→children map from the response, then processes each series folder locally — no extra API calls needed for the index structure. The only additional network requests during scanning are:

- One `listDirectChildren` call per series to find the first image in the first chapter folder (for the cover file ID)
- One partial download (512 KB) per CBZ-based series to extract `ComicInfo.xml` metadata if present

The resulting index is serialised to `index.json` and stored on-device. All subsequent browsing and searching reads from this local file.

### 2. Cover Loading

Cover images are cached permanently in the app's private file storage (`filesDir`). The cover strategy depends on the chapter type of each series:

**Folder-based series:** The Drive file ID of the first image in the first chapter folder is stored in the index. On first display, the image is downloaded directly and saved as a JPEG.

**CBZ-based series:** The Drive file ID and byte size of the first CBZ file are stored in the index. Cover extraction uses a two-step Range request strategy:

1. Download the **last 64 KB** of the CBZ, which contains the ZIP central directory and End of Central Directory (EOCD) record.
2. Parse the central directory to find the alphabetically-first image entry — getting its exact local file header offset and compressed size.
3. Issue a second Range request for exactly that image's bytes.
4. Decompress (DEFLATE) or pass through (STORED) and decode with `BitmapFactory`.

This approach bypasses `ZipInputStream` entirely, which is necessary because many CBZ creators set the EXT bit (flag `0x0008`) on STORED entries. This causes `compressedSize = 0` in local file headers; `ZipInputStream` then reads zero bytes per entry, interprets actual file content as a data descriptor, and corrupts its stream position. The central directory always has correct sizes and offsets regardless of the EXT bit.

Covers are served to Mihon's image loader (Coil) via the `LocalPageServer` (see below) rather than as `file://` URIs, which allows Coil to retry after a 404 once a background download completes.

### 3. Chapter Reading — LocalPageServer

Mihon's reader uses `HttpPageLoader`, which fetches pages via OkHttp. OkHttp only handles `http://` and `https://` — `file://` URIs are rejected before the request starts. To serve locally-cached files without any actual network traffic, the extension runs a minimal HTTP/1.0 server bound to loopback:

```
LocalPageServer — java.net.ServerSocket on 127.0.0.1:45678
```

Each `fetchPageList()` call:
1. Downloads and extracts the chapter's pages into `filesDir/mihon-remote/reading/{seriesId}/{chapterId}/`
2. Returns a `List<Page>` where each `imageUrl` is `http://127.0.0.1:45678/cache/{relative-path}`
3. Mihon's `HttpPageLoader` fetches each URL; `LocalPageServer` reads the file and responds with a `200 OK`

`LocalPageServer` handles one request per thread (daemon threads, spun up per connection). It URL-decodes paths (OkHttp percent-encodes filenames), validates against path traversal, infers MIME type from file extension, and sends the file bytes.

Using a fixed port (45678) rather than an OS-assigned ephemeral port ensures that `thumbnail_url` values stored in Mihon's database remain valid across app restarts.

**CBZ chapters** are downloaded in full, then extracted using `ZipFile` (which reads the central directory at EOF and enumerates all entries robustly — avoiding the same EXT-bit issues that affect `ZipInputStream` for reading).

**Folder-based chapters** download up to 4 image files concurrently from Drive.

### 4. Source Identity and Database Persistence

Each library's Mihon source ID is derived by hashing:
```
"eu.kanade.tachiyomi.extension.remotelibrary/{rootFolderId}"
```

The Drive folder ID (e.g. `1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs`) is the `LibraryConfig.id` and is the stable anchor. It never changes for a given folder, so uninstalling and reinstalling the extension, then re-adding the same library URL, produces the **same source ID** — preserving all read progress, library entries, and history in Mihon's database.

### 5. OkHttp Classloader Isolation

Mihon's extension system loads extensions using a `ChildFirstPathClassLoader`, which resolves classes from the extension DEX before the host app. Bundling OkHttp (`implementation`) inside the extension causes two distinct `okhttp3.Request` class objects to exist at runtime (one from each classloader). The JVM verifier then rejects `RemoteLibrarySource` with a `LinkageError` because the `HttpSource` superclass method signatures reference a different class object than the override.

The extension declares OkHttp as `compileOnly` — present at compile time (needed because `RemoteLibrarySource` extends `HttpSource` which uses OkHttp types) but absent from the DEX at runtime. At runtime, OkHttp resolves from Mihon's classloader, matching what `HttpSource` expects. All actual HTTP work (Drive API, OAuth token exchange) uses `java.net.HttpURLConnection`, which is always present on Android.

---

## Folder Structure

Your Drive folder should match how Mihon organises downloaded manga:

```
/My Manga/                        ← your configured root folder
  /Berserk/
    /Chapter 001/                 ← folder-based chapter
      001.jpg
      002.jpg
    /Chapter 002/
      001.jpg
  /One Piece/
    Vol.98 Ch.1137.cbz            ← CBZ-based chapter
    Vol.98 Ch.1138.cbz
  /Mixed Series/
    /Chapter 001/                 ← folder and CBZ chapters can coexist
    Chapter 002.cbz
```

Both chapter types are fully supported and can be mixed within the same series. Metadata is read from `ComicInfo.xml` inside CBZ files where present (title, author, tags, status, summary).

---

## Features

| Feature | Status |
|---|---|
| Google Drive browsing | ✅ Working |
| Folder-based chapter reading | ✅ Working |
| CBZ chapter reading | ✅ Working |
| Cover images (folder-based series) | ✅ Working |
| Cover images (CBZ series) | ✅ Working |
| ComicInfo.xml metadata | ✅ Working |
| Multiple libraries | ✅ Working |
| Offline browsing (after scan) | ✅ Working |
| Persistent source ID across reinstalls | ✅ Working |
| Search by title | ✅ Working |
| Reading cache with size cap and LRU eviction | ✅ Working |
| Per-library settings (rescan, clear cache, remove) | ✅ Working |
| Google Account connection/disconnect | ✅ Working |
| Scan progress UI | ✅ Working |
| WebDAV / SFTP / S3 / SMB backends | 📋 Planned |
| Cover load modes (lazy / eager / prefetch N) | ✅ Implemented |

---

## Setup

1. Install the extension APK
2. In Mihon → Browse → Extensions, find **Remote Library** and open its settings (gear icon)
3. Tap **Google Account** and sign in
4. Tap **Add Library**, paste your Drive folder URL, tap **Verify**, then **Save**
5. Wait for the initial scan to complete
6. Restart Mihon — your library appears as a source in the Browse tab

> **Tip:** Use `adb install -r app-debug.apk` to update the extension without wiping app data. A full uninstall clears the local index and cover cache (Mihon's read-progress database is unaffected if you re-add the same folder URL).

---

## Settings

**Per-library** (accessed via the gear icon → library section):

| Setting | Default | Description |
|---|---|---|
| Rescan Library | — | Rebuilds the index and cover cache from Drive |
| Clear Cover Cache | — | Deletes cached cover images for the library |
| Remove Library | — | Removes library config and all local data |

**Global:**

| Setting | Description |
|---|---|
| Add Library | Add a new Drive folder as a library |
| Google Account | Shows connected account; tap to manage |

---

## Architecture

```
RemoteLibraryFactory (SourceFactory)
├── RemoteLibrarySetupSource   — ConfigurableSource; settings gear icon; always present
└── RemoteLibrarySource (×N)   — one per library; extends HttpSource

RemoteLibrarySource
├── IndexManager               — loads/saves index.json from filesDir
├── CoverCache                 — downloads and caches cover JPEGs
│   └── ZipCentralDirectory    — extracts covers from CBZ via central directory parsing
├── ReadingCache               — downloads chapter pages; LRU eviction
│   └── ZipFile                — extracts CBZ chapters (robust to EXT-bit entries)
└── LocalPageServer            — loopback HTTP server on port 45678; serves cached files

IndexGenerator
├── GoogleDriveClient          — Drive REST API via HttpURLConnection (not OkHttp)
├── ZipPartialReader           — extracts ComicInfo.xml from partial CBZ download
└── ComicInfoParser            — XmlPullParser-based ComicInfo.xml reader

DriveAuthManager               — OAuth2 via Google Play Services; tokens in EncryptedSharedPreferences
LibraryRegistry                — library configs persisted via ContentProvider (cross-process safe)
```

---

## Building

```bash
git clone https://github.com/YOUR_USERNAME/Mihon-Remote-Local.git
cd Mihon-Remote-Local
./gradlew assembleDebug

# Install without wiping app data (preferred for development iteration):
adb install -r app/build/outputs/apk/debug/app-debug.apk

# First install:
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Known Limitations

- **Index staleness:** The local index is not automatically refreshed when Drive files change. Use Rescan Library to rebuild it.
- **Scan depth:** The scanner fetches two levels under the root folder (series → chapters). Images inside chapter folders are not indexed during the scan — they are listed lazily on first chapter open.
- **Large libraries:** Initial scan time scales with the number of series (one extra API call per series for the cover file ID). A library with 200 series takes roughly 200 extra `listDirectChildren` calls.
- **CBZ compression:** Only STORED (method 0) and DEFLATE (method 8) are supported for cover extraction. Bzip2 or LZMA-compressed CBZ files will show no cover.

---

## License

[MIT License](LICENSE)

---

## Acknowledgements

- [Mihon](https://github.com/mihonapp/mihon) — the open source manga reader this extension is built for
- [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) — reference for extension project structure
