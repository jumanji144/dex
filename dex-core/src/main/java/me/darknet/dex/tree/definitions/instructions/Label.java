package me.darknet.dex.tree.definitions.instructions;

public class Label implements Instruction {

    public static int UNASSIGNED = -1;

    private int index = UNASSIGNED;
    private int offset = UNASSIGNED;

    public Label() {
    }

    public Label(int index, int offset) {
        this.index = index;
        this.offset = offset;
    }

    public int index() {
        return index;
    }

    public void index(int index) {
        this.index = index;
    }

    public int offset() {
        return offset;
    }

    public void offset(int offset) {
        this.offset = offset;
    }

    @Override
    public int opcode() {
        return -1;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Label label = (Label) obj;
        return index == label.index && offset == label.offset;
    }

    @Override
    public int hashCode() {
        int result = index;
        result = 31 * result + offset;
        return result;
    }

    @Override
    public String toString() {
        return "@label " + index + " (offset: " + offset + ")";
    }
}
