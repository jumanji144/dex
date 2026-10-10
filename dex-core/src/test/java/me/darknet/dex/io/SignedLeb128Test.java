package me.darknet.dex.io;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignedLeb128Test {

    @Test
    void signedValuesRoundTripIncludingBit6BoundaryValues() throws Exception {
        // 64..127 and -65..-128 end in a group whose bit 6 is set, so they must keep their sign on read.
        int[] values = {0, 1, 63, 64, 93, 127, 128, 8192 + 64, -1, -64, -65, -128, 1000, -1000,
                Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int value : values) {
            Output output = Output.wrap();
            output.writeLeb128(value);
            Input input = Input.wrap(output.buffer());
            assertEquals(value, input.readLeb128(), "signed LEB128 round trip of " + value);
        }
    }
}
