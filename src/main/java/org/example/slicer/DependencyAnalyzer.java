package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;
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


    public void expandFixpoint(Set<CtElement> seed) {
        Deque<CtElement> sigQ = new ArrayDeque<>();
        Deque<CtExecutable<?>> bodyQ = new ArrayDeque<>();

        // Seen sets to avoid re-processing
        Set<CtElement> seenSig = new HashSet<>();
        Set<CtExecutable<?>> seenBody = new HashSet<>();

        // seed queues
        for (CtElement s : seed) {
            if (s == null) continue;
            sigQ.add(s);
            if (s instanceof CtExecutable) {
                CtExecutable<?> ex = (CtExecutable<?>) s;
                if (keep.retentionOf(ex) == KeepSet.Retention.FULL) bodyQ.add(ex);
            }
        }

        // Single-pass scanner for bodies of FULL executables
        CtScanner bodyScanner = new CtScanner() {

            private void markExec(CtInvocation<?> ctx, CtExecutableReference<?> ref) {
                if (ref == null) return;

                CtExecutable<?> decl = ref.getDeclaration();
                if (decl != null) {
                    keep.markSig(decl);
                    if (seenSig.add(decl)) sigQ.add(decl);
                } else {
                    // unresolved → your existing conservative policy
                    if (ctx != null) keepOverloadGroup(ctx, ref);
                }

                if (ref.getDeclaringType() != null) keep.markSig(ref.getDeclaringType());
                if (ref.getType() != null) keep.markSig(ref.getType());

                // If this executable belongs to an interface, keep all its methods (signatures)
                if (decl instanceof CtMethod) {
                    CtType<?> owner = ((CtMethod<?>) decl).getDeclaringType();
                    if (owner != null && owner.isInterface()) {
                        keep.markSig(owner);
                        for (CtMethod<?> mm : owner.getMethods()) keep.markSig(mm);
                    }
                }

            }

            private void markField(CtFieldReference<?> fr) {
                if (fr == null) return;
                CtField<?> fd = fr.getFieldDeclaration();
                if (fd != null) {
                    keep.markSig(fd);
                    if (seenSig.add(fd)) sigQ.add(fd);
                }
                if (fr.getType() != null) keep.markSig(fr.getType());
                if (fr.getDeclaringType() != null) keep.markSig(fr.getDeclaringType());
            }

            private void markType(CtTypeReference<?> tr) {
                if (tr == null) return;
                keep.markSig(tr);
                CtType<?> decl = tr.getDeclaration();
                if (decl != null && seenSig.add(decl)) sigQ.add(decl);
            }

            @Override public <T> void visitCtInvocation(CtInvocation<T> inv) {
                keep.markSig(inv);                       // keep call site
                markExec(inv, inv.getExecutable());      // pass context invocation
                super.visitCtInvocation(inv);
            }

            @Override public <T> void visitCtConstructorCall(CtConstructorCall<T> call) {
                keep.markSig(call);
                markExec(null, call.getExecutable());
                markType(call.getType());
                super.visitCtConstructorCall(call);
            }

            @Override
            public <T, E extends CtExpression<?>> void visitCtExecutableReferenceExpression(
                    CtExecutableReferenceExpression<T, E> e) {
                keep.markSig(e);
                markExec(null, e.getExecutable());
                super.visitCtExecutableReferenceExpression(e);
            }

            @Override public <T> void visitCtFieldRead(CtFieldRead<T> r)  { keep.markSig(r);  markField(r.getVariable());  super.visitCtFieldRead(r); }
            @Override public <T> void visitCtFieldWrite(CtFieldWrite<T> w){ keep.markSig(w);  markField(w.getVariable());  super.visitCtFieldWrite(w); }
            @Override public <T> void visitCtTypeAccess(CtTypeAccess<T> a){ keep.markSig(a);  markType(a.getAccessedType()); super.visitCtTypeAccess(a); }
            @Override public <T> void visitCtNewArray(CtNewArray<T> na)   { keep.markSig(na); markType(na.getType());     super.visitCtNewArray(na); }

            @Override public void visitCtCatch(CtCatch c) {
                keep.markSig(c);
                if (c.getParameter() != null) markType(c.getParameter().getType());
                super.visitCtCatch(c);
            }

            @Override
            public void visitCtForEach(CtForEach fe) {
                keep.markSig(fe);
                CtExpression<?> expr = fe.getExpression();
                if (expr != null && expr.getType() != null) keep.markSig(expr.getType());
                super.visitCtForEach(fe);
            }

            @Override public void visitCtThrow(CtThrow t) {
                keep.markSig(t);
                if (t.getThrownExpression() != null && t.getThrownExpression().getType() != null)
                    keep.markSig(t.getThrownExpression().getType());
                super.visitCtThrow(t);
            }

            @Override public void visitCtTryWithResource(CtTryWithResource t) {
                keep.markSig(t);
                for (CtResource<?> r : t.getResources()) {
                    keep.markSig(r);
                    markType(r.getType());
                    CtExpression<?> init = r.getDefaultExpression();
                    if (init != null && init.getType() != null) keep.markSig(init.getType());
                }
                super.visitCtTryWithResource(t);
            }

            @Override public <T> void visitCtNewClass(CtNewClass<T> nc) {
                keep.markSig(nc);
                CtClass<?> anon = nc.getAnonymousClass();
                if (anon != null) {
                    keep.markSig(anon);
                    if (seenSig.add(anon)) sigQ.add(anon);
                }
                super.visitCtNewClass(nc);
            }
        };

        // Fixpoint: process signatures (cheap), then bodies (once per FULL)
        while (!sigQ.isEmpty() || !bodyQ.isEmpty()) {

            // Signatures: chase only signature-level info; enqueue bodies only for FULL members
            while (!sigQ.isEmpty()) {
                CtElement d = sigQ.poll();
                if (d == null || !seenSig.add(d)) continue;

                if (d instanceof CtMethod) {
                    CtMethod<?> m = (CtMethod<?>) d;
                    keep.markSig(m);
                    if (m.getType() != null) keep.markSig(m.getType());
                    for (CtParameter<?> p : m.getParameters()) if (p.getType() != null) keep.markSig(p.getType());
                    for (CtTypeReference<?> thr : m.getThrownTypes()) keep.markSig(thr);
                    if (keep.retentionOf(m) == KeepSet.Retention.FULL && seenBody.add(m)) bodyQ.add(m);
                    // If it's an interface method, keep the whole interface signatures
                    CtType<?> owner = m.getDeclaringType();
                    if (owner != null && owner.isInterface()) {
                        keep.markSig(owner);
                        for (CtMethod<?> mm : owner.getMethods()) keep.markSig(mm);
                    }


                } else if (d instanceof CtConstructor) {
                    CtConstructor<?> c = (CtConstructor<?>) d;
                    keep.markSig(c);
                    for (CtParameter<?> p : c.getParameters()) if (p.getType() != null) keep.markSig(p.getType());
                    for (CtTypeReference<?> thr : c.getThrownTypes()) keep.markSig(thr);
                    if (keep.retentionOf(c) == KeepSet.Retention.FULL && seenBody.add(c)) bodyQ.add(c);

                } else if (d instanceof CtField) {
                    CtField<?> f = (CtField<?>) d;
                    keep.markSig(f);
                    if (f.getType() != null) keep.markSig(f.getType());

                } else if (d instanceof CtType) {
                    CtType<?> t = (CtType<?>) d;
                    keep.markSig(t);
                    if (t instanceof CtClass) {
                        CtTypeReference<?> sc = ((CtClass<?>) t).getSuperclass();
                        if (sc != null) keep.markSig(sc);
                    }
                    for (CtTypeReference<?> i : t.getSuperInterfaces()) keep.markSig(i);
                }
            }

            // Bodies: scan each FULL body once with the fast scanner
            while (!bodyQ.isEmpty()) {
                CtExecutable<?> ex = bodyQ.poll();
                if (!seenBody.add(ex)) continue;
                CtBlock<?> b = ex.getBody();
                if (b != null) b.accept(bodyScanner);
            }
        }
    }



    // keep the whole overload group if unresolved
    private void keepOverloadGroup(CtInvocation<?> inv, CtExecutableReference<?> ref) {
        CtTypeReference<?> holder = ref.getDeclaringType();
        if (holder == null || holder.getDeclaration() == null) return;
        CtType<?> type = holder.getDeclaration();
        for (CtMethod<?> m : type.getMethods()) {
            if (m.getSimpleName().equals(ref.getSimpleName())) keep.markSig(m);
        }
    }

}
