#!/usr/bin/env python3
"""Independent FFmpeg decoder check of Android JNI self-test output; run on build VM."""
import array,json,math,pathlib,subprocess,sys
p=pathlib.Path(sys.argv[1])
codec=sys.argv[2] if len(sys.argv)>2 else 'ac3'
assert codec in ('ac3','eac3')
meta=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=codec_name,sample_rate,channels,channel_layout,bit_rate,duration','-of','json',str(p)]))['streams'][0]
assert meta['codec_name']==codec and meta['channels']==6
assert int(meta['sample_rate'])==48000 and int(meta['bit_rate'])==640000
assert abs(float(meta['duration'])-24)<0.01
assert p.stat().st_size==750*2560
raw=subprocess.check_output(['ffmpeg','-v','error','-err_detect','crccheck+explode','-i',str(p),'-f','f32le','pipe:1'])
audio=array.array('f');audio.frombytes(raw)
for channel,name in enumerate(('FL','FR','C','LFE','surround L','surround R')):
 start=int((channel*4+0.5)*48000)*6;end=int((channel*4+3.5)*48000)*6
 rms=[math.sqrt(sum(v*v for v in audio[start+c:end:6])/((end-start)//6)) for c in range(6)]
 assert rms[channel]>0.01,(name,rms)
 assert all(v<0.0001 for i,v in enumerate(rms) if i!=channel),(name,rms)
 print(name, 'RMS', ['%.6f'%v for v in rms])
print('PASS: JNI capture '+codec+', 48kHz/640kbit/s/6ch/24s, CRC/decode, six independent lanes incl C and LFE')
