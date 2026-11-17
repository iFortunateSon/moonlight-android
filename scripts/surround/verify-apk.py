#!/usr/bin/env python3
"""Check the packaged manifest, ABI, notices and diagnostic source sets."""
import os
from pathlib import Path
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1])
variant = sys.argv[2]
assert variant in ('debug', 'release')
aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0/aapt'
badging = subprocess.check_output([aapt, 'dump', 'badging', apk], text=True)
package = 'io.github.ifortunateson.moonlight.surround.' + ('debug' if variant == 'debug' else 'unofficial')
assert "package: name='" + package + "'" in badging, badging.splitlines()[0]
manifest = subprocess.check_output([aapt, 'dump', 'xmltree', apk, 'AndroidManifest.xml'], text=True)
for activity in ('Ac3ProbeActivity', 'RendererProbeActivity'):
    assert (activity in manifest) == (variant == 'debug'), (variant, activity)
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    for fixture in ('channels.ac3', 'channels.eac3', 'channels.pcm'):
        assert ('assets/' + fixture in names) == (variant == 'debug'), (variant, fixture)
    assert 'assets/FFmpeg-COPYING.LGPLv2.1' in names
    assert 'assets/FFmpeg-NOTICE.txt' in names
    assert 'lib/armeabi-v7a/libmoonlight-ac3.so' in names
    assert not any(p.startswith(('lib/arm64-v8a/', 'lib/x86/', 'lib/x86_64/')) for p in names)
    if variant == 'release':
        for name in names:
            if name.endswith('.dex'):
                code = archive.read(name)
                assert b'Ac3ProbeActivity' not in code and b'RendererProbeActivity' not in code
print('PASS:', variant, package, 'ARMv7; packaged manifest/assets/notices')
