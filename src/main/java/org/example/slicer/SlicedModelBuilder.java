package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.ImportCleaner;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.declaration.CtTypeMember;
import java.util.*;
import java.util.stream.Collectors;

public class SlicedModelBuilder {

    public final CtModel model;
    public final Set<CtElement> toKeep;
    private final Set<String> unresolvedTypes = new HashSet<>();
    public static boolean leanMode = false;
    public static CtMethod<?> trueTargetMethod = null;

    public SlicedModelBuilder(CtModel model, Set<CtElement> dependencies) {
        this.model = model;
        this.toKeep = new HashSet<>(dependencies);
    }

    public void slice() {
        if (!leanMode) {
            expandTransitiveTypeReferences();
            expandFieldDependencies();
            expandInvokedMethodDependencies();
            expandMethodBodyDependencies();
        }
        else {
            expandMinimalDependencies();  // 🔥 NEW
        }

        for (CtElement el : toKeep) {
            if (el instanceof CtAnonymousExecutable) {
                CtAnonymousExecutable block = (CtAnonymousExecutable) el;

                for (CtAssignment assign : block.getElements(new TypeFilter<>(CtAssignment.class))) {
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
                                        target.getAccessedType().getQualifiedName().equals(field.getDeclaringType().getQualifiedName())) {

                                    // ✅ Replace with simple field read: STANDARD_CHARSET_MAP = ...
                                    CtFieldRead<?> newLhs = (CtFieldRead<?>) field.getFactory().Code().createVariableRead(ref, true);
                                    assign.setAssigned(newLhs);
                                }
                            }
                        }
                    }
                }
            }
        }


        // 🔒 Filter out elements with unresolved or missing declarations
        toKeep.removeIf(el -> {
            if (el instanceof CtTypeReference<?>) {
                CtTypeReference<?> ref = (CtTypeReference<?>) el;
                CtType<?> decl = ref.getDeclaration();
                // ⚠️ Allow generics, allow unresolved java.* types
                if (decl == null && !ref.getQualifiedName().matches("^[A-Z]$")) {
                    return true;
                }
                return decl != null && decl.getPosition() instanceof spoon.reflect.cu.position.NoSourcePosition;
            }

            if (el instanceof CtExecutableReference<?>) {
                CtExecutableReference<?> ref = (CtExecutableReference<?>) el;
                CtExecutable<?> decl = ref.getDeclaration();
                return decl == null || decl.getPosition() == null || decl.getPosition() instanceof spoon.reflect.cu.position.NoSourcePosition;
            }
            if (el instanceof CtMethod<?>) {
                CtType<?> parent = ((CtMethod<?>) el).getDeclaringType();
                return parent == null || parent.getPosition() == null || parent.getPosition() instanceof spoon.reflect.cu.position.NoSourcePosition;
            }
            if (el instanceof CtConstructor<?>) {
                CtType<?> parent = ((CtConstructor<?>) el).getDeclaringType();
                return parent == null || parent.getPosition() == null || parent.getPosition() instanceof spoon.reflect.cu.position.NoSourcePosition;
            }
            if (el instanceof CtFieldReference<?>) {
                CtFieldReference<?> ref = (CtFieldReference<?>) el;
                CtField<?> field = ref.getDeclaration();
                return field == null || field.getPosition() == null || field.getPosition() instanceof spoon.reflect.cu.position.NoSourcePosition;
            }
            if (el instanceof CtField<?>) {
                CtField<?> field = (CtField<?>) el;
                return field.getDeclaringType() == null;
            }
            if (el instanceof CtInvocation<?>) {
                CtInvocation<?> inv = (CtInvocation<?>) el;
                return inv.getExecutable().getDeclaration() == null;
            }
            if (el instanceof CtConstructorCall<?>) {
                CtConstructorCall<?> call = (CtConstructorCall<?>) el;
                return call.getExecutable().getDeclaration() == null;
            }
            return false;
        });


        for (CtType<?> type : model.getAllTypes()) {
            //System.out.println("Kept class: " + type.getQualifiedName());
            //System.out.println("  Kept methods: " + type.getMethods().stream().filter(toKeep::contains).collect(Collectors.toList()));
            //System.out.println("  Kept fields: " + type.getFields().stream().filter(toKeep::contains).collect(Collectors.toList()));
            if (!shouldKeepType(type)) {
                // 🔥 Delete only if not needed
                type.delete();
            } else {
                pruneType(type);
            }
        }


        for (CtType<?> type : new ArrayList<>(model.getAllTypes())) {
            boolean isUsed = toKeep.contains(type)
                    || toKeep.stream().anyMatch(e ->
                    e instanceof CtTypeMember && ((CtTypeMember) e).getDeclaringType() == type);
            if (!isUsed) {
                type.delete(); // very important
            }
        }

        // 🔁 Clean again to avoid unreferenced dead nodes lingering
        toKeep.removeIf(el -> {
            if (el instanceof CtElement) {
                CtType<?> type = el.getParent(CtType.class);
                return type != null && !model.getAllTypes().contains(type);
            }
            return false;
        });


        for (CtType<?> type : model.getAllTypes()) {
            ImportCleaner cleaner = new ImportCleaner();
            cleaner.process(type);
        }

        // 🔧 Ensure all kept classes have required method implementations
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type instanceof CtClass)) continue;

            CtClass<?> clazz = (CtClass<?>) type;

            List<CtMethod<?>> missing = getUnimplementedAbstractMethods(clazz);
            if (missing.isEmpty()) continue;

            if (!isInstantiated(clazz)) {
                if (clazz.hasModifier(ModifierKind.FINAL)) {
                  //  System.out.println("⚠️ Removing final from class to allow abstract: " + clazz.getQualifiedName());
                    clazz.removeModifier(ModifierKind.FINAL);
                }
                clazz.addModifier(ModifierKind.ABSTRACT);
               // System.out.println("🔧 Marked class abstract (not instantiated): " + clazz.getQualifiedName());
            } else {
                for (CtMethod<?> abstractMethod : missing) {
                    CtMethod<?> stub = abstractMethod.clone();
                    stub.removeModifier(ModifierKind.ABSTRACT);
                    stub.setBody(createStubBody(clazz, stub.getType()));
                    stub.setVisibility(ModifierKind.PUBLIC); // Ensure correct visibility
                    clazz.addMethod(stub);
                    //System.out.println("🧪 Stubbed abstract method: " + stub.getSignature() + " in " + clazz.getQualifiedName());
                }
            }
        }

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

// Safely remove fields from both model and toKeep
        for (CtField<?> field : removableFields) {
            toKeep.remove(field);
            field.delete();
            // Optional debug: System.out.println("Removed uninitialized static final field: " + field.getSimpleName());
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
                            // 🔥 Remove assignment from declaration
                            field.setAssignment(null);
                            break;
                        }
                    }
                }
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
        CtType<?> type = el.getParent(CtType.class);
        if (type == null) return false;

        // Ignore Java/JDK classes
        String qName = type.getQualifiedName();
        if (qName.startsWith("java.") || qName.startsWith("javax.")) return false;

        // Exclude primitive types or unresolved junk
        if (!qName.contains(".") || qName.equals("<nulltype>")) return false;

        // If the element's type exists in the Spoon model, we assume it's part of the project
        return model.getAllTypes().contains(type);
    }


    private void pruneType(CtType<?> type) {
        // Track accessed fields from kept methods
        Set<String> accessedFields = new HashSet<>();
        //System.out.println("🔍 Pruning type: " + type.getQualifiedName());

        // ✅ Preserve referenced nested types (e.g., Tracker)
        // ✅ Smarter preservation of nested types
        for (CtTypeMember member : type.getTypeMembers()) {
            if (member instanceof CtType<?>) {
                CtType<?> nested = (CtType<?>) member;

                boolean isActuallyReferenced = model.getElements(new TypeFilter<>(CtTypeReference.class)).stream()
                        .filter(toKeep::contains)
                        .anyMatch(ref -> ref.getQualifiedName().equals(nested.getQualifiedName()));


                if (isActuallyReferenced) {
                    toKeep.add(nested);
                    //System.out.println("🔐 [Lean] Keeping truly referenced nested type: " + nested.getQualifiedName());
                }
            }
        }


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

        // Remove or simplify methods
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
            } else if (leanMode && !isTargetMethod(method)) {
                if (type.isInterface()) {
                    method.setBody(null); // interfaces can't have bodies
                } else {
                    CtBlock<?> body = method.getFactory().Core().createBlock();
                    CtTypeReference<?> returnType = method.getType();


                    if (!"void".equals(returnType.getSimpleName())) {
                        String returnExpr = getDefaultReturn(returnType);
                        CtStatement returnStmt = method.getFactory().Code()
                                .createCodeSnippetStatement("return " + returnExpr);
                        body.addStatement(returnStmt);
                    }
                    for (CtInvocation<?> invocation : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                        if (!invocation.getExecutable().isStatic() && invocation.getTarget() == null) {
                         //   System.out.println("⚠️ LeanMode: Removing invocation with null target: " + invocation);
                            invocation.delete();
                        }
                    }

                    method.setBody(body);
                    method.setVisibility(ModifierKind.PUBLIC);

                }
            }

        }

        if (type instanceof CtClass<?>) {
            CtClass<?> clazz = (CtClass<?>) type;

            // 💡 Step: Check for missing abstract method implementations
            List<CtMethod<?>> missingMethods = getUnimplementedAbstractMethods(clazz);

            if (!missingMethods.isEmpty()) {
                if (!isInstantiated(clazz)) {
                    if (clazz.hasModifier(ModifierKind.FINAL)) {
                     //   System.out.println("⚠️ Removing final from class to allow abstract: " + clazz.getQualifiedName());
                        clazz.removeModifier(ModifierKind.FINAL);
                    }
                    clazz.addModifier(ModifierKind.ABSTRACT);
                  //  System.out.println("🔧 Marked class abstract (not instantiated): " + clazz.getQualifiedName());
                } else {
                    // ✅ Instantiated — stub missing methods
                    for (CtMethod<?> abstractMethod : missingMethods) {
                        CtMethod<?> stub = abstractMethod.clone();
                        stub.removeModifier(ModifierKind.ABSTRACT);
                        stub.setBody(createStubBody(clazz, stub.getType()));
                        stub.setVisibility(ModifierKind.PUBLIC); // Ensure correct visibility
                        clazz.addMethod(stub);
                       // System.out.println("🧪 Stubbed abstract method: " + stub.getSignature() + " in " + clazz.getQualifiedName());
                    }
                }
            }
            if (clazz.hasModifier(ModifierKind.ABSTRACT) && isInstantiated(clazz)) {
                List<CtMethod<?>> stillAbstract = clazz.getMethods().stream()
                        .filter(m -> m.hasModifier(ModifierKind.ABSTRACT))
                        .collect(Collectors.toList());

                if (stillAbstract.isEmpty()) {
                    clazz.removeModifier(ModifierKind.ABSTRACT);
                   // System.out.println("🧼 Removed abstract modifier: " + clazz.getQualifiedName());
                }
            }

        }


        for (CtField<?> field : new ArrayList<>(type.getFields())) {

            boolean used = toKeep.contains(field) || accessedFields.contains(field.getSimpleName());
//            System.out.println("Checking field: " + field.getSimpleName());
//            System.out.println(" - used? " + used);
//            System.out.println(" - in toKeep? " + toKeep.contains(field));
// 🚫 Special case: field refers to an unresolved nested type — remove it
            if (used) {
                CtTypeReference<?> fieldType = field.getType();
                if (fieldType != null) {
                    for (CtTypeReference<?> arg : fieldType.getActualTypeArguments()) {
                        CtType<?> argDecl = arg.getDeclaration();
                        if (argDecl != null && isInProject(argDecl) && !toKeep.contains(argDecl)) {
                            toKeep.add(argDecl);
                           // System.out.println("🔗 [Lean] Pulled in generic type argument: " + argDecl.getQualifiedName());
                        }
                    }
                }
            }

            if (!used) {
                CtTypeReference<?> fieldType = field.getType();
                if (fieldType != null && fieldType.getQualifiedName().contains("$") &&
                        fieldType.getDeclaration() == null) {
                   // System.out.println("🧹 Removing field with unresolved nested type: " + field.getSimpleName());
                    field.delete();
                    continue;
                }

                field.delete();
                continue;
            }


            if (SlicedModelBuilder.leanMode) {
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
                      //  System.out.println("⛔ Skipping enum constant: " + field.getSimpleName());
                        continue;
                    }

                    if (isReferencedInKeptCode) {
                      //  System.out.println("🛑 Preserving static final field (referenced): " + field.getSimpleName());

                        if (field.getAssignment() == null) {
                            Object fallback = getDefaultReturn(field.getType());

                            field.setAssignment(field.getFactory().Code().createCodeSnippetExpression(fallback.toString()));
                          //  System.out.println("🛠️ Initialized static final field with dummy value: " + field.getSimpleName());

                        }
                    } else {
                     //   System.out.println("🧹 Removing unused static final field: " + field.getSimpleName());
                        field.delete();
                    }
                } else if (hasBrokenAssignment) {
                   // System.out.println("⚠️ Removing invalid assignment from field (lean mode): " + field.getSimpleName());
                    field.setAssignment(null);
                }
            }


        }

        // ✅ Preserve static blocks (CtAnonymousExecutable) if they're in toKeep
        for (CtTypeMember member : new ArrayList<>(type.getTypeMembers())) {
            if (member instanceof CtAnonymousExecutable && !toKeep.contains(member)) {
                member.delete();
            }
        }

        // Prune constructors
        if (type instanceof CtClass<?>) {
            CtClass<?> clazz = (CtClass<?>) type;
            for (CtConstructor<?> ctor : new ArrayList<>(clazz.getConstructors())) {
                if (!toKeep.contains(ctor)) {
                    ctor.delete();
                }
            }
        }


        // Handle enums
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

        // Prune nested types recursively
        // Prune nested types recursively
        for (CtType<?> nested : new ArrayList<>(type.getNestedTypes())) {
            if (shouldKeepType(nested)) {
                // ✅ If nested type is kept, keep its constructors if it's a class
                if (nested instanceof CtClass<?>) {
                    for (CtConstructor<?> ctor : ((CtClass<?>) nested).getConstructors()) {
                        if (!toKeep.contains(ctor)) {
                            // toKeep.add(ctor);
                            //  System.out.println("🔧 [Lean] Preserved constructor in nested class: " + ctor.getSignature());

                            // 🧠 Add: Pull in all field references from this constructor
                            for (CtFieldAccess<?> access : ctor.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                                CtFieldReference<?> ref = access.getVariable();
                                CtField<?> field = ref.getDeclaration();
                                if (field != null && isInProject(field)) {
                                    //  toKeep.add(field);
                                    //  System.out.println("📌 [Lean] Preserved field used in constructor: " + field.getSimpleName());
                                }
                            }
                            // 🆕 Pull in fields declared in the nested type that are used in the constructor body
                            for (CtField<?> nestedField : nested.getFields()) {
                                boolean usedInCtor = ctor.getElements(new TypeFilter<>(CtFieldAccess.class)).stream()
                                        .map(CtFieldAccess::getVariable)
                                        .anyMatch(ref -> ref != null && ref.getSimpleName().equals(nestedField.getSimpleName()));

                                if (usedInCtor && !toKeep.contains(nestedField)) {
                                    //  toKeep.add(nestedField);
                                    //  System.out.println("📌 [Lean] Preserved nested field used in constructor: " + nestedField.getSimpleName());
                                }
                            }

                        }
                    }
                }

                pruneType(nested);
            } else {
                nested.delete();
            }

        }


        for (CtField<?> field : new ArrayList<>(type.getFields())) {
            if (!toKeep.contains(field)) {
             //   System.out.println("🧹 Hard removing undeclared field: " + field.getSimpleName());
                field.delete();
            }
        }

    }


    private boolean isTargetMethod(CtMethod<?> method) {
        if (trueTargetMethod == null) return false;
        return method.getDeclaringType().getQualifiedName().equals(
                trueTargetMethod.getDeclaringType().getQualifiedName()
        ) && method.getSignature().equals(trueTargetMethod.getSignature());
    }


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


            // In expandFieldDependencies
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
                            if (exec != null && isInProject(exec)) toKeep.add(exec);
                        }

                        for (CtFieldAccess<?> access : right.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                            CtField<?> field = access.getVariable().getDeclaration();
                            if (field != null) toKeep.add(field);
                        }
                    }
                }
            }

            // ✅ Ensure static initializers for static final fields are kept
            // ✅ Preserve assignments to final static fields inside static blocks
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

                                                // ✅ Keep static block
                                                toKeep.add(staticBlock);

                                                // ✅ Keep assignment
                                                toKeep.add(assign);

                                                // ✅ Keep assignment's parent statement
                                                CtStatement stmt = assign.getParent(CtStatement.class);
                                                if (stmt != null) {
                                                    toKeep.add(stmt);
                                                }

                                                // ✅ Keep the full block itself (just to be 100% sure)
                                                if (staticBlock.getBody() != null) {
                                                    toKeep.add(staticBlock.getBody());
                                                }

                                               // System.out.println("✅ Keeping full static assignment to: " + field.getSimpleName());
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
                if (ref != null && ref.getQualifiedName() != null && !ref.getQualifiedName().equals("<nulltype>")) {
                    queue.add(ref);
                }
            }
        }

        while (!queue.isEmpty()) {
            CtTypeReference<?> ref = queue.poll();
            if (ref == null || discovered.contains(ref)) continue;

            String qName = ref.getQualifiedName();
            Set<String> illegalNames = Set.of(
                    "int", "long", "boolean", "char", "float", "double", "byte", "short", "void", "<nulltype>"
            );

            if (ref.isPrimitive() || qName == null || illegalNames.contains(qName)) continue;
            if (qName.startsWith("java.")) continue;

            // ✅ Avoid short garbage names like 'i', 't'
            if (!qName.contains(".") || qName.matches("^[a-zA-Z]$")) continue;

            CtType<?> decl = ref.getDeclaration();
            if (decl == null || !model.getAllTypes().contains(decl)) {
                //System.out.println("⚠️ Unresolved type or not in model: " + qName);
                unresolvedTypes.add(qName);
                continue;
            }

            discovered.add(ref);
            toKeep.add(ref);


            // Avoid keeping entire class unless it's the entry point
            if (isEntryPoint(decl)) {
                toKeep.add(decl);
            }

            // Recurse into all nested references only if we haven’t seen them
            for (CtTypeReference<?> nestedRef : decl.getReferencedTypes()) {
                if (nestedRef != null &&
                        nestedRef.getQualifiedName() != null &&
                        !nestedRef.getQualifiedName().equals("<nulltype>") &&
                        !discovered.contains(nestedRef)) {
                    queue.add(nestedRef);
                }
            }

            // You can keep your field/method additions here if needed
            for (CtField<?> field : decl.getFields()) {
                if (toKeep.stream().anyMatch(e ->
                        e instanceof CtFieldAccess && ((CtFieldAccess<?>) e).getVariable().equals(field.getReference())
                )) {
                    toKeep.add(field);
                    if (field.getAssignment() != null) {
                        toKeep.addAll(field.getAssignment().getReferencedTypes());
                    }
                }
            }

            // Constructors
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
                    // Heuristic: fallback by name match when declaration is unavailable
                    CtTypeReference<?> declaringTypeRef = execRef.getDeclaringType();
                    if (declaringTypeRef != null) {
                        CtType<?> declaringType = declaringTypeRef.getDeclaration();
                        if (declaringType != null) {
                            for (CtMethod<?> method : declaringType.getMethods()) {
                                if (method.getSimpleName().equals(execRef.getSimpleName()) && isInProject(method)) {
                                    toKeep.add(method);
                                }
                            }
                        }
                    }

                }


            }
            if (el instanceof CtConstructorCall<?>) {
                CtConstructorCall<?> call = (CtConstructorCall<?>) el;
                List<CtExpression<?>> args = call.getArguments();
                for (CtExpression<?> arg : args) {
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
                // 🔐 prevent uncontrolled growth
                if (executable.getBody() != null) {
                    // Find field accesses
                    for (CtFieldAccess<?> access : executable.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                        CtField<?> field = access.getVariable().getDeclaration();
                        if (field != null && isInProject(field)) toKeep.add(field);
                    }

                    // Find method calls
                    for (CtInvocation<?> invocation : executable.getElements(new TypeFilter<>(CtInvocation.class))) {
                        CtExecutable<?> exec = invocation.getExecutable().getDeclaration();
                        if (exec != null && isInProject(exec)) toKeep.add(exec);
                    }

                    // Find constructor calls
                    for (CtConstructorCall<?> call : executable.getElements(new TypeFilter<>(CtConstructorCall.class))) {
                        CtExecutable<?> exec = call.getExecutable().getDeclaration();
                        if (exec != null && isInProject(exec)) toKeep.add(exec);
                    }

                    // Thrown exception types
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
                                        .anyMatch(m -> m.getSimpleName().equals(abstractMethod.getSimpleName()) &&
                                                m.getParameters().size() == abstractMethod.getParameters().size());

                                if (!isImplemented) {
                                    //System.out.println("🚨 Missing implementation for abstract method: " + abstractMethod.getSignature());
                                    toKeep.add(abstractMethod); // Force keep to avoid accidental slicing
                                }
                            }
                        }
                    }
                }
            }

        }

        // 🧱 Ensure constructors for direct superclasses are kept if class is kept
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

    public static CtMethod<?> getTrueTargetMethod() {
        return trueTargetMethod;
    }


    public static void setTrueTargetMethod(CtMethod<?> trueTargetMethod) {
        SlicedModelBuilder.trueTargetMethod = trueTargetMethod;
    }

    private String getDefaultReturn(CtTypeReference<?> type) {
        switch (type.getSimpleName()) {
            case "int":
            case "short":
            case "byte":
            case "long":
                return "0";
            case "float":
                return "0.0f";
            case "double":
                return "0.0";
            case "boolean":
                return "false";
            case "char":
                return "'a'";
            case "String":
                return "\"\"";
            case "SortedMap":
            case "Map":
                return "java.util.Collections.emptySortedMap()";
            case "List":
                return "java.util.Collections.emptyList()";
            default:
                return "null";
        }
    }

    private boolean isInstantiated(CtClass<?> clazz) {
        return toKeep.stream()
                .filter(CtConstructorCall.class::isInstance)
                .map(CtConstructorCall.class::cast)
                .anyMatch(call -> {
                    CtTypeReference<?> typeRef = call.getType();
                    return typeRef != null && typeRef.getQualifiedName().equals(clazz.getQualifiedName());
                });
    }


    private List<CtMethod<?>> getUnimplementedAbstractMethods(CtClass<?> clazz) {
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
                // 🥄 Spoon-declared supertype
                for (CtMethod<?> superMethod : superType.getMethods()) {
                    if (superMethod.hasModifier(ModifierKind.ABSTRACT)) {
                        if (!implementedSignatures.contains(superMethod.getSignature())) {
                            missing.add(superMethod);
                        }
                    }
                }
            } else {
                // 🪞 Fallback: Reflection
                try {
                    Class<?> refl = Class.forName(superRef.getQualifiedName());
                    for (java.lang.reflect.Method m : refl.getMethods()) {
                        if (!java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;

                        String sig = m.getName() + "(" + Arrays.stream(m.getParameterTypes())
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(", ")) + ")";

                        boolean alreadyImplemented = implementedSignatures.stream().anyMatch(s -> s.startsWith(m.getName() + "("));
                        if (alreadyImplemented) continue;

                        // 🔧 Create stub method
                        CtMethod<?> stub = clazz.getFactory().Core().createMethod();
                        stub.setSimpleName(m.getName());
                        stub.setType(clazz.getFactory().Type().createReference(m.getReturnType()));

                        for (int i = 0; i < m.getParameterCount(); i++) {
                            Class<?> paramType = m.getParameterTypes()[i];
                            CtParameter<?> param = clazz.getFactory().Core().createParameter();
                            param.setType(clazz.getFactory().Type().createReference(paramType));
                            param.setSimpleName("arg" + i);
                            stub.addParameter(param);
                        }

                        CtBlock<?> body = createStubBody(clazz, stub.getType());
                        stub.setBody(body);
                        missing.add(stub);
                    }
                } catch (ClassNotFoundException e) {
                  //  System.out.println("⚠️ Could not reflect type: " + superRef.getQualifiedName());
                }
            }
        }

        return missing;
    }

    private CtBlock<?> createStubBody(CtClass<?> clazz, CtTypeReference<?> returnType) {
        CtBlock<?> body = clazz.getFactory().Core().createBlock();

        if (!"void".equals(returnType.getSimpleName())) {
            String expr = getDefaultReturn(returnType);
            CtStatement returnStmt = clazz.getFactory().Code().createCodeSnippetStatement("return " + expr);
            body.addStatement(returnStmt);
        }

        return body;
    }


    private void expandMinimalDependencies() {
        Set<CtElement> copy = new HashSet<>(toKeep);

        for (CtElement el : copy) {
            // 🔹 Invocation resolution
            if (el instanceof CtInvocation<?>) {
                CtInvocation<?> invocation = (CtInvocation<?>) el;
                CtExecutable<?> exec = invocation.getExecutable().getDeclaration();
                if (exec != null) toKeep.add(exec);
            }

            // 🔹 Constructor resolution
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

                    // ✅ Only preserve static block if field is actually needed
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




        }
    }



}
