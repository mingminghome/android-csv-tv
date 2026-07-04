# TV,
![TV, Logo](https://github.com/user-attachments/assets/5a2a2258-a355-49ad-8e8f-bc2579397a0e)

**TV,** is an Android TV application built using the Leanback library, designed to display and play videos from a CSV file. The app allows users to specify a remote CSV file (e.g., a Google Sheets URL). It supports various video stream formats and web content, with pointer-based control for an enhanced TV experience.

## Video metadata sourced from
 - Published Google Sheets CSV (No hosting needed)  
 - Your own CSV

## Features

### 📺 Leanback & Custom UI
- **Leanback Grid Layout**: TV-optimized interface utilizing Android's Leanback framework to display channel groups and card lists.
- **Card Icon Polish**: No-thumbnail cards, special cards (Settings, Refresh, Browser, Update), and broken-image cards now use consistent 1.5× scaled icons with CENTER_INSIDE scaling for proper alignment.
- **Adaptive Scrolling Badge Ticker**: Card metadata badges (Resolution, Format, Latency, Audio-Only, Sound Channels, Domain Source) are center-aligned if they fit, or smoothly auto-scrolled via a self-reversing ticker animation when focused.
- **Domain Source & Audio Badges**: Shows the host domain name of the stream source and audio configuration (Stereo, 5.1, Mono), enriched with clean vector icons.
- **Guided CSV Setup Screen**: Dedicated TV setup interface (2-column layout) to input remote CSV spreadsheet URLs or Google Sheets IDs, featuring real-time verification and persistent storage. Separate titled "About" section and 2-column Utilities. Close button properly aligned with content margins.

### ⚡ Smart Live Stream Detection & Pre-check
- **Intelligent URL Resolver**: Automatically resolves shortened links (tinyurl, bit.ly, etc.) and redirect links.
- **Real-Time Focused Card Refresh**: Hovering focus over a grid card automatically queries fresh network metrics (latency, format, status). Uses a **network back-off schedule** (10-second initial update, transitioning to 1-minute cycles) to prevent server overload.
- **Thread-Safe Sniffing Buffer**: Optimizes GET sniff requests by reading a maximum of **8KB** from infinite live stream sockets and closing the connection immediately, preventing network thread locks.

### 🔄 App Updates
- **Auto Version Check on Startup**: Fetches latest release from GitHub on app launch (no settings toggle).
- **Update Card**: If a newer version is available, an "Update available vX.Y.Z" card appears in the Settings row next to the Browser card.
- **One-Click Update**: Clicking the card downloads the APK (in background) and automatically launches the system installer when complete. Prevents duplicate downloads if one is in progress.

### 📼 Advanced Native Playback (ExoPlayer)
- **Multi-Format Streaming**: Built-in support for HLS playlists (`.m3u8`), progressive MPEG-TS raw streams, MP4, and RTMP.
- **Auto-Format Detection**: Uses `DefaultMediaSourceFactory` to automatically detect stream containers and extract progressive feeds (like raw `application/octet-stream` broadcasts) natively.
- **Stalled Decoder & Black Screen Auto-Recovery**: Monitors rendering frames. If the decoder stalls on an active stream with no output for ~12 seconds (6 checks), the player performs a lightweight re-prepare (stop + re-set source) without full release. The UI stays visible with no loading flash or "reloading" toast.
- **Audio-Only Mode Visual Card**: Plays radio/audio-only feeds and shows a custom graphic visualizer card instead of a blank black screen.
- **SSL Error Workaround**: Safely bypasses SSL handshake errors to support playback of streams with expired/self-signed certificates on older devices.
- **Optimized Buffering**: Customizable buffering controls (min 60s, max 120s) for robust network streaming.

### 🌐 Interactive WebView Web Engine
- **Dedicated Browser Card**: A special card in the main UI for quick access to the in-app browser.
- **TV Pointer Navigation**: Dedicated D-pad pointer control that automatically snaps to the nearest clickable element. Improved behavior (toolbar no longer forces visibility on load for better content interaction).
- **WebView Action Toolbar**: Toggleable toolbar (Back, Forward, Reload, Home, Close, Desktop Mode, Auto-Fullscreen) with navigation button snapping. Adblock toggle uses Speaker Notes / Speaker Notes Off icons.
- **Security Redirect Alert**: Prompts the user with a confirmation dialog before allowing external webpage redirects.
- **Clean WebView Termination**: Automatically destroys web views, clears caches/cookies, and frees resources upon closing.
- **Screensaver Prevention**: Keeps the display active during WebView video streams.

## Screenshots
![tv](https://github.com/user-attachments/assets/56ccfd78-cef4-4b93-8a2f-e0064c0f3557)


## Prerequisites

- **Android Device**: An Android TV device or emulator running Android 5.0 (API 21) or higher. Tested on Amazon Fire 7 tablet (Fire OS 5.x).
- **Android Studio**: Version 2023.1.1 or later.
- **Internet Connection**: Required for fetching remote CSV files and streaming videos.

## Installation

### 1. Clone the Repository
Clone the project from GitHub:

```bash  
git clone https://github.com/<your-username>/android-csv-tv.git
cd android-csv-tv
```  

### 2. Build and Run
- Connect an Android TV device or start an emulator.
- Build and run the app:
```bash  
  ./gradlew assembleDebug adb install -r app/build/outputs/apk/debug/app-debug.apk
```  
### Compiled Version
A compiled version of the app is available for download at: [Release](https://github.com/mingminghome/android-csv-tv/releases/).

## Usage

### 1. Prerequisites for Using Google Sheets
- **Publish the Sheet**: In Google Sheets, go to **File > Share > Publish to web**.
- **Choose CSV Format**: Select "Comma-separated values (.csv)" as the format.
- **Get the Publish ID**: Copy the publish ID from the URL. The URL will look like `https://docs.google.com/spreadsheets/d/e/<publish-id>/pub?...`. The `<publish-id>` is the part between `/d/e/` and `/pub`.

### 2. For CSV URL
- Directly input the CSV link (e.g., `https://example.com/videos.csv`) in the settings screen.

### 3. Configure a Custom Sheet Link
- Navigate to the "Settings" row in the main screen.
- Select the "Settings" item to open the `SetupActivity`.
- Enter a Google Sheets publish ID (e.g., `1a2b3c4d5e6f7g8h9i0j`) or a direct CSV URL (e.g., `https://docs.google.com/spreadsheets/d/e/1a2b3c4d5e6f7g8h9i0j/pub?gid=0&single=true&output=csv`).
- Click the "Save" button.
- The app will validate the sheet link:
    - If valid, you’ll see a toast: `Sheet loaded successfully with X videos.`
- If invalid or no link is set, the app shows only the Browser, Refresh, and Settings cards (in the Settings row). If an update is available, an "Update available vX.Y.Z" card is also shown next to Browser.

### 4. Play Videos or Load Webpages
- Browse the video groups on the main screen.
- Select a video to play it in the `PlaybackFragment`, or load a webpage if the URL points to a web resource.
- Use the pointer to control the video player (e.g., play, pause, seek).

## CSV Format

The CSV file (provided via remote URL, e.g. Google Sheets or your own server) must have the following columns in this order:
- `groupName`: The group/category of the video (e.g., "Movies", "Live TV").
- `title`: The title of the video or webpage.
- `url`: The URL of the video stream (e.g., `.m3u8`, `.mp4`, `rtmp://`) or webpage (e.g., `https://example.com`).
- `thumbnailUrl`: (Optional) URL of the video thumbnail.

Example CSV:

    groupName,title,url,thumbnailUrl  
    Video,Big Buck Bunny,https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8,https://vz-48f70360-cc0.b-cdn.net/003223b9-e5e4-4047-8afd-7659d39924bd/thumbnail_8bbe7aa2.jpg  
    Web,Big Buck Bunny@Wiki,https://en.wikipedia.org/wiki/Big_Buck_Bunny, 



## Project Structure

- **`MainActivity.kt`**: The main entry point of the app, hosting the `MainFragment`.
- **`MainFragment.kt`**: Displays the Leanback UI with video rows (including Browser and Update cards) and a settings option.
- **`SetupActivity.kt`**: Allows users to specify a custom CSV URL or Google Sheets ID. Features a 2-column layout, separate About section, and 2-column Utilities.
- **`PlaybackFragment.kt`**: Handles video playback using ExoPlayer (with improved stall recovery).
- **`WebViewFragment.kt`**: Powers the in-app browser with pointer navigation, toolbar, and adblock.
- **`CardPresenter.kt`**: Custom card rendering (1.5× icons for no-thumbnail/special/broken cards).
- **`Utils.kt`**: Utility functions for fetching/parsing CSV data, version checking, and URL resolution.

## Known Issues

- **SSL Certificate Errors on Older Devices**:
    - Older devices (e.g., Fire 7 tablet running Fire OS 5.x) may encounter SSL errors (`Trust anchor for certification path not found`) when playing HTTPS streams due to an outdated certificate store.
    - A temporary workaround is implemented in `PlaybackFragment.kt` to bypass SSL validation (not recommended for production).
        - **Solution**: Test on a modern device (Android 9 or later), or host streams on a server with a certificate trusted by older devices.

- **Network Dependency**:
    - The app requires an internet connection to fetch remote CSV files and stream videos.

## Contributing

Contributions are welcome! To contribute:

1. Fork the repository.
2. Create a new branch:
```bash
  git checkout -b feature/your-feature-name
```
3. Make your changes and commit them:
```bash
  git commit -m "Add your feature description"
```
4. Push to your fork:
```bash
  git push origin feature/your-feature-name
```
5. Open a pull request on GitHub.

Please ensure your code follows the project’s coding style and includes appropriate tests.

## License

This project is licensed under the GNU General Public License v3 (GPLv3). See the LICENSE file for details.

## Support the Project

If you find this app useful, consider supporting its development by buying me a pint!

<a href="https://buymeacoffee.com/mingminghomework"><img src="https://img.buymeacoffee.com/button-api/?text=Buy me a pint&emoji=🍺&slug=mingminghomework&button_colour=5F7FFF&font_colour=ffffff&font_family=Cookie&outline_colour=000000&coffee_colour=FFDD00" alt="Buy me a pint"></a>


## Acknowledgments

- [Android Leanback Library](https://developer.android.com/training/tv/start/layouts) for the TV-friendly UI.
- [ExoPlayer (Media3)](https://github.com/androidx/media) for video playback.
- [OpenCSV](https://opencsv.sourceforge.net/) for CSV parsing.
- [OkHttp](https://square.github.io/okhttp/) for HTTP requests.

## Contact

For questions or feedback, please open an issue on GitHub.
