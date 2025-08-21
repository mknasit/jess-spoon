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

/**
 * Hard pruning with KeepSet policy.
 * SIGNATURE: methods get stubs; fields keep no initializer (except non-static finals must be initialized);
 * FULL: keep body/initializer.
 */
public class ModelPruner {

    private final CtModel model;
    private final KeepSet keep;
    private final MethodResolver resolver;

    public ModelPruner(CtModel model, KeepSet keep, MethodResolver resolver) {
        this.model = model;
        this.keep = keep;
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
        String qn = type.getQualifiedName();
        Set<String> illegal = Set.of("int","long","boolean","char","float","double","byte","short","void");
        if (illegal.contains(qn)) return false;
        if (qn.startsWith("java.") || qn.startsWith("javax.")) return true;
        if (!qn.contains(".")) return false;

        return keep.contains(type) || keep.elements().stream()
                .filter(el -> el instanceof CtTypeReference)
                .map(el -> ((CtTypeReference<?>) el).getQualifiedName())
                .anyMatch(qName -> qName.equals(qn));
    }

    private boolean isInProject(CtElement el) {
        return SlicingUtils.isInProject(el, model);
    }

    public void pruneType(CtType<?> type) {
        Set<String> accessedFields = new HashSet<>();

        // pull nested types that are referenced by kept type-refs
        for (CtTypeMember member : type.getTypeMembers()) {
            if (member instanceof CtType<?>) {
                CtType<?> nested = (CtType<?>) member;
                boolean refd = model.getElements(new TypeFilter<>(CtTypeReference.class)).stream()
                        .filter(keep.elements()::contains)
                        .anyMatch(ref -> ref.getQualifiedName().equals(nested.getQualifiedName()));
                if (refd) keep.markSig(nested);
            }
        }

        // field names accessed inside kept methods
        for (CtMethod<?> m : type.getMethods()) {
            if (keep.contains(m)) {
                for (CtFieldAccess<?> acc : m.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                    CtFieldReference<?> ref = acc.getVariable();
                    if (ref != null) accessedFields.add(ref.getSimpleName());
                }
            }
        }

        // remove or stub methods by policy
        for (CtMethod<?> m : new ArrayList<>(type.getMethods())) {
            boolean kept = keep.contains(m);
            boolean isInvoked = keep.elements().stream()
                    .filter(el -> el instanceof CtInvocation)
                    .map(el -> (CtInvocation<?>) el)
                    .anyMatch(inv -> {
                        CtExecutable<?> exec = inv.getExecutable().getDeclaration();
                        return exec != null && exec.equals(m);
                    });

            if (!kept && !isInvoked) {
                m.delete();
                continue;
            }

            KeepSet.Retention r = keep.retentionOf(m);
            if (r == KeepSet.Retention.FULL || SlicingUtils.isTargetMethod(m, trueTargetMethod)) {
                // keep body
            } else {
                // SIGNATURE → stub
                if (type.isInterface()) {
                    boolean isStatic  = m.hasModifier(ModifierKind.STATIC);
                    boolean isDefault = isDefaultInterfaceMethod(m, type);
                    if (isStatic || isDefault) {
                        if (m.getBody() == null) {
                            m.setBody(m.getFactory().Core().createBlock());
                        }
                    } else {
                        m.setBody(null);
                    }
                } else {
                    CtBlock<?> body = m.getFactory().Core().createBlock();
                    CtTypeReference<?> ret = m.getType();
                    if (ret != null && !"void".equals(ret.getSimpleName())) {
                        String expr = SlicingUtils.getDefaultReturn(ret);
                        body.addStatement(m.getFactory().Code().createCodeSnippetStatement("return " + expr));
                    }
                    for (CtInvocation<?> inv : m.getElements(new TypeFilter<>(CtInvocation.class))) {
                        if (!inv.getExecutable().isStatic() && inv.getTarget() == null) {
                            inv.delete();
                        }
                    }
                    m.setBody(body);
                    m.setVisibility(ModifierKind.PUBLIC);
                }
            }
        }

        // classes: abstract contract + ctor/TWR helpers
        if (type instanceof CtClass<?>) {
            CtClass<?> clazz = (CtClass<?>) type;

            keepSameTypeMethodRefTargets(clazz);

            CtConstructor<?> uc = ensureUrlConnectionCtor(clazz);
            if (uc != null) keep.markFull(uc);
            ensureSuperCtorOrSynthesize(clazz);
            ensureTryWithResourcesFriendlyClose(clazz);

            List<CtMethod<?>> missing = resolver.getUnimplementedAbstractMethods(clazz);
            if (!missing.isEmpty()) {
                if (!resolver.isInstantiated(clazz)) {
                    if (clazz.hasModifier(ModifierKind.FINAL)) clazz.removeModifier(ModifierKind.FINAL);
                    clazz.addModifier(ModifierKind.ABSTRACT);
                } else {
                    for (CtMethod<?> abs : missing) {
                        CtMethod<?> stub = abs.clone();
                        stub.removeModifier(ModifierKind.ABSTRACT);
                        stub.setBody(resolver.createStubBody(clazz, stub.getType()));
                        stub.setVisibility(ModifierKind.PUBLIC);
                        clazz.addMethod(stub);
                    }
                }
            }
            if (clazz.hasModifier(ModifierKind.ABSTRACT) && resolver.isInstantiated(clazz)) {
                boolean noneLeft = clazz.getMethods().stream().noneMatch(mm -> mm.hasModifier(ModifierKind.ABSTRACT));
                if (noneLeft) clazz.removeModifier(ModifierKind.ABSTRACT);
            }
        }

        // fields (very important for correctness/minimality)

        /*
        for (CtField<?> field : new ArrayList<>(type.getFields())) {

            boolean isStaticFinal = field.hasModifier(ModifierKind.STATIC) && field.hasModifier(ModifierKind.FINAL);
            if (isStaticFinal && field.getAssignment() == null) {
                boolean refd = fieldReferencedInKeptCode(field); // already in your file
                if (!refd) {
                    // not used anywhere we kept -> remove aggressively
                    keep.remove(field);
                    field.delete();
                    continue;
                } else {
                    // used, but kept as SIGNATURE -> give it a harmless initializer
                    if (keep.retentionOf(field) == KeepSet.Retention.SIGNATURE) {
                        String expr = SlicingUtils.getDefaultReturn(field.getType()); // returns "null" for refs, "0"/etc for primitives
                        field.setAssignment(field.getFactory().Code().createCodeSnippetExpression(expr));
                    }
                    // fall through to the rest of the existing logic
                }
            }

            boolean referencedInKept = fieldReferencedInKeptCode(field);
            boolean used = keep.contains(field)
                    || accessedFields.contains(field.getSimpleName())
                    || referencedInKept;

            if (!used) {
                field.delete();
                continue;
            }

            // promote into KeepSet if we saw usage
            keep.markSig(field);

            // generic arg decls (safe)
            CtTypeReference<?> ft = field.getType();
            if (ft != null) {
                for (CtTypeReference<?> arg : ft.getActualTypeArguments()) {
                    CtType<?> argDecl = arg.getDeclaration();
                    if (argDecl != null && isInProject(argDecl) && !keep.contains(argDecl)) {
                        keep.markSig(argDecl);
                    }
                }
            }

            // static blocks that assign this kept field → keep
            for (CtTypeMember member : type.getTypeMembers()) {
                if (!(member instanceof CtAnonymousExecutable)) continue;
                CtAnonymousExecutable block = (CtAnonymousExecutable) member;

                boolean assignsThisField = block.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                        .map(CtAssignment::getAssigned)
                        .filter(CtFieldAccess.class::isInstance)
                        .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                        .anyMatch(vref -> vref.equals(field.getReference()));

                if (assignsThisField) keep.markFull(block);
            }

            // retention policy on initializers
            KeepSet.Retention fr = keep.retentionOf(field);

            if (field.hasModifier(ModifierKind.STATIC)) {
                // static fields: signature policy removes initializer (unless FULL)
                if (fr == KeepSet.Retention.SIGNATURE) field.setAssignment(null);
            } else {
                // instance fields: special care for final
                if (field.hasModifier(ModifierKind.FINAL)) {
                    if (field.getAssignment() == null) {
                        // ensure declaration init so that "final not initialized in default ctor" never fires
                        String init = SlicingUtils.defaultInitExprFor(field.getType());
                        if (init != null) {
                            field.setAssignment(field.getFactory().Code().createCodeSnippetExpression(init));
                        } else {
                            // last resort: drop FINAL if we can't synthesize a legal default
                            field.removeModifier(ModifierKind.FINAL);
                        }
                    }
                } else {
                    // non-final instance fields can omit initializer safely
                    if (fr == KeepSet.Retention.SIGNATURE) {
                        field.setAssignment(null);
                    }
                }
            }

            // hygiene in lean mode: drop broken complex initializers
            if (leanMode && field.getAssignment() != null) {
                boolean brokenRef = field.getAssignment()
                        .getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).stream()
                        .anyMatch(refExpr -> refExpr.getExecutable() == null
                                || refExpr.getExecutable().getDeclaration() == null);
                boolean brokenCall = field.getAssignment()
                        .getElements(new TypeFilter<>(CtInvocation.class)).stream()
                        .anyMatch(inv -> inv.getExecutable().getDeclaration() == null);
                if (brokenRef || brokenCall) field.setAssignment(null);
            }
        }


         */


        // --- fields
        // --- fields (consolidated, no duplication) ---
        for (CtField<?> field : new ArrayList<>(type.getFields())) {
            boolean isFinal  = field.hasModifier(ModifierKind.FINAL);
            boolean isStatic = field.hasModifier(ModifierKind.STATIC);

            // Keep only if explicitly kept OR referenced by kept executables
            boolean used = keep.contains(field) || fieldReferencedInKeptCode(field);
            if (!used) {
                field.delete();
                continue;
            }

            // mark as kept (signature by default)
            keep.markSig(field);

            // Does any kept static block assign this field?
            boolean assignedInStaticBlock = type.getTypeMembers().stream()
                    .filter(CtAnonymousExecutable.class::isInstance)
                    .map(CtAnonymousExecutable.class::cast)
                    .flatMap(b -> b.getElements(new TypeFilter<>(CtAssignment.class)).stream())
                    .map(CtAssignment::getAssigned)
                    .filter(CtFieldAccess.class::isInstance)
                    .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                    .anyMatch(vref -> vref.equals(field.getReference()));

            // If a static block assigns it, keep that block FULL
            if (assignedInStaticBlock) {
                for (CtTypeMember m : type.getTypeMembers()) {
                    if (m instanceof CtAnonymousExecutable) {
                        CtAnonymousExecutable b = (CtAnonymousExecutable) m;
                        boolean assignsThis = b.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                                .map(CtAssignment::getAssigned)
                                .filter(CtFieldAccess.class::isInstance)
                                .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                                .anyMatch(vref -> vref.equals(field.getReference()));
                        if (assignsThis) keep.markFull(b);
                    }
                }
            }

            // --- STATIC FINAL fields ---
            if (isStatic && isFinal) {
                if (assignedInStaticBlock) {
                    // Avoid double-write: if a static block assigns it, declaration MUST NOT initialize it.
                    field.setAssignment(null);
                } else if (field.getAssignment() == null) {
                    // Still referenced but not assigned anywhere else: give a harmless initializer
                    // so "final" rule is satisfied.
                    String expr = SlicingUtils.getDefaultReturn(field.getType());
                    field.setAssignment(field.getFactory().Code().createCodeSnippetExpression(expr));
                }
            }

            // --- FINAL INSTANCE fields ---
            if (!isStatic && isFinal && type instanceof CtClass<?>) {
                CtClass<?> owner = (CtClass<?>) type;
                boolean assignedInCtor = assignedInAnyCtor(field, owner);
                if (assignedInCtor) {
                    // Constructor writes this final field => declaration must NOT have initializer
                    field.setAssignment(null);
                } else if (field.getAssignment() == null) {
                    // Not written in any kept ctor: give safe initializer to satisfy Java's "final" rule
                    String init = SlicingUtils.getDefaultReturn(field.getType());
                    field.setAssignment(field.getFactory().Code().createCodeSnippetExpression(init));
                }
            }

            // Signature policy: strip initializer for non-final fields (final ones we may have just added)
            if (keep.retentionOf(field) == KeepSet.Retention.SIGNATURE && !isFinal) {
                field.setAssignment(null);
            }
        }



        // remove static blocks not in keep
        for (CtTypeMember member : new ArrayList<>(type.getTypeMembers())) {
            if (member instanceof CtAnonymousExecutable && !keep.contains(member)) {
                member.delete();
            }
        }

        // constructors: remove those not kept
        // constructors (keep required URLConnection(URL) ctor if present/synthesized)
        if (type instanceof CtClass<?>) {
            CtClass<?> clazz = (CtClass<?>) type;
            for (CtConstructor<?> ctor : new ArrayList<>(clazz.getConstructors())) {
                if (!keep.contains(ctor)) {
                    CtTypeReference<?> superRef = clazz.getSuperclass();
                    boolean isUrlConnSubclass = superRef != null
                            && "java.net.URLConnection".equals(superRef.getQualifiedName());
                    if (isUrlConnSubclass) continue; // required for compile; don't delete
                    ctor.delete();
                }
            }
            ensureFinalInstanceFieldDefiniteAssignment((CtClass<?>) type);
        }




        // enums
        if (type instanceof CtEnum<?>) {
            CtEnum<?> e = (CtEnum<?>) type;
            for (CtEnumValue<?> val : e.getEnumValues()) keep.markSig(val);
            for (CtMethod<?> m : new ArrayList<>(e.getMethods())) {
                if (!keep.contains(m)) m.delete();
            }
        }

        // recurse into nested
        for (CtType<?> nested : new ArrayList<>(type.getNestedTypes())) {
            if (shouldKeepType(nested)) pruneType(nested);
            else nested.delete();
        }
    }

    // === helpers ===

    private static boolean isDefaultInterfaceMethod(CtMethod<?> m, CtType<?> owner) {
        return owner != null
                && owner.isInterface()
                && m.getBody() != null
                && !m.hasModifier(ModifierKind.ABSTRACT)
                && !m.hasModifier(ModifierKind.STATIC);
    }

    private void keepSameTypeMethodRefTargets(CtType<?> type) {
        for (CtExecutableReferenceExpression<?, ?> mr :
                type.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class))) {
            CtExecutable<?> decl = mr.getExecutable().getDeclaration();
            if (decl instanceof CtMethod) {
                CtMethod<?> m = (CtMethod<?>) decl;
                if (m.getDeclaringType() == type) keep.markSig(m);
            }
        }
    }

    private void ensureUrlConnectionCtorold(CtClass<?> clazz) {
        CtTypeReference<?> superRef = clazz.getSuperclass();
        if (superRef == null) return;
        if (!"java.net.URLConnection".equals(superRef.getQualifiedName())) return;

        boolean hasUrlCtor = clazz.getConstructors().stream().anyMatch(c ->
                c.getParameters().size() == 1 &&
                        "java.net.URL".equals(c.getParameters().get(0).getType().getQualifiedName()));
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
            ((CtClass) clazz).addConstructor((CtConstructor) ctor);
        }
    }

    // BEFORE: private void ensureUrlConnectionCtor(CtClass<?> clazz)
    private CtConstructor<?> ensureUrlConnectionCtor(CtClass<?> clazz) {
        CtTypeReference<?> superRef = clazz.getSuperclass();
        if (superRef == null) return null;
        if (!"java.net.URLConnection".equals(superRef.getQualifiedName())) return null;

        boolean hasUrlCtor = clazz.getConstructors().stream().anyMatch(c ->
                c.getParameters().size() == 1 &&
                        "java.net.URL".equals(c.getParameters().get(0).getType().getQualifiedName())
        );
        if (hasUrlCtor) return null;

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

        ((CtClass) clazz).addConstructor((CtConstructor) ctor);

        // ⬅️ Mark as kept so pruning doesn’t delete it
        keep.markFull(ctor);

        return ctor;
    }


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
            ((CtClass) clazz).addConstructor((CtConstructor) ctor);
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

    private boolean fieldReferencedInKeptCode(CtField<?> field) {
        CtFieldReference<?> want = field.getReference();
        for (CtElement el : keep.elements()) {
            if (!(el instanceof CtExecutable<?>)) continue;
            CtExecutable<?> exec = (CtExecutable<?>) el;
            for (CtFieldAccess<?> acc : exec.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                if (want.equals(acc.getVariable())) return true;
            }
        }
        return false;
    }


    private boolean assignedInAnyCtorold(CtField<?> field, CtClass<?> owner) {
        for (CtConstructor<?> ctor : owner.getConstructors()) {
            if (!keep.contains(ctor)) continue; // only consider constructors we kept
            for (CtAssignment<?, ?> asg : ctor.getElements(new TypeFilter<>(CtAssignment.class))) {
                CtExpression<?> lhs = asg.getAssigned();
                if (lhs instanceof CtFieldAccess<?>) {
                    CtFieldReference<?> vref = ((CtFieldAccess<?>) lhs).getVariable();
                    if (vref != null && vref.equals(field.getReference())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Put this anywhere in ModelPruner (e.g., under fieldReferencedInKeptCode)
    private boolean assignedInAnyCtor(CtField<?> field, CtClass<?> owner) {
        for (CtConstructor<?> ctor : owner.getConstructors()) {
            CtBlock<?> body = ctor.getBody();
            if (body == null) continue;
            boolean assigns =
                    body.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                            .map(CtAssignment::getAssigned)
                            .filter(CtFieldAccess.class::isInstance)
                            .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                            .anyMatch(vref -> vref.equals(field.getReference()));
            if (assigns) return true;
        }
        return false;
    }

    // Put this near the other helpers
    private void ensureFinalInstanceFieldDefiniteAssignmentold(CtClass<?> clazz) {
        for (CtField<?> f : new ArrayList<>(clazz.getFields())) {
            // only final, non-static
            if (!f.hasModifier(ModifierKind.FINAL) || f.hasModifier(ModifierKind.STATIC)) continue;
            // if already has initializer, fine
            if (f.getAssignment() != null) continue;

            // check remaining ctors (after pruning)
            boolean assignedInCtor = false;
            for (CtConstructor<?> ctor : clazz.getConstructors()) {
                CtBlock<?> body = ctor.getBody();
                if (body == null) continue;
                boolean writes =
                        body.getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtAssignment.class)).stream()
                                .map(CtAssignment::getAssigned)
                                .filter(CtFieldAccess.class::isInstance)
                                .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                                .anyMatch(v -> v.equals(f.getReference()));
                if (writes) { assignedInCtor = true; break; }
            }

            // if no ctor writes it, give a harmless default initializer
            if (!assignedInCtor) {
                String expr = SlicingUtils.getDefaultReturn(f.getType()); // "null", "0", "false", etc.
                f.setAssignment(clazz.getFactory().Code().createCodeSnippetExpression(expr));
            }
        }
    }

    /** Ensure every final instance field is definitely assigned without breaking ctor call order. */
    private void ensureFinalInstanceFieldDefiniteAssignment(CtClass<?> clazz) {
        List<CtConstructor<?>> ctors = new ArrayList<>(clazz.getConstructors());

        for (CtField<?> f : clazz.getFields()) {
            boolean isFinal  = f.hasModifier(ModifierKind.FINAL);
            boolean isStatic = f.hasModifier(ModifierKind.STATIC);
            if (!isFinal || isStatic) continue; // only instance finals

            boolean hasDeclInit = f.getAssignment() != null;
            String defaultExpr  = SlicingUtils.getDefaultReturn(f.getType()); // "null", "0", "false", etc.

            // Which ctors (if any) assign this field?
            Set<CtConstructor<?>> writers = new HashSet<>();
            for (CtConstructor<?> ctor : ctors) {
                CtBlock<?> body = ctor.getBody();
                if (body == null) continue;
                boolean writes =
                        body.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                                .map(CtAssignment::getAssigned)
                                .filter(CtFieldAccess.class::isInstance)
                                .map(fa -> ((CtFieldAccess<?>) fa).getVariable())
                                .anyMatch(vref -> vref.equals(f.getReference()));
                if (writes) writers.add(ctor);
            }

            if (writers.isEmpty()) {
                // No ctor writes it → assign at declaration (safe and preserves ctor call rules)
                if (!hasDeclInit) {
                    f.setAssignment(clazz.getFactory().Code().createCodeSnippetExpression(defaultExpr));
                }
            } else {
                // At least one ctor writes it → drop any declaration init to avoid double-assign
                if (hasDeclInit) f.setAssignment(null);

                // For ctors that DON'T write it yet, append a default assignment AT THE END
                for (CtConstructor<?> ctor : ctors) {
                    if (writers.contains(ctor)) continue;
                    CtBlock<?> body = ctor.getBody();
                    if (body == null) continue;

                    String stmt = "this." + f.getSimpleName() + " = " + defaultExpr + ";";
                    CtStatement assign = clazz.getFactory().Code().createCodeSnippetStatement(stmt);

                    // IMPORTANT: append — do NOT insert before this()/super(...)
                    body.addStatement(assign);
                }
            }
        }
    }




}
