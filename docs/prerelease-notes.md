Experimental Moonlight Android with AC-3/E-AC-3 5.1 output.

This is a private draft for the repository owner. Public APK distribution is
blocked by the review findings in issue #1, including loss of short sounds
before source silence. Do not publish this draft until those defects are
fixed and the replacement APK has been tested.

The v12.1 base preserves the hardware-tested prototype for comparison.
The v12.2 port is pending. Earlier device results do not validate this APK:
its package, source sets, minified release variant and signing key changed.

ARMv7 with NEON; Android 5.0+ for PCM, Android 10+ for Dolby output.
48 kHz, six independent channels, 640 kbit/s, 1536 samples/32 ms per frame.
Stereo uses the original PCM renderer. Restart the stream after changing
Audio Output Format. Auto attempts AC-3 under suitable conditions.

Co-installs with official Moonlight and the earlier private test apps.
Release package: `io.github.ifortunateson.moonlight.surround.unofficial`.
Pair this app with Sunshine separately.

Assets include an APK, SHA256SUMS, native symbols, build manifest and a
source snapshot containing recursive submodules, exact FFmpeg
7.1.1 source and build instructions. Moonlight: GPLv3; minimal FFmpeg:
LGPL-2.1-or-later. Signing keys and credentials are excluded.

Pending: review fixes, a new device test matrix, end-to-end speaker latency,
lip sync, full E-AC-3 game streaming and physical eARC disconnect/reconnect.

The legacy Opus/OpenSSL prebuilt libraries are preserved from upstream v12.1.
Their complete source/build provenance is a public-release requirement still
tracked in issue #1; this snapshot is not a complete dependency source closure.
