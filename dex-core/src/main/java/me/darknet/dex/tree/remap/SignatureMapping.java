package me.darknet.dex.tree.remap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for mapping generic signatures using a {@link DefinitionRemapper}.
 * <p>
 * Refer to JVMS "Signatures" for the syntax.
 */
public class SignatureMapping {
	private SignatureMapping() {
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param signature
	 * 		Signature to map, or {@code null}.
	 *
	 * @return Mapped signature, or {@code null}.
	 */
	public static @Nullable String mapClassSignature(@NotNull DefinitionRemapper remapper, @Nullable String signature) {
		if (signature == null) {
			return null;
		}
		return new Parser(remapper, signature).mapClassSignature();
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param signature
	 * 		Signature to map, or {@code null}.
	 *
	 * @return Mapped signature, or {@code null}.
	 */
	public static @Nullable String mapMethodSignature(@NotNull DefinitionRemapper remapper, @Nullable String signature) {
		if (signature == null) {
			return null;
		}
		return new Parser(remapper, signature).mapMethodSignature();
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param signature
	 * 		Signature to map, or {@code null}.
	 *
	 * @return Mapped signature, or {@code null}.
	 */
	public static @Nullable String mapTypeSignature(@NotNull DefinitionRemapper remapper, @Nullable String signature) {
		if (signature == null) {
			return null;
		}
		return new Parser(remapper, signature).mapTypeSignature();
	}

	/**
	 * Generic signature parser and remapping utility.
	 */
	private static final class Parser {
		private final DefinitionRemapper remapper;
		private final String signature;
		private int index;

		private Parser(@NotNull DefinitionRemapper remapper, @NotNull String signature) {
			this.remapper = remapper;
			this.signature = signature;
		}

		private @NotNull String mapClassSignature() {
			StringBuilder out = new StringBuilder(signature.length() + 16);

			// Optional formal type parameters, only present for generic classes.
			if (peek('<'))
				remapFormalTypeParameters(out);

			// Superclass and superinterface signatures.
			remapClassTypeSignature(out);
			while (hasRemaining())
				remapClassTypeSignature(out);

			return out.toString();
		}

		private @NotNull String mapMethodSignature() {
			StringBuilder out = new StringBuilder(signature.length() + 16);

			// Optional formal type parameters, only present for generic methods.
			if (peek('<'))
				remapFormalTypeParameters(out);

			// Argument types.
			expect('(');
			out.append(read());
			while (!peek(')'))
				remapJavaTypeSignature(out);

			// Return type.
			out.append(read());
			if (peek('V'))
				out.append(read());
			else
				remapJavaTypeSignature(out);

			// Optional throws signatures.
			while (hasRemaining() && peek('^')) {
				out.append(read());
				if (peek('T'))
					remapTypeVariableSignature(out);
				else
					remapClassTypeSignature(out);
			}
			return out.toString();
		}

		private @NotNull String mapTypeSignature() {
			StringBuilder out = new StringBuilder(signature.length() + 16);

			// Type signatures can only be field type signatures.
			remapFieldTypeSignature(out);
			if (hasRemaining())
				throw error("Unexpected trailing signature data");

			return out.toString();
		}

		private void remapFormalTypeParameters(@NotNull StringBuilder out) {
			// Example: <T:Ljava/lang/Object;U:Ljava/util/List<T;>;>
			// - T and U are the formal type parameters
			// - :Ljava/lang/Object; is the class bound of T
			// - :Ljava/util/List<T;>; is the class bound of U
			expect('<');
			out.append(read());
			while (!peek('>')) {
				out.append(readIdentifierUntil(':'));
				expect(':');
				out.append(read());
				if (isFieldTypeStart(peekOrEnd())) {
					remapFieldTypeSignature(out);
				}
				while (peek(':')) {
					out.append(read());
					remapFieldTypeSignature(out);
				}
			}
			out.append(read());
		}

		private void remapFieldTypeSignature(@NotNull StringBuilder out) {
			// Field signatures can be class type signatures, type variable signatures, or array type signatures.
			char next = requirePeek();
			switch (next) {
				case 'L' -> remapClassTypeSignature(out);
				case 'T' -> remapTypeVariableSignature(out);
				case '[' -> {
					out.append(read());
					remapJavaTypeSignature(out);
				}
				default -> throw error("Expected field type signature");
			}
		}

		private void remapJavaTypeSignature(@NotNull StringBuilder out) {
			// Types can be primitives, class types, type variables, or array types.
			// The last three are already handled by field type signature parsing.
			char next = requirePeek();
			switch (next) {
				case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> out.append(read());
				default -> remapFieldTypeSignature(out);
			}
		}

		private void remapTypeVariableSignature(@NotNull StringBuilder out) {
			// Example: TU; - U is the type variable name, and ; ends the signature.
			expect('T');
			out.append(read());
			out.append(readIdentifierUntil(';'));
			expect(';');
			out.append(read());
		}

		private void remapClassTypeSignature(@NotNull StringBuilder out) {
			expect('L');
			read();

			// Class type signatures can have multiple segments separated by '.', and each segment can have optional type arguments.
			List<Segment> segments = new ArrayList<>(2);
			StringBuilder segmentName = new StringBuilder();
			while (true) {
				char next = requirePeek();
				if (next == ';' || next == '<' || next == '.') {
					String arguments = null;
					if (segmentName.isEmpty())
						throw error("Empty class type segment");
					if (next == '<') {
						arguments = remapTypeArguments();
						next = requirePeek();
					}
					segments.add(new Segment(segmentName.toString(), arguments));
					if (next == '.') {
						read();
						segmentName.setLength(0);
						continue;
					}
					expect(';');
					read();
					break;
				}
				segmentName.append(read());
			}

			// Prefix --> Mapped prefix.
			String originalPrefix = segments.getFirst().name();
			String mappedPrefix = mapInternalName(originalPrefix);

			// First segment --> mapped as a top-level class, so we can directly map the segment name to the mapped prefix.
			out.append('L').append(mappedPrefix);
			if (segments.getFirst().arguments() != null)
				out.append(segments.getFirst().arguments());

			// Nested segments --> mapped as inner classes, so we need to derive the mapped segment name from the mapped prefix.
			String previousMappedPrefix = mappedPrefix;
			for (int i = 1; i < segments.size(); i++) {
				originalPrefix = originalPrefix + '$' + segments.get(i).name();
				mappedPrefix = mapInternalName(originalPrefix);
				out.append('.').append(deriveNestedSegment(previousMappedPrefix, mappedPrefix));
				if (segments.get(i).arguments() != null)
					out.append(segments.get(i).arguments());
				previousMappedPrefix = mappedPrefix;
			}
			out.append(';');
		}

		private @NotNull String remapTypeArguments() {
			StringBuilder out = new StringBuilder();
			expect('<');
			out.append(read());
			while (!peek('>')) {
				char next = requirePeek();
				if (next == '*') {
					out.append(read());
					continue;
				}
				if (next == '+' || next == '-')
					out.append(read());
				remapFieldTypeSignature(out);
			}
			out.append(read());
			return out.toString();
		}

		private @NotNull String deriveNestedSegment(@NotNull String previousMappedPrefix, @NotNull String mappedPrefix) {
			String nestedPrefix = previousMappedPrefix + '$';
			if (mappedPrefix.startsWith(nestedPrefix))
				return mappedPrefix.substring(nestedPrefix.length());
			int slash = mappedPrefix.lastIndexOf('/');
			int dollar = mappedPrefix.lastIndexOf('$');
			return mappedPrefix.substring(Math.max(slash, dollar) + 1);
		}

		private @NotNull String mapInternalName(@NotNull String internalName) {
			return Objects.requireNonNull(remapper.mapInternalName(internalName), "mapInternalName must not return null");
		}

		private boolean hasRemaining() {
			return index < signature.length();
		}

		private boolean peek(char expected) {
			return hasRemaining() && signature.charAt(index) == expected;
		}

		private char peekOrEnd() {
			return hasRemaining() ? signature.charAt(index) : 0;
		}

		private char requirePeek() {
			if (!hasRemaining()) {
				throw error("Unexpected end of signature");
			}
			return signature.charAt(index);
		}

		private char read() {
			return signature.charAt(index++);
		}

		private void expect(char expected) {
			if (!peek(expected))
				throw error("Expected '" + expected + "'");
		}

		private @NotNull String readIdentifierUntil(char delimiter) {
			int start = index;
			while (hasRemaining() && signature.charAt(index) != delimiter)
				index++;
			if (!hasRemaining())
				throw error("Expected '" + delimiter + "'");
			return signature.substring(start, index);
		}

		private boolean isFieldTypeStart(char c) {
			return c == 'L' || c == 'T' || c == '[';
		}

		private @NotNull IllegalArgumentException error(@NotNull String message) {
			return new IllegalArgumentException(message + " in signature '" + signature + "' at index " + index);
		}

		private record Segment(@NotNull String name, @Nullable String arguments) {}
	}
}
