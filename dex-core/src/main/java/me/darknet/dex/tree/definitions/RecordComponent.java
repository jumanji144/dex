package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A single component of a record class, describing one accessor and its backing field.
 *
 * @param name
 * 		Simple name of the component, which names both the accessor method and the backing field.
 * @param type
 * 		Declared type of the component, as it appears in the record header.
 * @param signature
 * 		Generic signature of the component if it is declared in terms of a more complicated type than the
 * 		component's descriptor can express. Can be {@code null} if the component has no signature.
 * @param annotations
 * 		Annotations declared on the component. Annotations on a record component are not visible through
 * 		reflection on the accessor or the field, so they are only reachable here.
 *
 * @author Matt
 */
public record RecordComponent(@NotNull String name,
                              @NotNull ClassType type,
                              @Nullable String signature,
                              @NotNull List<Annotation> annotations) {

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RecordComponent that))
            return false;

        return name.equals(that.name)
                && type.equals(that.type)
                && Objects.equals(signature, that.signature)
                && annotations.equals(that.annotations);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + Objects.hashCode(signature);
        result = 31 * result + annotations.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return name + " " + type;
    }

}
