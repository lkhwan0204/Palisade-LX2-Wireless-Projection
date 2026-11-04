package hmgapi.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Lookup and hook helpers, shaped like Xposed's XposedHelpers. */
public final class Hooks {
    private Hooks() {}

    public static Class<?> findClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("class not found: " + name, e);
        }
    }

    /**
     * Finds a declared method by exact parameter types. Each type is a Class or
     * a class name (resolved with the declaring class's loader).
     */
    public static Method findMethodExact(Class<?> cls, String name, Object... parameterTypes) {
        Class<?>[] types = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object t = parameterTypes[i];
            types[i] = t instanceof Class ? (Class<?>) t : findClass((String) t, cls.getClassLoader());
        }
        try {
            Method m = cls.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no method " + cls.getName() + "." + name, e);
        }
    }

    /** findAndHookMethod(cls, "name", ParamType1.class, "param.Type2", new MethodHook() {...}) */
    public static Method findAndHookMethod(Class<?> cls, String name, Object... parameterTypesAndHook) {
        int n = parameterTypesAndHook.length - 1;
        if (n < 0 || !(parameterTypesAndHook[n] instanceof MethodHook)) {
            throw new IllegalArgumentException("last argument must be a MethodHook");
        }
        Object[] types = new Object[n];
        System.arraycopy(parameterTypesAndHook, 0, types, 0, n);
        Method m = findMethodExact(cls, name, types);
        Bridge.hookMethod(m, (MethodHook) parameterTypesAndHook[n]);
        return m;
    }

    public static Method findAndHookMethod(String className, ClassLoader loader, String name,
            Object... parameterTypesAndHook) {
        return findAndHookMethod(findClass(className, loader), name, parameterTypesAndHook);
    }

    /** Reads a field, searching superclasses too. */
    public static Object getObjectField(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                // keep looking in the superclass
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no field " + name + " in " + obj.getClass().getName());
    }

    /**
     * Calls a method by name with these arguments, searching superclasses too.
     * Picks the first declared method whose parameters accept the arguments.
     */
    public static Object callMethod(Object obj, String name, Object... args) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || !accepts(m.getParameterTypes(), args)) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw new IllegalStateException(name + " threw", e.getCause());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalArgumentException("no method " + name + " for " + args.length + " args in "
                + obj.getClass().getName());
    }

    /** A static method by exact name and parameter types (an app's obfuscated helpers take exact names from a map). */
    public static Object callStaticMethod(Class<?> cls, String name, Class<?>[] types, Object... args) {
        try {
            Method m = cls.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(cls.getName() + "." + name + " threw", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("no " + cls.getName() + "." + name, e);
        }
    }

    public static int getStaticIntField(Class<?> cls, String name) {
        try {
            java.lang.reflect.Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("no " + cls.getName() + "." + name, e);
        }
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            Object a = args[i];
            if (a == null) {
                if (t.isPrimitive()) return false;
                continue;
            }
            if (t.isPrimitive()) t = boxed(t);
            if (!t.isInstance(a)) return false;
        }
        return true;
    }

    private static Class<?> boxed(Class<?> t) {
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == char.class) return Character.class;
        return t;
    }
}
