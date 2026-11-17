package com.limelight.binding.audio;

import java.nio.ByteBuffer;

/** Bounded transport for one coded frame. Writer calls must be nonblocking. */
public final class Ac3WriteLoop {
    public interface Writer { int write(ByteBuffer data); }
    public interface Clock { long millis(); void idle(); }

    /** Keep intentional shutdown separate from a broken output track. */
    public static final class CancelledException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private CancelledException() { super("AudioTrack write cancelled"); }
    }

    public static void writeFully(ByteBuffer buffer, Writer writer, Clock clock, long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Positive write timeout required");
        long started = clock.millis();
        while (buffer.hasRemaining()) {
            checkActive(clock, started, timeoutMs);
            int before = buffer.position();
            int remaining = buffer.remaining();
            int n = writer.write(buffer);
            if (n < 0) throw new IllegalStateException("AudioTrack write=" + n);
            if (n > remaining || buffer.position() != before + n) {
                throw new IllegalStateException("AudioTrack progress mismatch");
            }
            // Positive partial writes and the final write share the same budget.
            // Detect a vendor call that returns late, even if it filled the buffer.
            checkActive(clock, started, timeoutMs);
            if (n == 0) clock.idle();
        }
    }

    private static void checkActive(Clock clock, long started, long timeoutMs) {
        if (Thread.currentThread().isInterrupted()) throw new CancelledException();
        // Subtraction avoids overflowing an absolute deadline in clock tests.
        if (clock.millis() - started >= timeoutMs) {
            throw new IllegalStateException("AudioTrack write deadline exceeded");
        }
    }

    private Ac3WriteLoop() {}
}
