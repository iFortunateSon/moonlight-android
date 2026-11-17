#!/usr/bin/env python3
"""Bundle the exact tracked source, recursive submodules and FFmpeg source."""
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

root = Path(__file__).resolve().parents[2]
def require_clean(path):
    status = subprocess.check_output(
        ['git', 'status', '--porcelain', '--untracked-files=all'], cwd=path, text=True)
    if status:
        raise SystemExit('Tracked changes or unexpected untracked files: ' + str(path))

require_clean(root)
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
modules = []
for line in subprocess.check_output(['git', 'submodule', 'status', '--recursive'], cwd=root, text=True).splitlines():
    match = re.fullmatch(r' ([0-9a-f]{40}) ([^ ]+)(?: \(.*\))?', line)
    if not match:
        raise SystemExit('Submodules must match their recorded commits: ' + line)
    sha, path = match.groups()
    require_clean(root / path)
    modules.append({'path': path, 'commit': sha})
ffmpeg = root / '.build/downloads/ffmpeg-7.1.1.tar.xz'
digest = hashlib.sha256(ffmpeg.read_bytes()).hexdigest()
assert digest == '733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1'
manifest = {
    'repository': 'https://github.com/iFortunateSon/moonlight-android',
    'commit': commit,
    'upstream_base': '1b1b100e63e8ffcf1496e324ff7b57ee9f2db2a9',
    'submodules': modules,
    'ffmpeg': {'version': '7.1.1', 'sha256': digest,
               'configuration': 'scripts/surround/build-ffmpeg.sh',
               'license': 'LGPL-2.1-or-later; no GPL/nonfree options'},
    'android': {'abi': 'armeabi-v7a', 'min_sdk': 21, 'compile_sdk': 34,
                'ndk': '23.2.8568313', 'build_tools': '34.0.0',
                'jdk': '17', 'gradle': '8.2', 'agp': '8.2.2'},
    'scope': 'Experimental v12.1 prototype; not hardware acceptance of a new APK',
    'source_closure': 'App/submodules/FFmpeg included; full legacy Opus/OpenSSL source provenance pending',
    'apk_sha256': hashlib.sha256((root / '.build/release/moonlight-surround-armeabi-v7a.apk').read_bytes()).hexdigest(),
    'signing_certificate_sha256': hashlib.sha256(subprocess.check_output(
        ['openssl', 'x509', '-in', 'docs/signing-certificate.pem', '-outform', 'DER'], cwd=root)).hexdigest(),
}
output = root / '.build/release'
output.mkdir(parents=True, exist_ok=True)
manifest_path = output / 'build-manifest.json'
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
with tempfile.TemporaryDirectory(prefix='moonlight-source-') as temporary:
    stage = Path(temporary) / 'moonlight-surround-source'
    app = stage / 'moonlight-android'
    for path, destination in [(root, app)] + [(root / m['path'], app / m['path']) for m in modules]:
        data = subprocess.check_output(['git', 'archive', '--format=tar', 'HEAD'], cwd=path)
        destination.mkdir(parents=True, exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            archive.extractall(destination, filter='data')
    shutil.copy2(ffmpeg, stage / ffmpeg.name)
    shutil.copy2(manifest_path, stage / manifest_path.name)
    (stage / 'BUILD.txt').write_text(
        'Install JDK 17, Android SDK 34/build-tools 34.0.0/NDK 23.2.8568313, '
        'make, C compiler, Python 3, curl, ffmpeg, espeak-ng, unzip.\n'
        'Accept Android SDK licenses. Set JAVA_HOME and ANDROID_HOME.\n'
        'cd moonlight-android\nmkdir -p .build/downloads\n'
        'cp ../ffmpeg-7.1.1.tar.xz .build/downloads/\n'
        'bash scripts/surround/build.sh\n'
        'The build resolves the recorded Maven/Gradle/Android dependencies over HTTPS.\n'
        'See docs/surround.md for known defects, licenses and signing.\n')
    with tarfile.open(output / 'moonlight-surround-source.tar.gz', 'w:gz') as archive:
        archive.add(stage, arcname=stage.name)
print('PASS: source snapshot and manifest for', commit, '; legacy dependency source closure pending')
