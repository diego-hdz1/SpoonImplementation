package org.example;


import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {

        // Find all controllers/endpoints with the example of try-catch
        // Entry points

        // We can also do the same to find the @Entity, @Table, @Id, etc. TODO: Think about old systems that dont use this annotations
        // Maybe we can ask an LLM first to analyze what it uses for the data layer and the use it here

        PackageHierarchy packageHierarchy = new PackageHierarchy();
        packageHierarchy.getPackageHierarchy();

//        DependencyGraphExtractor dependencyGraphExtractor = new DependencyGraphExtractor();

        // Escanear dependencias del proyecto jBilling
//        DependencyScanner scanner = new DependencyScanner();
//        Path projectPath = Paths.get("../jBilling-master").toAbsolutePath().normalize();
//
//        System.out.println("Iniciando análisis de dependencias \n");
//        Map<String, String> dependencies = scanner.scanDependencies(projectPath);
//
//        System.out.println("\nAnálisis completado ");
//        System.out.println("Total de dependencias encontradas: " + dependencies.size());

    }
}