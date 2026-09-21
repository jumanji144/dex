package me.darknet.dex.codecs;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.io.Sections;
import org.jetbrains.annotations.NotNull;

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

    private void checkRead(@NotNull Input input, int size, int position) throws IOException {
        if(size == 0) return;
        if(input.position() == position + size) return;

        throw new IOException("Invalid section, given size: " + size + ", actual size: " + (input.position() - position));
    }

    @Override
    public @NotNull DexHeader read(@NotNull Input input) throws IOException {
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

        String versionString = new String(version).substring(0, 3); // cut 0 character
        int versionInt = Integer.parseInt(versionString);
        return new DexHeader(versionInt, linkData, map);
    }

    private void writeSectionInfo(@NotNull Output output, int offset, int size) throws IOException {
        output.writeInt(size);
        output.writeInt(size == 0 ? 0 : offset);
    }

    private byte[] computeSignature(@NotNull ByteBuffer buffer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(buffer);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(@NotNull DexHeader value, @NotNull Output output) throws IOException {
        // the final header requires a checksum of the entire file,
        // so we need to write all file contents before writing the header
        // neither do we know the file size until this is done
        // this will be filled with data by the map
        DexMapCodec dexMapCodec = new DexMapCodec();

        // The map codec lays the whole file out, link data included, so that every section's size is known
        // before the offsets and the file size are derived from it.
        dexMapCodec.write(value.map(), value.link(), output);

        Sections sections = dexMapCodec.sections(); // chunked up version of data
        int linkPosition = sections.offsetOf(sections.link());

        Output header = output.newOutput();

        String version = "0" + value.version() + "\0";

        header.writeBytes(DEX_FILE_MAGIC);
        header.writeBytes(version.getBytes());
        // skip checksum and signature
        header.seek(4 + 20);

        // now that we have this we must work our way up to the header
        header.writeInt(sections.size()); // file size, including the padding between sections
        header.writeInt(Sections.HEADER_SIZE); // header size
        header.writeInt(header.order() == ByteOrder.BIG_ENDIAN ? REVERSE_ENDIAN_CONSTANT : ENDIAN_CONSTANT);
        header.writeInt(value.link().length);
        header.writeInt(value.link().length == 0 ? 0 : linkPosition);

        header.writeInt(sections.offsetOf(sections.map()));

        // section data
        var map = value.map();

        writeSectionInfo(header, sections.offsetOf(sections.stringIds()), map.strings().size());
        writeSectionInfo(header, sections.offsetOf(sections.typeIds()), map.types().size());
        writeSectionInfo(header, sections.offsetOf(sections.protoIds()), map.protos().size());
        writeSectionInfo(header, sections.offsetOf(sections.fieldIds()), map.fields().size());
        writeSectionInfo(header, sections.offsetOf(sections.methodIds()), map.methods().size());
        writeSectionInfo(header, sections.offsetOf(sections.classDefs()), map.classes().size());

        // The hidden API data is an item of the data section, so the section has to span through it.
        // Reporting only the data buffer would leave that item outside the bounds the header describes.
        int dataEnd = sections.hiddenApi().position() == 0
                ? sections.offsetOf(sections.data()) + sections.data().position()
                : sections.offsetOf(sections.hiddenApi()) + sections.hiddenApi().position();
        writeSectionInfo(header, sections.offsetOf(sections.data()), dataEnd - sections.offsetOf(sections.data()));

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
