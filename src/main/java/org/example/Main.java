package org.example;

import org.example.analyzer.SpoonAnalyzer;
import org.example.slicer.SlicedModelBuilder;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtElement;


import java.util.Set;

public class Main {
    public static void main(String[] args) {
        // Set path to a test Java file (can be a folder too)
        String path = "src/test/java";
        SpoonAnalyzer analyzer = new SpoonAnalyzer(path);

        // Example: Find method "visit" in class "com.example.A"
        CtMethod<?> target = analyzer.findTargetMethod("com.example.A", "say");
        if (target == null) {
            System.out.println("Method not found.");
            return;
        }

        System.out.println("Found method: " + target.getSignature());

        Set<CtElement> deps = analyzer.collectResolvedDependencies(target);
        deps.add(target);
        System.out.println("Marked for keep: " + target.getSignature());

        for (CtElement e : deps) {
            System.out.println("[" + e.getClass().getSimpleName() + "] " + e.toString());
        }

        SlicedModelBuilder slicer = new SlicedModelBuilder(analyzer.getModel(), deps);
        slicer.slice();

    }
}
