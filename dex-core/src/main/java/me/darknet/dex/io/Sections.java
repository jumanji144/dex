package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model of all sections of a dex file.
 * <p>
 * See: <a href="https://source.android.com/docs/core/runtime/dex-format">Dex Format: File layout</a>
 */
public final class Sections {

	/** Size of a dex file header, and so the offset the first section starts at. */
	public static final int HEADER_SIZE = 0x70;

	/** Alignment of a section start, in bytes. */
	private static final int WORD = 4;

	/** Alignment of a byte-addressed section start. */
	private static final int BYTE = 1;

	private final Output stringIds;
	private final Output typeIds;
	private final Output protoIds;
	private final Output fieldIds;
	private final Output methodIds;
	private final Output classDefs;
	private final Output callSiteIds;
	private final Output methodHandles;
	private final Output data;
	private final Output hiddenApi;
	private final Output map;
	private final Output link;

	/** Every section in file order, paired with the alignment its start offset must satisfy. */
	private final List<Placement> placements;

	// The offsets of each section, which are computed by layout() and used by offsetOf() and write().
	private final Map<Output, Integer> offsets = new IdentityHashMap<>();
	private int endOffset = -1;

	public Sections(@NotNull Output output) {
		this.stringIds = output.newOutput();
		this.typeIds = output.newOutput();
		this.protoIds = output.newOutput();
		this.fieldIds = output.newOutput();
		this.methodIds = output.newOutput();
		this.classDefs = output.newOutput();
		this.callSiteIds = output.newOutput();
		this.methodHandles = output.newOutput();
		this.data = output.newOutput();
		this.hiddenApi = output.newOutput();
		this.map = output.newOutput();
		this.link = output.newOutput();

		// Pools and the map list hold fixed-width entries that are addressed by index, so they have to start
		// on a word boundary, and so does the hidden API data, which readers walk at a word-aligned stride.
		// The remaining sections promise nothing about their contents and follow whatever precedes them.
		this.placements = List.of(
				new Placement("string_ids", stringIds, WORD),
				new Placement("type_ids", typeIds, WORD),
				new Placement("proto_ids", protoIds, WORD),
				new Placement("field_ids", fieldIds, WORD),
				new Placement("method_ids", methodIds, WORD),
				new Placement("class_defs", classDefs, WORD),
				new Placement("call_site_ids", callSiteIds, WORD),
				new Placement("method_handles", methodHandles, WORD),
				new Placement("data", data, WORD),
				new Placement("hiddenapi_class_data", hiddenApi, WORD),
				new Placement("map_list", map, WORD),
				new Placement("link_data", link, BYTE));
	}

	/**
	 * Rounds an offset up to the next multiple of the given alignment.
	 *
	 * @param offset
	 * 		Offset to align.
	 * @param alignment
	 * 		Required alignment, which must be a power of two.
	 *
	 * @return The smallest offset at or after the given one that satisfies the alignment.
	 */
	public static int align(int offset, int alignment) {
		return (offset + alignment - 1) & -alignment;
	}

	/**
	 * Fixes every section's start offset, laying them out consecutively from the given file offset with
	 * padding wherever a section's alignment requires it.
	 * <p>
	 * Must be called once the sections' contents are final, since a section's size decides where the next one begins.
	 *
	 * @param start
	 * 		Offset of the first section, which is the header size for a dex file.
	 */
	public void layout(int start) {
		int offset = start;
		for (Placement placement : placements) {
			offset = align(offset, placement.alignment());
			offsets.put(placement.output(), offset);
			offset += placement.output().position();
		}
		this.endOffset = offset;
	}

	/**
	 * @param section
	 * 		Section to look up, which must be one of this instance's own sections.
	 *
	 * @return Offset the section starts at.
	 *
	 * @throws IllegalStateException
	 * 		If the layout has not been computed yet.
	 */
	public int offsetOf(@NotNull Output section) {
		Integer offset = offsets.get(section);
		if (offset == null)
			throw new IllegalStateException("Unknown section, or layout() has not been called yet");
		return offset;
	}

	/**
	 * @return Total size of the laid out file, including the padding between sections.
	 *
	 * @throws IllegalStateException
	 * 		If the layout has not been computed yet.
	 */
	public int size() {
		if (endOffset < 0)
			throw new IllegalStateException("layout() has not been called yet");
		return endOffset;
	}

	/**
	 * Writes every section at the offset {@link #layout(int)} gave it.
	 *
	 * @param output
	 * 		Output positioned at the start of the file, or at the header when the header is written separately.
	 *
	 * @throws IOException
	 * 		If a section cannot be written.
	 */
	public void write(@NotNull Output output) throws IOException {
		for (Placement placement : placements) {
			// Pad up to the offset the layout promised rather than appending to whatever came before, so the
			// bytes always land where the header and map list claim they are.
			output.position(offsetOf(placement.output()));
			output.write(placement.output());
		}
	}

	/**
	 * @return Buffer for the {@code string_ids} section.
	 */
	public @NotNull Output stringIds() {
		return stringIds;
	}

	/**
	 * @return Buffer for the {@code type_ids} section.
	 */
	public @NotNull Output typeIds() {
		return typeIds;
	}

	/**
	 * @return Buffer for the {@code proto_ids} section.
	 */
	public @NotNull Output protoIds() {
		return protoIds;
	}

	/**
	 * @return Buffer for the {@code field_ids} section.
	 */
	public @NotNull Output fieldIds() {
		return fieldIds;
	}

	/**
	 * @return Buffer for the {@code method_ids} section.
	 */
	public @NotNull Output methodIds() {
		return methodIds;
	}

	/**
	 * @return Buffer for the {@code class_defs} section.
	 */
	public @NotNull Output classDefs() {
		return classDefs;
	}

	/**
	 * @return Buffer for the {@code call_site_ids} section.
	 */
	public @NotNull Output callSiteIds() {
		return callSiteIds;
	}

	/**
	 * @return Buffer for the {@code method_handles} section.
	 */
	public @NotNull Output methodHandles() {
		return methodHandles;
	}

	/**
	 * @return Buffer for the {@code data} section.
	 */
	public @NotNull Output data() {
		return data;
	}

	/**
	 * @return Buffer for the {@code link} section.
	 */
	public @NotNull Output link() {
		return link;
	}

	/**
	 * Note, this is not part of the official dex format linked in the class documentation.
	 * Refer to {@code libdexfile/dex/dex_file.h} for the hidden API data format.
	 *
	 * @return Buffer for the {@code hiddenapi_class_data} section.
	 */
	public @NotNull Output hiddenApi() {
		return hiddenApi;
	}

	/**
	 * Note, this is not part of the official dex format linked in the class documentation.
	 * Refer to {@code libdexfile/dex/dex_file.h} for the hidden API data format.
	 *
	 * @return Buffer for {@code map_list} / {@code struct MapList}.
	 */
	public @NotNull Output map() {
		return map;
	}

	/**
	 * @param name
	 * 		Section name, used when reporting a layout problem.
	 * @param output
	 * 		Buffer holding the section.
	 * @param alignment
	 * 		Alignment the section's start offset must satisfy.
	 */
	private record Placement(@NotNull String name, @NotNull Output output, int alignment) {}
}
