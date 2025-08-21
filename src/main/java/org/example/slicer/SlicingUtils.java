package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.cu.position.NoSourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;

public final class SlicingUtils {

    private SlicingUtils() {}

    /** Mirror of your old unresolved/missing decl filter, compatible with Spoon 10.4.2. */
    public static boolean isUnresolved(CtElement el, CtModel model) {
        if (el instanceof CtTypeReference) {
            CtTypeReference<?> ref = (CtTypeReference<?>) el;
            CtType<?> decl = ref.getDeclaration();
            String qn = ref.getQualifiedName();
            if (decl == null) {
                if (qn == null) return true;
                if (qn.matches("^[A-Z]$")) return false;  // generic param like T
                if (qn.startsWith("java.")) return false; // allow unresolved JDK types
                return true;
            }
            return hasNoSource(decl);
        }

        if (el instanceof CtExecutableReference) {
            CtExecutableReference<?> ref = (CtExecutableReference<?>) el;
            CtExecutable<?> decl = ref.getDeclaration();
            return decl == null || hasNoSource(decl);
        }

        if (el instanceof CtMethod) {
            CtMethod<?> m = (CtMethod<?>) el;
            CtType<?> parent = m.getDeclaringType();
            return parent == null || hasNoSource(parent);
        }

        if (el instanceof CtConstructor) {
            CtConstructor<?> c = (CtConstructor<?>) el;
            CtType<?> parent = c.getDeclaringType();
            return parent == null || hasNoSource(parent);
        }

        if (el instanceof CtFieldReference) {
            CtFieldReference<?> ref = (CtFieldReference<?>) el;
            CtField<?> field = ref.getDeclaration();
            return field == null || hasNoSource(field);
        }

        if (el instanceof CtField) {
            CtField<?> f = (CtField<?>) el;
            return f.getDeclaringType() == null;
        }

        if (el instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) el;
            return inv.getExecutable() == null || inv.getExecutable().getDeclaration() == null;
        }

        if (el instanceof CtConstructorCall) {
            CtConstructorCall<?> call = (CtConstructorCall<?>) el;
            return call.getExecutable() == null || call.getExecutable().getDeclaration() == null;
        }

        return false;
    }

    public static boolean hasNoSource(CtElement el) {
        return el.getPosition() == null || el.getPosition() instanceof NoSourcePosition;
    }

    public static boolean isInProject(CtElement el, CtModel model) {
        CtType<?> type = el.getParent(CtType.class);
        if (type == null) return false;

        String qName = type.getQualifiedName();
        if (qName.startsWith("java.") || qName.startsWith("javax.")) return false;
        if (!qName.contains(".") || qName.equals("<nulltype>")) return false;

        return model.getAllTypes().contains(type);
    }

    public static boolean isTargetMethod(CtMethod<?> method, CtMethod<?> target) {
        if (method == null || target == null) return false;
        CtType<?> a = method.getDeclaringType();
        CtType<?> b = target.getDeclaringType();
        if (a == null || b == null) return false;
        return a.getQualifiedName().equals(b.getQualifiedName())
                && method.getSignature().equals(target.getSignature());
    }

    public static String getDefaultReturn(CtTypeReference<?> type) {
        if (type == null) return "null";
        switch (type.getSimpleName()) {
            case "int":
            case "short":
            case "byte":
            case "long":
                return "0";
            case "float":
                return "0.0f";
            case "double":
                return "0.0";
            case "boolean":
                return "false";
            case "char":
                return "'a'";
            case "String":
                return "\"\"";
            case "SortedMap":
                return "java.util.Collections.emptySortedMap()";
            case "Map":
                return "java.util.Collections.emptyMap()";
            case "List":
                return "java.util.Collections.emptyList()";
            case "Set":
                return "java.util.Collections.emptySet()";
            default:
                return "null";
        }
    }

    public static String defaultArgsForExecutable(CtConstructor<?> ctor) {
        java.util.List<String> args = new java.util.ArrayList<>();
        for (CtParameter<?> p : ctor.getParameters()) {
            args.add(defaultArgForType(p.getType()));
        }
        return String.join(", ", args);
    }

    private static String defaultArgForType(CtTypeReference<?> t) {
        if (t == null) return "null";
        if (t.isPrimitive()) {
            switch (t.getSimpleName()) {
                case "boolean": return "false";
                case "char":    return "'\\0'";
                case "float":   return "0.0f";
                case "double":  return "0.0";
                default:        return "0";
            }
        }
        return "null";
    }

    public static boolean implementsAny(CtClass<?> clazz, String... fqns) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (CtTypeReference<?> i : clazz.getSuperInterfaces()) {
            names.add(i.getQualifiedName());
        }
        CtTypeReference<?> s = clazz.getSuperclass();
        while (s != null) {
            names.add(s.getQualifiedName());
            CtType<?> decl = s.getDeclaration();
            if (decl instanceof CtClass) {
                for (CtTypeReference<?> i : ((CtClass<?>) decl).getSuperInterfaces()) {
                    names.add(i.getQualifiedName());
                }
                s = ((CtClass<?>) decl).getSuperclass();
            } else {
                break;
            }
        }
        for (String want : fqns) if (names.contains(want)) return true;
        return false;
    }

    // SlicingUtils.java
    public static String defaultArgsForParameterTypes(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            String v;
            if (!t.isPrimitive()) {
                v = "null";
            } else if (t == boolean.class) {
                v = "false";
            } else if (t == char.class) {
                v = "'\\0'";
            } else if (t == long.class) {
                v = "0L";
            } else if (t == float.class) {
                v = "0.0f";
            } else if (t == double.class) {
                v = "0.0";
            } else {
                v = "0";
            }
            if (i > 0) sb.append(", ");
            sb.append(v);
        }
        return sb.toString();
    }


}
