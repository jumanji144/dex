package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.EncodedField;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ClassDataItem(List<EncodedField> staticFields, List<EncodedField> instanceFields,
                            List<EncodedMethod> directMethods, List<EncodedMethod> virtualMethods) implements Item {

    public static final ItemCodec<ClassDataItem> CODEC = new ItemCodec<>() {
        @Override
        public ClassDataItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int staticFieldsSize = input.readULeb128();
            int instanceFieldsSize = input.readULeb128();
            int directMethodsSize = input.readULeb128();
            int virtualMethodsSize = input.readULeb128();

            List<EncodedField> staticFields = new ArrayList<>(staticFieldsSize);
            List<EncodedField> instanceFields = new ArrayList<>(instanceFieldsSize);
            List<EncodedMethod> directMethods = new ArrayList<>(directMethodsSize);
            List<EncodedMethod> virtualMethods = new ArrayList<>(virtualMethodsSize);

            readFields(input, context, staticFieldsSize, staticFields);
            readFields(input, context, instanceFieldsSize, instanceFields);

            readMethods(input, context, directMethodsSize, directMethods);
            readMethods(input, context, virtualMethodsSize, virtualMethods);

            return new ClassDataItem(staticFields, instanceFields, directMethods, virtualMethods);
        }

        private static void readMethods(Input input, DexMapAccess context, int directMethodsSize, List<EncodedMethod> directMethods) throws IOException {
            int lastMethodIndex = 0;
            for (int i = 0; i < directMethodsSize; i++) {
                int methodIndexDiff = input.readULeb128();
                int accessFlags = input.readULeb128();
                int codeOffset = input.readULeb128();
                lastMethodIndex += methodIndexDiff;
                CodeItem code = codeOffset == 0 ? null : CodeItem.CODEC.read(input.slice(codeOffset), context);
                directMethods.add(new EncodedMethod(context.methods().get(lastMethodIndex), accessFlags, code));
            }
        }

        private static void readFields(Input input, DexMapAccess context, int fieldsSize, List<EncodedField> fields) throws IOException {
            int lastFieldIndex = 0;
            for (int i = 0; i < fieldsSize; i++) {
                int fieldIndexDiff = input.readULeb128();
                int accessFlags = input.readULeb128();
                lastFieldIndex += fieldIndexDiff;
                fields.add(new EncodedField(context.fields().get(lastFieldIndex), accessFlags));
            }
        }

        @Override
        public void write0(ClassDataItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeULeb128(value.staticFields.size());
            output.writeULeb128(value.instanceFields.size());
            output.writeULeb128(value.directMethods.size());
            output.writeULeb128(value.virtualMethods.size());

            writeFields(output, context, value.staticFields);
            writeFields(output, context, value.instanceFields);

            writeMethods(output, context, value.directMethods);
            writeMethods(output, context, value.virtualMethods);
        }
    };

    private static void writeMethods(Output output, WriteContext context, List<EncodedMethod> methods) throws IOException {
        int lastMethodIndex = 0;
        for (EncodedMethod method : methods) {
            int methodIndexDiff = context.index().methods().indexOf(method.method());
            output.writeULeb128(methodIndexDiff - lastMethodIndex);
            output.writeULeb128(method.access());
            output.writeULeb128(method.code() == null ? 0 : context.offset(method.code()));
            lastMethodIndex = methodIndexDiff;
        }
    }

    private static void writeFields(Output output, WriteContext context, List<EncodedField> fields) throws IOException {
        int lastFieldIndex = 0;
        for (EncodedField field : fields) {
            int fieldIndexDiff = context.index().fields().indexOf(field.field());
            output.writeULeb128(fieldIndexDiff - lastFieldIndex);
            output.writeULeb128(field.access());
            lastFieldIndex = fieldIndexDiff;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(staticFields, instanceFields, directMethods, virtualMethods);
    }
}
