package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

public class NullTerminatedCharsetEncoder extends CharsetEncoder {
    protected NullTerminatedCharsetEncoder(@NotNull Charset cs, float averageBytesPerChar, float maxBytesPerChar) {
        super(cs, averageBytesPerChar, maxBytesPerChar);
    }

    public NullTerminatedCharsetEncoder(@NotNull Charset cs) {
        super(cs, 1f, 1f);
    }

    @Override
    protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
        while (in.hasRemaining()) {
            char c = in.get();
            if (c == 0) {
                out.put((byte) 0);
                return CoderResult.UNDERFLOW;
            }
            out.put((byte) c);
        }
        return CoderResult.UNDERFLOW;
    }
}
