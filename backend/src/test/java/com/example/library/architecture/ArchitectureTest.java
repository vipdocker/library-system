package com.example.library.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.and;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.example.library.common.config.WebConfig;
import com.example.library.security.CurrentUserArgumentResolver;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

// 改动本文件即改动架构约定，需在变更说明里写明理由。
@AnalyzeClasses(packages = "com.example.library", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.example.library";

    @ArchTest
    static final ArchRule only_known_top_level_packages = classes()
            .should().resideInAnyPackage(
                    ROOT,
                    ROOT + ".domain..",
                    ROOT + ".common..",
                    ROOT + ".security..",
                    ROOT + ".auth..",
                    ROOT + ".catalog..",
                    ROOT + ".reader..",
                    ROOT + ".loan..",
                    ROOT + ".stats..",
                    ROOT + ".reminder..",
                    ROOT + ".dunning..",
                    ROOT + ".seed..")
            .because("新增顶层模块时必须同时在 module_dependencies 中声明它的依赖方向");

    @ArchTest
    static final ArchRule module_dependencies = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy(ROOT + ".domain..")
            .layer("Common").definedBy(ROOT + ".common..")
            .layer("Security").definedBy(ROOT + ".security..")
            .layer("Auth").definedBy(ROOT + ".auth..")
            .layer("Catalog").definedBy(ROOT + ".catalog..")
            .layer("Reader").definedBy(ROOT + ".reader..")
            .layer("Loan").definedBy(ROOT + ".loan..")
            .layer("Stats").definedBy(ROOT + ".stats..")
            .layer("Reminder").definedBy(ROOT + ".reminder..")
            .layer("Dunning").definedBy(ROOT + ".dunning..")
            .layer("Seed").definedBy(ROOT + ".seed..")

            .whereLayer("Domain").mayNotAccessAnyLayer()
            .whereLayer("Common").mayNotAccessAnyLayer()
            .whereLayer("Security").mayOnlyAccessLayers("Common", "Domain")

            .whereLayer("Auth").mayNotBeAccessedByAnyLayer()
            .whereLayer("Catalog").mayNotBeAccessedByAnyLayer()
            .whereLayer("Reader").mayNotBeAccessedByAnyLayer()
            .whereLayer("Stats").mayNotBeAccessedByAnyLayer()
            .whereLayer("Seed").mayNotBeAccessedByAnyLayer()
            .whereLayer("Dunning").mayNotBeAccessedByAnyLayer()
            .whereLayer("Loan").mayOnlyBeAccessedByLayers("Reminder", "Dunning")
            .whereLayer("Reminder").mayOnlyBeAccessedByLayers("Dunning")

            // 存量例外：WebConfig 注册 security 的参数解析器，形成 common <-> security 环。不要新增同类例外。
            .ignoreDependency(WebConfig.class, CurrentUserArgumentResolver.class);

    @ArchTest
    static final ArchRule controllers_do_not_touch_persistence = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat(and(resideInAPackage(ROOT + ".."), simpleNameEndingWith("Repository")))
            .orShould().dependOnClassesThat().belongToAnyOf(JdbcClient.class, JdbcTemplate.class, EntityManager.class)
            .because("Controller 只做 HTTP 适配，数据访问放在 Service / QueryRepository");

    @ArchTest
    static final ArchRule nothing_depends_on_controllers = noClasses()
            .that().haveSimpleNameNotEndingWith("Controller")
            .should().dependOnClassesThat(and(resideInAPackage(ROOT + ".."), simpleNameEndingWith("Controller")));

    @ArchTest
    static final ArchRule dtos_are_plain_data = noClasses()
            .that().resideInAPackage(ROOT + "..dto..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.repository..")
            .orShould().dependOnClassesThat(and(resideInAPackage(ROOT + ".."), simpleNameEndingWith("Service")));

    @ArchTest
    static final ArchRule domain_is_web_agnostic = noClasses()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.security..", "jakarta.servlet..");
}
