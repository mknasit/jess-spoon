package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;

/**
 * Expands KeepSet minimally: lambdas/method refs/SAM, ctor args, super-ctor needs,
 * static blocks only when assigning a kept static field.
 */
public class DependencyAnalyzer {

    private final CtModel model;
    private final KeepSet keep;

    public DependencyAnalyzer(CtModel model, KeepSet keep) {
        this.model = model;
        this.keep = keep;
    }

    public void expandMinimalDependencies() {
        // lambdas + method references inside kept executables
        for (CtElement el : new ArrayList<>(keep.elements())) {
            if (!(el instanceof CtExecutable<?>)) continue;
            addLambdaAndMethodRefDepsInExecutable((CtExecutable<?>) el);
        }

        // constructor-call arg chaining (method calls inside arguments)
        for (CtElement el : new ArrayList<>(keep.elements())) {
            if (!(el instanceof CtConstructorCall<?>)) continue;
            CtConstructorCall<?> call = (CtConstructorCall<?>) el;

            for (CtExpression<?> arg : call.getArguments()) {
                for (CtInvocation<?> inner : arg.getElements(new TypeFilter<>(CtInvocation.class))) {
                    CtExecutable<?> decl = inner.getExecutable() != null ? inner.getExecutable().getDeclaration() : null;
                    if (decl != null) keep.markFull(decl);
                }
            }
        }

        // static blocks that assign kept static fields
        for (CtElement el : new ArrayList<>(keep.elements())) {
            if (!(el instanceof CtField<?>)) continue;
            CtField<?> field = (CtField<?>) el;
            if (!field.hasModifier(ModifierKind.STATIC)) continue;

            CtType<?> type = field.getDeclaringType();
            if (type == null) continue;

            for (CtTypeMember member : type.getTypeMembers()) {
                if (!(member instanceof CtAnonymousExecutable)) continue;
                CtAnonymousExecutable block = (CtAnonymousExecutable) member;

                boolean assignsField = block.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                        .map(CtAssignment::getAssigned)
                        .filter(CtFieldAccess.class::isInstance)
                        .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                        .anyMatch(vref -> vref.equals(field.getReference()));

                if (assignsField) keep.markFull(block);
            }
        }

        // ensure calling targets of unresolved method invocations by name inside the same declaring type
        for (CtElement el : new ArrayList<>(keep.elements())) {
            if (!(el instanceof CtInvocation<?>)) continue;
            CtInvocation<?> inv = (CtInvocation<?>) el;
            CtExecutableReference<?> ref = inv.getExecutable();
            if (ref == null || ref.getDeclaration() != null) continue;

            CtTypeReference<?> declTypeRef = ref.getDeclaringType();
            if (declTypeRef == null) continue;
            CtType<?> declType = declTypeRef.getDeclaration();
            if (!(declType instanceof CtType<?>)) continue;

            for (CtMethod<?> m : ((CtType<?>) declType).getMethods()) {
                if (m.getSimpleName().equals(ref.getSimpleName())) {
                    keep.markFull(m);
                }
            }
        }

        // keep super constructors (signatures) if subclass is kept
        for (CtType<?> t : model.getAllTypes()) {
            if (!(t instanceof CtClass<?>)) continue;
            CtClass<?> clazz = (CtClass<?>) t;
            if (!keep.contains(clazz)) continue;

            CtTypeReference<?> superRef = clazz.getSuperclass();
            if (superRef == null) continue;

            CtType<?> superDecl = superRef.getDeclaration();
            if (superDecl instanceof CtClass<?>) {
                for (CtConstructor<?> ctor : ((CtClass<?>) superDecl).getConstructors()) {
                    keep.markSig(ctor);
                }
            }
        }
    }

    // === helpers ===

    private void addLambdaAndMethodRefDepsInExecutable(CtExecutable<?> executable) {
        // method refs anywhere in this executable (this::m, Type::m, obj::m)
        for (CtExecutableReferenceExpression<?, ?> mr :
                executable.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class))) {
            CtExecutableReference<?> ref = mr.getExecutable();
            if (ref == null) continue;
            CtExecutable<?> decl = ref.getDeclaration();
            if (decl != null) keep.markFull(decl);
        }

        // lambdas/method-refs as invocation args → keep FI + SAM(s)
        for (CtInvocation<?> inv : executable.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutableReference<?> execRef = inv.getExecutable();
            if (execRef == null) continue;

            List<CtTypeReference<?>> paramTypes = execRef.getParameters();
            List<CtExpression<?>> args = inv.getArguments();
            int n = Math.min(args.size(), paramTypes.size());

            for (int i = 0; i < n; i++) {
                CtExpression<?> arg = args.get(i);
                CtTypeReference<?> expected = paramTypes.get(i);

                if (arg instanceof CtExecutableReferenceExpression<?, ?>) {
                    CtExecutable<?> target = ((CtExecutableReferenceExpression<?, ?>) arg)
                            .getExecutable().getDeclaration();
                    if (target != null) keep.markFull(target);
                }

                if (arg instanceof CtLambda<?> && expected != null) {
                    CtType<?> fiDecl = expected.getDeclaration();
                    if (fiDecl instanceof CtInterface<?>) {
                        CtInterface<?> iface = (CtInterface<?>) fiDecl;
                        keep.markSig(iface);
                        for (CtMethod<?> m : iface.getMethods()) {
                            if (m.hasModifier(ModifierKind.ABSTRACT)) {
                                keep.markSig(m);
                            }
                        }
                    }
                }
            }
        }
    }
}
