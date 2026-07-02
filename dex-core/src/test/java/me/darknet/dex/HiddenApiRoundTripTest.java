package me.darknet.dex;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.HiddenApiData;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HiddenApiRoundTripTest {

    @Test
    void preservesHiddenApiPayloadThroughHeaderAndTreeRoundTrip() throws Exception {
        // Synthetic dex file with a hidden API payload.
        DexHeader header = TestUtils.getDexHeader("SYN-hiddenapi");
        HiddenApiData hiddenApi = header.map().hiddenApi();
        assertNotNull(hiddenApi);

        // Round trip it
        Output headerOutput = Output.wrap();
        DexHeader.CODEC.write(header, headerOutput);
        DexHeader roundTrippedHeader = DexHeader.CODEC.read(Input.wrap(headerOutput.buffer()));
        HiddenApiData headerRoundTripHiddenApi = roundTrippedHeader.map().hiddenApi();

        assertNotNull(headerRoundTripHiddenApi);
        assertEquals(hiddenApi.itemCount(), headerRoundTripHiddenApi.itemCount());
        assertArrayEquals(hiddenApi.payload(), headerRoundTripHiddenApi.payload());

        DexFile dexFile = DexFile.CODEC.map(header, header.map());
        DexHeader treeHeader = DexFile.CODEC.unmap(dexFile, new me.darknet.dex.file.DexMapBuilder());
        HiddenApiData treeRoundTripHiddenApi = treeHeader.map().hiddenApi();

        assertNotNull(treeRoundTripHiddenApi);
        assertEquals(hiddenApi.itemCount(), treeRoundTripHiddenApi.itemCount());
        assertArrayEquals(hiddenApi.payload(), treeRoundTripHiddenApi.payload());
    }
}
