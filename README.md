# CloudShare

Local, offline, device-to-device file transfer for Android. No server, no cloud,
no account. Built as a real streaming transfer engine — not a UI demo.

## What actually works

- **Streaming pipeline** — 256 KiB chunks, 4 MiB blocks, 64-bit sizes. A 2 GB file
  never touches RAM in full.
- **Integrity** — CRC32 per block, SHA-256 per file. Files are finalized
  (atomic rename) only after the hash matches. Failures are reported, never hidden.
- **Resume** — receiver persists a block bitmap after every block, plus a reference
  to its temp file. Reconnect with the same session id: completed blocks are skipped
  on both sides; the sender re-seeds its digest over the skipped prefix.
- **Security** — TLS 1.3 with a fresh self-signed EC certificate per session; the
  peer pins the certificate's SHA-256 fingerprint obtained via QR payload or the
  short pairing code (~104-bit fingerprint). Session token checked in constant time.
  Incoming manifests require explicit user approval; paths are sanitized against
  traversal; received files are never executed or auto-opened.
- **Discovery** — real Wi-Fi Direct (`WifiP2pManager`) and NSD/DNS-SD on the LAN,
  plus QR/manual pairing fallback. No fake device lists.
- **Web Receiver** — optional temporary HTTP endpoint, token-in-URL, closed when the
  session ends. Browser uploads stream straight to the SAF receive tree.
- **Background** — foreground `dataSync` service; closing the UI never restarts a
  1 GB transfer.
- **Benchmark mode** — generates real files and pushes them through the full engine
  over loopback TCP; measured, not fabricated.

## Build

Open in Android Studio (Hedgehog or newer) and sync — it generates the Gradle
wrapper. Or:

    gradle wrapper        # once, from the project root
    ./gradlew assembleDebug
    ./gradlew testDebugUnitTest

`minSdk 26`, `targetSdk 34`, Kotlin 1.9, Jetpack Compose, Room-free (history is a
local capped JSONL file). Dependencies: BouncyCastle (per-session cert generation),
ZXing (QR), Coroutines, Compose BOM. Every dependency has a concrete job.

## Using it

1. On the receiver: **Receive** → *Start receiving*. A pairing code and
   `cxspair://` payload appear.
2. On the sender: **Send** → pick files → paste the payload (or scan the QR) → Send.
3. The receiver approves the manifest, blocks stream, and each file appears only
   after SHA-256 verification. Pause/resume/cancel are live; a failed transfer
   offers *Retry — resume available*.

## Known limits (honest)

- Bluetooth is intentionally unused — on modern Android it adds little beyond
  discovery, which QR/NSD already cover.
- Programmatic hotspot creation is restricted on recent Android; hotspot mode
  therefore pairs via QR/manual code.
- Loopback benchmark numbers measure the engine, not your radio. Real Wi-Fi
  throughput depends on the link.

## Layout

    core/protocol    binary framing, chunking, bitmap resume, hashes, manifest
    core/security    per-session TLS identity + fingerprint pinning, pairing codes
    core/network     TLS/1.3 TCP transport + loopback transport (tests, benchmark)
    transfer         engine, SAF/filesystem sinks, controller, web receiver
    discovery        Wi-Fi Direct + NSD wrappers
    service          foreground transfer service
    data             settings + local history
    benchmark        real loopback benchmark runner
    ui               Compose screens, themes, Arabic RTL + English LTR strings
