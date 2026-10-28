package com.latestay.negotiation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.latestay.negotiation", importOptions = ImportOption.DoNotIncludeTests.class)
class NegotiationPurityTest {

    @ArchTest
    static final ArchRule onlyDependsOnTheJdk = classes()
            .that().resideInAPackage("com.latestay.negotiation..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("com.latestay.negotiation..", "java..");
}
