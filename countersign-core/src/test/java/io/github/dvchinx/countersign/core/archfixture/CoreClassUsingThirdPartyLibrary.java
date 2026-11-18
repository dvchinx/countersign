package io.github.dvchinx.countersign.core.archfixture;

import org.assertj.core.api.Assertions;

/**
 * Test fixture: a class in the core package that depends on a third-party library. It proves that the
 * framework-free rule in {@code CoreArchitectureTest} actually fails when the rule is broken.
 */
public final class CoreClassUsingThirdPartyLibrary {

    private CoreClassUsingThirdPartyLibrary() {}

    static void useThirdPartyLibrary() {
        Assertions.assertThat(true).isTrue();
    }
}
