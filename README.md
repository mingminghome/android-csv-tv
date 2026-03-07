# TV,
![TV, Logo](https://github.com/user-attachments/assets/5a2a2258-a355-49ad-8e8f-bc2579397a0e)

**TV,** is a lightweight, robust Android TV application designed to turn any CSV-based link list (like Google Sheets) into a professional streaming interface. Built with the Leanback library and Media3 (ExoPlayer), it handles everything from standard video files to complex, dynamically-generated IPTV streams.

## Key Features

- **Leanback UI**: A 10-foot user interface optimized for remote control navigation.
- **Dynamic Source Management**:
  - **Google Sheets Integration**: Use a simple spreadsheet as your backend. Publish to CSV and the app handles the rest.
  - **Live Validation**: Intelligent link checking with automatic fallback to local `default_csv.csv`.
- **Advanced Headless Extraction**:
  - **Auto-Detection**: Automatically identifies video streams within regular web pages.
  - **Dynamic Interception**: Uses a background headless engine to "click" play buttons and intercept hidden `.m3u8` or `.flv` links.
  - **PHP Injector Support**: Specifically optimized to resolve streams from dynamic PHP scripts and IPTV injectors.
- **Next-Gen Playback**:
  - **Format Support**: HLS (M3U8), DASH (MPD), FLV, MP4, RTMP, and raw MPEG-TS.
  - **Quality Control**: Manual quality selection (1080p, 720p, 480p) or "Auto" bitrate switching.
  - **Deep Sniffing**: Content-based format detection (HLS/TS) that works even when file extensions are missing.
  - **Bypassing Blocks**: Custom User-Agent rotation to prevent server-side blocking of the player.
- **Robust Reliability**:
  - **Smart Retry**: Automatic HLS fallback and connection retry mechanism.
  - **Unified UI**: Centered, consistent loading and error states for a polished feel.
  - **SSL Legacy Support**: Workarounds for SSL errors on older Android/FireOS devices.

## Screenshots
![tv](https://github.com/user-attachments/assets/56ccfd78-cef4-4b93-8a2f-e0064c0f3557)

## Installation

### Prerequisites
- **Device**: Android TV or Android 5.0+ (Tested on FireOS and Android 14).
- **Network**: Internet access required for remote CSV and streaming.

### Build from Source
```bash  
git clone https://github.com/mingminghome/android-csv-tv.git
cd android-csv-tv
./gradlew assembleDebug
```  

## Usage

### 1. Preparing your Google Sheet
1. Open your sheet and ensure columns are: `groupName`, `title`, `url`, `thumbnailUrl`.
2. Go to **File > Share > Publish to web**.
3. Select **Comma-separated values (.csv)** and click Publish.
4. Copy the ID from the URL (the long string between `/d/e/` and `/pub`).

### 2. Configure the App
- Open **Settings** within the app.
- Paste your **Google Sheet ID** or a direct **CSV URL**.
- Select your preferred **Video Quality** (Auto is recommended for most users).
- Click **Save and Continue**.

### 3. CSV Format Example
| groupName | title | url | thumbnailUrl |
| :--- | :--- | :--- | :--- |
| News | Live News | `https://example.com/stream.m3u8` | `https://img.link/1.jpg` |
| Web | Web Player | `https://streaming-site.com/player.php?id=123` | `https://img.link/2.jpg` |

## Project Structure
- **`PlaybackFragment.kt`**: The core player engine using Media3.
- **`Utils.kt`**: The extraction engine (Jsoup + Headless WebView + OkHttp Sniffing).
- **`SetupActivity.kt`**: Configuration and quality settings.
- **`MainFragment.kt`**: The Leanback catalog interface.

## Support & Contributing
Feel free to open an issue or submit a pull request for new features or bug fixes.

If this project helps you, consider supporting its development:

<a href="https://buymeacoffee.com/mingminghomework"><img src="https://img.buymeacoffee.com/button-api/?text=Buy me a coffee&emoji=&slug=mingminghomework&button_colour=FFDD00&font_colour=000000&font_family=Cookie&outline_colour=000000&coffee_colour=ffffff" alt="Buy Me a Coffee"></a>

## License
Licensed under the GNU General Public License v3 (GPLv3).
