package me.darknet.dex.convert;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only measurements for observable JVM/decompiler quality.  This class
 * deliberately keeps CFR out of production conversion: callers provide the
 * already captured decompilation and this object records stable structural
 * and semantic markers alongside bytecode metrics.
 */
final class DecompilationQualityReport {
    private static final Pattern ALIAS = Pattern.compile("\\bvar\\d+_\\d+\\b");
    private static final Pattern BLOCK = Pattern.compile("(?m)^\\s*block\\d+:");
    private static final Pattern LABEL = Pattern.compile("(?m)^\\s*lbl\\d+:");

    private DecompilationQualityReport() {
    }

    static MethodMetrics capture(String owner, String methodName, byte[] classBytes,
                                 String decompiled, List<ConversionDiagnostic> diagnostics) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        MethodNode method = classNode.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing method " + owner + "." + methodName));

        String source = extractMethod(decompiled, methodName);
        int instructions = 0;
        int loads = 0;
        int stores = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) instructions++;
            if (instruction instanceof VarInsnNode variable) {
                switch (variable.getOpcode()) {
                    case org.objectweb.asm.Opcodes.ILOAD, org.objectweb.asm.Opcodes.LLOAD,
                            org.objectweb.asm.Opcodes.FLOAD, org.objectweb.asm.Opcodes.DLOAD,
                            org.objectweb.asm.Opcodes.ALOAD -> loads++;
                    case org.objectweb.asm.Opcodes.ISTORE, org.objectweb.asm.Opcodes.LSTORE,
                            org.objectweb.asm.Opcodes.FSTORE, org.objectweb.asm.Opcodes.DSTORE,
                            org.objectweb.asm.Opcodes.ASTORE -> stores++;
                    default -> { }
                }
            }
        }

        Map<Object, Integer> rangesByHandler = new IdentityHashMap<>();
        method.tryCatchBlocks.forEach(range -> rangesByHandler.merge(range.handler, 1, Integer::sum));
        int maxRangesPerHandler = rangesByHandler.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<ConversionDiagnostic> methodDiagnostics = diagnostics.stream()
                .filter(diagnostic -> diagnostic.method().contains(methodName))
                .toList();
        boolean fallback = methodDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION
                        && (diagnostic.message().contains("retried deterministic lowering")
                        || diagnostic.message().contains("retried guarded lowering")));

        Set<String> markers = semanticMarkers(source);

        List<String> failures = new ArrayList<>();
        for (String marker : List.of("Decompilation failed", "Unable to fully structure code",
                "Exception decompiling", "** GOTO")) {
            if (source.contains(marker)) failures.add(marker);
        }

        return new MethodMetrics(owner, methodName, sha256(classBytes), classBytes.length,
                instructions, method.maxLocals, method.tryCatchBlocks.size(), loads, stores,
                count(ALIAS, source), count(BLOCK, source), count(LABEL, source),
                source.contains("while (true)"), Set.copyOf(markers), List.copyOf(failures),
                rangesByHandler.size(), maxRangesPerHandler, fallback, List.copyOf(methodDiagnostics));
    }

    static QualityComparison compare(MethodMetrics baseline, MethodMetrics candidate,
                                      String archivedMethodSource) {
        Set<String> required = semanticMarkers(archivedMethodSource);
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(candidate.semanticMarkers());
        return new QualityComparison(baseline, candidate, Set.copyOf(required), Set.copyOf(missing),
                candidate.improvedOver(baseline));
    }

    static boolean repeatedBytesEqual(MethodMetrics first, MethodMetrics second) {
        return first.bytecodeSha256().equals(second.bytecodeSha256());
    }

    private static Set<String> semanticMarkers(String source) {
        Set<String> markers = new LinkedHashSet<>();
        if (source.contains("DataInputStream") && source.contains("DataOutputStream")) markers.add("nested-streams");
        if (source.contains("TransferProtocol.hello") && source.contains("writeFrame")) markers.add("hello-write");
        if (source.contains("readFrame") && source.contains("readHello")) markers.add("hello-read");
        if (source.contains("catch") && source.contains("finally")) markers.add("catch-finally");
        if (source.contains("activeSockets") && source.contains("cancelledTransfers")) markers.add("final-cleanup");
        if (source.contains("IdentityStore.hex") && source.contains("MessageDigest")) markers.add("digest-chain");
        if (source.contains("new StringBuilder") || source.contains("StringBuilder")) markers.add("builder");
        return Set.copyOf(markers);
    }

    static String extractMethod(String decompiled, String methodName) {
        int name = -1;
        int searchFrom = 0;
        String needle = methodName + "(";
        while (true) {
            int candidate = decompiled.indexOf(needle, searchFrom);
            if (candidate < 0) break;
            int before = candidate - 1;
            while (before >= 0 && Character.isWhitespace(decompiled.charAt(before))) before--;
            if (before < 0 || (decompiled.charAt(before) != '.' && decompiled.charAt(before) != ':')) {
                name = candidate;
                break;
            }
            searchFrom = candidate + needle.length();
        }
        if (name < 0) return decompiled;
        int declarationStart = decompiled.lastIndexOf('\n', name) + 1;
        int open = decompiled.indexOf('{', name);
        if (open < 0) return decompiled.substring(declarationStart);
        int depth = 0;
        for (int i = open; i < decompiled.length(); i++) {
            char character = decompiled.charAt(i);
            if (character == '{') depth++;
            else if (character == '}' && --depth == 0)
                return decompiled.substring(declarationStart, i + 1);
        }
        return decompiled.substring(declarationStart);
    }

    private static int count(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest)
                result.append(String.format("%02x", value & 0xFF));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    record MethodMetrics(String owner, String method, String bytecodeSha256, int bytecodeSize,
                         int instructionCount, int maxLocals, int tryCatchCount, int loadCount,
                         int storeCount, int aliasCount, int syntheticBlockCount, int syntheticLabelCount,
                         boolean hasInfiniteLoop, Set<String> semanticMarkers, List<String> failureMarkers,
                         int handlerCount, int maxRangesPerHandler, boolean aggressiveFallback,
                         List<ConversionDiagnostic> diagnostics) {
        boolean structurallyClean() {
            return failureMarkers.isEmpty();
        }

        boolean improvedOver(MethodMetrics baseline) {
            if (!structurallyClean()) return false;
            return aliasCount < baseline.aliasCount
                    || syntheticBlockCount < baseline.syntheticBlockCount
                    || syntheticLabelCount < baseline.syntheticLabelCount
                    || tryCatchCount < baseline.tryCatchCount
                    || storeCount < baseline.storeCount;
        }

        String summary() {
            return method + ": bytes=" + bytecodeSize + ", insns=" + instructionCount
                    + ", locals=" + maxLocals + ", ranges=" + tryCatchCount
                    + ", loads=" + loadCount + ", stores=" + storeCount
                    + ", aliases=" + aliasCount + ", blocks=" + syntheticBlockCount
                    + ", labels=" + syntheticLabelCount + ", fallback=" + aggressiveFallback
                    + ", handlers=" + handlerCount + ", maxRangesPerHandler=" + maxRangesPerHandler
                    + ", failures=" + failureMarkers;
        }
    }

    record QualityComparison(MethodMetrics baseline, MethodMetrics candidate,
                             Set<String> requiredMarkers, Set<String> missingMarkers,
                             boolean metricImproved) {
        boolean passes() {
            return !candidate.aggressiveFallback()
                    && candidate.structurallyClean()
                    && missingMarkers.isEmpty()
                    && metricImproved;
        }

        String failureReason() {
            if (candidate.aggressiveFallback()) return "aggressive method fell back to deterministic lowering";
            if (!candidate.structurallyClean()) return "decompilation failure markers: " + candidate.failureMarkers();
            if (!missingMarkers.isEmpty()) return "missing archived semantic markers: " + missingMarkers;
            if (!metricImproved) return "no measurable metric improvement over deterministic lowering";
            return "";
        }
    }
}
