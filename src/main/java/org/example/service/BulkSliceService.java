    // BulkSliceService.java  (replace file)

    package org.example.service;

    import org.example.analyzer.SpoonAnalyzer;
    import org.example.compiler.JavaCompilerService;
    import org.example.slicer.KeepSet;
    import org.example.slicer.SlicedModelBuilder;
    import spoon.reflect.declaration.*;

    import java.io.FileWriter;
    import java.io.IOException;
    import java.io.PrintWriter;
    import java.time.LocalTime;
    import java.util.*;
    import java.util.stream.Collectors;

    public class BulkSliceService {

        private final List<String> passed = new ArrayList<>();
        private final Map<String, Exception> failed = new LinkedHashMap<>();
        private final List<String> totalmethod = new ArrayList<>();
        private static final String LOG_FILE_PATH = "/Users/mitul/Documents/study/Thesis/partial compilation/debug/slicing_failures_log.txt";

        public void runFullAnalysis(String srcPath) {
            System.out.println("📁 Starting full analysis on: " + srcPath);

            // Build a base model ONCE to enumerate targets
            SpoonAnalyzer baseAnalyzer = new SpoonAnalyzer(srcPath);

            List<Map.Entry<String, String>> methods = new ArrayList<>();
            for (CtType<?> type : baseAnalyzer.getModel().getAllTypes()) {
                String className = type.getQualifiedName();
                for (CtMethod<?> method : type.getMethods()) {
                    if (!method.hasModifier(ModifierKind.PUBLIC)) continue;
                    if (method.isImplicit() || method.getSimpleName().equals("<init>")) continue;
                    methods.add(Map.entry(className, method.getSimpleName()));
                }
            }

            for (Map.Entry<String, String> entry : methods) {
                String className = entry.getKey();
                String methodName = entry.getValue();
                String methodId = className + "#" + methodName;

                try {
                    SpoonAnalyzer analyzer = new SpoonAnalyzer(srcPath); // fresh model per target

                    Map<String, List<String>> target = Map.of(className, List.of(methodName));
                    // keep your old API name
                    Set<CtElement> deps = analyzer.findAndCollectMultipleTargets(target);

                    SlicedModelBuilder slicer = new SlicedModelBuilder(analyzer.getModel(), deps);
                    totalmethod.add(methodId);

                    try {
                        slicer.slice();
                    } catch (Exception sliceEx) {
                        failed.put(methodId, new RuntimeException("Slicing error", sliceEx));
                        sliceEx.printStackTrace(System.err);
                        continue;
                    }

                    JavaCompilerService compiler = new JavaCompilerService();
                    boolean compiled;
                    try {
                        compiled = compiler.compileSlicedAnalyzer(analyzer);
                    } catch (Exception compEx) {
                        failed.put(methodId, new RuntimeException("Compilation error", compEx));
                        compEx.printStackTrace(System.err);
                        continue;
                    }

                    if (compiled) {
                        passed.add(methodId);
                    } else {
                        System.err.println("❌ Compilation crashed for " + methodId + ": ");
                        log(methodId + "❌ Compilation crashed for " + methodId);
                       // analyzer.printSlicedModel();
                        // Seed printed
                        System.out.println("🔎 Keeping elements (seed from analyzer):");
                        log("🔎 Keeping elements (seed from analyzer):");
                        deps.forEach(e -> {
                            String line = " - " + e.getClass().getSimpleName() + ": " + e;
                            System.out.println(line);
                            log(line);
                        });

                        // Kept after slicing, with retention labels
                        KeepSet keep = slicer.keep;
                        for (CtType<?> type : slicer.model.getAllTypes()) {
                            System.out.println("Kept class: " + type.getQualifiedName());
                            log("Kept class: " + type.getQualifiedName());

                            String keptMethods = type.getMethods().stream()
                                    .filter(keep::contains)
                                    .map(m -> m.toString() + " [" + keep.retentionOf(m) + "]")
                                    .collect(Collectors.toList()).toString();
                            System.out.println("  Kept methods: " + keptMethods);
                            log("  Kept methods: " + keptMethods);

                            String keptFields = type.getFields().stream()
                                    .filter(keep::contains)
                                    .map(f -> f.toString() + " [" + keep.retentionOf(f) + "]")
                                    .collect(Collectors.toList()).toString();
                            System.out.println("  Kept fields: " + keptFields);
                            log("  Kept fields: " + keptFields);
                        }

                        failed.put(methodId, new RuntimeException("Compilation failed (did not crash)"));
                        printProgress();
                        continue;
                    }

                } catch (Exception ex) {
                    failed.put(methodId, ex);
                    System.err.println("❌ Unexpected error for " + methodId + ": " + ex);
                    ex.printStackTrace(System.err);
                }

                printProgress();
            }
            Map<String, Long> byShortId = totalmethod.stream()
                    .map(id -> id.contains("(") ? id.substring(0, id.indexOf('(') > 0 ? id.lastIndexOf('#') : id.length()) : id) // crude: class#name
                    .collect(Collectors.groupingBy(s -> s, LinkedHashMap::new, Collectors.counting()));

            long duplicates = byShortId.values().stream().filter(c -> c > 1).count();
            System.out.println("Short-ID buckets with duplicates: " + duplicates);


            Set<String> missing = new HashSet<>(totalmethod);
            missing.removeAll(passed);
            missing.removeAll(failed.keySet());
            if (!missing.isEmpty()) {
                System.out.println("🔎 Suspected skipped methods:");
                for (String methodId : missing) System.out.println(" - " + methodId);
            }

            System.out.println("✅ Completed: " + passed.size() + " passed, " + failed.size() + " failed");
        }

        private void printProgress() {
            System.out.println("=== TOTAL METHOD === " + gettotalMethods().size());
            System.out.println("=== PASS === " + getPassedMethods().size());
            System.out.println("=== FAIL === " + getFailedMethodsWithErrors().size());
            log("=== TOTAL METHOD === " + gettotalMethods().size());
            log("=== PASS === " + getPassedMethods().size());
            log("=== FAIL === " + getFailedMethodsWithErrors().size());
        }

        public List<String> getPassedMethods() { return passed; }
        public List<String> gettotalMethods() { return totalmethod; }
        public Map<String, Exception> getFailedMethodsWithErrors() { return failed; }
        public boolean hasFailures() { return !failed.isEmpty(); }

        public static void log(String message) {
            try (FileWriter fw = new FileWriter(LOG_FILE_PATH, true);
                 PrintWriter out = new PrintWriter(fw)) {
                out.println(message);
            } catch (IOException e) {
                System.err.println("Error writing log: " + e.getMessage());
            }
        }
    }
