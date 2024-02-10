package me.darknet.dex.codecs;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.io.Codec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.io.Sections;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.Adler32;

public class DexHeaderCodec implements Codec<DexHeader> {

    private final byte[] DEX_FILE_MAGIC = new byte[] { 0x64, 0x65, 0x78, 0x0a };
    private final int REVERSE_ENDIAN_CONSTANT = 0x78563412;
    private final int ENDIAN_CONSTANT = 0x12345678;

    private void checkRead(Input input, int size, int position) throws IOException {
        if(size == 0) return;
        if(input.position() == position + size) return;

        throw new IOException("Invalid section, given size: " + size + ", actual size: " + (input.position() - position));
    }

    @Override
    public DexHeader read(Input input) throws IOException {
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

        byte[] linkData = input.slice(linkPosition).readBytes(linkSize);

        int mapOffset = input.readInt();

        DexMapCodec dexMapCodec = new DexMapCodec();
        DexMap map = dexMapCodec.read(input.slice(mapOffset));

        return new DexHeader(new String(version), linkData, map);
    }

    private int writeSectionInfo(Output output, Output section, int offset) throws IOException {
        int size = section.position();
        output.writeInt(size);
        output.writeInt(offset);
        return size;
    }

    private byte[] computeSignature(ByteBuffer buffer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(buffer);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(DexHeader value, Output output) throws IOException {
        // the final header requires a checksum of the entire file,
        // so we need to write all file contents before writing the header
        // neither do we know the file size until this is done
        // this will be filled with data by the map
        DexMapCodec dexMapCodec = new DexMapCodec();
        dexMapCodec.write(value.map(), output);

        Sections sections = dexMapCodec.sections(); // chunked up version of data
        int linkPosition = sections.size();

        sections.link().write(value.link());

        // now that we have this we must work our way up to the header
        Output part1 = output.newOutput();
        part1.writeInt(0x70 + sections.size()); // file size
        part1.writeInt(0x70); // header size
        part1.writeInt(part1.order() == ByteOrder.BIG_ENDIAN ? ENDIAN_CONSTANT : REVERSE_ENDIAN_CONSTANT);
        part1.writeInt(value.link().length);
        part1.writeInt(linkPosition);

        int mapPosition = sections.size() - sections.map().position();
        part1.writeInt(0x70 + mapPosition); // map offset

        // section data
        int offset = 0x70; // this is where data section begins
        offset += writeSectionInfo(part1, sections.stringIds(), offset);
        offset += writeSectionInfo(part1, sections.typeIds(), offset);
        offset += writeSectionInfo(part1, sections.protoIds(), offset);
        offset += writeSectionInfo(part1, sections.fieldIds(), offset);
        offset += writeSectionInfo(part1, sections.methodIds(), offset);
        offset += writeSectionInfo(part1, sections.classDefs(), offset);
        writeSectionInfo(part1, sections.data(), offset);

        // now we combine the data
        Output combined = sections.combine();

        part1.write(combined);

        byte[] signature = computeSignature(part1.buffer());

        // now we create a new header with the signature
        Output part2 = output.newOutput();
        part2.writeBytes(signature);
        part2.write(part1);

        // now we compute the adler32 of that header
        Adler32 adler32 = new Adler32();
        adler32.update(part2.buffer());
        long checksum = adler32.getValue();

        // now we write the final header
        output.writeBytes(DEX_FILE_MAGIC);
        output.writeBytes(value.version().getBytes());
        output.writeInt((int) checksum);
        output.write(part2);
    }
}
