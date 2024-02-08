package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.EncodedField;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record ClassDataItem(List<EncodedField> staticFields, List<EncodedField> instanceFields,
                            List<EncodedMethod> directMethods, List<EncodedMethod> virtualMethods) implements Item {

    public static final ItemCodec<ClassDataItem> CODEC = new ItemCodec<>() {
        @Override
        public ClassDataItem read0(Input input, DexMapAccess context) throws IOException {
            int staticFieldsSize = input.readULeb128();
            int instanceFieldsSize = input.readULeb128();
            int directMethodsSize = input.readULeb128();
            int virtualMethodsSize = input.readULeb128();

            List<EncodedField> staticFields = new ArrayList<>(staticFieldsSize);
            List<EncodedField> instanceFields = new ArrayList<>(instanceFieldsSize);
            List<EncodedMethod> directMethods = new ArrayList<>(directMethodsSize);
            List<EncodedMethod> virtualMethods = new ArrayList<>(virtualMethodsSize);

            int lastFieldIndex = 0;
            for (int i = 0; i < staticFieldsSize; i++) {
                int fieldIndexDiff = input.readULeb128();
                int accessFlags = input.readULeb128();
                lastFieldIndex += fieldIndexDiff;
                staticFields.add(new EncodedField(context.fields().get(lastFieldIndex), accessFlags));
            }

            lastFieldIndex = 0;
            for (int i = 0; i < instanceFieldsSize; i++) {
                int fieldIndexDiff = input.readULeb128();
                int accessFlags = input.readULeb128();
                lastFieldIndex += fieldIndexDiff;
                instanceFields.add(new EncodedField(context.fields().get(lastFieldIndex), accessFlags));
            }

            int lastMethodIndex = 0;
            for (int i = 0; i < directMethodsSize; i++) {
                int methodIndexDiff = input.readULeb128();
                int accessFlags = input.readULeb128();
                int codeOffset = input.readULeb128();
                lastMethodIndex += methodIndexDiff;
                CodeItem code = codeOffset == 0 ? null : CodeItem.CODEC.read(input.slice(codeOffset), context);
                directMethods.add(new EncodedMethod(context.methods().get(lastMethodIndex), accessFlags, code));
            }

            lastMethodIndex = 0;
            for (int i = 0; i < virtualMethodsSize; i++) {
                int methodIndexDiff = input.readULeb128();
                int accessFlags = input.readULeb128();
                int codeOffset = input.readULeb128();
                lastMethodIndex += methodIndexDiff;
                CodeItem code = codeOffset == 0 ? null : CodeItem.CODEC.read(input.slice(codeOffset), context);
                virtualMethods.add(new EncodedMethod(context.methods().get(lastMethodIndex), accessFlags, code));
            }

            return new ClassDataItem(staticFields, instanceFields, directMethods, virtualMethods);
        }

        @Override
        public void write0(ClassDataItem value, Output output, DexMapAccess context) throws IOException {
            // TODO
        }
    };

}
