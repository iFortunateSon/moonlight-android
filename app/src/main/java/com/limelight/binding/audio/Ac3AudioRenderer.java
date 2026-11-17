package com.limelight.binding.audio;

import android.content.Context;
import android.util.Log;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One encoder worker, one queued 32 ms frame, reusable storage, no blocking decoder writes. */
public final class Ac3AudioRenderer implements AudioRenderer {
    private static class Slot { final short[] pcm=new short[PcmFrameAccumulator.LENGTH]; long first,queued; }
    private final Context context;
    private final boolean eac3;
    private final PcmFrameAccumulator accumulator=new PcmFrameAccumulator();
    private final ArrayBlockingQueue<Slot> ready=new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<Slot> free=new ArrayBlockingQueue<>(3);
    private final AtomicLong dropped=new AtomicLong();
    private final AtomicLong droppedPcmPackets=new AtomicLong();
    private volatile boolean running,failed,injectFailure;
    void failForTest() { if(com.limelight.BuildConfig.DEBUG)injectFailure=true; }
    private Thread worker;
    private Ac3Track sink;
    private Ac3Encoder encoder;
    public Ac3AudioRenderer(Context context) {this(context,false);}
    public Ac3AudioRenderer(Context context,boolean eac3) {this.context=context;this.eac3=eac3;for(int i=0;i<3;i++)free.add(new Slot());}
    public boolean isEac3(){return eac3;}
    public boolean failed() { return failed; }
    public int setup(MoonBridge.AudioConfiguration config,int rate,int packetSamples) {
        if(config.channelCount!=6 || rate!=48000) return -1;
        try {
            Ac3Track.capabilities(context);
            encoder=new Ac3Encoder(eac3);
            // TCL's compressed HAL needs a stereo carrier mask; the encoded stream remains 3/2+LFE.
            // Start with the minimum buffer; a larger one is a creation fallback only.
            RuntimeException last=null;
            int[][] attempts=eac3?new int[][]{{1,1,0},{1,1,65536},{1,0,0},{0,0,0}}:new int[][]{{0,0,0},{1,0,0},{1,1,0},{1,1,65536}};
            for(int[] attempt:attempts) {
                try {sink=new Ac3Track(false,attempt[0]!=0,attempt[1]!=0,attempt[2],eac3);break;}
                catch(RuntimeException e) {last=e;Log.w(Ac3Track.TAG,"Dolby track candidate failed: "+e);}
            }
            if(sink==null)throw new IllegalStateException("No usable Dolby AudioTrack",last);
            running=true;
            worker=new Thread(this::run,"Moonlight-AC3");worker.start();
            Log.i(Ac3Track.TAG,"selected "+(eac3?"EAC3":"AC3")+" 48kHz 640kbit/s 5.1-back; Opus packetSamples="+packetSamples+" encoderPaddingSamples=256");
            return 0;
        } catch(RuntimeException | LinkageError e) {
            Log.e(Ac3Track.TAG,"Dolby setup failed",e);
            if(sink!=null) {sink.close();sink=null;}
            if(encoder!=null) {encoder.close();encoder=null;}
            return -1;
        }
    }
    private void enqueue(short[] pcm,long first) {
        if(!running) return;
        Slot slot=free.poll();
        if(slot==null) { dropped.incrementAndGet();return; }
        System.arraycopy(pcm,0,slot.pcm,0,pcm.length);
        slot.first=first;slot.queued=System.nanoTime();
        if(!ready.offer(slot)) {free.offer(slot);dropped.incrementAndGet();}
    }
    public void playDecodedAudio(short[] pcm) {
        if(MoonBridge.getPendingAudioDuration()>=40) {
            accumulator.reset();droppedPcmPackets.incrementAndGet();return;
        }
        accumulator.append(pcm,System.nanoTime(),this::enqueue);
    }
    private void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        ByteBuffer encoded=ByteBuffer.allocateDirect(2560);
        long frames=0,encodeTotal=0,writeTotal=0,maxEncode=0,maxWrite=0,maxQueue=0,maxBuffer=0;
        try {
            while(running) {
                Slot slot=ready.poll(20,TimeUnit.MILLISECONDS);
                if(slot==null)continue;
                try {
                    if(!running)break;
                    if(injectFailure)throw new IllegalStateException("Injected write/encoder failure for fallback test");
                    long start=System.nanoTime();
                    maxBuffer=Math.max(maxBuffer,slot.queued-slot.first);
                    maxQueue=Math.max(maxQueue,start-slot.queued);
                    int length=encoder.encode(slot.pcm,encoded);
                    long encodedAt=System.nanoTime();
                    sink.write(encoded,length);
                    long writtenAt=System.nanoTime();
                    long encode=encodedAt-start,write=writtenAt-encodedAt;
                    encodeTotal+=encode;writeTotal+=write;maxEncode=Math.max(maxEncode,encode);maxWrite=Math.max(maxWrite,write);
                    frames++;
                    if(frames%156==0) {
                        Log.i(Ac3Track.TAG,"metrics codec="+(eac3?"EAC3":"AC3")+" frames="+frames+" pcmBufferMaxMs="+maxBuffer/1e6+" queueWaitMaxMs="+maxQueue/1e6
                            +" encodeAvgMs="+encodeTotal/frames/1e6+" encodeMaxMs="+maxEncode/1e6
                            +" writeAvgMs="+writeTotal/frames/1e6+" writeMaxMs="+maxWrite/1e6
                            +" droppedPcmPackets="+droppedPcmPackets.get()+" discardedPartialPcmSamples="+accumulator.discardedSamples()+" queuedFrames="+ready.size()+" dropped32msFrames="+dropped.get()+" underruns="+sink.underruns()
                            +" intrinsicFrameMs=32 encoderPaddingMs=5.333 outputLatency=unmeasured");
                        sink.logRoute();
                        if(!sink.hdmiRouted())throw new IllegalStateException("HDMI/eARC route lost");
                    }
                } finally {free.offer(slot);}
            }
        } catch(InterruptedException | Ac3WriteLoop.CancelledException e) {
            if(running) {failed=true;Log.e(Ac3Track.TAG,"AC3 worker interrupted",e);}
            Thread.currentThread().interrupt();
        } catch(RuntimeException | LinkageError e) { failed=true;Log.e(Ac3Track.TAG,"Dolby runtime failure; fallback on next packet",e); }
        finally {
            running=false;
            try {sink.close();} finally {encoder.close();}
            Log.i(Ac3Track.TAG,"worker stopped frames="+frames+" dropped32msFrames="+dropped.get());
        }
    }
    public void start() {}
    public void stop() { shutdown(); }
    public void cleanup() { shutdown();accumulator.reset();ready.clear(); }
    private void shutdown() {
        running=false;
        if(worker!=null) {
            worker.interrupt();
            try {worker.join(500);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
            if(worker.isAlive())Log.e(Ac3Track.TAG,"AC3 worker still stopping");
        }
    }
}
