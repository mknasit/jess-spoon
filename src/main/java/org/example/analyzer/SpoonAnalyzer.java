package org.example.analyzer;

import org.example.slicer.SlicedModelBuilder;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SpoonAnalyzer {

    private final Launcher launcher;
    private final CtModel model;

    public SpoonAnalyzer(String inputPath) {
        this.launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setPreserveLineNumbers(false);
        launcher.getEnvironment().setTabulationSize(4);
        launcher.getEnvironment().setEncoding(StandardCharsets.UTF_8);
        launcher.getEnvironment().setSourceOutputDirectory(new File("out/gen"));

        launcher.addInputResource(inputPath);
        launcher.buildModel();
        this.model = launcher.getModel();
    }

    public CtModel getModel() { return model; }

    public Launcher getLauncher() { return launcher; }

    public CtMethod<?> findTargetMethod(String qualifiedClassName, String methodName) {
        for (CtType<?> t : model.getAllTypes()) {
            if (t.getQualifiedName().equals(qualifiedClassName) || t.getSimpleName().equals(qualifiedClassName)) {
                for (CtMethod<?> m : t.getMethods()) {
                    if (m.getSimpleName().equals(methodName)) return m;
                }
            }
        }
        return null;
    }

    /**
     * Minimal, target-centric seed. In lean mode, we ALWAYS use this.
     * Only what the target needs to compile:
     *  - the method itself (FULL later)
     *  - its declaring type (signature)
     *  - parameter/return/thrown types (type refs)
     *  - direct calls/ctor-calls/field-accesses in the body
     */
    public Set<CtElement> collectMinimalSeed(CtMethod<?> method) {
        Set<CtElement> seed = new LinkedHashSet<>();
        if (method == null) return seed;

        SlicedModelBuilder.setTrueTargetMethod(method);

        seed.add(method);                // the target
        seed.add(method.getType());      // return type

        CtType<?> owner = method.getDeclaringType();
        if (owner != null) {
            seed.add(owner);
            if (owner.getReference() != null) seed.add(owner.getReference());
            if (owner.getSuperclass() != null) seed.add(owner.getSuperclass());
            seed.addAll(owner.getSuperInterfaces());
        }

        for (CtParameter<?> p : method.getParameters()) {
            seed.add(p);
            seed.add(p.getType());
        }
        for (CtTypeReference<?> thr : method.getThrownTypes()) {
            seed.add(thr);
        }

        // direct body deps
        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            seed.add(inv);
            if (inv.getExecutable() != null && inv.getExecutable().getDeclaration() != null) {
                seed.add(inv.getExecutable().getDeclaration());
            }
        }
        for (CtConstructorCall<?> call : method.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            seed.add(call);
            if (call.getExecutable() != null && call.getExecutable().getDeclaration() != null) {
                seed.add(call.getExecutable().getDeclaration());
            }
            if (call.getType() != null && call.getType().getDeclaration() != null) {
                seed.add(call.getType().getDeclaration());
            }
        }
        for (CtFieldAccess<?> fa : method.getElements(new TypeFilter<>(CtFieldAccess.class))) {
            seed.add(fa);
            if (fa.getVariable() != null && fa.getVariable().getDeclaration() != null) {
                seed.add(fa.getVariable().getDeclaration());
            }
        }

        return seed;
    }

    /**
     * Pretty-print the (sliced) model to out/gen
     */
    public void printSlicedModel() {
        launcher.prettyprint();
    }

    public Set<CtElement> findAndCollectMultipleTargets(Map<String, List<String>> targetMap) {
        Set<CtElement> all = new LinkedHashSet<>();

        for (CtType<?> t : model.getAllTypes()) {
            String qn = t.getQualifiedName();
            String sn = t.getSimpleName();

            for (Map.Entry<String, List<String>> entry : targetMap.entrySet()) {
                String targetClass = entry.getKey();
                if (!targetClass.equals(qn) && !targetClass.equals(sn)) continue;

                for (CtMethod<?> m : t.getMethods()) {
                    if (entry.getValue().contains(m.getSimpleName())) {
                        all.addAll(collectMinimalSeed(m));
                    }
                }
            }
        }

        // strip unresolved type references (no declaration)
        all.removeIf(el ->
                el instanceof CtTypeReference && ((CtTypeReference<?>) el).getDeclaration() == null
        );
        return all;
    }
}
