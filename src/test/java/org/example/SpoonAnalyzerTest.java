package org.example;

import org.example.analyzer.SpoonAnalyzer;
import org.example.compiler.JavaCompilerService;
import org.example.service.BulkSliceService;
import org.example.slicer.SlicedModelBuilder;
import org.junit.jupiter.api.Test;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SpoonAnalyzerTest {

    @Test
    public void testSingleTargetMethodVisit() {
        SpoonAnalyzer analyzer = new SpoonAnalyzer("src/test/java/");

        Map<String, List<String>> targets = Map.of(
                "Worker", List.of("doWork")
        );

        Set<CtElement> deps = analyzer.findAndCollectMultipleTargets(targets);
        SlicedModelBuilder slicer = new SlicedModelBuilder(analyzer.getModel(), deps);
        slicer.slice();
        analyzer.printSlicedModel();
        JavaCompilerService compiler = new JavaCompilerService();
        compiler.compileSlicedAnalyzer(analyzer);

    }

    @Test
    public void testMultipleTargetMethodsVisitAndSay() {
        SpoonAnalyzer analyzer = new SpoonAnalyzer("src/test/java");

        Map<String, List<String>> targets = Map.of(
                "A", List.of("unusedMethod"),
                "B", List.of("say")
        );

        Set<CtElement> deps = analyzer.findAndCollectMultipleTargets(targets);
        SlicedModelBuilder slicer = new SlicedModelBuilder(analyzer.getModel(), deps);
        slicer.slice();
        analyzer.printSlicedModel();
        JavaCompilerService compiler = new JavaCompilerService();
        compiler.compileSlicedAnalyzer(analyzer);
        assertFalse(deps.isEmpty(), "Dependencies should not be empty");

        boolean foundVisit = deps.stream().anyMatch(e -> e.toString().contains("unusedMethod"));
        boolean foundSay = deps.stream().anyMatch(e -> e.toString().contains("say"));

        assertTrue(foundVisit, "Expected 'unusedMethod' method in dependencies");
        assertTrue(foundSay, "Expected 'say' method in dependencies");
    }


    @Test
    public void testCommonsLangSrcCompilation() {
        BulkSliceService bulk = new BulkSliceService();
       // bulk.runFullAnalysis("/Users/mitul/Documents/study/Thesis/partial compilation/slicing_testing/commons-lang/src/main/java");
       //bulk.runFullAnalysis("/Users/mitul/Documents/study/Thesis/partial compilation/slicing_testing/SimpleRtmp/src/com/github/faucamp/simplertmp");
         bulk.runFullAnalysis("/Users/mitul/Documents/study/Thesis/partial compilation/slicing_testing/commons-io/src/main/java");
        System.out.println("=== PASS === "+ bulk.getPassedMethods().size());
        //bulk.getPassedMethods().forEach(System.out::println);

        System.out.println("\n=== FAIL === "+ bulk.getFailedMethodsWithErrors().size());
        /*bulk.getFailedMethodsWithErrors().forEach((id, ex) -> {
            System.out.println(" - " + id);
            ex.printStackTrace(System.out);
        });*/

        assertFalse(bulk.hasFailures(), "Some methods failed slicing/compilation");
    }

    @Test
    public void testCountCommonsLangMethods() {
        SpoonAnalyzer analyzer = new SpoonAnalyzer("/Users/mitul/Documents/study/Thesis/partial compilation/slicing_testing/commons-lang/src/main/java");

        long methodCount = analyzer.getModel().getAllTypes().stream()
                .flatMap(t -> t.getMethods().stream())
                .count();

        System.out.println("📦 Total methods found: " + methodCount);
    }


}
