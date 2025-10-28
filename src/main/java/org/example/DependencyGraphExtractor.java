package org.example;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class DependencyGraphExtractor {

    private Map<String, Set<String>> classDependencies = new HashMap<>();
    private Map<String, Set<String>> methodDependencies = new HashMap<>();
    private CtModel model;

    public static void main(String[] args) {
//        if (args.length < 1) {
//            System.out.println("Uso: java DependencyGraphExtractor <ruta-al-codigo-fuente>");
//            System.out.println("Ejemplo: java DependencyGraphExtractor ./src");
//            return;
//        }

//        String sourcePath = args[0];
        String sourcePath = "../jBilling-master/src/java/com/sapienter/jbilling";
        DependencyGraphExtractor extractor = new DependencyGraphExtractor();

        extractor.analyze(sourcePath);

        extractor.printResults();
//        extractor.exportToGraphML("dependency-graph.graphml");
//        extractor.exportToJSON("dependency-graph.json");
    }


    public void analyze(String sourcePath) {
        System.out.println("Iniciando análisis de: " + sourcePath);

        // Configurar Spoon Launcher
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourcePath);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(6);
        launcher.getEnvironment().setAutoImports(true);

        // Construir el modelo
        model = launcher.buildModel();

        // Extraer dependencias de clases
        extractClassDependencies();

        // Extraer dependencias de métodos
        extractMethodDependencies();

        extractExternalClassDependencies();

        System.out.println("Análisis completado.");
    }


    private void extractClassDependencies() {
        System.out.println("\nExtrayendo dependencias de clases ");

        for (CtType<?> type : model.getAllTypes()) {
            String typeName = type.getQualifiedName();
            Set<String> dependencies = new HashSet<>();

            // 1. Herencia (superclass)
            CtTypeReference<?> superClass = type.getSuperclass();
            if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
                dependencies.add(superClass.getQualifiedName());
            }

            // 2. Interfaces implementadas
            for (CtTypeReference<?> interfaceRef : type.getSuperInterfaces()) {
                dependencies.add(interfaceRef.getQualifiedName());
            }

            // 3. Campos (atributos de la clase)
            for (CtField<?> field : type.getFields()) {
                CtTypeReference<?> fieldType = field.getType();
                if (fieldType != null) {
                    dependencies.add(fieldType.getQualifiedName());
                }
            }

            // 4. Parámetros y tipos de retorno de métodos
            for (CtMethod<?> method : type.getMethods()) {
                // Tipo de retorno
                CtTypeReference<?> returnType = method.getType();
                if (returnType != null) {
                    dependencies.add(returnType.getQualifiedName());
                }

                // Parámetros
                for (CtParameter<?> param : method.getParameters()) {
                    dependencies.add(param.getType().getQualifiedName());
                }

                // Excepciones
                for (CtTypeReference<?> exception : method.getThrownTypes()) {
                    dependencies.add(exception.getQualifiedName());
                }
            }

            // 5. Referencias de tipos usados en el código
            for (CtTypeReference<?> typeRef : type.getReferencedTypes()) {
                dependencies.add(typeRef.getQualifiedName());
            }

            // Filtrar tipos primitivos y del paquete java.lang (opcional)
            dependencies.removeIf(dep ->
                    dep.startsWith("java.lang.") ||
                            isPrimitiveType(dep)
            );

            if (!dependencies.isEmpty()) {
                classDependencies.put(typeName, dependencies);
            }
        }
    }

    private void extractExternalClassDependencies(){
        Map<String, String> uniqueDependencies = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : classDependencies.entrySet()) {
            System.out.println("\n" + entry.getKey() + " depende de:");
            for (String dep : entry.getValue()) {
                //TODO: REFACTOR THIS SO IT IS DYNAMIC
//                if(dep.startsWith("com")) continue;
//                if(!uniqueDependencies.containsKey(dep)){   //Para obtener lista unica de dependencias
//                    uniqueDependencies.put(dep, ""); //TODO: Cambiar esto a metodo separado
//                }
                System.out.println("  -> " + dep);
            }
        }
//        for(String s : uniqueDependencies.keySet()){
//            System.out.println(s);
//        }
    }

    private void extractMethodDependencies() {
        System.out.println("\nExtrayendo dependencias de métodos ");

        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                String methodSignature = getMethodSignature(method);
                Set<String> invocations = new HashSet<>();

                // Buscar todas las invocaciones de métodos dentro de este método
                List<CtInvocation<?>> methodInvocations = method.getElements(
                        new TypeFilter<>(CtInvocation.class)
                );

                for (CtInvocation<?> invocation : methodInvocations) {
                    CtExecutableReference<?> execRef = invocation.getExecutable();
                    if (execRef != null) {
                        String invokedMethod = execRef.getDeclaringType() != null ?
                                execRef.getDeclaringType().getQualifiedName() + "." +
                                        execRef.getSignature() :
                                execRef.getSignature();
                        invocations.add(invokedMethod);
                    }
                }

                if (!invocations.isEmpty()) {
                    methodDependencies.put(methodSignature, invocations);
                }
            }
        }
    }


    public void printResults() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RESULTADOS DEL ANÁLISIS DE DEPENDENCIAS");
        System.out.println("=".repeat(60));

        System.out.println("\n--- DEPENDENCIAS DE CLASES ---");
        System.out.println("Total de clases analizadas: " + classDependencies.size());
        for (Map.Entry<String, Set<String>> entry : classDependencies.entrySet()) {
            System.out.println("\n" + entry.getKey() + " depende de:");
            for (String dep : entry.getValue()) {
                System.out.println("  -> " + dep);
            }
        }

        System.out.println("\n\n--- DEPENDENCIAS DE MÉTODOS ---");
        System.out.println("Total de métodos analizados: " + methodDependencies.size());
        int count = 0;
        for (Map.Entry<String, Set<String>> entry : methodDependencies.entrySet()) {
            System.out.println("\n" + entry.getKey() + " invoca a:");
            for (String dep : entry.getValue()) {
                System.out.println("  -> " + dep);
            }
            // Limitar la salida para no saturar la consola
            if (++count > 10) {
                System.out.println("\n... (mostrando solo los primeros 10 métodos)");
                break;
            }
        }
    }


    public void exportToGraphML(String filename) {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
            writer.write("  <key id=\"label\" for=\"node\" attr.name=\"label\" attr.type=\"string\"/>\n");
            writer.write("  <key id=\"type\" for=\"edge\" attr.name=\"type\" attr.type=\"string\"/>\n");
            writer.write("  <graph id=\"DependencyGraph\" edgedefault=\"directed\">\n");

            // Nodos de clases
            Set<String> allNodes = new HashSet<>();
            allNodes.addAll(classDependencies.keySet());
            classDependencies.values().forEach(allNodes::addAll);

            for (String node : allNodes) {
                writer.write(String.format("    <node id=\"%s\">\n", escapeXml(node)));
                writer.write(String.format("      <data key=\"label\">%s</data>\n", escapeXml(node)));
                writer.write("    </node>\n");
            }

            // Aristas de clases
            int edgeId = 0;
            for (Map.Entry<String, Set<String>> entry : classDependencies.entrySet()) {
                for (String target : entry.getValue()) {
                    writer.write(String.format("    <edge id=\"e%d\" source=\"%s\" target=\"%s\">\n",
                            edgeId++, escapeXml(entry.getKey()), escapeXml(target)));
                    writer.write("      <data key=\"type\">class_dependency</data>\n");
                    writer.write("    </edge>\n");
                }
            }

            writer.write("  </graph>\n");
            writer.write("</graphml>\n");

            System.out.println("\nGrafo exportado a: " + filename);
        } catch (IOException e) {
            System.err.println("Error al exportar GraphML: " + e.getMessage());
        }
    }

    public void exportToJSON(String filename) {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("{\n");
            writer.write("  \"classDependencies\": {\n");

            int i = 0;
            for (Map.Entry<String, Set<String>> entry : classDependencies.entrySet()) {
                writer.write(String.format("    \"%s\": [", escapeJson(entry.getKey())));
                int j = 0;
                for (String dep : entry.getValue()) {
                    writer.write(String.format("\"%s\"", escapeJson(dep)));
                    if (++j < entry.getValue().size()) writer.write(", ");
                }
                writer.write("]");
                if (++i < classDependencies.size()) writer.write(",");
                writer.write("\n");
            }

            writer.write("  },\n");
            writer.write("  \"methodDependencies\": {\n");

            i = 0;
            for (Map.Entry<String, Set<String>> entry : methodDependencies.entrySet()) {
                writer.write(String.format("    \"%s\": [", escapeJson(entry.getKey())));
                int j = 0;
                for (String dep : entry.getValue()) {
                    writer.write(String.format("\"%s\"", escapeJson(dep)));
                    if (++j < entry.getValue().size()) writer.write(", ");
                }
                writer.write("]");
                if (++i < methodDependencies.size()) writer.write(",");
                writer.write("\n");
            }

            writer.write("  }\n");
            writer.write("}\n");

            System.out.println("Grafo exportado a: " + filename);
        } catch (IOException e) {
            System.err.println("Error al exportar JSON: " + e.getMessage());
        }
    }


    private String getMethodSignature(CtMethod<?> method) {
        String className = method.getDeclaringType().getQualifiedName();
        String methodName = method.getSimpleName();
        StringBuilder params = new StringBuilder();

        for (CtParameter<?> param : method.getParameters()) {
            if (params.length() > 0) params.append(", ");
            params.append(param.getType().getSimpleName());
        }

        return String.format("%s.%s(%s)", className, methodName, params.toString());
    }

    private boolean isPrimitiveType(String typeName) {
        return typeName.equals("int") || typeName.equals("long") ||
                typeName.equals("double") || typeName.equals("float") ||
                typeName.equals("boolean") || typeName.equals("char") ||
                typeName.equals("byte") || typeName.equals("short") ||
                typeName.equals("void");
    }

    private String escapeXml(String str) {
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // Getters para acceso programático

    public Map<String, Set<String>> getClassDependencies() {
        return classDependencies;
    }

    public Map<String, Set<String>> getMethodDependencies() {
        return methodDependencies;
    }
}