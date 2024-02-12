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
import java.util.Map;
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
        input.order(ByteOrder.LITTLE_ENDIAN); // read it as little endian
        int endianTag = input.readInt();
        input.order(switch (endianTag) {
            case ENDIAN_CONSTANT -> ByteOrder.LITTLE_ENDIAN;
            case REVERSE_ENDIAN_CONSTANT -> ByteOrder.BIG_ENDIAN;
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

    private void writeSectionInfo(Output output, int offset, int size) throws IOException {
        output.writeInt(size);
        output.writeInt(offset);
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
        Map<Object, Integer> offsets = dexMapCodec.offsets(); // offsets of sections
        int linkPosition = sections.size();

        sections.link().write(value.link());

        Output header = output.newOutput();

        header.writeBytes(DEX_FILE_MAGIC);
        header.writeBytes(value.version().getBytes());
        // skip checksum and signature
        header.seek(4 + 20);

        // now that we have this we must work our way up to the header
        header.writeInt(0x70 + sections.size()); // file size
        header.writeInt(0x70); // header size
        header.writeInt(header.order() == ByteOrder.BIG_ENDIAN ? REVERSE_ENDIAN_CONSTANT : ENDIAN_CONSTANT);
        header.writeInt(value.link().length);
        header.writeInt(linkPosition);

        header.writeInt(offsets.get(sections.map()));

        // section data
        var map = value.map();

        writeSectionInfo(header, offsets.get(sections.stringIds()), map.strings().size());
        writeSectionInfo(header, offsets.get(sections.typeIds()), map.types().size());
        writeSectionInfo(header, offsets.get(sections.protoIds()), map.protos().size());
        writeSectionInfo(header, offsets.get(sections.fieldIds()), map.fields().size());
        writeSectionInfo(header, offsets.get(sections.methodIds()), map.methods().size());
        writeSectionInfo(header, offsets.get(sections.classDefs()), map.classes().size());
        writeSectionInfo(header, offsets.get(sections.data()), sections.data().position());

        sections.write(header);

        // now we compute signature and checksum
        ByteBuffer headerBuffer = header.buffer();
        byte[] signature = computeSignature(headerBuffer.slice(8 + 4 + 20, headerBuffer.limit() - 8 - 4 - 20));
        headerBuffer.position(8 + 4); // skip magic and version and checksum
        headerBuffer.put(signature);

        Adler32 adler32 = new Adler32();
        adler32.update(headerBuffer.slice(8 + 4, headerBuffer.limit() - 8 - 4));
        long checksum = adler32.getValue();
        headerBuffer.position(8);
        headerBuffer.putInt((int) checksum);

        output.write(header);
    }
}
