import com.openaria.openaria_echo_mobile.ui.FocusPeaking;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;

/** JVM allocation benchmark; this does not measure Android rendering or camera FPS. */
class FocusPeakingBenchmark {
    private static final int WIDTH = 1024;
    private static final int HEIGHT = 512;
    private static final int COLOR = 0xffe858ff;
    private static volatile int[] sink;

    private static int[] fullFrameReference(int[] pixels) {
        int[] luminance = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            luminance[i] = (77 * ((pixel >>> 16) & 255)
                + 150 * ((pixel >>> 8) & 255) + 29 * (pixel & 255)) >>> 8;
        }
        int[] mask = new int[pixels.length];
        for (int y = 1; y < HEIGHT - 1; y++) {
            for (int x = 1; x < WIDTH - 1; x++) {
                int i = y * WIDTH + x;
                if (Math.abs(luminance[i + 1] - luminance[i - 1])
                    + Math.abs(luminance[i + WIDTH] - luminance[i - WIDTH]) >= 72) {
                    mask[i] = COLOR;
                }
            }
        }
        return mask;
    }

    @SuppressWarnings("deprecation")
    private static double[] measure(ThreadMXBean bean, Supplier<int[]> operation) {
        long thread = Thread.currentThread().getId();
        long allocated = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) sink = operation.get();
        return new double[] {
            (System.nanoTime() - start) / 10_000_000.0,
            (bean.getThreadAllocatedBytes(thread) - allocated) / 10.0
        };
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    public static void main(String[] args) {
        int[] pixels = new Random(809).ints(WIDTH * HEIGHT).toArray();
        Supplier<int[]> operation = () ->
            FocusPeaking.INSTANCE.computeMask(pixels, WIDTH, HEIGHT, 72, COLOR, WIDTH * HEIGHT).getPixels();
        if (!Arrays.equals(fullFrameReference(pixels), operation.get())) throw new AssertionError("focus mask changed");
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 100; i++) sink = operation.get();
        double[] milliseconds = new double[11];
        double[] allocatedBytes = new double[11];
        for (int round = 0; round < 11; round++) {
            double[] result = measure(bean, operation);
            milliseconds[round] = result[0];
            allocatedBytes[round] = result[1];
        }
        System.out.printf(Locale.ROOT,
            "{\"width\":%d,\"height\":%d,\"samples\":11,\"medianMs\":%.4f,\"allocatedBytesPerFrame\":%.0f,"
                + "\"maskHash\":%d}%n",
            WIDTH, HEIGHT, median(milliseconds), median(allocatedBytes), Arrays.hashCode(sink));
    }
}
