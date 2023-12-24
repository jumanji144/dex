package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public record ClassDefItem(TypeItem type, int access, TypeItem superType, List<TypeItem> interfaces,
                           StringItem sourceFile, ClassDataItem classData)
        implements Item {

    public static ItemCodec<ClassDefItem> CODEC = new ItemCodec<ClassDefItem>() {

        @Override
        public int alignment() {
            return 4;
        }

        @Override
        public ClassDefItem read0(Input input, DexMapAccess context) throws IOException {
            TypeItem type = context.types().get(input.readInt());
            int accessFlags = input.readInt();
            int superIndex = input.readInt();
            TypeItem superType = superIndex == -1 ? null : context.types().get(superIndex);
            int interfacesOffset = input.readInt();

            List<TypeItem> interfaces = interfacesOffset == 0
                    ? Collections.emptyList()
                    : TypeListItem.CODEC.read(input.slice(interfacesOffset), context).types();

            int sourceFileIndex = input.readInt();
            StringItem sourceFile = sourceFileIndex == -1 ? null : context.strings().get(sourceFileIndex);

            int annotationsOffset = input.readInt(); // TODO: annotations_directory_item
            int classDataOffset = input.readInt();
            ClassDataItem classData = ClassDataItem.CODEC.read(input.slice(classDataOffset), context);

            int staticValuesOffset = input.readInt(); // TODO: encoded_array_item

            return new ClassDefItem(type, accessFlags, superType, interfaces, sourceFile, classData);
        }

        @Override
        public void write0(ClassDefItem value, Output output, DexMapAccess context) throws IOException {

        }
    };

}
