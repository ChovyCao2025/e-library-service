package com.elibrary;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/** 验证领域层依赖方向与 SQL 集中管理规则，防止架构约束在重构时退化。 */
@AnalyzeClasses(packages = "com.elibrary", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_FRAMEWORKS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.mybatis..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule OUTBOX_DOMAIN_IS_INDEPENDENT = noClasses()
            .that().resideInAPackage("..shared.outbox.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..shared.outbox.application..", "..shared.outbox.infrastructure..");

    @ArchTest
    static final ArchRule WEB_API_IS_INDEPENDENT = noClasses()
            .that().resideInAPackage("..shared.web.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..shared.web.error..", "..shared.web.security..");

    @ArchTest
    static final ArchRule SQL_BELONGS_IN_MAPPER_XML = noMethods()
            .should().beAnnotatedWith(Select.class)
            .orShould().beAnnotatedWith(Insert.class)
            .orShould().beAnnotatedWith(Update.class)
            .orShould().beAnnotatedWith(Delete.class);
}
