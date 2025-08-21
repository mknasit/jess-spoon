package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.stream.Collectors;

import static org.example.slicer.SlicedModelBuilder.leanMode;
import static org.example.slicer.SlicedModelBuilder.trueTargetMethod;

public class ModelPruner {

    private final CtModel model;
    private final Set<CtElement> toKeep;
    private final MethodResolver resolver;

    public ModelPruner(CtModel model, Set<CtElement> toKeep, MethodResolver resolver) {
        this.model = model;
        this.toKeep = toKeep;
        this.resolver = resolver;
    }

    public void pruneModel() {
        for (CtType<?> type : new ArrayList<>(model.getAllTypes())) {
            if (!shouldKeepType(type)) {
                type.delete();
            } else {
                pruneType(type);
            }
        }
    }

    private boolean shouldKeepType(CtType<?> type) {
        String qualifiedName = type.getQualifiedName();
        Set<String> illegal = Set.of("int", "long", "boolean", "char", "float", "double", "byte", "short", "void");
        if (illegal.contains(qualifiedName)) return false;
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return true;
        if (!qualifiedName.contains(".")) return false;

        return toKeep.contains(type) || toKeep.stream()
                .filter(el -> el instanceof CtTypeReference)
                .map(el -> ((CtTypeReference<?>) el).getQualifiedName())
                .anyMatch(qName -> qName.equals(qualifiedName));
    }

    private boolean isInProject(CtElement el) {
        return SlicingUtils.isInProject(el, model);
    }

    public void pruneType(CtType<?> type) {
        Set<String> accessedFields = new HashSet<>();

        // preserve truly referenced nested types
        for (CtTypeMember member : type.getTypeMembers()) {
            if (member instanceof CtType<?>) {
                CtType<?> nested = (CtType<?>) member;
                boolean isActuallyReferenced = model.getElements(new TypeFilter<>(CtTypeReference.class)).stream()
                        .filter(toKeep::contains)
                        .anyMatch(ref -> ref.getQualifiedName().equals(nested.getQualifiedName()));
                if (isActuallyReferenced) {
                    toKeep.add(nested);
                }
            }
        }

        // collect field names accessed by kept methods
        for (CtMethod<?> method : type.getMethods()) {
            if (toKeep.contains(method)) {
                for (CtFieldAccess<?> access : method.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                    CtFieldReference<?> ref = access.getVariable();
                    if (ref != null) {
                        accessedFields.add(ref.getSimpleName());
                    }
                }
            }
        }

        // remove or simplify methods
        for (CtMethod<?> method : new ArrayList<>(type.getMethods())) {
            boolean explicitlyKept = toKeep.contains(method);
            boolean isInvoked = toKeep.stream()
                    .filter(el -> el instanceof CtInvocation)
                    .map(el -> (CtInvocation<?>) el)
                    .anyMatch(inv -> {
                        CtExecutable<?> exec = inv.getExecutable().getDeclaration();
                        return exec != null && exec.equals(method);
                    });

            if (!explicitlyKept && !isInvoked) {
                method.delete();
            }  else if (leanMode && !SlicingUtils.isTargetMethod(method, trueTargetMethod)) {
            if (type.isInterface()) {
                method.setBody(null);
            } else {
                CtBlock<?> body = method.getFactory().Core().createBlock();
                CtTypeReference<?> returnType = method.getType();
                if (!"void".equals(returnType.getSimpleName())) {
                    String returnExpr = SlicingUtils.getDefaultReturn(returnType);
                    CtStatement returnStmt = method.getFactory().Code()
                            .createCodeSnippetStatement("return " + returnExpr);
                    body.addStatement(returnStmt);
                }
                for (CtInvocation<?> invocation : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                    if (!invocation.getExecutable().isStatic() && invocation.getTarget() == null) {
                        invocation.delete();
                    }
                }
                method.setBody(body);
                method.setVisibility(ModifierKind.PUBLIC);
            }
        }

    }

        // abstract method resolution (inside pruning, like your old code)
        if (type instanceof CtClass<?>) {
            CtClass<?> clazz = (CtClass<?>) type;
            // --- NEW: keep method-ref targets in same class BEFORE deletions
            keepSameTypeMethodRefTargets(clazz);

            // 💡 your existing abstract method handling...

            // --- NEW: ctor + TWR safety (runs in lean or full)
            ensureUrlConnectionCtor(clazz);
            ensureSuperCtorOrSynthesize(clazz);
            ensureTryWithResourcesFriendlyClose(clazz);

            List<CtMethod<?>> missingMethods = resolver.getUnimplementedAbstractMethods(clazz);
            if (!missingMethods.isEmpty()) {
                if (!resolver.isInstantiated(clazz)) {
                    if (clazz.hasModifier(ModifierKind.FINAL)) {
                        clazz.removeModifier(ModifierKind.FINAL);
                    }
                    clazz.addModifier(ModifierKind.ABSTRACT);
                } else {
                    for (CtMethod<?> abstractMethod : missingMethods) {
                        CtMethod<?> stub = abstractMethod.clone();
                        stub.removeModifier(ModifierKind.ABSTRACT);
                        stub.setBody(resolver.createStubBody(clazz, stub.getType()));
                        stub.setVisibility(ModifierKind.PUBLIC);
                        clazz.addMethod(stub);
                    }
                }
            }
            if (clazz.hasModifier(ModifierKind.ABSTRACT) && resolver.isInstantiated(clazz)) {
                List<CtMethod<?>> stillAbstract = clazz.getMethods().stream()
                        .filter(m -> m.hasModifier(ModifierKind.ABSTRACT))
                        .collect(Collectors.toList());

                if (stillAbstract.isEmpty()) {
                    clazz.removeModifier(ModifierKind.ABSTRACT);
                }
            }
        }

        // fields
        for (CtField<?> field : new ArrayList<>(type.getFields())) {
            boolean used = toKeep.contains(field) || accessedFields.contains(field.getSimpleName());

            if (used) {
                CtTypeReference<?> fieldType = field.getType();
                if (fieldType != null) {
                    for (CtTypeReference<?> arg : fieldType.getActualTypeArguments()) {
                        CtType<?> argDecl = arg.getDeclaration();
                        if (argDecl != null && isInProject(argDecl) && !toKeep.contains(argDecl)) {
                            toKeep.add(argDecl);
                        }
                    }
                }
            }

            if (!used) {
                CtTypeReference<?> fieldType = field.getType();
                if (fieldType != null && fieldType.getQualifiedName().contains("$") &&
                        fieldType.getDeclaration() == null) {
                    field.delete();
                    continue;
                }
                field.delete();
                continue;
            }

            if (leanMode) {
                boolean isStaticFinal = field.hasModifier(ModifierKind.STATIC) && field.hasModifier(ModifierKind.FINAL);
                boolean hasBrokenAssignment = false;

                if (field.getAssignment() != null) {
                    boolean hasBrokenRef = field.getAssignment()
                            .getElements(new TypeFilter<>(CtExecutableReferenceExpression.class))
                            .stream()
                            .anyMatch(refExpr -> refExpr.getExecutable() == null || refExpr.getExecutable().getDeclaration() == null);

                    boolean hasBrokenCall = field.getAssignment()
                            .getElements(new TypeFilter<>(CtInvocation.class))
                            .stream()
                            .anyMatch(inv -> inv.getExecutable().getDeclaration() == null);

                    hasBrokenAssignment = hasBrokenRef || hasBrokenCall;
                }

                boolean isReferencedInKeptCode = model
                        .getElements(new TypeFilter<>(CtFieldRead.class))
                        .stream()
                        .anyMatch(read -> {
                            CtFieldReference<?> ref = read.getVariable();
                            return ref != null && ref.getSimpleName().equals(field.getSimpleName());
                        });

                if (isStaticFinal && (hasBrokenAssignment || field.getAssignment() == null)) {
                    if (field instanceof CtEnumValue<?>) {
                        continue;
                    }

                    if (isReferencedInKeptCode) {
                        if (field.getAssignment() == null) {
                            String fallback = SlicingUtils.getDefaultReturn(field.getType());
                            field.setAssignment(field.getFactory().Code().createCodeSnippetExpression(fallback));
                        }
                    } else {
                        field.delete();
                    }
                } else if (hasBrokenAssignment) {
                    field.setAssignment(null);
                }
            }
        }

        // remove static blocks not in toKeep
        for (CtTypeMember member : new ArrayList<>(type.getTypeMembers())) {
            if (member instanceof CtAnonymousExecutable && !toKeep.contains(member)) {
                member.delete();
            }
        }

        // prune constructors
        if (type instanceof CtClass<?>) {
            CtClass<?> clazz = (CtClass<?>) type;
            for (CtConstructor<?> ctor : new ArrayList<>(clazz.getConstructors())) {
                if (!toKeep.contains(ctor)) {
                    ctor.delete();
                }
            }
        }

        // enums
        if (type instanceof CtEnum<?>) {
            CtEnum<?> enumType = (CtEnum<?>) type;
            toKeep.addAll(enumType.getFields());

            for (CtEnumValue<?> val : enumType.getEnumValues()) {
                toKeep.add(val);
                for (CtConstructorCall<?> call : val.getElements(new TypeFilter<>(CtConstructorCall.class))) {
                    CtExecutable<?> exec = call.getExecutable().getDeclaration();
                    if (exec != null) toKeep.add(exec);
                }
            }

            for (CtMethod<?> method : new ArrayList<>(enumType.getMethods())) {
                if (!toKeep.contains(method)) {
                    method.delete();
                }
            }
        }

        // nested types recursively
        for (CtType<?> nested : new ArrayList<>(type.getNestedTypes())) {
            if (shouldKeepType(nested)) {
                if (nested instanceof CtClass<?>) {
                    for (CtConstructor<?> ctor : ((CtClass<?>) nested).getConstructors()) {
                        if (!toKeep.contains(ctor)) {
                            // Optionally keep ctor-related fields; behavior preserved by recursion
                        }
                    }
                }
                pruneType(nested);
            } else {
                nested.delete();
            }
        }

        // hard remove undeclared fields
        for (CtField<?> field : new ArrayList<>(type.getFields())) {
            if (!toKeep.contains(field)) {
                field.delete();
            }
        }
    }


    // keep method-reference targets when they refer to methods in the same class
    private void keepSameTypeMethodRefTargets(CtType<?> type) {
        for (CtExecutableReferenceExpression<?, ?> mr :
                type.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class))) {
            CtExecutable<?> decl = mr.getExecutable().getDeclaration();
            if (decl instanceof CtMethod) {
                CtMethod<?> m = (CtMethod<?>) decl;
                if (m.getDeclaringType() == type) {
                    toKeep.add(m);
                }
            }
        }
    }

    // ensure URLConnection subclasses have the protected (URL) ctor with super(url)
    private void ensureUrlConnectionCtor(CtClass<?> clazz) {
        CtTypeReference<?> superRef = clazz.getSuperclass();
        if (superRef == null) return;
        if (!"java.net.URLConnection".equals(superRef.getQualifiedName())) return;

        boolean hasUrlCtor = clazz.getConstructors().stream().anyMatch(c ->
                c.getParameters().size() == 1 &&
                        "java.net.URL".equals(c.getParameters().get(0).getType().getQualifiedName())
        );
        if (!hasUrlCtor) {
            CtConstructor<?> ctor = clazz.getFactory().Core().createConstructor();
            ctor.setSimpleName(clazz.getSimpleName());
            ctor.addModifier(ModifierKind.PROTECTED);

            CtParameter<?> p = clazz.getFactory().Core().createParameter();
            p.setSimpleName("url");
            p.setType(clazz.getFactory().Type().createReference("java.net.URL"));
            ctor.addParameter(p);

            CtBlock<?> body = clazz.getFactory().Core().createBlock();
            body.addStatement(clazz.getFactory().Code().createCodeSnippetStatement("super(url);"));
            ctor.setBody(body);
            ((CtClass) clazz).addConstructor(ctor);

        }
    }

    // synthesize/insert super(...) when superclass lacks a no-arg ctor
    private void ensureSuperCtorOrSynthesize(CtClass<?> clazz) {
        CtTypeReference<?> superRef = clazz.getSuperclass();
        if (superRef == null) return;

        CtType<?> superDecl = superRef.getDeclaration();
        if (!(superDecl instanceof CtClass)) return;

        CtConstructor<?> noArg = ((CtClass<?>) superDecl).getConstructors().stream()
                .filter(c -> c.getParameters().isEmpty()).findFirst().orElse(null);
        if (noArg != null) return;

        CtConstructor<?> chosen = ((CtClass<?>) superDecl).getConstructors().stream()
                .min(Comparator.comparingInt(c -> c.getParameters().size()))
                .orElse(null);
        if (chosen == null) return;

        String args = SlicingUtils.defaultArgsForExecutable(chosen);

        if (clazz.getConstructors().isEmpty()) {
            CtConstructor<?> ctor = clazz.getFactory().Core().createConstructor();
            ctor.setSimpleName(clazz.getSimpleName());
            ctor.addModifier(ModifierKind.PUBLIC);
            CtBlock<?> body = clazz.getFactory().Core().createBlock();
            body.addStatement(clazz.getFactory().Code().createCodeSnippetStatement("super(" + args + ");"));
            ctor.setBody(body);
            ((CtClass) clazz).addConstructor(ctor);

        } else {
            for (CtConstructor<?> ctor : clazz.getConstructors()) {
                CtBlock<?> body = ctor.getBody();
                if (body == null) continue;
                CtStatement first = body.getStatements().isEmpty() ? null : body.getStatement(0);
                boolean callsThisOrSuper =
                        first instanceof CtInvocation
                                && ((((CtInvocation<?>) first).getTarget() instanceof CtThisAccess)
                                || (((CtInvocation<?>) first).getTarget() instanceof CtSuperAccess));
                if (!callsThisOrSuper) {
                    body.insertBegin(clazz.getFactory().Code()
                            .createCodeSnippetStatement("super(" + args + ");"));
                }
            }
        }
    }

    // make AutoCloseable classes TWR-friendly: close() throws IOException
    private void ensureTryWithResourcesFriendlyClose(CtClass<?> clazz) {
        if (!SlicingUtils.implementsAny(clazz, "java.lang.AutoCloseable", "java.io.Closeable")) return;

        CtMethod<?> close = clazz.getMethods().stream()
                .filter(m -> m.getSimpleName().equals("close") && m.getParameters().isEmpty())
                .findFirst().orElse(null);

        if (close == null) {
            CtMethod<Void> m = clazz.getFactory().Core().createMethod();
            m.setSimpleName("close");
            m.setType(clazz.getFactory().Type().VOID_PRIMITIVE);
            m.addModifier(ModifierKind.PUBLIC);
            m.addThrownType(clazz.getFactory().Type().createReference("java.io.IOException"));
            m.setBody(clazz.getFactory().Core().createBlock());
            clazz.addMethod(m);
        } else {
            boolean hasIOException = close.getThrownTypes().stream()
                    .anyMatch(t -> "java.io.IOException".equals(t.getQualifiedName()));
            if (!hasIOException) {
                close.setThrownTypes(new java.util.HashSet<>(
                        java.util.Collections.singletonList(
                                clazz.getFactory().Type().createReference("java.io.IOException")
                        )));
            }
        }
    }

}
