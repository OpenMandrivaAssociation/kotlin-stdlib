/**
 * kotlin.Array's size property is lowered to a field plus {@code <get-size>} before
 * MappedEnumWhenLowering runs on the stdlib sources. Return that getter.
 */
public final class StdlibDiag {
    private static boolean reported;
    private static boolean reportedSelf;
    private static boolean reportedUnbound;
    private static int skippedBuiltins;
    private static int strippedParents;
    private static int emptyOverrides;
    private static int ignoredClashes;

    private StdlibDiag() {}

    private static boolean isBuiltinFile(Object file) {
        try {
            Object entry = file.getClass().getMethod("getFileEntry").invoke(file);
            String name = String.valueOf(entry.getClass().getMethod("getName").invoke(entry));
            return name.contains("/jvm/builtins/") || name.contains("\\jvm\\builtins\\");
        } catch (Throwable thrown) {
            thrown.printStackTrace(System.out);
            return false;
        }
    }

    /**
     * 2.4 drops SuppressBytecodeGeneration files before JVM lowering.
     * 2.0.21 has no such check, so the source intrinsics stay visible.
     */
    public static int removeSuppressedBuiltins(Object module) {
        try {
            Object files = module.getClass().getMethod("getFiles").invoke(module);
            java.util.List<?> list = (java.util.List<?>) files;
            java.util.Iterator<?> iterator = list.iterator();
            int removed = 0;
            while (iterator.hasNext()) {
                Object file = iterator.next();
                if (isBuiltinFile(file)) {
                    iterator.remove();
                    removed++;
                }
            }
            System.out.println("REMOVED_BUILTINS " + removed + " left " + list.size());
            return removed;
        } catch (Throwable thrown) {
            thrown.printStackTrace(System.out);
            return -1;
        }
    }

    /**
     * Top-level stdlib intrinsics are parented by the file facade kotlin.LibraryKt.
     * 2.0.21 compares that parent with package kotlin and misses Any?.toString and String?.plus.
     */
    public static Object builtinParent(Object fqName) {
        if (fqName == null) {
            return null;
        }
        try {
            Object parent = fqName.getClass().getMethod("parent").invoke(fqName);
            String parentName = String.valueOf(parent);
            if (parentName.endsWith("LibraryKt")) {
                Object pkg = parent.getClass().getMethod("parent").invoke(parent);
                if (strippedParents < 12) {
                    strippedParents++;
                    System.out.println("FACADEPARENT " + fqName + " -> " + pkg);
                }
                return pkg;
            }
            return parent;
        } catch (Throwable thrown) {
            thrown.printStackTrace(System.out);
            return null;
        }
    }

    /**
     * stdlib compilation sees both the Kotlin Map member and the JDK member.
     * They share keySet/values/entrySet JVM descriptors and are not separate methods.
     */
    public static boolean ignoreCollectionClash(Object signature) {
        try {
            String name = String.valueOf(signature.getClass().getMethod("getName").invoke(signature));
            if ("keySet".equals(name) || "values".equals(name) || "entrySet".equals(name)) {
                if (ignoredClashes < 12) {
                    Object desc = signature.getClass().getMethod("getDesc").invoke(signature);
                    System.out.println("IGNORECLASH " + name + " " + desc);
                }
                ignoredClashes++;
                return true;
            }
        } catch (Throwable thrown) {
            thrown.printStackTrace(System.out);
        }
        return false;
    }

    /** Mapped builtin fake overrides can have an empty super list. Keep the method's own descriptor. */
    public static void noteEmptyOverride(Object function) {
        if (emptyOverrides >= 8) {
            emptyOverrides++;
            return;
        }
        emptyOverrides++;
        try {
            Object name = function.getClass().getMethod("getName").invoke(function);
            Object parent = function.getClass().getMethod("getParent").invoke(function);
            System.out.println("EMPTYOVERRIDE " + name + " in " + parent);
        } catch (Throwable thrown) {
            System.out.println("EMPTYOVERRIDE " + function.getClass().getName());
        }
    }

    /** kotlin.jvm builtins are compiler intrinsics. 2.0.21 has no SuppressBytecodeGeneration check. */
    public static boolean suppressBytecode(Object file) {
        if (!isBuiltinFile(file)) {
            return false;
        }
        skippedBuiltins++;
        if (skippedBuiltins <= 30) {
            System.out.println("SKIPBYTECODE builtin");
        }
        return true;
    }

    /** Keep a rewritten override symbol. Drop unbound symbols and a function overriding itself. */
    public static Object keepOverride(Object function, Object mapped) {
        if (mapped == null) {
            return null;
        }
        try {
            Object ownSymbol = function.getClass().getMethod("getSymbol").invoke(function);
            if (ownSymbol == mapped) {
                if (!reportedSelf) {
                    System.out.println("KEEPOVERRIDE drop self");
                    reportedSelf = true;
                }
                return null;
            }
            Object bound = mapped.getClass().getMethod("isBound").invoke(mapped);
            if (!Boolean.TRUE.equals(bound)) {
                if (!reportedUnbound) {
                    System.out.println("KEEPOVERRIDE drop unbound");
                    reportedUnbound = true;
                }
                return null;
            }
            return mapped;
        } catch (Throwable thrown) {
            thrown.printStackTrace(System.out);
            return null;
        }
    }

    public static Object arraySizeGetter(Object symbol) {
        try {
            Object owner = symbol.getClass().getMethod("getOwner").invoke(symbol);
            Object decls = owner.getClass().getMethod("getDeclarations").invoke(owner);
            int count = ((Number) decls.getClass().getMethod("size").invoke(decls)).intValue();
            for (int i = 0; i < count; i++) {
                Object declaration = decls.getClass().getMethod("get", int.class).invoke(decls, i);
                if (!declaration.getClass().getName().contains("IrFunction")) {
                    continue;
                }
                String declarationName = String.valueOf(
                        declaration.getClass().getMethod("getName").invoke(declaration));
                if ("<get-size>".equals(declarationName)) {
                    if (!reported) {
                        System.out.println("ARRAYSIZE getter " + declarationName);
                        reported = true;
                    }
                    return declaration;
                }
            }
        } catch (Throwable thrown) {
            thrown.printStackTrace(System.out);
        }
        if (!reported) {
            System.out.println("ARRAYSIZE missing");
            reported = true;
        }
        return null;
    }
}
