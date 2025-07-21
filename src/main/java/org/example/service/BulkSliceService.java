package org.example.service;

import org.example.analyzer.SpoonAnalyzer;
import org.example.compiler.JavaCompilerService;
import org.example.slicer.SlicedModelBuilder;
import spoon.reflect.declaration.*;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

public class BulkSliceService {

    private final List<String> passed = new ArrayList<>();
    private final Map<String, Exception> failed = new LinkedHashMap<>();
    private final List<String> totalmethod = new ArrayList<>();

    public void runFullAnalysis(String srcPath) {
        System.out.println("📁 Starting full analysis on: " + srcPath);

        // Step 1: Load model ONCE to extract all method identifiers
        SpoonAnalyzer baseAnalyzer = new SpoonAnalyzer(srcPath);

        List<Map.Entry<String, String>> methods = new ArrayList<>();
        for (CtType<?> type : baseAnalyzer.getModel().getAllTypes()) {
            String className = type.getQualifiedName();
            for (CtMethod<?> method : type.getMethods()) {
                if (!method.hasModifier(ModifierKind.PUBLIC)) continue;
                if (method.isImplicit() || method.getSimpleName().equals("<init>")) continue;// skip
                methods.add(Map.entry(className, method.getSimpleName()));
            }
        }
        for (Map.Entry<String, String> entry : methods) {
            String className = entry.getKey();
            String methodName = entry.getValue();
            String methodId = className + "#" + methodName;
            totalmethod.add(methodId);

            try {

                System.out.println("start time in ms: " + LocalTime.now());

                SpoonAnalyzer analyzer = new SpoonAnalyzer(srcPath); // new model per method

                Map<String, List<String>> target = Map.of(className, List.of(methodName));
                Set<CtElement> deps = analyzer.findAndCollectMultipleTargets(target);

                System.out.println("after dependencies time in ms: " + LocalTime.now());
                SlicedModelBuilder slicer = new SlicedModelBuilder(analyzer.getModel(), deps);

                try {
                    slicer.slice();
                    System.out.println("after slicing time in ms: " + LocalTime.now());
                } catch (Exception sliceEx) {
                    failed.put(methodId, new RuntimeException("Slicing error", sliceEx));
                    System.err.println("❌ Slicing failed for " + methodId + ": " + sliceEx);
                    sliceEx.printStackTrace(System.err);
                    continue;
                }

                JavaCompilerService compiler = new JavaCompilerService();
                boolean compiled;
                try {
                    compiled = compiler.compileSlicedAnalyzer(analyzer);
                    System.out.println("after compilation time in ms: " + LocalTime.now());
                } catch (Exception compEx) {
                    failed.put(methodId, new RuntimeException("Compilation error", compEx));
                    System.err.println("❌ Compilation crashed for " + methodId + ": " + compEx);
                    compEx.printStackTrace(System.err);
                    continue;
                }

                if (compiled) {
                    passed.add(methodId);
                } else {
                    System.out.println("🔎 Keeping elements:");
                    deps.forEach(e -> System.out.println("  - " + e.getClass().getSimpleName() + ": " + e.toString()));
                    for (CtType<?> type : slicer.model.getAllTypes()) {
                        System.out.println("Kept class: " + type.getQualifiedName());
                        System.out.println("  Kept methods: " + type.getMethods().stream().filter(slicer.toKeep::contains).collect(Collectors.toList()));
                        System.out.println("  Kept fields: " + type.getFields().stream().filter(slicer.toKeep::contains).collect(Collectors.toList()));
                    }
                    //analyzer.printSlicedModel();
                    System.err.println("❌ Compilation crashed for " + methodId + ": ");
                    failed.put(methodId, new RuntimeException("Compilation failed (did not crash)"));
                    System.out.println("=== TOTAL METHOD === " + gettotalMethods().size());
                    System.out.println("=== PASS === " + getPassedMethods().size());
                    System.out.println("=== FAIL === " + getFailedMethodsWithErrors().size());
                    continue;
                }

            } catch (Exception ex) {
                failed.put(methodId, ex);
                System.err.println("❌ Unexpected error for " + methodId + ": " + ex);
                ex.printStackTrace(System.err);
            }
            // Print status after each
            System.out.println("=== TOTAL METHOD === " + gettotalMethods().size());
            System.out.println("=== PASS === " + getPassedMethods().size());
            System.out.println("=== FAIL === " + getFailedMethodsWithErrors().size());
        }

        Set<String> missing = new HashSet<>(totalmethod);
        missing.removeAll(passed);
        missing.removeAll(failed.keySet());

        if (!missing.isEmpty()) {
            System.out.println("🔎 Suspected skipped methods:");
            for (String methodId : missing) {
                System.out.println(" - " + methodId);
            }
        }

        System.out.println("✅ Completed: " + passed.size() + " passed, " + failed.size() + " failed");
    }

    public List<String> getPassedMethods() {
        return passed;
    }

    public List<String> gettotalMethods() {
        return totalmethod;
    }

    public Map<String, Exception> getFailedMethodsWithErrors() {
        return failed;
    }

    public boolean hasFailures() {
        return !failed.isEmpty();
    }
}
