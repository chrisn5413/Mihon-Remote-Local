# Mihon Cloud Extension

A [Mihon](https://mihon.app) source extension that lets you browse and read manga stored in Google Drive — directly within Mihon, with no server software or backend required.

> **Status:** Early development — not yet ready for general use.

---

## Overview

Is downloading chapters making you running out of space on your device? Do you want an easy way to store and view your saved series on multiple devices? Move your saved titles over to Google Drive and this extension will make it browsable as a source.

The way this extension works is simple. Drive is treated as a dumb file store. The extension scans it once to build a local index, then uses that index for all browsing. After the initial scan, only fetching chapters uses the network, browsing your library does not. 

**This extension is read-only.** It never modifies, uploads to, or deletes anything from your Drive.

---

## How It Works

1. You copy your Mihon downloads folder to Google Drive (same folder structure, no changes needed)
2. You add a library pointing at that Drive folder
3. The extension scans the folder once and builds a local index on your device
4. Your library is now browsable from the Mihon source list

The local index is permanent — it stays on your device until you delete it or rescan. Browsing and searching never touch the network. Only opening a chapter (to download pages) makes Drive API calls.

---

## Requirements

- Android 6.0 (API 26) or higher
- Mihon installed
- A Google account with the manga files in Google Drive

---

## Expected Folder Structure

Your Drive folder should match how Mihon organizes downloaded manga:

```
/My Manga/                    ← your configured root folder
  /Berserk/
    /Chapter 001/             ← chapter as image folder
      001.jpg
      002.jpg
    /Chapter 002/
      001.jpg
  /One Piece/
    Chapter 001.cbz           ← chapter as CBZ archive
    Chapter 002.cbz
```

If you've been using Mihon's download feature, your downloads folder already looks like this. Just copy it to Drive.

**Both folder-based and CBZ-based chapters are supported**, and they can be mixed within the same series.

---

## Features

- Browse your Drive manga library as a native Mihon source
- One-time scan — builds a local index that persists across sessions
- Local cover image cache — covers load once and stay cached
- Configurable cover loading: lazy, eager, or prefetch N
- Metadata from `ComicInfo.xml` where available in CBZ files
- Multiple libraries — add multiple Drive folders as separate sources
- Offline browsing — cached library and covers work without internet
- Read-only — never modifies your Drive files

---

## Supported Storage Backends

| Backend | Status |
|---|---|
| Google Drive | 🚧 In development |
| WebDAV (Nextcloud, ownCloud) | 📋 Planned |
| SFTP | 📋 Planned |
| S3-compatible (Backblaze B2, Cloudflare R2) | 📋 Planned |
| SMB / Samba | 📋 Planned |

---

## Setup

> Installation and setup instructions will be added when the first release is available.

**High-level steps:**
1. Install the extension APK
2. In Mihon → Browse → Sources → Cloud Extension → Settings
3. Connect your Google account
4. Add a library pointing at your Drive manga folder
5. Wait for the initial scan to complete
6. Browse your library

---

## Settings

**Per-library:**

| Setting | Default | Description |
|---|---|---|
| Cover load mode | Lazy | How covers load: Lazy (on scroll), Eager (all on open), Prefetch N |
| Prefetch count | 50 | Number of covers to prefetch (Prefetch N mode only) |
| Keep reading cache | Off | Keep downloaded chapter pages between sessions |
| Reading cache limit | 500 MB | Max local cache size for downloaded chapters |
| Rescan Library | — | Delete index and rebuild from Drive (also clears cover cache) |
| Remove library | — | Remove library and all local data |

**Global:**

| Setting | Description |
|---|---|
| Google Account | Shows connected account |
| Disconnect Account | Revokes Google auth tokens |

---

## Development

### Building

```bash
git clone https://github.com/YOUR_USERNAME/mihon-cloud-extension.git
cd mihon-cloud-extension
./gradlew assembleDebug
./gradlew installDebug
```

### Contributing

Contributions are welcome. Before opening a pull request:

1. Check [open issues](../../issues) to avoid duplicate work
2. For significant changes, open an issue first to discuss the approach
3. Follow existing code style
4. Add or update tests for changed logic

---

## Roadmap

- [ ] Google Drive backend (Phase 1)
  - [ ] OAuth authentication
  - [ ] Bulk folder scan (files.list with ancestors query)
  - [ ] Local index generation and persistence
  - [ ] Multiple library support
  - [ ] Cover cache (lazy/eager/prefetch modes)
  - [ ] Folder-based chapter reading
  - [ ] CBZ chapter reading
  - [ ] Settings UI (per-library and global)
  - [ ] Scan progress UI
  - [ ] Offline fallback
- [ ] WebDAV backend (Phase 2)
- [ ] SFTP backend (Phase 2)
- [ ] S3-compatible backend (Phase 3)
- [ ] SMB backend (Phase 3)

---

## License

[MIT License](LICENSE)

---

## Acknowledgements

- [Mihon](https://github.com/mihonapp/mihon) — the open source manga reader this extension is built for
- [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) — reference for Mihon extension structure
