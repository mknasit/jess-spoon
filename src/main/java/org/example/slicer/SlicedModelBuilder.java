package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.visitor.ImportCleaner;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates: seed -> expand minimal deps -> transform -> prune -> clean.
 */
public class SlicedModelBuilder {

    public final CtModel model;
    public final KeepSet keep;

    public static boolean leanMode = true;
    public static CtMethod<?> trueTargetMethod = null;

    private final DependencyAnalyzer depAnalyzer;
    private final ModelPruner pruner;
    private final MethodResolver resolver;

    public SlicedModelBuilder(CtModel model, Set<CtElement> initialSeed) {
        this.model = model;
        this.keep = new KeepSet();
        for (CtElement el : initialSeed) keep.markSig(el);


        this.depAnalyzer = new DependencyAnalyzer(model, keep);
        this.resolver = new MethodResolver(model, keep.elements());
        this.pruner = new ModelPruner(model, keep, resolver);
    }

    public void slice() {
        // target method is FULL


        if (trueTargetMethod != null) {
            keep.markFull(trueTargetMethod);
            CtType<?> owner = trueTargetMethod.getDeclaringType();
            if (owner != null) keep.markSig(owner);
        }
        depAnalyzer.expandFixpoint(keep.elements());
        // Minimal expansion only (lean)
       // depAnalyzer.expandMinimalDependencies();

        // (optional) rewrite qualified static assignments inside kept static blocks
        rewriteQualifiedStaticAssignments();

        // filter unresolved/missing declarations
        for (CtElement el : new ArrayList<>(keep.elements())) {
            if (SlicingUtils.isUnresolved(el, model)) keep.remove(el);
        }

        // prune
        pruner.pruneModel();

        // drop orphan top-level types
        for (CtType<?> type : new ArrayList<>(model.getAllTypes())) {
            boolean used = keep.contains(type) ||
                    keep.elements().stream().anyMatch(e -> e instanceof CtTypeMember &&
                            ((CtTypeMember) e).getDeclaringType() == type);
            if (!used) type.delete();
        }

        // clean dangling keeps whose parent got deleted
        for (CtElement el : new ArrayList<>(keep.elements())) {
            CtType<?> t = el.getParent(CtType.class);
            if (t != null && !model.getAllTypes().contains(t)) keep.remove(el);
        }

        // clean imports
        for (CtType<?> type : model.getAllTypes()) new ImportCleaner().process(type);

        // last pass: static final collisions (if any kept both decl init and block init)
        detachDuplicateStaticFinalInits();
    }

    private void rewriteQualifiedStaticAssignments() {
        for (CtElement el : keep.elements()) {
            if (!(el instanceof CtAnonymousExecutable)) continue;
            CtAnonymousExecutable block = (CtAnonymousExecutable) el;
            for (CtAssignment<?, ?> assign : block.getElements(new TypeFilter<>(CtAssignment.class))) {
                CtExpression<?> lhs = assign.getAssigned();
                if (!(lhs instanceof CtFieldAccess<?>)) continue;

                CtFieldAccess<?> fa = (CtFieldAccess<?>) lhs;
                CtFieldReference<?> ref = fa.getVariable();
                if (ref == null || !ref.isFinal() || !ref.isStatic()) continue;

                CtField<?> field = ref.getDeclaration();
                if (field == null) continue;

                boolean sameOwner = field.getDeclaringType() == block.getParent(CtType.class);
                // BEFORE: you had createVariableRead(...) which turns the LHS into a read
                CtExpression<?> newLhs = field.getFactory().Code().createVariableWrite(ref, true);
                @SuppressWarnings({"rawtypes","unchecked"})
                CtAssignment raw = (CtAssignment) assign;
                raw.setAssigned((CtExpression) newLhs);

// De-qualify so it prints as: STANDARD_CHARSET_MAP = ...;
                if (newLhs instanceof CtFieldAccess) {
                    ((CtFieldAccess<?>) newLhs).setTarget(null);
                }



            }
        }
    }

    private void detachDuplicateStaticFinalInits() {
        for (CtField<?> field : keep.elements().stream()
                .filter(CtField.class::isInstance).map(CtField.class::cast).collect(Collectors.toList())) {
            if (!field.hasModifier(ModifierKind.STATIC) || !field.hasModifier(ModifierKind.FINAL)) continue;

            CtType<?> declaringType = field.getDeclaringType();
            if (declaringType == null) continue;

            boolean assignedInBlock = declaringType.getTypeMembers().stream()
                    .filter(CtAnonymousExecutable.class::isInstance)
                    .map(CtAnonymousExecutable.class::cast)
                    .flatMap(b -> b.getElements(new TypeFilter<>(CtAssignment.class)).stream())
                    .map(CtAssignment::getAssigned)
                    .filter(CtFieldAccess.class::isInstance)
                    .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                    .anyMatch(vref -> vref.equals(field.getReference()));

            if (assignedInBlock) field.setAssignment(null);
        }
    }

    public static void setTrueTargetMethod(CtMethod<?> trueTarget) {
        SlicedModelBuilder.trueTargetMethod = trueTarget;
    }
}
