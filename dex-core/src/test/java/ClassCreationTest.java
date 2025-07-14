import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.*;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

@Disabled
public class ClassCreationTest {

    @Test
    public void testCreateSimpleClass() throws IOException {

        ClassDefinition classDefinition = new ClassDefinition(
                Types.instanceTypeFromInternalName("test"),
                0,
                Types.instanceType(Object.class));

        MethodMember methodMember = new MethodMember(
                Types.methodTypeFromDescriptor("()V"),
                0,
                "test");

        Code code = new Code(1, 0, 3);

        List<Instruction> instructions = List.of(
                new StaticFieldInstruction(ContainerOperation.GET_OBJECT, 0,
                        Types.instanceType(System.class), "out", Types.instanceType(PrintStream.class)),
                new ConstStringInstruction(1, "Hello, World!"),
                new InvokeInstruction(Invoke.VIRTUAL, Types.instanceType(PrintStream.class), "println",
                        Types.methodTypeFromDescriptor("(Ljava/lang/String;)V"), 0, 1),
                new ReturnInstruction()
        );

        code.instructions(instructions);
        methodMember.code(code);

        classDefinition.putMethod(methodMember);

        DexFile dexFile = new DexFile(39, List.of(classDefinition), new byte[0]);

        // write to file
        DexHeader header = DexFile.CODEC.unmap(dexFile, new DexMapBuilder());

        Output output = Output.wrap();

        DexHeader.CODEC.write(header, output);

        output.pipe(new FileOutputStream("output.dex"));

        return;
    }

}
