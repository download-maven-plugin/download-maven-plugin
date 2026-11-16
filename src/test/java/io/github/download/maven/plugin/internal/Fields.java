package io.github.download.maven.plugin.internal;

import java.lang.reflect.Field;
import org.codehaus.plexus.util.ReflectionUtils;

/**
 * Reflective access to private fields, e.g. mojo parameters that Maven injects.
 */
public final class Fields {

    private Fields() {
    }

    /**
     * Sets a field declared by the object's class or one of its superclasses.
     */
    public static void set(final Object object, final String name, final Object value) {
        try {
            field(object.getClass(), name).set(object, value);
        } catch (final IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Reads a field declared by the object's class or one of its superclasses.
     */
    public static <T> T get(final Object object, final String name, final Class<T> type) {
        try {
            return type.cast(field(object.getClass(), name).get(object));
        } catch (final IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Reads a static field.
     */
    public static <T> T getStatic(final Class<?> owner, final String name, final Class<T> type) {
        try {
            return type.cast(field(owner, name).get(null));
        } catch (final IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Field field(final Class<?> owner, final String name) {
        final Field field = ReflectionUtils.getFieldByNameIncludingSuperclasses(name, owner);
        if (field == null) {
            throw new IllegalArgumentException(String.format("%s has no field %s", owner.getName(), name));
        }
        field.setAccessible(true);
        return field;
    }
}
