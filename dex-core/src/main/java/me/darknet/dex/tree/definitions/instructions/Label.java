package me.darknet.dex.tree.definitions.instructions;

public class Label implements Instruction {

    public static int UNASSIGNED = -1;

    private int index = UNASSIGNED;
    private int position = UNASSIGNED;

    public Label() {
    }

    public Label(int index, int position) {
        this.index = index;
        this.position = position;
    }

    public int index() {
        return index;
    }

    public void index(int index) {
        this.index = index;
    }

    public int position() {
        return position;
    }

    public void position(int offset) {
        this.position = offset;
    }

    @Override
    public int opcode() {
        return -1;
    }

    @Override
    public int byteSize() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Label label = (Label) obj;
        return index == label.index && position == label.position;
    }

    @Override
    public int hashCode() {
        int result = index;
        result = 31 * result + position;
        return result;
    }

    @Override
    public String toString() {
        return "@label " + index + " (offset: " + position + ")";
    }
}
