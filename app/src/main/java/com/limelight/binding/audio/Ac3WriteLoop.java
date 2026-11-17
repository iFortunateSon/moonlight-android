package com.limelight.binding.audio;
import java.nio.ByteBuffer;
/** AudioTrack transport loop, separated to test partial writes and errors without a HAL. */
public final class Ac3WriteLoop {
    public interface Writer { int write(ByteBuffer data); }
    public interface Clock { long millis(); void idle(); }
    public static void writeFully(ByteBuffer buffer,Writer writer,Clock clock,long timeoutMs) {
        long deadline=clock.millis()+timeoutMs;
        while(buffer.hasRemaining()) {
            int before=buffer.position();
            int n=writer.write(buffer);
            if(n<0)throw new IllegalStateException("AudioTrack write="+n);
            if(buffer.position()!=before+n)throw new IllegalStateException("AudioTrack progress mismatch");
            if(n==0) {
                if(clock.millis()>=deadline)throw new IllegalStateException("AudioTrack stalled");
                clock.idle();
            }
        }
    }
    private Ac3WriteLoop() {}
}
