# Kiko — Smart Video Compressor

A 100% local, offline Android app that compresses videos (targeting 4K sources) down
to a maximum of 1920x1080 HEVC/H.265 using the device's hardware encoder
(AndroidX Media3 Transformer + MediaCodec). No server, no upload, no account, no
FFmpeg, no software encoding. Built and tuned for the Samsung Galaxy S23 Ultra
(SM-S918B, Android 14).

## Project layout

- `core-logic/` — pure Kotlin/JVM module with the deterministic compression math
  (output resolution, effective FPS, target bitrate, skip rules, output filename
  collision handling). No Android dependency, fully unit tested.
- `app/` — the Android application module (Jetpack Compose UI, Media3 Transformer
  integration, foreground service, MediaStore/Photo Picker I/O).

## Building

```
./gradlew :core-logic:test      # pure-Kotlin algorithm tests, no Android SDK required
./gradlew :app:assembleRelease  # full APK; requires the Android SDK and network access
                                 # to Google's Maven repository (dl.google.com / maven.google.com)
```

See the project's build report (provided by the assistant that generated this code)
for what was actually verified in the sandbox this project was authored in, versus
what still needs to be built/verified in an environment with Android SDK + Google
Maven access.
