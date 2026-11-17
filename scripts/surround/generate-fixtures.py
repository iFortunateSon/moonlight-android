#!/usr/bin/env python3
"""Generate English neural speech for debug assets; run with the prepared TTS venv."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import wave

import numpy as np
import onnxruntime as ort
from piper import PiperVoice, SynthesisConfig
from piper.config import PiperConfig

RATE = 48000
SLOT = 4 * RATE
CHANNELS = ('FL', 'FR', 'C', 'LFE', 'BL', 'BR')
LABELS = ('Front left', 'Front right', 'Center', None, 'Rear left', 'Rear right')
REVISION = 'e9ab671f2a0e50a701b34361136ba3e6a3a9eb0c'
MODEL_SHA256 = '5d4f08ba6a2a48c44592eed3ce56bf85e9de3dd4e20df90541ae68a8310c029a'
CONFIG_SHA256 = '7e1f4634af596d83cca997fb7a931ba80b70f8a316a2655ee69c55365e0ace14'
CARD_SHA256 = '289f7421072d689a3d91f6c632f486af46f9b8b04417536104a4ea667b8a394b'
WHEEL_SHA256 = '25b4d3f31ff70c8fa7151908e00aaa5650cbdf16bca8fcf21299f3941b89a7d3'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_encoded(path, codec):
    info = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_entries',
        'stream=codec_name,sample_rate,channels,bit_rate,duration', '-of', 'json', str(path),
    ]))['streams'][0]
    assert info['codec_name'] == codec and info['channels'] == 6
    assert int(info['sample_rate']) == RATE and int(info['bit_rate']) == 640000
    assert abs(float(info['duration']) - 24.0) < 0.01 and path.stat().st_size == 750 * 2560
    decoded = subprocess.check_output([
        'ffmpeg', '-nostdin', '-v', 'error', '-err_detect', 'crccheck+explode',
        '-i', str(path), '-c:a', 'pcm_f32le', '-f', 'f32le', 'pipe:1',
    ])
    audio = np.frombuffer(decoded, dtype='<f4').reshape((-1, 6))
    assert len(audio) == RATE * 24
    for channel, name in enumerate(CHANNELS):
        # Skip transition/padding regions; preserve six distinct speaker positions.
        start = int((channel * 4 + 0.5) * RATE)
        end = int((channel * 4 + 3.5) * RATE)
        rms = np.sqrt(np.mean(audio[start:end].astype(np.float64) ** 2, axis=0))
        assert rms[channel] > 0.01, (codec, name, rms)
        assert all(value < 0.0001 for other, value in enumerate(rms) if other != channel), \
            (codec, name, rms)
    print('PASS:', codec, '48kHz/640kbit/s/6ch/24s, CRC/decode and six isolated lanes')


def generate(root, output):
    models = root / '.build/tts/models'
    model = models / 'en_US-ljspeech-high.onnx'
    config = models / 'en_US-ljspeech-high.onnx.json'
    assert sha256(model) == MODEL_SHA256, 'Cached model failed checksum'
    assert sha256(config) == CONFIG_SHA256, 'Cached config failed checksum'
    assert sha256(models / 'MODEL_CARD') == CARD_SHA256, 'Cached model card failed checksum'
    assert importlib.metadata.version('piper-tts') == '1.8.0'
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.enable_cpu_mem_arena = False
    voice = PiperVoice(
        config=PiperConfig.from_dict(json.loads(config.read_text())),
        session=ort.InferenceSession(str(model), sess_options=options,
                                     providers=['CPUExecutionProvider']),
    )
    settings = SynthesisConfig(length_scale=1.05, noise_scale=0.5,
                               noise_w_scale=0.333, normalize_audio=True)
    audio = np.zeros((SLOT * 6, 6), dtype='<i2')
    phrases = []
    for channel, (name, label) in enumerate(zip(CHANNELS, LABELS)):
        if label is None:
            count = RATE * 3
            samples = np.sin(2.0 * np.pi * 60.0 * np.arange(count) / RATE) * 3900.0
            fade = int(RATE * 0.02)
            envelope = np.linspace(0.0, 1.0, fade)
            samples[:fade] *= envelope
            samples[-fade:] *= envelope[::-1]
            mono = np.rint(samples).astype('<i2')
            audio[channel * SLOT + RATE // 4:channel * SLOT + RATE // 4 + count, channel] = mono
            phrases.append({'channel': name, 'text': '60 Hz LFE tone', 'repeats': 1})
            continue
        native = output / f'{name}.wav'
        with wave.open(str(native), 'wb') as wav:
            voice.synthesize_wav(label + '.', wav, syn_config=settings)
        raw = subprocess.check_output([
            'ffmpeg', '-nostdin', '-v', 'error', '-i', str(native), '-ar', str(RATE),
            '-ac', '1', '-c:a', 'pcm_s16le', '-f', 's16le', 'pipe:1',
        ])
        mono = np.frombuffer(raw, dtype='<i2').astype(np.float64)
        assert len(mono) > RATE // 5 and np.max(np.abs(mono)) > 0, name
        mono = np.rint(mono * (12000.0 / np.max(np.abs(mono)))).astype('<i2')
        leading, gap, trailing = RATE // 4, int(RATE * 0.4), RATE // 4
        repeats = 2 if leading + 2 * len(mono) + gap + trailing <= SLOT else 1
        assert leading + repeats * len(mono) + (repeats - 1) * gap + trailing <= SLOT, \
            f'Utterance exceeds its slot; do not truncate: {name}'
        offset = channel * SLOT + leading
        for _ in range(repeats):
            audio[offset:offset + len(mono), channel] = mono
            offset += len(mono) + gap
        phrases.append({'channel': name, 'text': label,
                        'duration_s': len(mono) / RATE, 'repeats': repeats})
        print(name, label, f'{len(mono) / RATE:.3f}s', f'repeats={repeats}')
    for channel, name in enumerate(CHANNELS):
        slot = audio[channel * SLOT:(channel + 1) * SLOT]
        assert np.count_nonzero(slot[:, channel]) > RATE // 5
        assert not np.any(slot[:, [other for other in range(6) if other != channel]])
        assert np.max(np.abs(slot.astype(np.int32))) <= 12000
        print('PASS: isolated PCM', name)
    pcm = output / 'channels.pcm'
    pcm.write_bytes(audio.tobytes())
    assert pcm.stat().st_size == RATE * 24 * 6 * 2
    for codec in ('ac3', 'eac3'):
        encoded = output / f'channels.{codec}'
        subprocess.run([
            'ffmpeg', '-nostdin', '-v', 'error', '-f', 's16le', '-ar', str(RATE),
            '-ac', '6', '-channel_layout', '5.1', '-i', str(pcm),
            '-c:a', codec, '-b:a', '640k', '-f', codec, str(encoded),
        ], check=True)
        verify_encoded(encoded, codec)
    return {
        'build_time_only': True,
        'engine': {'name': 'piper-tts', 'version': '1.8.0', 'wheel_sha256': WHEEL_SHA256,
                   'source': 'https://github.com/OHF-Voice/piper1-gpl/tree/v1.8.0',
                   'license': 'GPL-3.0; generation tool, not packaged in APK'},
        'voice': {'name': 'en_US-ljspeech-high', 'hf_revision': REVISION,
                  'model_sha256': MODEL_SHA256, 'config_sha256': CONFIG_SHA256,
                  'card_sha256': CARD_SHA256,
                  'card': f'https://huggingface.co/rhasspy/piper-voices/blob/{REVISION}/en/en_US/ljspeech/high/MODEL_CARD',
                  'dataset': 'https://keithito.com/LJ-Speech-Dataset/',
                  'dataset_license': 'Public domain; voice repository metadata declares MIT'},
        'runtime_packages': {name: importlib.metadata.version(name)
                             for name in ('piper-tts', 'onnxruntime', 'numpy', 'pathvalidate')},
        'sample_rate': RATE, 'sample_format': 's16le', 'channels': list(CHANNELS),
        'duration_s': 24, 'slot_duration_s': 4, 'phrases': phrases,
        'speech_peak': 12000, 'lfe_peak': 3900,
        'sha256': {f'channels.{kind}': sha256(output / f'channels.{kind}')
                   for kind in ('pcm', 'ac3', 'eac3')},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, help='Moonlight checkout root')
    args = parser.parse_args()
    root = args.root.resolve()
    build = root / '.build'
    build.mkdir(parents=True, exist_ok=True)
    assets = root / 'app/src/debug/assets'
    assets.mkdir(parents=True, exist_ok=True)
    # Each run gets a fresh directory, so a successful prior build is reusable.
    # Do not replace APK inputs until all new PCM/codec checks have passed.
    with tempfile.TemporaryDirectory(prefix='neural-fixtures-', dir=build) as temporary:
        output = Path(temporary)
        manifest = generate(root, output)
        for name, expected in manifest['sha256'].items():
            shutil.copyfile(output / name, assets / name)
            assert sha256(assets / name) == expected
        (build / 'tts-fixture-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('PASS: neural English debug fixtures, 24s/48kHz/PCM16/5.1-back; model/runtime stay outside APK')


if __name__ == '__main__':
    main()
