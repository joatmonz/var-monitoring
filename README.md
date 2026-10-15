# VAR Camera

**Video review for amateur football, on an Android tablet.**

VAR Camera turns a camera, an HDMI capture card and an Android tablet into a video
assistant referee system for five-a-side and amateur pitches. It always keeps the last
30 seconds of play. When a moment is disputed, one tap opens those seconds in a review
player built for refereeing decisions: frame by frame, in slow motion, looped around the
moment of contact, zoomed in.

## What you need

| | |
|---|---|
| **Camera** | Any camera or camcorder with a clean HDMI output (1080p, 50 or 60 fps recommended) |
| **Capture card** | A USB HDMI capture card that streams MJPEG over **UVC bulk transfer**, such as the common MS2109 / MS2130-based cards |
| **Tablet or phone** | Android 7.0 or newer with USB host (OTG) support |
| **Cable** | A USB-C OTG adapter or cable that is wired for OTG |

```
Camera ──HDMI──▶ USB capture card ──USB OTG──▶ Android tablet
```

> Cards that stream video isochronously cannot be read through Android's USB API.
> The app detects this and says so.

## Features

### Live
- 1080p live preview at up to 50 fps.
- **Rolling 30-second buffer:** press **REC** once and the app always keeps the last
  30 seconds of play.
- Shows a clear *No Signal* screen when the camera is switched off or unplugged,
  instead of a frozen picture.
- Warns you when the picture is live but nothing is being recorded. While recording, a
  red frame surrounds the screen.
- Audio level meter for the camera's HDMI sound.
- Reconnects to the capture card by itself.

### Review
- **REVIEW** saves the last 30 seconds as a clip in about a second, at the camera's
  original quality. Recording continues in the background.
- Full-resolution playback with exact frame stepping and ±1-second jumps.
- Playback speed from 0.10× to 2×.
- **MARK** the moment of contact, set **IN** and **OUT** points, and **LOOP** the range.
- Pinch to zoom into the full-resolution frame.
- Timeline with a whole-clip overview and a filmstrip for precise scrubbing.
- Frame-accurate timecode, for example `0:14.49 / 0:30.00`.
- Sound in sync with the picture at normal speed.
- Clip gallery with delete.
- Tap the picture to hide the controls. With the controls shown, the video resizes so
  they never cover it.

### Diagnostics
- An on-device log of connection, signal, battery and temperature events. Share it from
  the **LOG** button to troubleshoot problems at the pitch.

## How it works

| Component | Role |
|---|---|
| `uvc/UvcCamera` | A lightweight UVC driver built on the Android USB host API. It negotiates the MJPEG stream, reads it over bulk transfer and detects signal loss. |
| `uvc/UvcPreviewView` | Live preview rendering. |
| `recording/FrameRingBuffer` | Stores the card's MJPEG frames exactly as received in a 30-second rolling buffer. Clips are never re-encoded, so saving is instant and lossless. |
| `recording/AudioRingBuffer` | Captures the HDMI audio from the card's USB audio interface and aligns it to the video frames. |
| `review/ReviewPlayerManager` | Multi-threaded, frame-accurate player for saved clips. |
| `review/ClipAudioPlayer` | Plays a clip's sound in sync with the picture. |
| `diag/DiagnosticLog` | Daily diagnostic log files. |

Every frame is stored as an independent JPEG, so seeking and frame stepping are exact.
A 30-second 1080p50 clip takes roughly 150–250 MB. The app keeps up to 20 clips and
always leaves 1 GB of storage free.

### Clip format

Clips are saved in the app's storage as `clips/clip_yyyyMMdd_HHmmss/`:
- `chunk_NNNNNN.mjpg`: one second of video, stored as repeated
  `[timestamp ns: Long][length: Int][JPEG]` records.
- `audio.pcm`: a short header (`"VARA"`, sample rate, channels, start time), followed by
  48 kHz 16-bit stereo PCM.

## Building

Requires JDK 17 and the Android SDK (API 34).

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## License

All rights reserved.
