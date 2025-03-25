# TV,
![TV, Logo](https://github.com/user-attachments/assets/5a2a2258-a355-49ad-8e8f-bc2579397a0e)

**TV,** is an Android TV application built using the Leanback library, designed to display and play videos from a CSV file. The app allows users to specify a remote CSV file (e.g., a Google Sheets URL) or fall back to a local default CSV file (`default_csv.csv`). It supports various video stream formats and web content, with pointer-based control for an enhanced TV experience.

## Video metadata sourced from
 - Published Google Sheets CSV (No hosting needed)  
 - Your own CSV

## Features

- **Leanback UI**: A TV-friendly interface using Android's Leanback library, with rows of video groups and a settings option.
- **Dynamic Video Source**: Load videos from a remote CSV file (e.g., a published Google Sheets URL) or a local default CSV file.
- **Video Playback**: Supports multiple stream types:
    - HLS (`.m3u8`) using ExoPlayer's HLS support.
    - MP4 (`.mp4`) using ExoPlayer.
    - RTMP (`rtmp://`) using ExoPlayer's RTMP extension.
- **Webpage Loading**: Directly load webpages specified in the CSV file.
- **Pointer Control**: Use a pointer to control the video player, optimized for TV navigation.
- **Settings Screen**: Allows users to specify a custom CSV URL or Google Sheets ID to load videos dynamically.
- **Error Handling**: Gracefully handles invalid CSV URLs by falling back to the default CSV file, with user feedback via toast messages.
- **Buffering Configuration**: Customizable buffering settings for smooth playback on TV devices.

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
git clone https://github.com/<your-username>/android-csv-tv.gitcd android-csv-tv
```  

### 2. Build and Run
- Connect an Android TV device or start an emulator.
- Build and run the app:
```bash  
  ./gradlew assembleDebug adb install -r app/build/outputs/apk/debug/app-debug.apk
```  
### Compiled Version
A compiled version of the app is available for download at: [Release](https://github.com/mingminghome/android-csv-tv/releases/tag/release).

## Usage

### 1. Prerequisites for Using Google Sheets
- **Publish the Sheet**: In Google Sheets, go to **File > Share > Publish to web**.
- **Choose CSV Format**: Select "Comma-separated values (.csv)" as the format.
- **Get the Publish ID**: Copy the publish ID from the URL. The URL will look like `https://docs.google.com/spreadsheets/d/e/<publish-id>/pub?...`. The `<publish-id>` is the part between `/d/e/` and `/pub`.

### 2. For CSV URL
- Directly input the CSV link (e.g., `https://example.com/videos.csv`) in the settings screen.

### 3. Launch the App
- On first launch, the app will load videos from the default CSV file (`res/raw/default_csv.csv`) if no custom sheet link is set.

### 4. Configure a Custom Sheet Link
- Navigate to the "Settings" row in the main screen.
- Select the "Settings" item to open the `SetupActivity`.
- Enter a Google Sheets publish ID (e.g., `1a2b3c4d5e6f7g8h9i0j`) or a direct CSV URL (e.g., `https://docs.google.com/spreadsheets/d/e/1a2b3c4d5e6f7g8h9i0j/pub?gid=0&single=true&output=csv`).
- Click the "Save" button.
- The app will validate the sheet link:
    - If valid, you’ll see a toast: `Sheet loaded successfully with X videos.`
- If invalid, you’ll see a toast: `Invalid sheet link: <error>. Using default CSV file.`

### 5. Play Videos or Load Webpages
- Browse the video groups on the main screen.
- Select a video to play it in the `PlaybackFragment`, or load a webpage if the URL points to a web resource.
- Use the pointer to control the video player (e.g., play, pause, seek).

## CSV Format

The CSV file (remote or local) must have the following columns in this order:
- `groupName`: The group/category of the video (e.g., "Movies", "Live TV").
- `title`: The title of the video or webpage.
- `url`: The URL of the video stream (e.g., `.m3u8`, `.mp4`, `rtmp://`) or webpage (e.g., `https://example.com`).
- `thumbnailUrl`: (Optional) URL of the video thumbnail.

Example `default_csv.csv`:  

    groupName,title,url,thumbnailUrl  
    Video,Big Buck Bunny,https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8,https://vz-48f70360-cc0.b-cdn.net/003223b9-e5e4-4047-8afd-7659d39924bd/thumbnail_8bbe7aa2.jpg  
    Web,Big Buck Bunny@Wiki,https://en.wikipedia.org/wiki/Big_Buck_Bunny, 



## Project Structure

- **`MainActivity.kt`**: The main entry point of the app, hosting the `MainFragment`.
- **`MainFragment.kt`**: Displays the Leanback UI with video rows and a settings option.
- **`SetupActivity.kt`**: Allows users to specify a custom CSV URL or Google Sheets ID.
- **`PlaybackFragment.kt`**: Handles video playback using ExoPlayer.
- **`Utils.kt`**: Utility functions for fetching and parsing CSV data.
- **`res/raw/default_csv.csv`**: The default CSV file used when no valid sheet link is provided.

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

If you find this app useful, consider supporting its development by buying me a coffee!

<a href="https://buymeacoffee.com/mingminghomework"><img src="https://img.buymeacoffee.com/button-api/?text=Buy me a coffee&emoji=&slug=mingminghomework&button_colour=FFDD00&font_colour=000000&font_family=Cookie&outline_colour=000000&coffee_colour=ffffff" alt="Buy Me a Coffee"></a>


## Acknowledgments

- [Android Leanback Library](https://developer.android.com/training/tv/start/layouts) for the TV-friendly UI.
- [ExoPlayer (Media3)](https://github.com/androidx/media) for video playback.
- [OpenCSV](https://opencsv.sourceforge.net/) for CSV parsing.
- [OkHttp](https://square.github.io/okhttp/) for HTTP requests.

## Contact

For questions or feedback, please open an issue on GitHub.
