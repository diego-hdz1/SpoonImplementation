package org.example;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyScanner {
    private static final Pattern JAR_VERSION_PATTERN = Pattern.compile("([^/\\\\]+?)-([0-9][^/\\\\]*)\\.jar");

    public Map<String, String> scanDependencies(Path projectRoot) throws Exception {
        return scanDependencies(projectRoot, true);
    }


    public Map<String, String> scanDependencies(Path projectRoot, boolean generateReport) throws Exception {
        System.out.println("Analizando proyecto en: " + projectRoot);

        Map<String, String> dependencyMap = new LinkedHashMap<>();

        // Leer versiones desde application.properties
        Path appProps = projectRoot.resolve("application.properties");
        if (Files.exists(appProps)) {
            dependencyMap.putAll(parseApplicationProperties(appProps));
        }

        //Leer rutas desde build.xml
        Path buildXml = projectRoot.resolve("build.xml");
        Set<Path> jarDirs = new HashSet<>();
        if (Files.exists(buildXml)) {
            jarDirs.addAll(parseBuildXmlForJarDirs(buildXml, projectRoot));
        }

        //Buscar .jar en las rutas detectadas
        for (Path dir : jarDirs) {
            if (Files.exists(dir)) {
                try (var files = Files.walk(dir)) {
                    files.filter(f -> f.toString().endsWith(".jar")).forEach(jar -> {
                        Matcher m = JAR_VERSION_PATTERN.matcher(jar.getFileName().toString());
                        if (m.find()) {
                            dependencyMap.putIfAbsent(m.group(1), m.group(2));
                        } else {
                            dependencyMap.putIfAbsent(jar.getFileName().toString(), "(sin versión detectable)");
                        }
                    });
                }
            }
        }

        //  Mostrar resumen
        System.out.println("\nDependencias detectadas:");
        dependencyMap.forEach((k, v) -> System.out.printf("  • %-40s %s%n", k, v));

        // Guardar a archivo CSV si se solicita
        if (generateReport) {
            Path output = projectRoot.resolve("dependency-report.csv");
            try (BufferedWriter writer = Files.newBufferedWriter(output)) {
                writer.write("Dependency,Version\n");
                for (var e : dependencyMap.entrySet()) {
                    writer.write(e.getKey() + "," + e.getValue() + "\n");
                }
            }
            System.out.println("\nReporte generado en: " + output);
        }

        return dependencyMap;
    }

    public static void main(String[] args) throws Exception {
        Path projectRoot;
        if (args.length > 0) {
            projectRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        } else {
            projectRoot = Paths.get(".").toAbsolutePath().normalize();
        }

        DependencyScanner scanner = new DependencyScanner();
        scanner.scanDependencies(projectRoot);
    }

    private static Map<String, String> parseApplicationProperties(Path file) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim();
                    if (key.startsWith("plugins.") || key.startsWith("app.")) {
                        result.put(key, val);
                    }
                }
            }
        }
        return result;
    }

    private static Set<Path> parseBuildXmlForJarDirs(Path buildXml, Path baseDir) {
        Set<Path> dirs = new HashSet<>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setIgnoringComments(true);
            dbFactory.setIgnoringElementContentWhitespace(true);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(buildXml.toFile());
            doc.getDocumentElement().normalize();

            NodeList filesets = doc.getElementsByTagName("fileset");
            for (int i = 0; i < filesets.getLength(); i++) {
                Element el = (Element) filesets.item(i);
                String dirAttr = el.getAttribute("dir");
                if (!dirAttr.isEmpty()) {
                    Path path = resolveAntPath(dirAttr, baseDir);
                    dirs.add(path);
                }
            }
        } catch (Exception e) {
            System.err.println("Error leyendo build.xml: " + e.getMessage());
        }
        return dirs;
    }

    private static Path resolveAntPath(String antPath, Path baseDir) {
        // Reemplazar variables ${var} si se encuentran
        String cleaned = antPath.replace("${basedir}", baseDir.toString());
        // No resolver variables externas como ${env.GRAILS_HOME}, solo las locales
        return baseDir.resolve(cleaned).normalize();
    }
}
