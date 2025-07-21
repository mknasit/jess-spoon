package org.example.slicer;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.ImportCleaner;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtTypeMember;
;
import java.util.*;
import java.util.stream.Collectors;

public class SlicedModelBuilder {

    public final CtModel model;
    public final Set<CtElement> toKeep;
    private final Set<String> unresolvedTypes = new HashSet<>();

    public SlicedModelBuilder(CtModel model, Set<CtElement> dependencies) {
        this.model = model;
        this.toKeep = new HashSet<>(dependencies);
    }

    public void slice() {
        expandTransitiveTypeReferences();
        expandFieldDependencies();
        expandInvokedMethodDependencies();
        expandMethodBodyDependencies();

        // 🔒 Filter out elements with unresolved or missing declarations
        toKeep.removeIf(el -> {
            if (el instanceof CtTypeReference<?>) {
                CtTypeReference<?> ref = (CtTypeReference<?>) el;
                CtType<?> decl = ref.getDeclaration();
                return decl == null || decl.getPosition() == null || decl.getPosition() instanceof spoon.reflect.cu.position.NoSourcePosition;
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
            } else {
                if (method.getBody() == null && !method.hasModifier(ModifierKind.ABSTRACT)) {
                    CtBlock<?> emptyBody = method.getFactory().Core().createBlock();
                    method.setBody(emptyBody);
                } else if (!isTargetMethod(method) && !isInvoked) {
                    method.getBody().getStatements().clear();
                }
            }
        }

        /* Remove unused fields
        for (CtField<?> field : new ArrayList<>(type.getFields())) {
            boolean used = toKeep.contains(field) || accessedFields.contains(field.getSimpleName());
            if (!used) {
                field.delete();
            } else {
                // Pull in assignment references if

                if (field.getAssignment() != null) {
                    toKeep.addAll(field.getAssignment().getReferencedTypes());
                    for (CtInvocation<?> invocation : field.getAssignment().getElements(new TypeFilter<>(CtInvocation.class))) {
                        CtExecutable<?> exec = invocation.getExecutable().getDeclaration();
                        if (exec != null) toKeep.add(exec);
                    }
                }
            }
        }
*/


        // Remove unused fields
        for (CtField<?> field : new ArrayList<>(type.getFields())) {
            boolean used = toKeep.contains(field) || accessedFields.contains(field.getSimpleName());
            if (!used) {
                field.delete();
            } else {
                // ⚠️ Special handling for static final fields
                boolean isFinalStatic = field.hasModifier(ModifierKind.FINAL) && field.hasModifier(ModifierKind.STATIC);

                if (isFinalStatic) {
                    boolean hasAssignment = field.getAssignment() != null;

                    boolean hasStaticInitAssignment = toKeep.stream()
                            .filter(CtAssignment.class::isInstance)
                            .map(CtAssignment.class::cast)
                            .anyMatch(assign -> {
                                CtExpression<?> lhs = assign.getAssigned();
                                return lhs instanceof CtFieldAccess &&
                                        ((CtFieldAccess<?>) lhs).getVariable().equals(field.getReference());
                            });

                    if (!hasAssignment && !hasStaticInitAssignment) {
                        System.out.println("⚠️ Removing final static field without assignment: " + field.getSimpleName());
                        field.delete();
                        continue;
                    }

                    if (hasStaticInitAssignment) {
                        // Even if field has no assignment — static block covers it
                        field.removeModifier(ModifierKind.FINAL); // ✅ always remove final
                        System.out.println("🧹 Removed FINAL modifier from field: " + field.getSimpleName());
                    }

                    if (hasAssignment && hasStaticInitAssignment) {
                        field.setAssignment(null);
                    }
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
        for (CtType<?> nested : new ArrayList<>(type.getNestedTypes())) {
            if (shouldKeepType(nested)) {
                pruneType(nested);
            } else {
                nested.delete();
            }
        }
    }



    private boolean isTargetMethod(CtMethod<?> method) {
        return toKeep.contains(method)
                || method.getSimpleName().equals("main")
                || method.hasModifier(ModifierKind.PUBLIC);

    }


    private void expandFieldDependencies() {
        for (CtElement el : new HashSet<>(toKeep)) {
            if (el instanceof CtFieldAccess) {
                CtFieldReference<?> ref = ((CtFieldAccess<?>) el).getVariable();
                CtField<?> field = ref.getDeclaration();
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

                                                System.out.println("✅ Keeping full static assignment to: " + field.getSimpleName());
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
                                if (method.getSimpleName().equals(execRef.getSimpleName())&& isInProject(method)) {
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
                                    System.out.println("🚨 Missing implementation for abstract method: " + abstractMethod.getSignature());
                                    toKeep.add(abstractMethod); // Force keep to avoid accidental slicing
                                }
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


}
