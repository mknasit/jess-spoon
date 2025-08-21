package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.Arrays;

public final class SlicingUtils {
    private SlicingUtils() {}

    public static boolean isTargetMethod(CtMethod<?> m, CtMethod<?> target) {
        if (m == null || target == null) return false;
        return m.getDeclaringType().getQualifiedName().equals(
                target.getDeclaringType().getQualifiedName()
        ) && m.getSignature().equals(target.getSignature());
    }

    public static boolean isInProject(CtElement el, CtModel model) {
        CtType<?> t = el.getParent(CtType.class);
        if (t == null) return false;
        String qn = t.getQualifiedName();
        if (qn.startsWith("java.") || qn.startsWith("javax.")) return false;
        return model.getAllTypes().contains(t);
    }

    public static boolean implementsAny(CtClass<?> c, String... qns) {
        for (CtTypeReference<?> i : c.getSuperInterfaces()) {
            for (String qn : qns) if (qn.equals(i.getQualifiedName())) return true;
        }
        return false;
    }

    public static String getDefaultReturn(CtTypeReference<?> type) {
        if (type == null) return "null";
        String s = type.getSimpleName();
        switch (s) {
            case "int":
            case "short":
            case "byte":
            case "long": return "0";
            case "float": return "0.0f";
            case "double": return "0.0";
            case "boolean": return "false";
            case "char": return "'\\0'";
            case "String": return "\"\"";
            case "SortedMap":
                return "java.util.Collections.emptySortedMap()";
            case "Map":
                return "java.util.Collections.emptyMap()";
            case "List":
                return "java.util.Collections.emptyList()";
            case "Set":
                return "java.util.Collections.emptySet()";
            default: return "null";
        }
    }



    /** default initializer expression usable in a field declaration (esp. for final instance fields). */
    public static String defaultInitExprFor(CtTypeReference<?> type) {
        if (type == null) return "null";

        if (type.isPrimitive()) {
            switch (type.getSimpleName()) {
                case "int":
                case "short":
                case "byte":
                case "long": return "0";
                case "float": return "0.0f";
                case "double": return "0.0";
                case "boolean": return "false";
                case "char": return "'\\0'";
            }
            return "0";
        }

        // arrays
        if (type instanceof CtArrayTypeReference<?>) {
            CtTypeReference<?> comp = ((CtArrayTypeReference<?>) type).getComponentType();
            String compQN = comp != null ? comp.getQualifiedName() : "java.lang.Object";
            return "new " + compQN + "[0]";
        }
        // fallback for unresolved array qnames like "int[]" when Spoon didn't build a CtArrayTypeReference
        if (type.getQualifiedName() != null && type.getQualifiedName().endsWith("[]")) {
            String base = type.getQualifiedName().substring(0, type.getQualifiedName().length() - 2);
            if (base.isEmpty()) base = "java.lang.Object";
            return "new " + base + "[0]";
        }

        // object/reference
        return "null";
    }

    public static String defaultArgsForExecutable(CtExecutable<?> exec) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (CtParameter<?> p : exec.getParameters()) {
            if (!first) sb.append(", ");
            first = false;
            CtTypeReference<?> t = p.getType();
            sb.append(defaultInitExprFor(t));
        }
        return sb.toString();
    }

    public static String defaultArgsForParameterTypes(Class<?>[] pts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(", ");
            Class<?> pt = pts[i];
            if (pt.isPrimitive()) {
                if (pt == boolean.class) sb.append("false");
                else if (pt == char.class) sb.append("'\\0'");
                else if (pt == float.class) sb.append("0.0f");
                else if (pt == double.class) sb.append("0.0");
                else sb.append("0");
            } else if (pt.isArray()) {
                String comp = pt.getComponentType().getName();
                sb.append("new ").append(comp).append("[0]");
            } else {
                sb.append("null");
            }
        }
        return sb.toString();
    }

    /** Treat unresolved references as non-project / should drop from KeepSet. */
    public static boolean isUnresolved(CtElement el, CtModel model) {
        if (el instanceof CtTypeReference) {
            CtTypeReference<?> r = (CtTypeReference<?>) el;
            if (r.getDeclaration() == null && !r.getQualifiedName().startsWith("java.")) {
                return true;
            }
        }
        if (el instanceof CtExecutableReference) {
            return ((CtExecutableReference<?>) el).getDeclaration() == null;
        }
        if (el instanceof CtFieldReference) {
            return ((CtFieldReference<?>) el).getDeclaration() == null;
        }
        if (el instanceof CtMethod) {
            CtType<?> parent = ((CtMethod<?>) el).getDeclaringType();
            return parent == null || !model.getAllTypes().contains(parent);
        }
        if (el instanceof CtConstructor) {
            CtType<?> parent = ((CtConstructor<?>) el).getDeclaringType();
            return parent == null || !model.getAllTypes().contains(parent);
        }
        return false;
    }
}
