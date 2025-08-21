package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.util.*;
import java.util.stream.Collectors;

public class MethodResolver {

    private final CtModel model;
    private final Set<CtElement> kept; // read-only view

    public MethodResolver(CtModel model, Set<CtElement> kept) {
        this.model = model;
        this.kept = kept;
    }

    public boolean isInstantiated(CtClass<?> clazz) {
        return kept.stream()
                .filter(CtConstructorCall.class::isInstance)
                .map(CtConstructorCall.class::cast)
                .anyMatch(call -> {
                    CtTypeReference<?> typeRef = call.getType();
                    return typeRef != null && typeRef.getQualifiedName().equals(clazz.getQualifiedName());
                });
    }

    public CtBlock<?> createStubBody(CtClass<?> clazz, CtTypeReference<?> returnType) {
        CtBlock<?> body = clazz.getFactory().Core().createBlock();
        if (returnType != null && !"void".equals(returnType.getSimpleName())) {
            String expr = SlicingUtils.getDefaultReturn(returnType);
            CtStatement returnStmt = clazz.getFactory().Code().createCodeSnippetStatement("return " + expr);
            body.addStatement(returnStmt);
        }
        return body;
    }

    public List<CtMethod<?>> getUnimplementedAbstractMethods(CtClass<?> clazz) {
        List<CtMethod<?>> missing = new ArrayList<>();
        Set<String> implementedSignatures = clazz.getMethods().stream()
                .map(CtMethod::getSignature)
                .collect(Collectors.toSet());

        Set<CtTypeReference<?>> superTypes = new HashSet<>();
        if (clazz.getSuperclass() != null) superTypes.add(clazz.getSuperclass());
        superTypes.addAll(clazz.getSuperInterfaces());

        for (CtTypeReference<?> superRef : superTypes) {
            CtType<?> superType = superRef.getDeclaration();

            if (superType != null) {
                for (CtMethod<?> superMethod : superType.getMethods()) {
                    if (superMethod.hasModifier(ModifierKind.ABSTRACT)) {
                        if (!implementedSignatures.contains(superMethod.getSignature())) {
                            missing.add(superMethod);
                        }
                    }
                }
            } else {
                // reflection fallback
                try {
                    Class<?> refl = Class.forName(superRef.getQualifiedName());
                    for (java.lang.reflect.Method m : refl.getMethods()) {
                        if (!java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                        boolean already = implementedSignatures.stream()
                                .anyMatch(sig -> sig.startsWith(m.getName() + "("));
                        if (already) continue;

                        CtMethod<?> stub = clazz.getFactory().Core().createMethod();
                        stub.setSimpleName(m.getName());
                        stub.setType(clazz.getFactory().Type().createReference(m.getReturnType()));
                        for (int i = 0; i < m.getParameterCount(); i++) {
                            CtParameter<?> p = clazz.getFactory().Core().createParameter();
                            p.setSimpleName("arg" + i);
                            p.setType(clazz.getFactory().Type().createReference(m.getParameterTypes()[i]));
                            stub.addParameter(p);
                        }
                        stub.setBody(createStubBody(clazz, stub.getType()));
                        missing.add(stub);
                    }
                } catch (Throwable ignore) {}
            }
        }
        return missing;
    }
}
