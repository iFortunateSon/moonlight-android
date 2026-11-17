# Moonlight Surround

This fork encodes PCM 5.1 into AC-3 or E-AC-3 on Android TV without root.
[Issue #1](https://github.com/iFortunateSon/moonlight-android/issues/1) tracks
implementation, review fixes, testing and releases. The existing upstream
request is [#1095](https://github.com/moonlight-stream/moonlight-android/issues/1095).
Public APK distribution remains blocked by the review findings. Draft releases
are for the repository owner and are not ready for public distribution.

## Branches and upstream requirements

`master` preserves upstream v12.2 (`b48494cb`). `surround` is based on v12.1
(`1b1b100e`), which was used for the earlier device tests. The v12.2 port is
pending in issue #1. Unmodified v12.2 was built and linted on the dedicated
VM; that baseline does not include this encoder.

No separate CONTRIBUTING.md or PR template was found in the inspected
upstream tree. Preserve its style, history, recursive submodules and the
explicit applicationId requirement in app/build.gradle. The release suffix
`.unofficial` remains. Java namespace `com.limelight` stays for JNI. The root
flavor is outside this TV test scope. The first ABI is `armeabi-v7a` with
NEON; this build makes no arm64 or 16 KB page compatibility claim.

## Audio path

Sunshine sends Opus 5.1. The pinned moonlight-common-c converts its mapping
to FL, FR, C, LFE, BL, BR; JNI supplies interleaved PCM16. `Game` selects
`AdaptiveAudioRenderer`. The original `AndroidAudioRenderer` is unchanged.
Stereo and unsupported configurations use that original PCM renderer.

`PcmFrameAccumulator` collects 1536 samples per channel. `Ac3AudioRenderer`
sends complete frames to a worker with a one-frame queue. `Ac3Encoder`
converts PCM16 into planar float with `AV_CHANNEL_LAYOUT_5POINT1_BACK`.
Libavcodec performs AC-3 bitstream channel ordering. Center and LFE remain
separate. AC-3 carries 3/2+LFE without separate side/back speaker labels.

Both codecs use 48 kHz, 640 kbit/s and 32 ms frames. E-AC-3 does not reduce
the frame duration at these settings. The production path writes no files.
Only libavcodec/libavutil and the two encoders are included; the app does
not launch an FFmpeg process. A setup/write failure falls back on the next
PCM callback. The stop/fallback race is still tracked in issue #1.

`Ac3Track` tries raw AC3/EAC3 with a 5.1 mask, offload 5.1, then offload with
a stereo carrier mask. The carrier mask does not change the six-channel
bitstream. On TCL, ordinary AudioTrack creation sometimes failed despite
positive support APIs. Those APIs are diagnostic signals; receiver indication
and individual speaker tests are needed to verify output. IEC61937 is a
debug-probe option and is never wrapped around a raw encoded AudioTrack.

## Build and release process

Use Linux x86_64, JDK 17, Python 3.12+, a C compiler, make, curl, unzip,
ffmpeg and espeak-ng. Android SDK packages: platform 34, build-tools 34.0.0,
NDK 23.2.8568313, with SDK licenses accepted. Gradle 8.2 and AGP 8.2.2 are
pinned. The owner's cloud agent must not host Android builds; use the
dedicated build VM or a standard GitHub-hosted runner.

```sh
git clone --recurse-submodules --branch surround https://github.com/iFortunateSon/moonlight-android.git
cd moonlight-android
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
bash scripts/surround/build.sh
```

The script verifies the FFmpeg checksum, builds minimal libraries, generates
English spoken debug fixtures, runs Java/C tests and the production JNI
encoder on a host JVM with independent CRC/decode/channel-isolation checks,
then builds debug/release APKs and runs lint. Host JNI tests do not validate
Android AudioTrack or ARM execution. APK checks inspect both packaged
manifests and assets: probes and fixtures must appear only in debug.
Release is minified and uses a separate fork signing key.

Debug package: `io.github.ifortunateson.moonlight.surround.debug`.
Release package: `io.github.ifortunateson.moonlight.surround.unofficial`.

`Surround build` runs on `surround` pushes and PRs targeting `surround`, without signing secrets, using a read-only
token. Reports are retained for 7 days; no APK is uploaded while blockers
remain. Actions are pinned to commit SHAs. Public CI has no private runner,
ADB connection or access to the owner's home infrastructure.

`Prepare draft prerelease` runs manually from `surround` only. Repository
secrets: `SURROUND_KEYSTORE_B64` (PKCS12, alias `moonlight-surround`) and
`SURROUND_STORE_PASSWORD` (the same password for store and key). It repeats
build/tests/lint, signs the release APK and creates a draft prerelease only.
It does not publish or install anything automatically. Use a new tag such
as `surround-v12.1-alpha.1`, tied to the exact commit. Existing releases are
not overwritten. Before publishing, resolve issue #1 blockers and record
new APK test results in the release notes.

`scripts/surround/source-bundle.py` requires a clean commit and clean,
initialized recursive submodules. It includes their contents, the exact
FFmpeg archive, build scripts, original licenses/notices and a manifest.
Rebuilding still requires the recorded Maven/Gradle and Android SDK
dependencies over HTTPS. Byte-identical APK reproduction is not claimed.
GitHub's default source.zip omits submodules and FFmpeg.

## Earlier device results and known defects

These results belong to earlier private v12.1 APKs on TCL P745/API30/ARMv7
and Hisense AX5140Q over eARC. The owner accepted ready-made AC-3, live
encoding and the full Sunshine Desktop/game path: six correct speaker
positions and Dolby Digital indication. The E-AC-3 fixture also had correct
positions and Dolby Audio indication; that label alone does not distinguish
DD from DD+.

Native captures of both codecs passed independent FFmpeg decoding, CRC
and channel-isolation checks. The E-AC-3 paced test ran 240.62 s/7500 frames
with dropped 0, queue 0, underrun 0, encodeAvg 1.466 ms/max 3.24 ms. These are
software metrics, not end-to-end speaker latency. Full E-AC-3 game output,
lip sync, controllers on the new APK, physical eARC hotplug and the target
of at most 50 ms additional delay remain unverified. Receiver standby is
not classified as an encoder defect.

Review reproduced loss of a 10 ms PCM cue before 100 ms source silence, and
positive partial writes taking 1000 ms despite a 150 ms deadline. Queue age,
stop/fallback races, worker startup and guaranteed release after pause/flush
errors also remain open. Existing unit tests miss those defects. Passing
CI does not mean they are fixed. All work is tracked in the single issue #1.

## Device checks and PCM recovery

```sh
adb install -r moonlight-surround-armeabi-v7a.apk
adb logcat -v threadtime -s MoonlightAC3:I '*:S' > moonlight-surround.log
adb shell dumpsys audio > audio.txt
adb shell dumpsys media.audio_flinger > audio-flinger.txt
```

Select Surround 5.1 and Audio Output Format, then restart the stream.
PCM (Original) restores the original renderer. AC-3/E-AC-3 select their
encoder; Auto attempts AC-3 with a suitable HDMI route. Sunshine and the
game must supply real 5.1. TCL: enable eARC and passthrough; Hisense: select
HDMI eARC. Settings and audio policy vary on other devices.

Spoken tests are available in the debug APK only:

```sh
# Ready-made AC-3, offload with stereo carrier by default:
adb shell am start -n io.github.ifortunateson.moonlight.surround.debug/com.limelight.binding.audio.Ac3ProbeActivity
# Production renderer with live E-AC-3:
adb shell am start -n io.github.ifortunateson.moonlight.surround.debug/com.limelight.binding.audio.RendererProbeActivity --es format eac3
# Direct live encoder probe, E-AC-3:
adb shell am start -n io.github.ifortunateson.moonlight.surround.debug/com.limelight.binding.audio.Ac3ProbeActivity --ez encode true --ez eac3 true
```

Inspect the Activity's actual extras before extending a test. Acceptance
requires codec indication and each voice/subwoofer in the intended speaker,
without duplication, pauses or clicks. Then test PCM2/PCM6, format changes,
restart, games and route loss/return. On failure, choose PCM and restart.
Official Moonlight can run separately; its data is not modified.

## Licenses

Moonlight retains GPLv3 and existing dependency notices. FFmpeg 7.1.1 is
built without GPL/nonfree options, with AC-3/E-AC-3 encoders and required
libavcodec/libavutil components: LGPL-2.1-or-later. Both APKs contain its
license and notice. The matching source archive and configure script
accompany the draft APK. Fork changes are included in the source bundle.
See [FFmpeg legal](https://ffmpeg.org/legal.html). The snapshot also contains upstream's prebuilt Opus/OpenSSL libraries and
headers. Their full source/build provenance must be completed before public
binary distribution; the v12.1 snapshot alone does not establish complete
corresponding source for those existing native dependencies. ENet source is
included through the recursive submodule. This remains in issue #1.
