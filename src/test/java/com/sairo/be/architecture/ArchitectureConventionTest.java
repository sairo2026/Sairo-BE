package com.sairo.be.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ArchitectureConventionTest {

  private static final JavaClasses APPLICATION_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.sairo.be");

  @Test
  void domainPackagesMustNotHaveCycles() {
    slices()
        .matching("com.sairo.be.domain.(*)..")
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
}
