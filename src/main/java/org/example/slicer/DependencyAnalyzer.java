package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;

public class DependencyAnalyzer {

    private final CtModel model;
    private final Set<CtElement> toKeep;
    private final Set<String> unresolvedTypes = new HashSet<>();

    public DependencyAnalyzer(CtModel model, Set<CtElement> toKeep) {
        this.model = model;
        this.toKeep = toKeep;
    }

    public void expandAllDependencies() {
        expandTransitiveTypeReferences();
        expandFieldDependencies();
        expandInvokedMethodDependencies();
        expandMethodBodyDependencies();
    }

    public void expandMinimalDependencies() {
        Set<CtElement> copy = new HashSet<>(toKeep);

        for (CtElement el : copy) {
            if (el instanceof CtInvocation<?>) {
                CtInvocation<?> invocation = (CtInvocation<?>) el;
                CtExecutable<?> exec = invocation.getExecutable().getDeclaration();
                if (exec != null) toKeep.add(exec);
            }

            if (el instanceof CtConstructorCall<?>) {
                CtConstructorCall<?> call = (CtConstructorCall<?>) el;
                CtExecutable<?> exec = call.getExecutable().getDeclaration();
                if (exec != null) toKeep.add(exec);

                for (CtExpression<?> arg : call.getArguments()) {
                    for (CtInvocation<?> inner : arg.getElements(new TypeFilter<>(CtInvocation.class))) {
                        CtExecutable<?> innerExec = inner.getExecutable().getDeclaration();
                        if (innerExec != null) toKeep.add(innerExec);
                    }
                }
            }

            if (el instanceof CtFieldAccess<?>) {
                CtField<?> field = ((CtFieldAccess<?>) el).getVariable().getDeclaration();

                if (field != null && field.hasModifier(ModifierKind.STATIC)) {
                    if (!toKeep.contains(field)) continue;

                    CtType<?> declaringType = field.getDeclaringType();
                    if (declaringType != null) {
                        for (CtTypeMember member : declaringType.getTypeMembers()) {
                            if (member instanceof CtAnonymousExecutable) {
                                CtAnonymousExecutable staticBlock = (CtAnonymousExecutable) member;

                                boolean assignsField = staticBlock.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                                        .anyMatch(assign -> {
                                            CtExpression<?> lhs = assign.getAssigned();
                                            if (lhs instanceof CtFieldAccess<?>) {
                                                CtFieldReference<?> lhsRef = ((CtFieldAccess<?>) lhs).getVariable();
                                                return lhsRef.equals(field.getReference());
                                            }
                                            return false;
                                        });

                                if (assignsField) {
                                    toKeep.add(staticBlock);
                                    if (staticBlock.getBody() != null)
                                        toKeep.add(staticBlock.getBody());
                                }
                            }
                        }
                    }
                }
            }
            if (el instanceof CtExecutable<?>) {
                addLambdaAndMethodRefDepsInExecutable((CtExecutable<?>) el);
            }
        }
    }

    // -------------------- full expansion helpers --------------------

    private void expandFieldDependencies() {
        for (CtElement el : new HashSet<>(toKeep)) {
            if (el instanceof CtFieldAccess) {
                CtFieldReference<?> ref = ((CtFieldAccess<?>) el).getVariable();
                CtField<?> field = ref.getDeclaration();
                if (field != null && field.getType() != null &&
                        field.getType().getQualifiedName().contains("$") &&
                        field.getType().getDeclaration() == null) {
                    continue;
                }

                if (field != null) {
                    toKeep.add(field);
                    CtType<?> declaringType = field.getDeclaringType();
                    if (declaringType != null && isEntryPoint(declaringType)) {
                        toKeep.add(declaringType);
                    }
                }
            }

            if (el instanceof CtConstructorCall && toKeep.contains(el)) {
                for (CtFieldAccess<?> fieldAccess : el.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                    CtField<?> field = fieldAccess.getVariable().getDeclaration();
                    if (field != null) {
                        toKeep.add(field);
                    }
                }

                for (CtInvocation<?> invocation : el.getElements(new TypeFilter<>(CtInvocation.class))) {
                    CtExecutable<?> exec = invocation.getExecutable().getDeclaration();
                    if (exec != null) {
                        toKeep.add(exec);
                    }
                }
            }

            if (el instanceof CtThisAccess) {
                CtTypeReference<?> typeRef = ((CtThisAccess<?>) el).getType();
                if (typeRef != null && typeRef.getDeclaration() != null) {
                    toKeep.add(typeRef.getDeclaration());
                }
            }

            if (el instanceof CtConstructor<?>) {
                CtConstructor<?> ctor = (CtConstructor<?>) el;
                for (CtAssignment<?, ?> assignment : ctor.getElements(new TypeFilter<>(CtAssignment.class))) {
                    CtExpression<?> right = assignment.getAssignment();
                    if (right != null) {
                        toKeep.addAll(right.getReferencedTypes());
                        for (CtInvocation<?> call : right.getElements(new TypeFilter<>(CtInvocation.class))) {
                            CtExecutable<?> exec = call.getExecutable().getDeclaration();
                            if (exec != null && SlicingUtils.isInProject(exec, model)) toKeep.add(exec);
                        }

                        for (CtFieldAccess<?> access : right.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                            CtField<?> field = access.getVariable().getDeclaration();
                            if (field != null) toKeep.add(field);
                        }
                    }
                }
            }

            // ensure static init for static finals is kept
            for (CtElement elements : new HashSet<>(toKeep)) {
                if (elements instanceof CtField) {
                    CtField<?> field = (CtField<?>) elements;
                    if (field.hasModifier(ModifierKind.STATIC) && field.hasModifier(ModifierKind.FINAL)) {
                        CtType<?> declaringType = field.getDeclaringType();
                        if (declaringType != null) {
                            for (CtTypeMember member : declaringType.getTypeMembers()) {
                                if (member instanceof CtAnonymousExecutable) {
                                    CtAnonymousExecutable staticBlock = (CtAnonymousExecutable) member;
                                    for (CtAssignment<?, ?> assign : staticBlock.getElements(new TypeFilter<>(CtAssignment.class))) {
                                        CtExpression<?> lhs = assign.getAssigned();
                                        if (lhs instanceof CtFieldAccess) {
                                            CtFieldReference<?> assignedRef = ((CtFieldAccess<?>) lhs).getVariable();
                                            if (assignedRef.equals(field.getReference())) {
                                                toKeep.add(staticBlock);
                                                toKeep.add(assign);
                                                CtStatement stmt = assign.getParent(CtStatement.class);
                                                if (stmt != null) toKeep.add(stmt);
                                                if (staticBlock.getBody() != null) {
                                                    toKeep.add(staticBlock.getBody());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void expandTransitiveTypeReferences() {
        Set<CtTypeReference<?>> discovered = new HashSet<>();
        Queue<CtTypeReference<?>> queue = new LinkedList<>();

        for (CtElement el : toKeep) {
            for (CtTypeReference<?> ref : el.getReferencedTypes()) {
                if (ref != null && ref.getQualifiedName() != null && !"<nulltype>".equals(ref.getQualifiedName())) {
                    queue.add(ref);
                }
            }
        }

        while (!queue.isEmpty()) {
            CtTypeReference<?> ref = queue.poll();
            if (ref == null || discovered.contains(ref)) continue;

            String qName = ref.getQualifiedName();
            Set<String> illegalNames = Set.of("int","long","boolean","char","float","double","byte","short","void","<nulltype>");
            if (ref.isPrimitive() || qName == null || illegalNames.contains(qName)) continue;
            if (qName.startsWith("java.")) continue;
            if (!qName.contains(".") || qName.matches("^[a-zA-Z]$")) continue; // avoid junk

            CtType<?> decl = ref.getDeclaration();
            if (decl == null || !model.getAllTypes().contains(decl)) {
                unresolvedTypes.add(qName);
                continue;
            }

            discovered.add(ref);
            toKeep.add(ref);

            if (isEntryPoint(decl)) {
                toKeep.add(decl);
            }

            for (CtTypeReference<?> nestedRef : decl.getReferencedTypes()) {
                if (nestedRef != null &&
                        nestedRef.getQualifiedName() != null &&
                        !"<nulltype>".equals(nestedRef.getQualifiedName()) &&
                        !discovered.contains(nestedRef)) {
                    queue.add(nestedRef);
                }
            }

            for (CtField<?> field : decl.getFields()) {
                if (toKeep.stream().anyMatch(e ->
                        e instanceof CtFieldAccess && ((CtFieldAccess<?>) e).getVariable().equals(field.getReference()))) {
                    toKeep.add(field);
                    if (field.getAssignment() != null) {
                        toKeep.addAll(field.getAssignment().getReferencedTypes());
                    }
                }
            }

            if (decl instanceof CtClass<?>) {
                for (CtConstructor<?> ctor : ((CtClass<?>) decl).getConstructors()) {
                    boolean used = toKeep.stream().anyMatch(e ->
                            e instanceof CtConstructorCall &&
                                    ((CtConstructorCall<?>) e).getExecutable().getDeclaration() == ctor);
                    if (used) toKeep.add(ctor);
                }
            }
        }
    }

    private void expandInvokedMethodDependencies() {
        for (CtElement el : new HashSet<>(toKeep)) {
            if (el instanceof CtInvocation) {
                CtExecutableReference<?> execRef = ((CtInvocation<?>) el).getExecutable();
                CtExecutable<?> exec = execRef.getDeclaration();

                if (exec != null) {
                    toKeep.add(exec);
                } else {
                    CtTypeReference<?> declaringTypeRef = execRef.getDeclaringType();
                    if (declaringTypeRef != null) {
                        CtType<?> declaringType = declaringTypeRef.getDeclaration();
                        if (declaringType != null) {
                            for (CtMethod<?> method : declaringType.getMethods()) {
                                if (method.getSimpleName().equals(execRef.getSimpleName())
                                        && SlicingUtils.isInProject(method, model)) {
                                    toKeep.add(method);
                                }
                            }
                        }
                    }
                }
            }

            if (el instanceof CtConstructorCall<?>) {
                CtConstructorCall<?> call = (CtConstructorCall<?>) el;
                for (CtExpression<?> arg : call.getArguments()) {
                    for (CtInvocation<?> innerCall : arg.getElements(new TypeFilter<>(CtInvocation.class))) {
                        CtExecutable<?> exec = innerCall.getExecutable().getDeclaration();
                        if (exec instanceof CtMethod<?>) {
                            toKeep.add(exec);
                            for (CtTypeReference<?> thrown : ((CtMethod<?>) exec).getThrownTypes()) {
                                if (thrown.getDeclaration() != null) {
                                    toKeep.add(thrown.getDeclaration());
                                }
                            }
                        }
                    }
                }
            }

            if (el instanceof CtFieldAccess<?>) {
                CtField<?> field = ((CtFieldAccess<?>) el).getVariable().getDeclaration();
                if (field != null) {
                    toKeep.add(field);
                }
            }
        }
    }

    private void expandMethodBodyDependencies() {
        for (CtElement el : new HashSet<>(toKeep)) {

            if (el instanceof CtExecutable<?>) {
                CtExecutable<?> executable = (CtExecutable<?>) el;
                if (!toKeep.contains(executable)) continue;

                if (executable.getBody() != null) {
                    for (CtFieldAccess<?> access : executable.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                        CtField<?> field = access.getVariable().getDeclaration();
                        if (field != null && SlicingUtils.isInProject(field, model)) toKeep.add(field);
                    }

                    for (CtInvocation<?> invocation : executable.getElements(new TypeFilter<>(CtInvocation.class))) {
                        CtExecutable<?> exec = invocation.getExecutable().getDeclaration();
                        if (exec != null && SlicingUtils.isInProject(exec, model)) toKeep.add(exec);
                    }

                    for (CtConstructorCall<?> call : executable.getElements(new TypeFilter<>(CtConstructorCall.class))) {
                        CtExecutable<?> exec = call.getExecutable().getDeclaration();
                        if (exec != null && SlicingUtils.isInProject(exec, model)) toKeep.add(exec);
                    }

                    if (executable instanceof CtMethod<?>) {
                        for (CtTypeReference<?> thrown : ((CtMethod<?>) executable).getThrownTypes()) {
                            if (thrown.getDeclaration() != null) {
                                toKeep.add(thrown.getDeclaration());
                            }
                        }
                    }
                }
            }

            if (el instanceof CtClass<?>) {
                CtClass<?> clazz = (CtClass<?>) el;
                CtTypeReference<?> superTypeRef = clazz.getSuperclass();
                if (superTypeRef != null) {
                    CtType<?> superType = superTypeRef.getDeclaration();
                    if (superType != null) {
                        for (CtMethod<?> abstractMethod : superType.getMethods()) {
                            if (abstractMethod.hasModifier(ModifierKind.ABSTRACT)) {
                                boolean isImplemented = clazz.getMethods().stream()
                                        .anyMatch(m -> m.getSimpleName().equals(abstractMethod.getSimpleName())
                                                && m.getParameters().size() == abstractMethod.getParameters().size());
                                if (!isImplemented) {
                                    toKeep.add(abstractMethod);
                                }
                            }
                        }
                    }
                }
            }
        }

        // ensure super ctors are kept
        for (CtType<?> type : model.getAllTypes()) {
            if (type instanceof CtClass<?>) {
                CtClass<?> clazz = (CtClass<?>) type;
                CtTypeReference<?> superTypeRef = clazz.getSuperclass();
                if (superTypeRef != null) {
                    CtType<?> superType = superTypeRef.getDeclaration();
                    if (superType instanceof CtClass<?>) {
                        for (CtConstructor<?> ctor : ((CtClass<?>) superType).getConstructors()) {
                            if (ctor.getParameters().stream().allMatch(p -> p.getType() != null)) {
                                toKeep.add(ctor);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isEntryPoint(CtType<?> type) {
        return toKeep.contains(type) ||
                toKeep.stream().anyMatch(el ->
                        el instanceof CtTypeMember && ((CtTypeMember) el).getDeclaringType() == type);
    }


    // Keep SAMs for lambdas and targets of method references that appear inside an executable
    private void addLambdaAndMethodRefDepsInExecutable(CtExecutable<?> executable) {
        // targets of method references anywhere in this executable (this::m, Type::m, obj::m)
        for (CtExecutableReferenceExpression<?, ?> mr :
                executable.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class))) {
            CtExecutableReference<?> ref = mr.getExecutable();
            if (ref != null) {
                CtExecutable<?> decl = ref.getDeclaration();
                if (decl != null) {
                    toKeep.add(decl);
                }
            }
        }

        // lambdas/method-refs passed as invocation args — keep the functional interface + its SAM
        for (CtInvocation<?> inv : executable.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutableReference<?> execRef = inv.getExecutable();
            if (execRef == null) continue;

            java.util.List<CtTypeReference<?>> paramTypes = execRef.getParameters();
            java.util.List<CtExpression<?>> args = inv.getArguments();
            int n = Math.min(args.size(), paramTypes.size());

            for (int i = 0; i < n; i++) {
                CtExpression<?> arg = args.get(i);
                CtTypeReference<?> expected = paramTypes.get(i);

                // method reference as arg
                if (arg instanceof CtExecutableReferenceExpression<?, ?>) {
                    CtExecutable<?> target = ((CtExecutableReferenceExpression<?, ?>) arg).getExecutable().getDeclaration();
                    if (target != null) toKeep.add(target);
                }

                // lambda as arg: keep the functional interface + its single abstract method(s)
                if (arg instanceof CtLambda<?> && expected != null) {
                    CtType<?> fiDecl = expected.getDeclaration();
                    if (fiDecl instanceof CtInterface<?>) {
                        CtInterface<?> iface = (CtInterface<?>) fiDecl;
                        toKeep.add(iface);
                        for (CtMethod<?> m : iface.getMethods()) {
                            if (m.hasModifier(ModifierKind.ABSTRACT)) {
                                toKeep.add(m);
                            }
                        }
                    }
                }
            }
        }
    }

}
