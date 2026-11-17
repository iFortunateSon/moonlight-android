package com.limelight.binding.audio;

import android.content.Context;
import android.media.*;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Raw AC-3/E-AC-3 use their matching encodings. Only AC-3 has an IEC test path. */
public final class Ac3Track implements AutoCloseable {
    public static final String TAG = "MoonlightAC3";
    private final AudioTrack track;
    private final boolean iec;
    private final byte[] burst = new byte[6144];
    private long bytesWritten;
    private Ac3WriteLoop.Writer writer;
    private static final Ac3WriteLoop.Clock CLOCK=new Ac3WriteLoop.Clock() {
        public long millis() {return SystemClock.elapsedRealtime();}
        public void idle() {SystemClock.sleep(1);}
    };
    public static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    }
    public static AudioFormat format(boolean iec) {return format(iec,false);}
    public static AudioFormat format(boolean iec,boolean eac3) {
        if(iec && eac3)throw new IllegalArgumentException("EAC3 IEC encapsulation is not enabled");
        return new AudioFormat.Builder().setSampleRate(48000)
            .setChannelMask(iec ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_5POINT1)
            .setEncoding(iec ? AudioFormat.ENCODING_IEC61937 : eac3?AudioFormat.ENCODING_E_AC3:AudioFormat.ENCODING_AC3).build();
    }
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 29 && AudioTrack.isDirectPlaybackSupported(format(false), attributes());
    }
    public static void capabilities(Context context) {
        if (Build.VERSION.SDK_INT < 23) return;
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            Log.i(TAG, "device id="+d.getId()+" type="+d.getType()+" name="+d.getProductName()
                +" encodings="+Arrays.toString(d.getEncodings())+" channels="+Arrays.toString(d.getChannelCounts())
                +" masks="+Arrays.toString(d.getChannelMasks()));
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Log.i(TAG,"offload EAC3="+manager.isOffloadedPlaybackSupported(format(false,true),attributes())+" direct EAC3="+AudioTrack.isDirectPlaybackSupported(format(false,true),attributes()));
            Log.i(TAG,"offload AC3="+manager.isOffloadedPlaybackSupported(format(false),attributes())+" direct AC3="+supported()+" IEC61937="+AudioTrack.isDirectPlaybackSupported(format(true), attributes()));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Log.i(TAG,"directSupport AC3="+manager.getDirectPlaybackSupport(format(false),attributes())+" EAC3="+manager.getDirectPlaybackSupport(format(false,true),attributes()));
        }
    }
    public Ac3Track(boolean iec) { this(iec, false); }
    public Ac3Track(boolean iec, boolean offload) { this(iec, offload, false, 0); }
    public Ac3Track(boolean iec, boolean offload, boolean carrierStereo, int testBuffer) {this(iec,offload,carrierStereo,testBuffer,false);}
    public Ac3Track(boolean iec, boolean offload, boolean carrierStereo, int testBuffer,boolean eac3) {
        if (Build.VERSION.SDK_INT < 24) throw new IllegalStateException("AC3 output requires API 24");
        this.iec = iec;
        AudioFormat f = carrierStereo ? new AudioFormat.Builder(format(iec,eac3)).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build() : format(iec,eac3);
        int minimum = AudioTrack.getMinBufferSize(48000,f.getChannelMask(),f.getEncoding());
        // Start with one complete coded frame. The HAL can still enforce a larger minimum.
        Log.i(TAG,"attempt mode="+(iec?"IEC61937":eac3?"EAC3":"AC3")+" offload="+offload+" minBytes="+minimum+" mask="+f.getChannelMask()+" testBuffer="+testBuffer);
        AudioTrack.Builder builder = new AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(f)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(testBuffer,Math.max(minimum,iec ? 6144 : 2560)));
        if(offload && Build.VERSION.SDK_INT>=29) builder.setOffloadedPlayback(true);
        track=builder.build();
        writer=buffer->{int n=track.write(buffer,buffer.remaining(),AudioTrack.WRITE_NON_BLOCKING);if(n>0)bytesWritten+=n;return n;};
        if (track.getState()!=AudioTrack.STATE_INITIALIZED) { track.release(); throw new IllegalStateException("AC3 track uninitialized"); }
        Log.i(TAG,"created mode="+(iec?"IEC61937":eac3?"EAC3":"AC3")+" minBytes="+minimum+" bufferFrames="+track.getBufferSizeInFrames());
        try {track.play();} catch(RuntimeException e) {track.release();throw e;}
        logRoute();
    }
    public void logRoute() {
        if (Build.VERSION.SDK_INT < 23) return;
        AudioDeviceInfo d=track.getRoutedDevice();
        Log.i(TAG,"route="+(d==null?"pending":d.getId()+"/"+d.getType()+"/"+d.getProductName()));
    }
    public void write(byte[] frame, int length) { write(ByteBuffer.wrap(frame,0,length),length); }
    public void write(ByteBuffer frameBuffer, int length) {
        byte[] frame = iec ? new byte[length] : null;
        if(iec) { frameBuffer.get(frame); frameBuffer.rewind(); }
        if (length!=2560 || (frameBuffer.get(0)&255)!=0x0b || (frameBuffer.get(1)&255)!=0x77)
            throw new IllegalArgumentException("Expected raw 48kHz/640k Dolby frame");
        ByteBuffer buffer=frameBuffer;
        if (iec) {
            Arrays.fill(burst,(byte)0);
            burst[0]=0x72; burst[1]=(byte)0xf8; burst[2]=0x1f; burst[3]=0x4e;
            burst[4]=1; burst[5]=(byte)(frame[5]&7); // AC3 data type + bsmod
            burst[6]=(byte)(length*8); burst[7]=(byte)((length*8)>>8);
            for(int i=0;i<length;i+=2) { burst[i+8]=frame[i+1]; burst[i+9]=frame[i]; }
            buffer=ByteBuffer.wrap(burst); length=burst.length;
        }
        Ac3WriteLoop.writeFully(buffer,writer,CLOCK,150);
    }
    public boolean hdmiRouted() {
        if (Build.VERSION.SDK_INT < 23) return false;
        AudioDeviceInfo d=track.getRoutedDevice();
        return d!=null && (d.getType()==AudioDeviceInfo.TYPE_HDMI || d.getType()==AudioDeviceInfo.TYPE_HDMI_ARC || d.getType()==AudioDeviceInfo.TYPE_HDMI_EARC);
    }
    public int underruns() { return Build.VERSION.SDK_INT>=24 ? track.getUnderrunCount():-1; }
    public void close() { Log.i(TAG,"close bytes="+bytesWritten+" underruns="+underruns()); track.pause(); track.flush(); track.release(); }
}
