import com.limelight.binding.audio.Ac3WriteLoop;
import java.nio.ByteBuffer;

public class WriteLoopTest {
    static class Clock implements Ac3WriteLoop.Clock {
        long t;
        Clock() {}
        Clock(long initial) { t = initial; }
        public long millis() { return t; }
        public void idle() { t++; }
    }
    static void check(boolean result, String reason) {
        if (!result) throw new AssertionError(reason);
    }
    static IllegalStateException failure(Runnable run) {
        try { run.run(); }
        catch (IllegalStateException expected) { return expected; }
        throw new AssertionError("error accepted");
    }
    static int advance(ByteBuffer data, int count) {
        int n = Math.min(count, data.remaining());
        data.position(data.position() + n);
        return n;
    }
    static void slowComplete(long duration, boolean succeeds) {
        Clock clock = new Clock();
        ByteBuffer data = ByteBuffer.allocate(2560);
        Runnable run = () -> Ac3WriteLoop.writeFully(data, b -> {
            clock.t += duration;
            return advance(b, b.remaining());
        }, clock, 150);
        if (succeeds) { run.run(); check(!data.hasRemaining(), "slow complete write"); }
        else check(failure(run).getMessage().contains("deadline"), "late complete write");
    }
    static void cancellation(boolean initiallyInterrupted, boolean positiveWrite) {
        Clock clock = new Clock();
        int[] calls = {0};
        try {
            if (initiallyInterrupted) Thread.currentThread().interrupt();
            IllegalStateException error = failure(() -> Ac3WriteLoop.writeFully(
                ByteBuffer.allocate(2560), b -> {
                    calls[0]++;
                    Thread.currentThread().interrupt();
                    return positiveWrite ? advance(b, 100) : 0;
                }, clock, 150));
            check(error instanceof Ac3WriteLoop.CancelledException, "distinct cancellation");
            check(Thread.currentThread().isInterrupted(), "preserve interrupt flag");
            check(calls[0] == (initiallyInterrupted ? 0 : 1), "no writes after cancellation");
            check(clock.t == 0, "no idle after cancellation");
        } finally { Thread.interrupted(); }
    }
    public static void main(String[] args) {
        ByteBuffer b = ByteBuffer.allocate(2560);
        Clock clock = new Clock(); int[] count = {0};
        Ac3WriteLoop.writeFully(b, data -> count[0]++ % 3 == 0 ? 0 : advance(data, 73), clock, 150);
        check(!b.hasRemaining(), "partial and zero writes");
        failure(() -> Ac3WriteLoop.writeFully(ByteBuffer.allocate(10), d -> -6, new Clock(), 150));
        Clock stalled = new Clock();
        failure(() -> Ac3WriteLoop.writeFully(ByteBuffer.allocate(10), d -> 0, stalled, 150));
        check(stalled.t == 150, "zero-write deadline");
        failure(() -> Ac3WriteLoop.writeFully(ByteBuffer.allocate(10), d -> 1, new Clock(), 150));
        failure(() -> Ac3WriteLoop.writeFully(ByteBuffer.allocate(10), d -> 11, new Clock(), 150));

        Clock trickle = new Clock(); ByteBuffer partial = ByteBuffer.allocate(10000);
        IllegalStateException error = failure(() -> Ac3WriteLoop.writeFully(partial, data -> {
            trickle.t += 10;
            return advance(data, 100);
        }, trickle, 150));
        check(error.getMessage().contains("deadline"), "positive-write deadline");
        check(trickle.t == 150 && partial.position() == 1500, "trickle bounded at 150ms/1500bytes");
        for (long duration : new long[]{100, 120, 149}) slowComplete(duration, true);
        for (long duration : new long[]{150, 151, 1000}) slowComplete(duration, false);
        for (long duration : new long[]{100, 149}) {
            Clock slow = new Clock(); int[] calls = {0};
            ByteBuffer data = ByteBuffer.allocate(2560);
            Ac3WriteLoop.writeFully(data, d -> {
                if (calls[0]++ == 0) { slow.t += duration; return advance(d, 1280); }
                return advance(d, d.remaining());
            }, slow, 150);
            check(!data.hasRemaining() && slow.t == duration, "slow partial sink below budget");
        }
        Clock mixed = new Clock(); int[] calls = {0};
        failure(() -> Ac3WriteLoop.writeFully(ByteBuffer.allocate(2560), d -> {
            calls[0]++;
            if (calls[0] == 1) { mixed.t += 100; return advance(d, 1280); }
            return 0;
        }, mixed, 150));
        check(mixed.t == 150, "zero writes do not reset positive-write budget");
        Clock wraps = new Clock(Long.MAX_VALUE - 5);
        ByteBuffer wrapped = ByteBuffer.allocate(10);
        Ac3WriteLoop.writeFully(wrapped, d -> { wraps.t += 10; return advance(d, 10); }, wraps, 150);
        check(!wrapped.hasRemaining(), "elapsed duration tolerates clock wrap");
        cancellation(true, false);
        cancellation(false, false);
        cancellation(false, true);
        ByteBuffer fresh = ByteBuffer.allocate(10);
        Ac3WriteLoop.writeFully(fresh, d -> advance(d, d.remaining()), new Clock(), 150);
        check(!fresh.hasRemaining(), "fresh transport after failure/cancellation");
        System.out.println("PASS: partial/zero/negative writes, progress validation, overall deadline, 100-149ms slow sinks, interrupt cancellation, clock wrap, fresh transport");
    }
}
