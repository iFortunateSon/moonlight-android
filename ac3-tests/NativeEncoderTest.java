import com.limelight.binding.audio.Ac3Encoder;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Execute the production JNI encoder on a host JVM, then decode independently. */
public final class NativeEncoderTest {
    private static void rejected(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Invalid input was accepted");
        } catch (IllegalStateException expected) {
            // The public wrapper translates native validation errors.
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        for (boolean eac3 : new boolean[] {false, true}) {
            String codec = eac3 ? "eac3" : "ac3";
            ByteBuffer coded = ByteBuffer.allocateDirect(2560);
            short[] pcm = new short[1536 * 6];
            byte[] packet = new byte[2560];
            Ac3Encoder encoder = new Ac3Encoder(eac3);
            try (OutputStream stream = Files.newOutputStream(output.resolve("jni." + codec))) {
                rejected(() -> encoder.encode(new short[6], coded));
                rejected(() -> encoder.encode(pcm, ByteBuffer.allocateDirect(2559)));
                rejected(() -> encoder.encode(pcm, ByteBuffer.allocate(2560)));
                for (int frame = 0; frame < 750; frame++) {
                    int channel = frame / 125;
                    double frequency = channel == 3 ? 60.0 : 440.0 + channel * 110.0;
                    java.util.Arrays.fill(pcm, (short) 0);
                    for (int sample = 0; sample < 1536; sample++) {
                        int position = frame * 1536 + sample;
                        pcm[sample * 6 + channel] = (short) (8000.0 *
                                Math.sin(2.0 * Math.PI * frequency * position / 48000.0));
                    }
                    if (encoder.encode(pcm, coded) != 2560 || coded.remaining() != 2560) {
                        throw new AssertionError("Unexpected packet size");
                    }
                    coded.get(packet);
                    stream.write(packet);
                }
            } finally {
                encoder.close();
                encoder.close();
            }
            rejected(() -> encoder.encode(pcm, coded));
            try (Ac3Encoder restarted = new Ac3Encoder(eac3)) {
                restarted.encode(new short[1536 * 6], coded);
            }
            System.out.println("PASS: host JNI " + codec +
                    ", 750 frames, input validation, idempotent close, new encoder after close");
        }
    }
}
