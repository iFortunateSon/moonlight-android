package com.limelight.binding.audio;
import java.nio.ByteBuffer;
/** Single-owner encoder; all encode/close calls must run on the audio worker. */
public final class Ac3Encoder implements AutoCloseable {
    static { System.loadLibrary("moonlight-ac3"); }
    private long handle;
    public Ac3Encoder() {this(false);}
    public Ac3Encoder(boolean eac3) { handle=create(eac3); if(handle==0) throw new IllegalStateException("Dolby encoder init failed; eac3="+eac3); }
    private static native long create(boolean eac3);
    private static native int encodeNative(long handle, short[] pcm, ByteBuffer output);
    private static native void destroy(long handle);
    public int encode(short[] pcm, ByteBuffer output) {
        if(handle==0) throw new IllegalStateException("Encoder closed");
        output.clear();
        int n=encodeNative(handle,pcm,output);
        if(n!=2560) throw new IllegalStateException("Dolby encode="+n);
        output.limit(n);return n;
    }
    public void close() { if(handle!=0) { destroy(handle);handle=0; } }
}
