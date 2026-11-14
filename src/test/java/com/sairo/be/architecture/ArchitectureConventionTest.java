package com.sairo.be.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.sairo.be.global.security.access.RequiresAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureConventionTest {

  private static final String BASE = "com.sairo.be";

  private static final List<String> DOMAIN_MODULES =
      List.of(
          "auth",
          "office",
          "property",
          "customer",
          "visit",
          "contract",
          "task",
          "home",
          "settlement",
          "billing",
          "migration",
          "support",
          "ops",
          "legal");

  private static final List<String> LAYER_PACKAGES =
      List.of("controller", "service", "entity", "repository", "dto", "mapper");

  private static final JavaClasses APPLICATION_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages(BASE);

  @Test
  void domainPackagesMustNotHaveCycles() {
    slices()
        .matching(BASE + ".domain.(*)..")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void controllersMustNotDependOnPersistenceDetails() {
    classes()
        .that()
        .resideInAPackage("..controller..")
        .should()
        .onlyDependOnClassesThat()
        .resideOutsideOfPackages("..repository..", "..entity..")
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void servicesMustNotDependOnControllers() {
    classes()
        .that()
        .resideInAPackage("..service..")
        .should()
        .onlyDependOnClassesThat()
        .resideOutsideOfPackage("..controller..")
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void entitiesMustNotUseClassLevelSetterOrData() {
    classes()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .notBeAnnotatedWith("lombok.Setter")
        .andShould()
        .notBeAnnotatedWith("lombok.Data")
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void fieldInjectionIsForbidden() {
    fields().should().notBeAnnotatedWith(Autowired.class).check(APPLICATION_CLASSES);
  }

  @Test
  void domainClassesMustLiveInDeclaredModules() {
    String[] modulePackages =
        DOMAIN_MODULES.stream()
            .map(module -> BASE + ".domain." + module + "..")
            .toArray(String[]::new);

    classes()
        .that()
        .resideInAPackage(BASE + ".domain..")
        .should()
        .resideInAnyPackage(modulePackages)
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void domainModulesMustUseOnlyDeclaredLayerPackages() {
    String[] layerPackages =
        DOMAIN_MODULES.stream()
            .flatMap(
                module ->
                    LAYER_PACKAGES.stream()
                        .map(layer -> BASE + ".domain." + module + "." + layer + ".."))
            .toArray(String[]::new);

    classes()
        .that()
        .resideInAPackage(BASE + ".domain..")
        .should()
        .resideInAnyPackage(layerPackages)
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void globalMustNotDependOnDomain() {
    classes()
        .that()
        .resideInAPackage(BASE + ".global..")
        .should()
        .onlyDependOnClassesThat()
        .resideOutsideOfPackage(BASE + ".domain..")
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void restControllersMustResideInControllerPackages() {
    classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideInAPackage("..controller..")
        .allowEmptyShould(true)
        .check(APPLICATION_CLASSES);
  }

  @Test
  void requestHandlersMustDeclareAccessPermission() {
    Stream.of(
            GetMapping.class,
            PostMapping.class,
            PutMapping.class,
            PatchMapping.class,
            DeleteMapping.class,
            RequestMapping.class)
        .forEach(
            mapping ->
                methods()
                    .that()
                    .areAnnotatedWith(mapping)
                    .and()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .beAnnotatedWith(RequiresAccess.class)
                    .orShould()
                    .beDeclaredInClassesThat()
                    .areAnnotatedWith(RequiresAccess.class)
                    .allowEmptyShould(true)
                    .check(APPLICATION_CLASSES));
  }
}
