#!/usr/bin/env python3
"""Generate our spoken six-channel diagnostic assets on the build VM."""
import array
import math
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(sys.argv[1]).resolve()
main = root / 'app/src/debug/assets'
debug = root / 'app/src/debug/assets'
main.mkdir(parents=True, exist_ok=True)
debug.mkdir(parents=True, exist_ok=True)
labels = ['Front left', 'Front right', 'Center channel',
          'Subwoofer', 'Surround left', 'Surround right']
audio = array.array('h', [0]) * (48000 * 24 * 6)
with tempfile.TemporaryDirectory(prefix='moonlight-voices-') as temporary:
    for channel, label in enumerate(labels):
        if channel == 3:
            samples = [int(3900 * math.sin(2 * math.pi * 60 * i / 48000)) for i in range(144000)]
        else:
            wav = pathlib.Path(temporary) / f'{channel}.wav'
            subprocess.run(['espeak-ng', '-v', 'en-us', '-s', '125', '-w', str(wav),
                            label + '. ' + label + '.'], check=True)
            raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(wav),
                                           '-ar', '48000', '-ac', '1', '-f', 's16le', 'pipe:1'])
            samples = array.array('h')
            samples.frombytes(raw)
            if sys.byteorder != 'little':
                samples.byteswap()
            samples = samples[:192000]
            peak = max(abs(x) for x in samples) or 1
            samples = [int(x * 9500 / peak) for x in samples]
        for i, value in enumerate(samples):
            audio[(channel * 192000 + i) * 6 + channel] = value
    if sys.byteorder != 'little':
        audio.byteswap()
    pcm = debug / 'channels.pcm'
    pcm.write_bytes(audio.tobytes())
    for codec in ('ac3', 'eac3'):
        subprocess.run(['ffmpeg', '-y', '-v', 'error', '-f', 's16le', '-ar', '48000',
                    '-ac', '6', '-channel_layout', '5.1', '-i', str(pcm),
                    '-c:a', codec, '-b:a', '640k', '-f', codec, str(main / ('channels.' + codec))], check=True)
