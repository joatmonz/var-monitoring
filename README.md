# Halısaha VAR

An Android tablet app that brings video review (VAR) to amateur five-a-side football.

One operator films the match with a camera whose HDMI output goes into a USB capture
card plugged into an Android tablet. The tablet shows the live picture and always keeps
the last 30 seconds. When there is a disputed moment, the operator presses **REVIEW**,
and those 30 seconds open in a review player built for refereeing decisions: frame by
frame, in slow motion, looped around the moment of contact, zoomed in.

## Hardware

```
Camera (Sony A7S II) ──Micro HDMI→HDMI──▶ HDMI capture card ──USB-C OTG──▶ Android tablet
```

- **Capture card:** USB 2.0 HDMI grabber, MS2130 class (`345f:2130`, "USB3 Video").
  It must stream video in **bulk** mode: Android's public USB API cannot read
  isochronous video. The card also carries the camera's HDMI audio as a USB audio device.
- **Tablet:** any Android 7.0+ (API 24) device with USB host (OTG). Tested on a Lenovo
  Tab M10 (TB-X306F, MediaTek MT6765, Android 11) and a Samsung Galaxy S21 Ultra.
- **Camera:** any camera with clean HDMI output at 1080p50. Turn the on-screen info
  overlay off for HDMI, and set power saving to its longest delay.

Use a USB-C adapter or cable that is wired correctly for OTG. Some cheap USB-A → USB-C
adapters are not, and stricter phones then ignore the card entirely.

## Features

**Live screen**
- 1920×1080 at 50 fps live preview.
- Detects a camera that is off or unplugged (no frames, frozen frames or a blank
  "no signal" screen) and shows *No Signal* instead of a stale picture.
- **REC** keeps a rolling buffer of the last 30 seconds. A banner nags when the picture
  is live but nothing is being recorded, and a red frame surrounds the screen while
  recording.
- HDMI audio level meter.
- Reconnects to the card by itself, and keeps an on-device diagnostic log (**LOG** button).

**Review screen**
- Full-resolution 1080p50 playback with exact frame stepping (`◀ Frame` / `Frame ▶`)
  and ±1 s jumps.
- Playback speed from 0.10× to 2× (presets and a slider).
- **MARK** the moment of contact. **IN** / **OUT** set the loop range freely, or the mark
  sets it to ± 0.5–3 s. **LOOP** replays the range.
- Pinch to zoom, decoded from the full-resolution frame.
- Two-level timeline: an overview of the whole clip, and a filmstrip that opens on tap
  for precise scrubbing.
- Timecode shown as `m:ss.ff` (frame within the second), for example `0:14.49 / 0:30.00`.
- Sound in sync with the picture at 1×.
- Clip gallery with delete. Recording continues underneath while you review.
- Tap the picture to hide the controls. With the controls shown, the video shrinks to
  stay clear of them.

## How it works

| Piece | What it does |
|---|---|
| `uvc/UvcCamera` | A minimal UVC driver on the Android USB host API. It parses the descriptors, negotiates MJPEG 1080p50 (probe/commit), reads the bulk endpoint and reassembles frames from UVC payload headers. It also watches for signal loss. Libraries such as libuvc/AUSBC never delivered frames from this bulk-only card. |
| `uvc/UvcPreviewView` | Live preview. Decodes the JPEGs at half resolution on a background thread and detects a flat "no signal" picture. |
| `recording/FrameRingBuffer` | Writes the card's MJPEG frames **untouched** to 1-second chunk files and keeps the last 30 s. Saving a clip just copies those chunks, so there is no re-encoding and no quality loss, and it takes about a second. The tablet's H.264 encoder could not sustain 50 fps, which is why the frames are stored as they arrive. |
| `recording/AudioRingBuffer` | Captures the card's HDMI audio (USB audio input) into a 45 s in-memory ring and cuts it to the clip's exact frame span. |
| `review/MjpegClip`, `ClipAudio` | Indexed, random-access readers for saved clips. |
| `review/ReviewPlayerManager` | The player. Two decoder threads stay ahead of the playhead and a display thread draws to a `SurfaceView`. Every frame is an independent JPEG, so seeking and stepping are exact. |
| `review/ClipAudioPlayer` | Plays the clip's sound following the picture's clock and corrects drift. |
| `diag/DiagnosticLog` | A daily text log of USB, stream, battery and heat events. Shared with the **LOG** button, or read from `Android/data/com.example.varcamera/files/logs/`. |

Measured on the MT6765 tablet: about 49–50 fps from the card at about 107 KB per frame,
which makes a 30-second clip roughly 160–260 MB. Up to 20 clips are kept, and 1 GB of
storage is always left free.

### Clip format

Each clip is a folder `clips/clip_yyyyMMdd_HHmmss/` containing:
- `chunk_NNNNNN.mjpg`: one second of frames, repeated as `[timestamp ns: Long][length: Int][JPEG]`.
- `audio.pcm`: `[magic "VARA"][sample rate][channels][first sample time ns]`, followed by
  16-bit stereo PCM at 48 kHz.

Clips can only be played inside the app. They are not standard video files.

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 34).

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The release build is minified and signed with the debug key, so it can be sideloaded
directly. Install it with `adb install -r app-release.apk`, or copy it to the device and
open it.

## Project status

The feature set above is in use at real matches. Next up is visual design polish.
The audio/video offset at capture time has not been calibrated yet.
