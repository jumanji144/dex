package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static me.darknet.dex.file.instructions.Opcodes.GOTO;
import static me.darknet.dex.file.instructions.Opcodes.GOTO_16;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests that a branch ends up reaching the instruction it names, including when the distance forces it into
 * a wider form.
 * <p>
 * An instruction's width and its encoding are decided together: the branch form is chosen from the
 * distance to its target while the distance is measured from the widths of everything before it. Deciding
 * them separately leaves the branch taking up a different amount of space than the layout assumed, which
 * moves every following instruction out from under its recorded offset and points the branch at whatever
 * ends up there.
 */
class CodeLayoutTest {

    @Test
    void forwardBranchBeyondTheNarrowRangeReachesItsTarget() {
        // A goto can only reach 127 units ahead in its one-unit form, so this one has to widen.
        CodeLayout layout = layout(300, true);

        GotoInstruction branch = layout.branch();
        assertInstanceOf(GotoInstruction.class, branch);
        assertEquals(GOTO_16, branch.opcode(), "the branch must widen to reach 300 units ahead");
        assertEquals(2, branch.unitSize());

        // The target sits after the branch and every nop between them, measured in code units.
        int expectedTarget = layout.branchOffset() + branch.unitSize() + layout.nops();
        assertEquals(expectedTarget, branch.jump().position(),
                "the branch reaches the wrong instruction, so the layout moved underneath it");
    }

    @Test
    void backwardBranchBeyondTheNarrowRangeReachesItsTarget() {
        // The narrow form takes a signed offset, so a backwards branch past -128 cannot use it either.
        CodeLayout layout = layout(300, false);

        GotoInstruction branch = layout.branch();
        assertInstanceOf(GotoInstruction.class, branch);
        assertEquals(GOTO_16, branch.opcode(), "the branch must widen to reach 300 units back");
        assertEquals(0, branch.jump().position(), "a backwards branch must land on the start label");
    }

    @Test
    void shortBranchesStayInTheNarrowForm() {
        // The narrow form is still chosen when it fits, so widening is not applied unconditionally.
        CodeLayout layout = layout(1, true);

        GotoInstruction branch = layout.branch();
        assertEquals(GOTO, branch.opcode(), "a branch that fits must keep the narrow form");
        assertEquals(1, branch.unitSize());
        assertEquals(layout.branchOffset() + 1 + 1, branch.jump().position());
    }

    /**
     * Builds a method whose body is a single branch over a run of nops, writes it as a dex and reads it back.
     *
     * @param nops
     * 		How many nops the branch has to reach over.
     * @param forward
     * 		Whether the branch jumps over the nops or back across them.
     *
     * @return The branch as it came back from the written file, with the layout it was read at.
     */
    private static CodeLayout layout(int nops, boolean forward) {
        Label start = new Label(0, 0);
        Label target = new Label(1, -1);

        Code code = new Code(0, 0, 1);
        code.addInstruction(start);
        if (forward) {
            code.addInstruction(new GotoInstruction(GOTO, target));
            for (int i = 0; i < nops; i++) {
                code.addInstruction(new NopInstruction());
            }
            code.addInstruction(target);
        } else {
            for (int i = 0; i < nops; i++) {
                code.addInstruction(new NopInstruction());
            }
            code.addInstruction(new GotoInstruction(GOTO, start));
            code.addInstruction(target);
        }
        code.addInstruction(new ReturnInstruction());

        try {
            DexHeader header = DexFile.CODEC.unmap(new DexFile(39, List.of(classWith(code))), new DexMapBuilder());
            Output output = Output.wrap();
            DexHeader.CODEC.write(header, output);
            DexHeader read = DexHeader.CODEC.read(Input.wrap(output.buffer()));
            DexFile file = DexFile.CODEC.map(read, read.map());

            Code roundTripped = file.definitions().getFirst().getMethod("run", "()V").getCode();
            GotoInstruction branch = roundTripped.getInstructions().stream()
                    .filter(GotoInstruction.class::isInstance)
                    .map(GotoInstruction.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the branch did not survive the round trip"));

            // Where the branch itself sits, so the target can be checked against it rather than a constant.
            int branchOffset = 0;
            for (Instruction instruction : roundTripped.getInstructions()) {
                if (instruction == branch) {
                    break;
                }
                branchOffset += instruction.unitSize();
            }

            return new CodeLayout(branch, branchOffset, nops);
        } catch (Exception e) {
            throw new AssertionError("Failed to round trip a method with a " + nops + " unit branch", e);
        }
    }

    /**
     * @param code
     * 		Body to place in the returned class.
     *
     * @return A class defining {@code Example.run()V} with the given body.
     */
    private static ClassDefinition classWith(Code code) {
        MethodMember method = new MethodMember("run", Types.methodTypeFromDescriptor("()V"), 0x0008);
        method.setCode(code);

        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName("layout/Subject"), Types.OBJECT, 0x0001);
        definition.putMethod(method);
        return definition;
    }

    /**
     * A branch read back from a written file, together with where it sits and how far it had to reach.
     *
     * @param branch
     * 		The branch instruction as read back.
     * @param branchOffset
     * 		Offset the branch itself starts at, in code units.
     * @param nops
     * 		How many nops the branch was asked to reach over.
     */
    private record CodeLayout(GotoInstruction branch, int branchOffset, int nops) {}
}
