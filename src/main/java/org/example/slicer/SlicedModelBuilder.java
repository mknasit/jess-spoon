package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.ImportCleaner;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.stream.Collectors;

public class SlicedModelBuilder {

    public final CtModel model;
    public final Set<CtElement> toKeep;

    public static boolean leanMode = false;
    public static CtMethod<?> trueTargetMethod = null;

    private final DependencyAnalyzer dependencyAnalyzer;
    private final ModelPruner pruner;
    private final MethodResolver resolver;

    public SlicedModelBuilder(CtModel model, Set<CtElement> dependencies) {
        this.model = model;
        this.toKeep = new HashSet<>(dependencies);
        this.dependencyAnalyzer = new DependencyAnalyzer(model, toKeep);
        this.resolver = new MethodResolver(model, toKeep);
        this.pruner = new ModelPruner(model, toKeep, resolver);
    }

    public void slice() {
        // 1) Expand dependencies (same logic as before)
        if (!leanMode) {
            dependencyAnalyzer.expandAllDependencies();
        } else {
            dependencyAnalyzer.expandMinimalDependencies();
        }

        // 2) Rewrite qualified static assignments inside kept static blocks (your original slice() logic)
        for (CtElement el : toKeep) {
            if (el instanceof CtAnonymousExecutable) {
                CtAnonymousExecutable block = (CtAnonymousExecutable) el;

                for (CtAssignment<?, ?> assign : block.getElements(new TypeFilter<>(CtAssignment.class))) {
                    CtExpression<?> lhs = assign.getAssigned();

                    if (lhs instanceof CtFieldAccess<?>) {
                        CtFieldAccess<?> fieldAccess = (CtFieldAccess<?>) lhs;
                        CtFieldReference<?> ref = fieldAccess.getVariable();

                        if (ref != null && ref.isFinal() && ref.isStatic()) {
                            CtField<?> field = ref.getDeclaration();

                            // Only rewrite if target is a qualified type access
                            if (fieldAccess.getTarget() instanceof CtTypeAccess<?>) {
                                CtTypeAccess<?> target = (CtTypeAccess<?>) fieldAccess.getTarget();
                                if (field != null &&
                                        target.getAccessedType().getQualifiedName()
                                                .equals(field.getDeclaringType().getQualifiedName())) {

                                    // Replace with simple field read: STANDARD_CHARSET_MAP = ...
                                    // AFTER
                                    CtExpression<?> newLhs = field.getFactory().Code().createVariableRead(ref, true);

                                    // avoid generic capture issues on CtAssignment#setAssigned
                                    @SuppressWarnings({"rawtypes", "unchecked"})
                                    CtAssignment raw = (CtAssignment) assign;
                                    raw.setAssigned(newLhs);


                                }
                            }
                        }
                    }
                }
            }
        }

        // 3) Filter unresolved or missing declarations (same rules as your old code)
        toKeep.removeIf(el -> SlicingUtils.isUnresolved(el, model));

        // 4) Prune types/members (keeps your original pruneType behavior)
        pruner.pruneModel();

        // 5) Remove orphan top-level types that aren’t referenced by toKeep members
        for (CtType<?> type : new ArrayList<>(model.getAllTypes())) {
            boolean isUsed = toKeep.contains(type)
                    || toKeep.stream().anyMatch(e -> e instanceof CtTypeMember
                    && ((CtTypeMember) e).getDeclaringType() == type);
            if (!isUsed) {
                type.delete();
            }
        }

        // 6) Clean dangling toKeep entries whose parent type got deleted
        toKeep.removeIf(el -> {
            CtType<?> type = el.getParent(CtType.class);
            return type != null && !model.getAllTypes().contains(type);
        });

        // 7) Clean imports
        for (CtType<?> type : model.getAllTypes()) {
            new ImportCleaner().process(type);
        }

        // 8) Ensure abstract contracts (same as old code)
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type instanceof CtClass)) continue;

            CtClass<?> clazz = (CtClass<?>) type;
            List<CtMethod<?>> missing = resolver.getUnimplementedAbstractMethods(clazz);
            if (missing.isEmpty()) continue;

            if (!resolver.isInstantiated(clazz)) {
                if (clazz.hasModifier(ModifierKind.FINAL)) {
                    clazz.removeModifier(ModifierKind.FINAL);
                }
                clazz.addModifier(ModifierKind.ABSTRACT);
            } else {
                for (CtMethod<?> abstractMethod : missing) {
                    CtMethod<?> stub = abstractMethod.clone();
                    stub.removeModifier(ModifierKind.ABSTRACT);
                    stub.setBody(resolver.createStubBody(clazz, stub.getType()));
                    stub.setVisibility(ModifierKind.PUBLIC);
                    clazz.addMethod(stub);
                }
            }
        }

        // 9) Remove uninitialized static final fields or detach conflicting assignments (your original tail-pass)
        List<CtField<?>> removableFields = new ArrayList<>();
        for (CtElement el : toKeep) {
            if (el instanceof CtField<?>) {
                CtField<?> field = (CtField<?>) el;
                boolean isStaticFinal = field.hasModifier(ModifierKind.STATIC) && field.hasModifier(ModifierKind.FINAL);
                if (isStaticFinal && field.getAssignment() == null) {
                    removableFields.add(field);
                }
            }
        }
        for (CtField<?> field : removableFields) {
            toKeep.remove(field);
            field.delete();
        }

        for (CtField<?> field : new ArrayList<>(toKeep.stream()
                .filter(CtField.class::isInstance)
                .map(CtField.class::cast)
                .collect(Collectors.toList()))) {

            if (field.hasModifier(ModifierKind.STATIC) && field.hasModifier(ModifierKind.FINAL)) {
                CtType<?> declaringType = field.getDeclaringType();
                if (declaringType == null) continue;

                for (CtTypeMember member : declaringType.getTypeMembers()) {
                    if (member instanceof CtAnonymousExecutable) {
                        CtAnonymousExecutable staticBlock = (CtAnonymousExecutable) member;

                        boolean assignsInBlock = staticBlock.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                                .anyMatch(assign -> {
                                    CtExpression<?> lhs = assign.getAssigned();
                                    return lhs instanceof CtFieldAccess &&
                                            ((CtFieldAccess<?>) lhs).getVariable().equals(field.getReference());
                                });

                        if (assignsInBlock) {
                            field.setAssignment(null);
                            break;
                        }
                    }
                }
            }
        }
    }

    public static void setTrueTargetMethod(CtMethod<?> trueTarget) {
        SlicedModelBuilder.trueTargetMethod = trueTarget;
    }
}
