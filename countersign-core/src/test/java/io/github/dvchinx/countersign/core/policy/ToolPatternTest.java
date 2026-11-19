package io.github.dvchinx.countersign.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ToolPatternTest {

    @ParameterizedTest
    @CsvSource({
        "payments.transfer, payments.transfer, true",
        "payments.transfer, payments.transferAll, false",
        "payments.transfer, Payments.Transfer, false",
        "db.*,              db.query,          true",
        "db.*,              db.admin.drop,     true",
        "db.*,              db.,               true",
        "db.*,              dbx.query,         false",
        "*,                 anything,          true",
        "*.delete,          users.delete,      true",
        "*.delete,          users.deleteAll,   false",
        "db?query,          db?query,          true",
        "db?query,          dbxquery,          false",
        "a.b,               axb,               false"
    })
    void matchesWholeToolNamesWithStarAsTheOnlyWildcard(String glob, String toolName, boolean expected) {
        assertThat(ToolPattern.of(glob).matches(toolName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "db .*"})
    void rejectsBlankOrSpacedPatterns(String glob) {
        assertThatThrownBy(() -> ToolPattern.of(glob)).isInstanceOf(InvalidPolicyException.class);
    }
}
