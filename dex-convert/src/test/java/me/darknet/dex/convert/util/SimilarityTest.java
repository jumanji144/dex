package me.darknet.dex.convert.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Similarity}.
 */
class SimilarityTest {
    @Test
    void identicalSourceScoresExactlyOne() {
        // Test that identical source code has a similarity score of 1.0
        String source = "class Sample { int value; int get() { return value; } }";
        assertEquals(1.0, Similarity.similarity(source, source));
    }

    @Test
    void insertingOneStatementHasSmallMonotonicDecrease() {
        // Test that inserting a single statement into the source code results in a small decrease in similarity score
        String base = "class Sample { int get(int x) { int a = x + 1; int b = a + 2; int c = b + 3; return c; } }";
        String changed = "class Sample { int get(int x) { int a = x + 1; int b = a + 2; int extra = 99; int c = b + 3; return c; } }";
        double score = Similarity.similarity(base, changed);
        assertTrue(score < 1.0);
        assertTrue(score > 0.98, "one insertion should be a small penalty: " + score);
    }

    @Test
    void unrelatedContentCannotIncreaseSimilarity() {
        // Test that adding unrelated content to the source code cannot increase the similarity score
        String base = "class Sample { int get() { return 1; } }";
        String related = "class Sample { int get() { return 2; } }";
        String withMethod = "class Sample { int get() { return 2; } void unrelated() { System.out.println(2); } }";
        assertTrue(Similarity.similarity(base, withMethod) <= Similarity.similarity(base, related));
    }

    @Test
    void methodOrderIsComparedAsBag() {
        // Test that the order of methods in a class does not affect the similarity score
        String first = "class Sample { int one() { return 1; } int two() { return 2; } }";
        String reordered = "class Sample { int two() { return 2; } int one() { return 1; } }";
        assertEquals(1.0, Similarity.similarity(first, reordered));
    }

    @Test
    void unaryOperandChangesAreDetected() {
        // Test that changing the operand of a unary operation (like negation) is detected as a change in similarity
        String first = "class Sample { int negate(int x, int y) { return -x; } }";
        String changed = "class Sample { int negate(int x, int y) { return -y; } }";
        assertTrue(Similarity.similarity(first, changed) < 1.0);
    }

    @Test
    void repeatedCallsAreDeterministic() {
        // Test that repeated calls to the similarity function with the same inputs produce the same result
        String source = "class Sample { int get(int x) { if (x > 0) return x; return -x; } }";
        double expected = Similarity.similarity(source, source);
        for (int i = 0; i < 4; i++) assertEquals(expected, Similarity.similarity(source, source));
    }
}
