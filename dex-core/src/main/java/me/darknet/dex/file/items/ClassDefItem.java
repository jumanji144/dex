package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public record ClassDefItem(TypeItem type, int access, @Nullable TypeItem superType, TypeListItem interfaces,
                           @Nullable StringItem sourceFile, @Nullable AnnotationsDirectoryItem directory,
                           ClassDataItem classData, @Nullable EncodedArrayItem staticValues)
        implements Item {

    public static ItemCodec<ClassDefItem> CODEC = new ItemCodec<>() {

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

            TypeListItem interfaces = interfacesOffset == 0
                    ? TypeListItem.EMPTY
                    : TypeListItem.CODEC.read(input.slice(interfacesOffset), context);

            int sourceFileIndex = input.readInt();
            StringItem sourceFile = sourceFileIndex == -1 ? null : context.strings().get(sourceFileIndex);

            int annotationsOffset = input.readInt();
            AnnotationsDirectoryItem directory = annotationsOffset == 0
                    ? null
                    : AnnotationsDirectoryItem.CODEC.read(input.slice(annotationsOffset), context);
            int classDataOffset = input.readInt();
            ClassDataItem classData = ClassDataItem.CODEC.read(input.slice(classDataOffset), context);

            int staticValuesOffset = input.readInt();
            EncodedArrayItem staticValues = staticValuesOffset == 0
                    ? null
                    : EncodedArrayItem.CODEC.read(input.slice(staticValuesOffset), context);

            return new ClassDefItem(type, accessFlags, superType, interfaces, sourceFile, directory, classData,
                    staticValues);
        }

        @Override
        public void write0(ClassDefItem value, Output output, WriteContext context) throws IOException {
            output.writeInt(context.index().types().indexOf(value.type()));
            output.writeInt(value.access());
            output.writeInt(value.superType() == null ? -1 : context.index().types().indexOf(value.superType()));
            output.writeInt(value.interfaces().types().isEmpty() ? 0 : context.offset(value.interfaces()));
            output.writeInt(value.sourceFile() == null ? -1 : context.index().strings().indexOf(value.sourceFile()));
            output.writeInt(value.directory() == null ? 0 : context.offset(value.directory()));
            output.writeInt(context.offset(value.classData()));
            output.writeInt(value.staticValues() == null ? 0 : context.offset(value.staticValues()));
        }
    };

}
