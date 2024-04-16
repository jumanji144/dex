package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.EncodedField;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        public void write0(ClassDataItem value, Output output, WriteContext context) throws IOException {
            output.writeULeb128(value.staticFields.size());
            output.writeULeb128(value.instanceFields.size());
            output.writeULeb128(value.directMethods.size());
            output.writeULeb128(value.virtualMethods.size());

            int lastFieldIndex = 0;
            for (EncodedField field : value.staticFields) {
                int fieldIndexDiff = context.index().fields().indexOf(field.field());
                output.writeULeb128(fieldIndexDiff - lastFieldIndex);
                output.writeULeb128(field.access());
                lastFieldIndex = fieldIndexDiff;
            }

            lastFieldIndex = 0;
            for (EncodedField field : value.instanceFields) {
                int fieldIndexDiff = context.index().fields().indexOf(field.field());
                output.writeULeb128(fieldIndexDiff - lastFieldIndex);
                output.writeULeb128(field.access());
                lastFieldIndex = fieldIndexDiff;
            }

            int lastMethodIndex = 0;
            for (EncodedMethod method : value.directMethods) {
                int methodIndexDiff = context.index().methods().indexOf(method.method());
                output.writeULeb128(methodIndexDiff - lastMethodIndex);
                output.writeULeb128(method.access());
                output.writeULeb128(method.code() == null ? 0 : context.offset(method.code()));
                lastMethodIndex = methodIndexDiff;
            }

            lastMethodIndex = 0;
            for (EncodedMethod method : value.virtualMethods) {
                int methodIndexDiff = context.index().methods().indexOf(method.method());
                output.writeULeb128(methodIndexDiff - lastMethodIndex);
                output.writeULeb128(method.access());
                output.writeULeb128(method.code() == null ? 0 : context.offset(method.code()));
                lastMethodIndex = methodIndexDiff;
            }
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(staticFields, instanceFields, directMethods, virtualMethods);
    }
}
