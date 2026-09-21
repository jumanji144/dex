package ex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;

/**
 * Fixture covering the metadata that used to be dropped by dex-core on read: nest host/members,
 * permitted subclasses, record components, annotation defaults and parameter annotations.
 */
public class MetadataFixture {

    /** Annotation carrying a default value, which D8 encodes as dalvik.annotation.AnnotationDefault. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.TYPE})
    public @interface Defaults {
        String name() default "fallback";

        int count() default 7;

        Class<?> type() default String.class;

        String[] tags() default {"a", "b"};
    }

    /** Annotation applied to a record component, which is only visible on the component itself. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface ComponentAnn {
        int value();
    }

    /**
     * Record with a plain component, an annotated component and a generic component that needs a signature.
     */
    public record Point(@ComponentAnn(1) String name,
                        int x,
                        @ComponentAnn(2) List<String> labels,
                        Map<String, Integer> lookup) {
    }

    /** Enclosing type of a nest, whose members reach into each other's private state. */
    public static class NestHost {
        private int secret = 41;

        public class Inner {
            public int peek() {
                // Reading the outer field from the inner class is what puts both in one nest.
                return secret + 1;
            }
        }

        public int innerPeek() {
            return new Inner().peek();
        }
    }

    /** Sealed hierarchy, encoded as PermittedSubclasses on the interface. */
    public sealed interface Shape permits Circle, Square {
        double area();
    }

    public static final class Circle implements Shape {
        public double area() {
            return 0;
        }
    }

    public static final class Square implements Shape {
        public double area() {
            return 0;
        }
    }

    /**
     * Signature polymorphic call, which needs invoke-polymorphic rather than invoke-virtual.
     *
     * @param handle
     * 		Handle to invoke.
     * @param a
     * 		First argument.
     * @param b
     * 		Second argument.
     *
     * @return Result of the invocation.
     *
     * @throws Throwable
     * 		If the invocation fails.
     */
    public static int polymorphic(MethodHandle handle, int a, int b) throws Throwable {
        return (int) handle.invokeExact(a, b);
    }

    /**
     * @param annotated
     * 		Annotated parameter.
     * @param plain
     * 		Unannotated parameter that must keep its slot in the parameter annotation list.
     *
     * @return Sum of the arguments.
     */
    public int parameterAnnotations(@ComponentAnn(3) int annotated, int plain) {
        return annotated + plain;
    }

    /**
     * @param name
     * 		Name binding.
     *
     * @return The bound name.
     */
    @Defaults(name = "bound", count = 3)
    public String configured(String name) {
        return name;
    }
}
