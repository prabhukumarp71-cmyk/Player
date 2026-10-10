# Player

A high-performance Android video recording and playback application built with Jetpack Compose and CameraX.

## Features

- **Real Hardware Video Resolutions**:
  - 4K UHD (3840×2160)
  - 1080p FHD (1920×1080)
  - 720p HD (1280×720)
  - Hardware sensor encoder validation directly through CameraX `Recorder` and `QualitySelector`.
  - Stamped container metadata verification (resolution, bitrate, file size).

- **Optical Far Only Mode**:
  - Locks lens focus directly to optical infinity (`CONTROL_AF_MODE_OFF` with `LENS_FOCUS_DISTANCE = 0.0f` via Camera2 Interop).
  - Bypasses close foreground subjects to keep distant objects crisp and in focus.
  - Viewfinder reticle overlay HUD with infinity badge (`[ ∞ ]`).

- **Foreground Recording ("Player")**:
  - `PlayerRecordingService` runs in the foreground during recording.
  - Swipe-to-dismiss notification: when swiped away, recording continues uninterrupted without bringing the notification back.
  - Zero post-save notifications after recording finishes.

- **Direct Local Storage Saving**:
  - Videos are automatically written to `Movies/Player` in device storage (`MediaStore.Video.Media`).
  - Integrated local video player with interactive playback, scrub bar, sharing, and clip deletion.

- **Minimalist Aesthetic**:
  - Dark studio viewfinder UI without brand logos or watermarks.

---

## Building the Project

### Building on GitHub (GitHub Actions)
A pre-configured GitHub Actions workflow is included in `.github/workflows/android.yml`.
When you push to `main` or `master`, GitHub Actions will:
1. Set up JDK 17
2. Decode the included `debug.keystore.base64` into `debug.keystore`
3. Execute unit tests (`./gradlew testDebugUnitTest`)
4. Build the APK (`./gradlew assembleDebug`)
5. Upload the compiled APK as a downloadable artifact: **`Player-Debug-APK`**

### Building Locally
1. Clone the repository:
   ```bash
   git clone <your-repo-url>
   cd player
   ```

2. Decode the debug keystore (required for `debugConfig` signing):
   ```bash
   # On macOS / Linux:
   base64 -d debug.keystore.base64 > debug.keystore

   # On Windows (PowerShell):
   [IO.File]::WriteAllBytes("debug.keystore", [Convert]::FromBase64String((Get-Content "debug.keystore.base64")))
   ```

3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   The APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

4. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```
