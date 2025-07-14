package me.darknet.dex.file.instructions;

public sealed interface PseudoFormat extends Format permits FormatFilledArrayData, FormatPackedSwitch, FormatSparseSwitch {
	int P_PACKED_SWITCH = 0x0100;
	int P_SPARSE_SWITCH = 0x0200;
	int P_FILL_ARRAY_DATA = 0x0300;
}
