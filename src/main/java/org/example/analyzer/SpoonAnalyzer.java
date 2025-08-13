package org.example.analyzer;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.example.slicer.SlicedModelBuilder;

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


    public CtModel getModel() {
        return model;
    }

    public Launcher getLauncher() {
        return launcher;
    }

    // 1. Find a method by its class name and method name
    public CtMethod<?> findTargetMethod(String qualifiedClassName, String methodName) {
        for (CtType<?> ctType : model.getAllTypes()) {
            if (ctType.getSimpleName().equals(qualifiedClassName) || ctType.getQualifiedName().equals(qualifiedClassName)) {
                for (CtMethod<?> method : ctType.getMethods()) {
                    if (method.getSimpleName().equals(methodName)) {
                        return method;
                    }

                }
            }
        }
        return null;
    }


    public Set<CtElement> collectResolvedDependencies(CtMethod<?> method) {
        Set<CtElement> dependencies = new HashSet<>();
        SlicedModelBuilder.setTrueTargetMethod(method);
        if (SlicedModelBuilder.leanMode && !isTargetMethod(method)) {
            dependencies.add(method);
            dependencies.add(method.getType());

            for (CtParameter<?> param : method.getParameters()) {
                dependencies.add(param);
                dependencies.add(param.getType());
            }

            for (CtTypeReference<?> thrown : method.getThrownTypes()) {
                dependencies.add(thrown);
            }

            CtType<?> type = method.getDeclaringType();
            if (type != null) {
                dependencies.add(type);
                dependencies.add(type.getReference());

                // optional: add interface and superclass
                if (type.getSuperclass() != null) {
                    dependencies.add(type.getSuperclass());
                }
                dependencies.addAll(type.getSuperInterfaces());
            }

            return dependencies;
        }


        // ✅ Method invocations
        for (CtInvocation<?> invocation : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            dependencies.add(invocation);
            CtExecutable<?> resolved = invocation.getExecutable().getDeclaration();
            if (resolved != null) dependencies.add(resolved);
            if (resolved instanceof CtMethod) {
                CtType<?> declaringType = ((CtMethod<?>) resolved).getDeclaringType();
                if (declaringType != null) dependencies.add(declaringType);
            }
            if (resolved instanceof CtConstructor) {
                CtType<?> declaringType = ((CtConstructor<?>) resolved).getDeclaringType();
                if (declaringType != null) dependencies.add(declaringType);
            }

        }

        // ✅ Constructor calls
        for (CtConstructorCall<?> ctor : method.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            dependencies.add(ctor);
            CtExecutable<?> resolved = ctor.getExecutable().getDeclaration();
            if (resolved != null) dependencies.add(resolved);
            CtTypeReference<?> typeRef = ctor.getType();
            if (typeRef != null && typeRef.getDeclaration() != null) {
                dependencies.add(typeRef.getDeclaration());
            }
            for (CtExpression<?> arg : ctor.getArguments()) {
                dependencies.addAll(arg.getElements(new TypeFilter<>(CtElement.class)));
            }

        }


        // ✅ Field reads and writes (incl. static fields)
        for (CtFieldAccess<?> field : method.getElements(new TypeFilter<>(CtFieldAccess.class))) {
            dependencies.add(field);
            var resolved = field.getVariable().getDeclaration();
            if (resolved != null) dependencies.add(resolved);
        }

        // ✅ Return + parameter types
        dependencies.add(method.getType());
        for (CtParameter<?> param : method.getParameters()) {
            boolean isUsed = method.getElements(new TypeFilter<>(CtVariableRead.class)).stream()
                    .anyMatch(read -> read.getVariable().equals(param.getReference()));
            if (isUsed) {
                dependencies.add(param);
                dependencies.add(param.getType());
            }
        }


        // ✅ Thrown exceptions
        for (CtThrow thrownStmt : method.getElements(new TypeFilter<>(CtThrow.class))) {
            CtExpression<?> thrownExpr = thrownStmt.getThrownExpression();
            if (thrownExpr != null) dependencies.add(thrownExpr.getType());
        }


        // ✅ Annotations
        for (CtAnnotation<?> annotation : method.getAnnotations()) {
            dependencies.add(annotation.getAnnotationType());
        }

        // ✅ Declaring type + supertype/interfaces (even unresolved)
        CtType<?> declaringType = method.getDeclaringType();
        if (declaringType != null) {
            dependencies.add(declaringType);
            CtTypeReference<?> superType = declaringType.getSuperclass();
            if (superType != null) dependencies.add(superType);
            for (CtTypeReference<?> iface : declaringType.getSuperInterfaces()) {
                dependencies.add(iface);
            }
        }

        // ✅ Overridden methods (e.g., from interfaces/superclasses)
        for (CtMethod<?> overridden : method.getTopDefinitions()) {
            dependencies.add(overridden);
        }


        // 🔁 Superclass & interfaces hierarchy
        CtTypeReference<?> currentRef = method.getDeclaringType().getReference();
        while (currentRef != null) {
            dependencies.add(currentRef);
            if (currentRef.getDeclaration() != null) {
                dependencies.add(currentRef.getDeclaration());
            }
            currentRef = currentRef.getSuperclass();
        }


        // ✅ Add all fields, methods, constructors of the declaring class
        if (declaringType != null) {
            dependencies.addAll(declaringType.getFields());
            //   dependencies.addAll(declaringType.getMethods());
            if (declaringType instanceof CtClass<?>) {
                dependencies.addAll(((CtClass<?>) declaringType).getConstructors());
            }
            Set<CtType<?>> nestedUsed = new HashSet<>();
            for (CtType<?> nested : declaringType.getNestedTypes()) {
                for (CtElement dep : dependencies) {
                    if (dep instanceof CtTypeReference && ((CtTypeReference<?>) dep).getQualifiedName().equals(nested.getQualifiedName())) {
                        nestedUsed.add(nested);
                    }
                }
            }
            dependencies.addAll(nestedUsed);

        }

        // ✅ Static final fields
        if (declaringType != null) {
            for (CtField<?> field : declaringType.getFields()) {
                if (field.hasModifier(ModifierKind.STATIC) && field.hasModifier(ModifierKind.FINAL)) {
                    dependencies.add(field);
                    if (field.getAssignment() != null) {
                        dependencies.add(field.getAssignment());
                    }
                }
            }
        }

        for (CtFieldReference<?> fieldRef : method.getElements(new TypeFilter<>(CtFieldReference.class))) {
            if (fieldRef.getDeclaration() != null) {
                dependencies.add(fieldRef.getDeclaration());
            }
        }

        dependencies.removeIf(dep -> dep instanceof CtTypeReference && (
                ((CtTypeReference<?>) dep).getQualifiedName().startsWith("java.")
        ));


        return dependencies;
    }


    /**
     * Finds and collects resolved dependencies for multiple target methods.
     * Map: class name → list of method names
     */
    public Set<CtElement> findAndCollectMultipleTargets(Map<String, List<String>> targetMap) {
        Set<CtElement> allDependencies = new HashSet<>();

        for (CtType<?> ctType : model.getAllTypes()) {
            String simpleName = ctType.getSimpleName();
            String qualifiedName = ctType.getQualifiedName();

            for (Map.Entry<String, List<String>> entry : targetMap.entrySet()) {
                String targetClass = entry.getKey();
                List<String> methodNames = entry.getValue();

                if (targetClass.equals(simpleName) || targetClass.equals(qualifiedName)) {
                    for (CtMethod<?> method : ctType.getMethods()) {
                        if (methodNames.contains(method.getSimpleName())) {
                            //System.out.println("Found method: " + method.getSignature());
                            allDependencies.add(method);
                            allDependencies.addAll(collectResolvedDependencies(method));
                        }
                    }
                }
            }
        }
        // Filter unresolved or non-model types (e.g., unresolved imports)
        allDependencies.removeIf(dep ->
                dep instanceof CtTypeReference && ((CtTypeReference<?>) dep).getDeclaration() == null
        );
        return allDependencies;
    }

    /**
     * Pretty-prints the current model to disk (out/gen).
     */
    public void printSlicedModel() {
        launcher.prettyprint();
    }


    private boolean isTargetMethod(CtMethod<?> method) {
        return method.getSimpleName().equals("main")
                || method.hasModifier(ModifierKind.PUBLIC);
    }


}
