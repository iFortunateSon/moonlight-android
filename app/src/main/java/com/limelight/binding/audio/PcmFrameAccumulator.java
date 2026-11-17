package com.limelight.binding.audio;
/** Interleaved FL FR C LFE BL BR, 48 kHz. The callback borrows storage until it returns. */
public final class PcmFrameAccumulator {
    public static final int SAMPLES=1536, CHANNELS=6, LENGTH=SAMPLES*CHANNELS;
    public interface Sink { void frame(short[] pcm, long firstSampleNanos); }
    private final short[] frame=new short[LENGTH];
    private int used;
    private long firstSampleNanos,lastAppendNanos;
    private volatile long discardedSamples;
    public void append(short[] input,long now,Sink sink) {
        if(input.length%CHANNELS!=0) throw new IllegalArgumentException("Partial PCM sample");
        // Sunshine may stop sending PCM during source silence. Never join
        // a stale partial frame to audio arriving after a discontinuity.
        if(used>0 && now-lastAppendNanos>64000000L)reset();
        lastAppendNanos=now;
        int offset=0;
        while(offset<input.length) {
            if(used==0) firstSampleNanos=now;
            int n=Math.min(LENGTH-used,input.length-offset);
            System.arraycopy(input,offset,frame,used,n);used+=n;offset+=n;
            if(used==LENGTH) { sink.frame(frame,firstSampleNanos);used=0; }
        }
    }
    public int bufferedSamples() { return used/CHANNELS; }
    public long discardedSamples() {return discardedSamples;}
    public void reset() {discardedSamples+=used/CHANNELS;used=0;firstSampleNanos=0;lastAppendNanos=0;}
}
