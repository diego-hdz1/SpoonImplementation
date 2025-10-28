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

    private Map<String, List<DependencyInfo>> classDependencies = new HashMap<>();
    private Map<String, Set<String>> methodDependencies = new HashMap<>();
    private CtModel model;

    private static class DependencyInfo {
        String dependency;
        String type;  // "inheritance", "interface", "field", "return", "parameter", "exception", "reference"
        String method;  // nombre del método donde se usa (si aplica)
        String visibility;  // "public", "private", "protected", "package"

        public DependencyInfo(String dependency, String type, String method, String visibility) {
            this.dependency = dependency;
            this.type = type;
            this.method = method;
            this.visibility = visibility;
        }

        public DependencyInfo(String dependency, String type) {
            this(dependency, type, null, null);
        }
    }

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

        //extractor.printResults();
        //extractor.exportToGraphML("dependency-graph.graphml");
        extractor.exportToJSON("dependency-graph.json");
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
        //extractMethodDependencies();

        extractExternalClassDependencies();

        System.out.println("Análisis completado.");
    }


    private void extractClassDependencies() {
        System.out.println("\nExtrayendo dependencias de clases ");

        for (CtType<?> type : model.getAllTypes()) {
            String typeName = type.getQualifiedName();
            List<DependencyInfo> dependencies = new ArrayList<>();

            // 1. Herencia (superclass)
            CtTypeReference<?> superClass = type.getSuperclass();
            if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
                String depName = superClass.getQualifiedName();
                if (shouldIncludeDependency(depName)) {
                    dependencies.add(new DependencyInfo(depName, "inheritance"));
                }
            }

            // 2. Interfaces implementadas
            for (CtTypeReference<?> interfaceRef : type.getSuperInterfaces()) {
                String depName = interfaceRef.getQualifiedName();
                if (shouldIncludeDependency(depName)) {
                    dependencies.add(new DependencyInfo(depName, "interface"));
                }
            }

            // 3. Campos (atributos de la clase)
            for (CtField<?> field : type.getFields()) {
                CtTypeReference<?> fieldType = field.getType();
                if (fieldType != null) {
                    String depName = fieldType.getQualifiedName();
                    if (shouldIncludeDependency(depName)) {
                        String visibility = getVisibility(field.getModifiers());
                        dependencies.add(new DependencyInfo(depName, "field", field.getSimpleName(), visibility));
                    }
                }
            }

            // 4. Parámetros y tipos de retorno de métodos
            for (CtMethod<?> method : type.getMethods()) {
                String methodName = method.getSimpleName();
                String methodVisibility = getVisibility(method.getModifiers());

                // Tipo de retorno
                CtTypeReference<?> returnType = method.getType();
                if (returnType != null) {
                    String depName = returnType.getQualifiedName();
                    if (shouldIncludeDependency(depName)) {
                        dependencies.add(new DependencyInfo(depName, "return", methodName, methodVisibility));
                    }
                }

                // Parámetros
                for (CtParameter<?> param : method.getParameters()) {
                    String depName = param.getType().getQualifiedName();
                    if (shouldIncludeDependency(depName)) {
                        dependencies.add(new DependencyInfo(depName, "parameter", methodName, methodVisibility));
                    }
                }

                // Excepciones
                for (CtTypeReference<?> exception : method.getThrownTypes()) {
                    String depName = exception.getQualifiedName();
                    if (shouldIncludeDependency(depName)) {
                        dependencies.add(new DependencyInfo(depName, "exception", methodName, methodVisibility));
                    }
                }
            }

            if (!dependencies.isEmpty()) {
                classDependencies.put(typeName, dependencies);
            }
        }
    }

    private boolean shouldIncludeDependency(String depName) {
        return !depName.startsWith("java.lang.") &&
               !depName.startsWith("<nulltype>") &&
               !isPrimitiveType(depName);
    }

    private String getVisibility(Set<ModifierKind> modifiers) {
        if (modifiers.contains(ModifierKind.PUBLIC)) return "public";
        if (modifiers.contains(ModifierKind.PRIVATE)) return "private";
        if (modifiers.contains(ModifierKind.PROTECTED)) return "protected";
        return "package";
    }

    private void extractExternalClassDependencies(){
        Set<String> uniqueDependencies = new HashSet<>();

        for (Map.Entry<String, List<DependencyInfo>> entry : classDependencies.entrySet()) {
            System.out.println("\n" + entry.getKey() + " depende de:");
            for (DependencyInfo depInfo : entry.getValue()) {
                //TODO: REFACTOR THIS SO IT IS DYNAMIC
                if(depInfo.dependency.startsWith("com")) continue;
                //Para obtener lista unica de dependencias
                uniqueDependencies.add(depInfo.dependency);
                System.out.println("  -> " + depInfo.dependency + " [" + depInfo.type + "]");
            }
        }
        System.out.println("\n\n--- DEPENDENCIAS EXTERNAS ÚNICAS ---");
        for(String s : uniqueDependencies){
            System.out.println(s);
        }

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
        for (Map.Entry<String, List<DependencyInfo>> entry : classDependencies.entrySet()) {
            System.out.println("\n" + entry.getKey() + " depende de:");
            for (DependencyInfo depInfo : entry.getValue()) {
                String methodInfo = depInfo.method != null ? " [método: " + depInfo.method + "]" : "";
                String visibilityInfo = depInfo.visibility != null ? " [" + depInfo.visibility + "]" : "";
                System.out.println("  -> " + depInfo.dependency + " [" + depInfo.type + "]" + methodInfo + visibilityInfo);
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
            writer.write("  <key id=\"method\" for=\"edge\" attr.name=\"method\" attr.type=\"string\"/>\n");
            writer.write("  <key id=\"visibility\" for=\"edge\" attr.name=\"visibility\" attr.type=\"string\"/>\n");
            writer.write("  <graph id=\"DependencyGraph\" edgedefault=\"directed\">\n");

            // Nodos de clases
            Set<String> allNodes = new HashSet<>();
            allNodes.addAll(classDependencies.keySet());
            for (List<DependencyInfo> deps : classDependencies.values()) {
                for (DependencyInfo dep : deps) {
                    allNodes.add(dep.dependency);
                }
            }

            for (String node : allNodes) {
                writer.write(String.format("    <node id=\"%s\">\n", escapeXml(node)));
                writer.write(String.format("      <data key=\"label\">%s</data>\n", escapeXml(node)));
                writer.write("    </node>\n");
            }

            // Aristas de clases
            int edgeId = 0;
            for (Map.Entry<String, List<DependencyInfo>> entry : classDependencies.entrySet()) {
                for (DependencyInfo depInfo : entry.getValue()) {
                    writer.write(String.format("    <edge id=\"e%d\" source=\"%s\" target=\"%s\">\n",
                            edgeId++, escapeXml(entry.getKey()), escapeXml(depInfo.dependency)));
                    writer.write(String.format("      <data key=\"type\">%s</data>\n", escapeXml(depInfo.type)));
                    if (depInfo.method != null) {
                        writer.write(String.format("      <data key=\"method\">%s</data>\n", escapeXml(depInfo.method)));
                    }
                    if (depInfo.visibility != null) {
                        writer.write(String.format("      <data key=\"visibility\">%s</data>\n", escapeXml(depInfo.visibility)));
                    }
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

            int i = 0;
            for (Map.Entry<String, List<DependencyInfo>> entry : classDependencies.entrySet()) {
                writer.write(String.format("  \"%s\": [\n", escapeJson(entry.getKey())));

                List<DependencyInfo> deps = entry.getValue();
                for (int j = 0; j < deps.size(); j++) {
                    DependencyInfo dep = deps.get(j);
                    writer.write("    {\n");
                    writer.write(String.format("      \"dependency\": \"%s\",\n", escapeJson(dep.dependency)));
                    writer.write(String.format("      \"type\": \"%s\"", escapeJson(dep.type)));

                    // Agregar method y visibility solo si no son null
                    if (dep.method != null) {
                        writer.write(",\n");
                        writer.write(String.format("      \"method\": \"%s\"", escapeJson(dep.method)));
                    }
                    if (dep.visibility != null) {
                        writer.write(",\n");
                        writer.write(String.format("      \"visibility\": \"%s\"", escapeJson(dep.visibility)));
                    }

                    writer.write("\n    }");
                    if (j < deps.size() - 1) {
                        writer.write(",");
                    }
                    writer.write("\n");
                }

                writer.write("  ]");
                if (++i < classDependencies.size()) {
                    writer.write(",");
                }
                writer.write("\n");
            }

            writer.write("}\n");

            System.out.println("Dependencias exportadas a: " + filename);
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

    public Map<String, List<DependencyInfo>> getClassDependencies() {
        return classDependencies;
    }

    public Map<String, Set<String>> getMethodDependencies() {
        return methodDependencies;
    }
}