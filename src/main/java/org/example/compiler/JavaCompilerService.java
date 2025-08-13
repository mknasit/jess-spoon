package org.example.compiler;

import org.example.analyzer.SpoonAnalyzer;
import org.example.service.BulkSliceService;
import spoon.reflect.declaration.CtType;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;

public class JavaCompilerService {

    public boolean compileSlicedAnalyzer(SpoonAnalyzer analyzer) {
        File tempSourceDir;
        try {
            tempSourceDir = Files.createTempDirectory("sliced-compile-src").toFile();
        } catch (IOException e) {
            //System.err.println("❌ Failed to create temp source directory.");
            return false;
        }

        // Step 1: Export sources from SpoonAnalyzer
        exportSlicedSources(analyzer, tempSourceDir);

        // Step 2: Compile them to another temp directory
        File tempClassDir;
        try {
            tempClassDir = Files.createTempDirectory("sliced-classes").toFile();
        } catch (IOException e) {
            //System.err.println("❌ Failed to create temp class output directory.");
            deleteDirectory(tempSourceDir);
            return false;
        }

        boolean success = compileFromSource(tempSourceDir, tempClassDir);

        // Step 3: Clean up
        deleteDirectory(tempSourceDir);
        deleteDirectory(tempClassDir);

        // Step 4: Print result
        if (success) {
          //  System.out.println("✅ Compilation succeeded.");
        } else {
            System.out.println("❌ Compilation failed.");
        }

        return success;
    }


    private void exportSlicedSources(SpoonAnalyzer analyzer, File targetDir) {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        for (CtType<?> type : analyzer.getModel().getAllTypes()) {

            type.getFactory().CompilationUnit().getOrCreate(type).getImports()
                    .removeIf(imp -> {
                        CtReference ref = imp.getReference();
                        return ref instanceof CtTypeReference &&
                                !analyzer.getModel().getAllTypes().contains(((CtTypeReference<?>) ref).getDeclaration());
                    });

            String code;

            try {
                DefaultJavaPrettyPrinter printer = new DefaultJavaPrettyPrinter(analyzer.getLauncher().getEnvironment());
                printer.calculate(type.getPosition().getCompilationUnit(), Collections.singletonList(type));
                code = printer.getResult();
            } catch (Exception e) {
                System.err.println("⚠️ Fallback to toString for type: " + type.getSimpleName());
                code = type.toString(); // fallback if printer fails
            }

            File outputFile = new File(targetDir, type.getSimpleName() + ".java");
            try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(code);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private boolean compileFromSourceold(File sourceDir, File outputDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            //System.err.println("❌ No system Java compiler found. Are you using a JDK?");
            return false;
        }

        List<File> javaFiles = findJavaFiles(sourceDir);
        if (javaFiles.isEmpty()) {
            //System.err.println("❌ No Java files to compile.");
            return false;
        }

        // System.out.println("🛠️ Compiling sliced source files:");
        // javaFiles.forEach(file -> System.out.println(" - " + file.getName()));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromFiles(javaFiles);
            List<String> options = List.of("-d", outputDir.getAbsolutePath());

            JavaCompiler.CompilationTask task = compiler.getTask(new PrintWriter(System.out, true), fileManager, null, options, null, sources);
            return task.call();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }


    private boolean compileFromSource(File sourceDir, File outputDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return false;

        List<File> javaFiles = findJavaFiles(sourceDir);
        if (javaFiles.isEmpty()) return false;

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromFiles(javaFiles);
            List<String> options = List.of("-d", outputDir.getAbsolutePath());

            JavaCompiler.CompilationTask task = compiler.getTask(new PrintWriter(System.out, true), fileManager, diagnostics, options, null, sources);
            boolean result = task.call();

            if (!result) {
                System.err.println("Compilation errors:");
                for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                     System.err.println(diag);
                    BulkSliceService.log("Compilation errors:"+diag);
                }
            }

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("CopilaionFailed because " + e.getMessage());
            return false;
        }
    }


    private List<File> findJavaFiles(File dir) {
        List<File> javaFiles = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return javaFiles;

        for (File file : files) {
            if (file.isDirectory()) {
                javaFiles.addAll(findJavaFiles(file));
            } else if (file.getName().endsWith(".java")) {
                javaFiles.add(file);
            }
        }
        return javaFiles;
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            } else {
                file.delete();
            }
        }
        dir.delete();
    }
}
