package org.example;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtPackage;

import java.util.HashSet;
import java.util.Set;

public class PackageHierarchy {

    public Launcher launcher;

    //Pasar a una super clase y que todos la hereden para no duplicar codigo

    public PackageHierarchy(){
        launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true); // ignora dependencias
        launcher.getEnvironment().setComplianceLevel(6); // Java 6
        //launcher.getEnvironment().setSourceClasspath("".split(":"));
        launcher.addInputResource("../jBilling-master/src/java/com/sapienter/jbilling");
        launcher.buildModel();
    }

    public void getPackageHierarchy(){

        CtModel model = launcher.getModel();
        Set<CtPackage> uniquePackages = new HashSet<>();

        // Quitar los getters y setters que no son de utilidad para el analisis
        // Pero como se pueden quitar sin afectar a otros files que pueden tener "get" o "set"???
        model.getAllTypes().forEach(type -> {
            System.out.println("Class: " + type.getQualifiedName());
            for (CtMethod<?> m : type.getMethods()) {
                System.out.println("  -> " + m.getSimpleName());
            }
            uniquePackages.add(type.getPackage());
            //System.out.println(type.getPackage());
        });

        // We can exclude certain things, like the web app
        System.out.println("\nWe can obtain all the packages and create the 'architecture'");
        //TODO: Check if this particular project needs a wider scope, not only the Java src, the grails-app
        for(CtPackage ctPackage : uniquePackages){
            System.out.println(ctPackage);
        }
    }

}
