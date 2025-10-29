package org.example;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EndpointControllerInspection {

    private final String[] ENDPOINT_ANNOTATIONS = {
        "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "RequestMapping", "RestController",
        "Get", "Post", "Put", "Delete"
    };
    public Launcher launcher;

    public EndpointControllerInspection(){
        launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true); // ignora dependencias
        launcher.getEnvironment().setComplianceLevel(6); // Java 6
        //launcher.getEnvironment().setSourceClasspath("".split(":"));
        launcher.addInputResource("../erp-mes-backend-master/src/main/java/com/herokuapp/erpmesbackend/erpmesbackend");
        launcher.buildModel();
    }

    public void extractControllers(){
        CtModel model = launcher.getModel();
        Set<CtType> controllersFound = new HashSet<>();

        // Buscar clases anotadas con anotaciones de endpoint
        TypeFilter<CtType> classFilter = new TypeFilter<>(CtType.class) {
            @Override
            public boolean matches(CtType element) {
                // Verificar si la clase tiene alguna de las anotaciones de endpoint
                for (String annotation : ENDPOINT_ANNOTATIONS) {
                    if (element.getAnnotations().stream()
                            .anyMatch(a -> a.getAnnotationType().getSimpleName().equals(annotation))) {
                        return true;
                    }
                }
                return false;
            }
        };

        List<CtType> annotatedClasses = model.getElements(classFilter);
        controllersFound.addAll(annotatedClasses);

        // Buscar metodos anotados con anotaciones de endpoint
        TypeFilter<CtMethod> methodFilter = new TypeFilter<>(CtMethod.class) {
            @Override
            public boolean matches(CtMethod element) {
                // Verificar si el metodo tiene alguna de las anotaciones de endpoint
                for (String annotation : ENDPOINT_ANNOTATIONS) {
                    if (element.getAnnotations().stream()
                            .anyMatch(a -> a.getAnnotationType().getSimpleName().equals(annotation))) {
                        return true;
                    }
                }
                return false;
            }
        };

        List<CtMethod> annotatedMethods = model.getElements(methodFilter);

        System.out.println("\n=== Controllers/Clases con anotaciones de endpoint ===");
        for (CtType type : controllersFound) {
            System.out.println("Clase: " + type.getQualifiedName());
            // Mostrar anotaciones de la clase
            type.getAnnotations().forEach(a -> {
                String annotationName = a.getAnnotationType().getSimpleName();
                if (java.util.Arrays.asList(ENDPOINT_ANNOTATIONS).contains(annotationName)) {
                    String annotationValue = getAnnotationValue(a);
                    System.out.println("  -> @" + annotationName + annotationValue);
                }
            });
        }

        System.out.println("\n=== Metodos con anotaciones de endpoint ===");
        for (CtMethod method : annotatedMethods) {
            System.out.println("Metodo: " + method.getDeclaringType().getQualifiedName()
                    + "." + method.getSimpleName() + "()");
            // Mostrar que anotacion tiene
            method.getAnnotations().forEach(a -> {
                String annotationName = a.getAnnotationType().getSimpleName();
                if (java.util.Arrays.asList(ENDPOINT_ANNOTATIONS).contains(annotationName)) {
                    String annotationValue = getAnnotationValue(a);
                    System.out.println("  -> @" + annotationName + annotationValue);
                }
            });
        }
    }

    private String getAnnotationValue(spoon.reflect.declaration.CtAnnotation<?> annotation) {
        try {
            // Intentar obtener el valor por defecto (value)
            Object value = annotation.getValue("value");
            if (value != null) {
                return "(" + value.toString() + ")";
            }

            // Intentar obtener el valor de "path" (algunos frameworks usan path en vez de value)
            value = annotation.getValue("path");
            if (value != null) {
                return "(path=" + value.toString() + ")";
            }

            // Si no tiene valor, retornar vacio
            return "()";
        } catch (Exception e) {
            return "()";
        }
    }

    public void extractEntryPoints(){

    }

}
