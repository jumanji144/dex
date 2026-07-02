package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.AccessFlags;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.ConstStringInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.type.Types;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeCodecTest implements AccessFlags {

    @Test
    void roundTripsDebugInfoAndPayloadInstructions() throws Exception {
        Label start = label(0, 0, 10);
        Label switchCase = label(1, 4, 20);
        Label end = label(2, 8, 30);
        Label handler = label(3, 10, 40);

        Code code = new Code(1, 1, 2);
        code.addInstruction(start);
        code.addInstruction(new FillArrayDataInstruction(0, new byte[] {1, 2, 3, 4}, 1));
        code.addInstruction(new PackedSwitchInstruction(0, 7, List.of(switchCase, end)));
        code.addInstruction(new SparseSwitchInstruction(0, new LinkedHashMap<>(java.util.Map.of(1, switchCase, 2, end))));
        code.addInstruction(new GotoInstruction(end));
        code.addInstruction(switchCase);
        code.addInstruction(new GotoInstruction(end));
        code.addInstruction(handler);
        code.addInstruction(new ReturnInstruction(0, Return.VOID));
        code.addInstruction(end);
        code.addInstruction(new ReturnInstruction(0, Return.VOID));
        code.addTryCatch(new TryCatch(start, end, List.of(new Handler(handler, null))));
        code.setDebugInfo(new DebugInformation(
                List.of(
                        new DebugInformation.LineNumber(start, 10),
                        new DebugInformation.LineNumber(switchCase, 20),
                        new DebugInformation.LineNumber(end, 30)
                ),
                List.of("arg0"),
                List.of(new DebugInformation.LocalVariable(0, "local", Types.INT, null, start, end))
        ));

        MethodMember method = new MethodMember("payloads", Types.methodTypeFromDescriptor("()V"), ACC_PUBLIC | ACC_STATIC);
        method.setCode(code);
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName("example/Payloads"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        definition.putMethod(method);

        DexFile dexFile = new DexFile(39, List.of(definition));
        DexHeader header = DexFile.CODEC.unmap(dexFile, new DexMapBuilder());

        Output output = Output.wrap();
        DexHeader.CODEC.write(header, output);
        DexHeader roundTrippedHeader = DexHeader.CODEC.read(Input.wrap(output.buffer()));
        DexFile roundTripped = DexFile.CODEC.map(roundTrippedHeader, roundTrippedHeader.map());

        MethodMember roundTrippedMethod = roundTripped.definitions().getFirst().getMethods().values().iterator().next();
        Code roundTrippedCode = roundTrippedMethod.getCode();
        assertEquals(code.getDebugInfo().parameterNames(), roundTrippedCode.getDebugInfo().parameterNames());
        assertEquals(debugLines(code.getDebugInfo()), debugLines(roundTrippedCode.getDebugInfo()));
        assertEquals(debugLocals(code.getDebugInfo()), debugLocals(roundTrippedCode.getDebugInfo()));
        assertEquals(tryCatchSemantics(code), tryCatchSemantics(roundTrippedCode));
        assertTrue(roundTrippedCode.getInstructions().stream().anyMatch(FillArrayDataInstruction.class::isInstance));
        assertTrue(roundTrippedCode.getInstructions().stream().anyMatch(PackedSwitchInstruction.class::isInstance));
        assertTrue(roundTrippedCode.getInstructions().stream().anyMatch(SparseSwitchInstruction.class::isInstance));
        assertTrue(roundTrippedCode.getInstructions().stream().anyMatch(NopInstruction.class::isInstance));
    }

    @Test
    void preservesConstStringJumboEncodingForJumboSample() throws Exception {
        DexHeader header = TestUtils.getDexHeader("056-const-string-jumbo");
        DexFile dexFile = DexFile.CODEC.map(header, header.map());
        DexHeader out = DexFile.CODEC.unmap(dexFile, new DexMapBuilder());

        boolean originalHasJumbo = hasJumboConstString(header);
        boolean roundTrippedHasJumbo = hasJumboConstString(out);

        assertTrue(originalHasJumbo, "The jumbo sample should contain a jumbo const-string");
        assertTrue(roundTrippedHasJumbo, "Round-tripped code should preserve jumbo const-string instructions");
    }

    private static Label label(int index, int position, int lineNumber) {
        Label label = new Label(index, position);
        label.lineNumber(lineNumber);
        return label;
    }

    private static boolean hasJumboConstString(DexHeader header) {
        for (CodeItem codeItem : header.map().codes()) {
            for (Format format : codeItem.instructions()) {
                if (format instanceof FormatAAopBBBB32 jumbo
                        && jumbo.op() == ConstStringInstruction.CONST_STRING_JUMBO) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> debugLines(DebugInformation debugInformation) {
        return debugInformation.lineNumbers().stream()
                .map(line -> line.label().position() + ":" + line.line())
                .toList();
    }

    private static List<String> debugLocals(DebugInformation debugInformation) {
        return debugInformation.locals().stream()
                .map(local -> local.register()
                        + ":" + local.name()
                        + ":" + local.type().descriptor()
                        + ":" + Objects.toString(local.signature())
                        + ":" + local.start().position()
                        + ":" + local.end().position())
                .toList();
    }

    private static List<String> tryCatchSemantics(Code code) {
        return code.tryCatch().stream()
                .map(tryCatch -> tryCatch.begin().position()
                        + ":" + tryCatch.end().position()
                        + ":" + tryCatch.handlers().stream()
                        .map(handler -> handler.handler().position() + ":" + Objects.toString(handler.exceptionType()))
                        .toList())
                .toList();
    }
}
