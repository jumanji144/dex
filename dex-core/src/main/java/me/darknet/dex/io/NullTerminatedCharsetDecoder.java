package me.darknet.dex.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

public class NullTerminatedCharsetDecoder extends CharsetDecoder {


    /**
     * Initializes a new decoder.  The new decoder will have the given
     * chars-per-byte values and its replacement will be the
     * string <code>"&#92;uFFFD"</code>.
     *
     * @param cs                  The charset that created this decoder
     * @param averageCharsPerByte A positive float value indicating the expected number of
     *                            characters that will be produced for each input byte
     * @param maxCharsPerByte     A positive float value indicating the maximum number of
     *                            characters that will be produced for each input byte
     * @throws IllegalArgumentException If the preconditions on the parameters do not hold
     */
    protected NullTerminatedCharsetDecoder(Charset cs, float averageCharsPerByte, float maxCharsPerByte) {
        super(cs, averageCharsPerByte, maxCharsPerByte);
    }

    public NullTerminatedCharsetDecoder(Charset cs) {
        super(cs, 1f, 1f);
    }

    @Override
    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        CharsetDecoder decoder = charset().newDecoder();
        while (in.hasRemaining()) {
            byte b = in.get(in.position());
            if (b == 0) {
                out.flip();
                return CoderResult.UNDERFLOW;
            }
            CoderResult result = decoder.decode(in, out, false);
        }
        out.flip();
        return CoderResult.UNDERFLOW;
    }
}
