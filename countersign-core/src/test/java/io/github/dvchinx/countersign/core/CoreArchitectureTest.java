package io.github.dvchinx.countersign.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.dvchinx.countersign.core.archfixture.CoreClassUsingThirdPartyLibrary;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code countersign-core} framework-free (design principle 5 in CLAUDE.md).
 *
 * <p>The core must compile with only the JDK, JSpecify and one JSON library. Checking the bytecode catches what a
 * dependency review misses, such as a transitive Spring type sneaking in through a helper.
 */
class CoreArchitectureTest {

    private static final String CORE = "io.github.dvchinx.countersign.core..";

    // When the JSON library is chosen (ADR required), add its package here and nothing else.
    private static final ArchRule CORE_IS_FRAMEWORK_FREE = classes()
            .that()
            .resideInAPackage(CORE)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "org.jspecify..", CORE)
            .because("countersign-core must not depend on Spring, Spring AI or any other framework");

    @Test
    void coreDependsOnlyOnJdkJSpecifyAndItself() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dvchinx.countersign.core");

        CORE_IS_FRAMEWORK_FREE.check(production);
    }

    @Test
    void rejectsCoreClassThatUsesAThirdPartyLibrary() {
        JavaClasses offending = new ClassFileImporter().importClasses(CoreClassUsingThirdPartyLibrary.class);

        assertThatThrownBy(() -> CORE_IS_FRAMEWORK_FREE.check(offending))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.assertj");
    }
}
