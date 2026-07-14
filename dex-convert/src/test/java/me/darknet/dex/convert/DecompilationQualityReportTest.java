package me.darknet.dex.convert;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DecompilationQualityReportTest {
    @Test
    void qualityComparisonRequiresStructureMarkersAndMetricImprovement() {
        DecompilationQualityReport.MethodMetrics baseline = metrics(
                20, 8, 4, 10, 0, 0, 0, Set.of(), List.of(), false);
        DecompilationQualityReport.MethodMetrics candidate = metrics(
                18, 6, 4, 7, 0, 0, 0,
                Set.of("nested-streams", "hello-write", "hello-read", "catch-finally", "final-cleanup"),
                List.of(), false);

        DecompilationQualityReport.QualityComparison comparison =
                DecompilationQualityReport.compare(baseline, candidate,
                        "DataInputStream DataOutputStream TransferProtocol.hello writeFrame "
                                + "readFrame readHello catch finally activeSockets cancelledTransfers");

        assertTrue(comparison.passes());
        assertTrue(comparison.missingMarkers().isEmpty());
    }

    @Test
    void fallbackIsNotReportedAsAggressiveQualitySuccess() {
        DecompilationQualityReport.MethodMetrics baseline = metrics(
                20, 8, 4, 10, 0, 0, 0, Set.of(), List.of(), false);
        DecompilationQualityReport.MethodMetrics fallback = metrics(
                18, 6, 4, 7, 0, 0, 0, Set.of(), List.of(), true);

        DecompilationQualityReport.QualityComparison comparison =
                DecompilationQualityReport.compare(baseline, fallback, "DataInputStream");

        assertFalse(comparison.passes());
        assertTrue(comparison.failureReason().contains("fell back"));
    }

    @Test
    void repeatedMethodHashesAreTheDeterminismCheck() {
        DecompilationQualityReport.MethodMetrics first = metrics(
                20, 8, 4, 10, 0, 0, 0, Set.of(), List.of(), false);
        DecompilationQualityReport.MethodMetrics second = metrics(
                20, 8, 4, 10, 0, 0, 0, Set.of(), List.of(), false);
        assertTrue(DecompilationQualityReport.repeatedBytesEqual(first, second));
    }

    private static DecompilationQualityReport.MethodMetrics metrics(
            int bytes, int instructions, int locals, int stores,
            int ranges, int aliases, int blocks, Set<String> markers,
            List<String> failures, boolean fallback) {
        return new DecompilationQualityReport.MethodMetrics(
                "Owner", "method", "hash", bytes, instructions, locals, ranges,
                stores, stores, aliases, blocks, 0, false, markers, failures,
                ranges, 1, fallback, List.of());
    }
}
