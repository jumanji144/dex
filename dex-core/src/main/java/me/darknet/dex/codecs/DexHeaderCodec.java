package me.darknet.dex.codecs;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.io.Codec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;

public class DexHeaderCodec implements Codec<DexHeader> {

    private final byte[] DEX_FILE_MAGIC = new byte[] { 0x64, 0x65, 0x78, 0x0a };

    private void checkRead(Input input, int size, int position) throws IOException {
        if(size == 0) return;
        if(input.position() == position + size) return;

        throw new IOException("Invalid section, given size: " + size + ", actual size: " + (input.position() - position));
    }

    @Override
    public DexHeader read(Input input) throws IOException {
        final int ENDIAN_CONSTANT = 0x12345678;
        final int REVERSE_ENDIAN_CONSTANT = 0x78563412;

        byte[] magic = input.readBytes(4);
        if (!Arrays.equals(magic, DEX_FILE_MAGIC)) {
            throw new IOException("Invalid magic");
        }
        byte[] version = input.readBytes(4);

        int position = input.position();
        input.skipBytes(4 + 20 + 4 + 4); // skip checksum, signature, file_size, header_size

        // to read endianness
        int endianTag = input.readInt();
        input.order(switch (endianTag) {
            case ENDIAN_CONSTANT -> ByteOrder.BIG_ENDIAN;
            case REVERSE_ENDIAN_CONSTANT -> ByteOrder.LITTLE_ENDIAN;
            default -> throw new IOException("Invalid endian tag");
        });

        input.position(position);

        long checksum = input.readUnsignedInt();
        byte[] signature = input.readBytes(20);
        long fileSize = input.readUnsignedInt();
        int headerSize = input.readInt();
        input.skipBytes(4); // skip endian tag (already read)

        // verification
        if(input.size() != fileSize) {
            throw new IOException("Invalid file size");
        }

        int linkSize = input.readInt();
        int linkPosition = input.readInt();

        checkRead(input, linkSize, linkPosition);

        int mapOffset = input.readInt();

        DexMapCodec dexMapCodec = new DexMapCodec();
        dexMapCodec.read(input.slice(mapOffset));

        return new DexHeader(new String(version));
    }

    @Override
    public void write(DexHeader value, Output output) throws IOException {

    }
}
