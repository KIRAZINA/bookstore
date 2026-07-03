package com.example.bookstore.performance;

import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.LongSummaryStatistics;
import java.util.concurrent.TimeUnit;

/**
 * Collects latency samples and computes percentile statistics for performance characterization.
 */
public final class LatencyStatistics {

    private final long[] samplesNanos;
    private int count;

    public LatencyStatistics(int capacity) {
        this.samplesNanos = new long[capacity];
        this.count = 0;
    }

    public void recordNanos(long durationNanos) {
        if (count < samplesNanos.length) {
            samplesNanos[count++] = durationNanos;
        }
    }

    public void recordMillis(long durationMillis) {
        recordNanos(TimeUnit.MILLISECONDS.toNanos(durationMillis));
    }

    public int size() {
        return count;
    }

    public PerformanceSnapshot snapshot(String operation) {
        if (count == 0) {
            return new PerformanceSnapshot(operation, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        long[] sorted = Arrays.copyOf(samplesNanos, count);
        Arrays.sort(sorted);

        double avgMs = Arrays.stream(sorted)
                .mapToDouble(n -> n / 1_000_000.0)
                .average()
                .orElse(0);

        return new PerformanceSnapshot(
                operation,
                count,
                toMs(sorted[0]),
                percentileMs(sorted, 50),
                percentileMs(sorted, 95),
                percentileMs(sorted, 99),
                toMs(sorted[sorted.length - 1]),
                avgMs,
                throughputPerSecond(avgMs)
        );
    }

    private static double percentileMs(long[] sortedNanos, int percentile) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
        index = Math.max(0, Math.min(index, sortedNanos.length - 1));
        return toMs(sortedNanos[index]);
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double throughputPerSecond(double avgMs) {
        if (avgMs <= 0) {
            return 0;
        }
        return 1000.0 / avgMs;
    }

    public record PerformanceSnapshot(
            String operation,
            int samples,
            double minMs,
            double medianMs,
            double p95Ms,
            double p99Ms,
            double maxMs,
            double avgMs,
            double throughputPerSecond
    ) {
        public void log() {
            System.out.printf(
                    "%n=== %s (%d samples) ===%n" +
                    "  avg=%.2fms  median=%.2fms  p95=%.2fms  p99=%.2fms  max=%.2fms  throughput=%.2f ops/s%n",
                    operation, samples, avgMs, medianMs, p95Ms, p99Ms, maxMs, throughputPerSecond
            );
        }
    }

    public static LongSummaryStatistics summarizeCounts(long[] values) {
        return Arrays.stream(values).summaryStatistics();
    }

    public static DoubleSummaryStatistics summarizeDoubles(double[] values) {
        return Arrays.stream(values).summaryStatistics();
    }
}
