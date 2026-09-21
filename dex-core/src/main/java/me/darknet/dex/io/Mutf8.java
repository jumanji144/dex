package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Encoder for the modified UTF-8 the dex format uses for string data.
 * <p>
 * Modified UTF-8 is not standard UTF-8. It only uses the one-, two- and three-byte forms, encodes a code
 * point above {@code U+FFFF} as the two three-byte sequences of its surrogate pair, and encodes
 * {@code U+0000} in two bytes rather than one so that a plain zero byte can terminate the string.
 * <p>
 * Writing standard UTF-8 instead is not a harmless difference: a character outside the basic plane
 * occupies four bytes rather than six, so the recorded length no longer describes the bytes that follow and
 * a reader walks off the end of the string.
 */
public final class Mutf8 {

    /** Two-byte form of {@code U+0000}, which standard UTF-8 would write as a lone terminator byte. */
    private static final int ENCODED_NULL_HIGH = 0xC0;
    private static final int ENCODED_NULL_LOW = 0x80;

    private Mutf8() {}

    /**
     * Writes a string in modified UTF-8, followed by its terminating zero byte.
     *
     * @param output
     * 		Output to write to.
     * @param value
     * 		String to encode.
     *
     * @throws IOException
     * 		If the output cannot be written.
     */
    public static void write(@NotNull Output output, @NotNull String value) throws IOException {
        // The length is counted in UTF-16 code units, which is exactly how many chars the string holds, so a
        // surrogate pair counts as two and matches the two three-byte sequences written for it below.
        output.writeULeb128(value.length());

        for (int i = 0; i < value.length(); i++) {
            int unit = value.charAt(i);
            if (unit == 0) {
                output.write(ENCODED_NULL_HIGH);
                output.write(ENCODED_NULL_LOW);
            } else if (unit <= 0x7F) {
                output.write(unit);
            } else if (unit <= 0x7FF) {
                output.write(0xC0 | (unit >> 6));
                output.write(0x80 | (unit & 0x3F));
            } else {
                // Surrogate halves land here too, which is what encodes a supplementary character as its
                // pair of three-byte sequences rather than a single four-byte one.
                output.write(0xE0 | (unit >> 12));
                output.write(0x80 | ((unit >> 6) & 0x3F));
                output.write(0x80 | (unit & 0x3F));
            }
        }

        output.write(0);
    }
}
