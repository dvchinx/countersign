package io.github.dvchinx.countersign.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.guard.Verdict;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.JsonString;
import io.github.dvchinx.countersign.core.model.Redaction;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.redaction.PatternRedactor;
import io.github.dvchinx.countersign.core.redaction.RedactionRule;
import io.github.dvchinx.countersign.core.spi.Redactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;

/** Redaction rules from {@code countersign.redactors} and {@code countersign.redactions} (ADR 0015). */
@ExtendWith(OutputCaptureExtension.class)
class RedactionAutoConfigurationTest {

    /** A CRM tool whose result carries data the model should not see. */
    static class CrmTools {

        int lookups;

        record Customer(String name, String email, String phone, String card) {}

        @Tool(name = "crm_find_customer", description = "Find a customer by id")
        Customer findCustomer(String id) {
            lookups++;
            return new Customer("Ana Pérez", "ana@example.com", "+57 300 000 0000", "4111 1111 1111 1111");
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(CountersignAutoConfiguration.class));

    /** The redaction example from README.md, read from the file so the two cannot drift apart. */
    private static String readmeRedactionYaml() throws IOException {
        String readme = Files.readString(Path.of("..", "README.md"));
        int section = readme.indexOf("**Redacting results.**");
        int start = readme.indexOf("```yaml\n", section) + "```yaml\n".length();
        return readme.substring(start, readme.indexOf("```", start));
    }

    private static ApplicationContextRunner withReadmeExample(ApplicationContextRunner runner) {
        return runner.withInitializer(context -> {
            try {
                new YamlPropertySourceLoader()
                        .load(
                                "README redaction",
                                new ByteArrayResource(readmeRedactionYaml().getBytes()))
                        .forEach(context.getEnvironment().getPropertySources()::addFirst);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static InvalidPolicyException policyError(Throwable startupFailure) {
        for (Throwable t = startupFailure; t != null; t = t.getCause()) {
            if (t instanceof InvalidPolicyException policyException) {
                return policyException;
            }
        }
        throw new AssertionError("No InvalidPolicyException in " + startupFailure);
    }

    private static ToolInvocation crmCall() {
        return ToolInvocation.of(
                "crm_find_customer", JsonObject.builder().put("id", "c-7").build());
    }

    @Test
    void theReadmeExampleKeepsCardsAndContactsFromTheModel(CapturedOutput output) {
        CrmTools crm = new CrmTools();
        ScriptedChatModel model = new ScriptedChatModel("crm_find_customer", "{\"id\":\"c-7\"}");

        withReadmeExample(new ApplicationContextRunner())
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class,
                        ChatClientAutoConfiguration.class,
                        CountersignAutoConfiguration.class))
                .withBean(ChatModel.class, () -> model)
                .run(context -> {
                    context.getBean(ChatClient.Builder.class)
                            .build()
                            .prompt("find c-7")
                            .tools(crm)
                            .call()
                            .content();

                    assertThat(crm.lookups).isEqualTo(1);
                    assertThat(model.toolResult())
                            .isEqualTo("{\"name\":\"Ana Pérez\",\"email\":\"[REDACTED]\",\"phone\":\"[REDACTED]\","
                                    + "\"card\":\"[CARD]\"}");
                    assertThat(context.getBean(InMemoryAuditSink.class)
                                    .receipts()
                                    .getFirst()
                                    .redactions())
                            .containsExactly("no-cards-to-the-model");
                    assertThat(output).contains("1 redaction rule");
                });
    }

    @Test
    void usesRedactorBeansByNameAndAddsRedactionRuleBeans() {
        runner.withPropertyValues(
                        "countersign.policies[0].id=crm-reads",
                        "countersign.policies[0].tools=crm_*",
                        "countersign.policies[0].decision=allow",
                        "countersign.redactions[0].id=shout",
                        "countersign.redactions[0].tools=crm_*",
                        "countersign.redactions[0].target=result",
                        "countersign.redactions[0].redactors=upperCase")
                .withBean("upperCase", Redactor.class, () -> Redactor.strings(text -> text.toUpperCase(Locale.ROOT)))
                .withBean(
                        RedactionRule.class,
                        () -> RedactionRule.builder("from-code")
                                .tools("crm_*")
                                .target(Redaction.Target.RESULT)
                                .redactors(PatternRedactor.of("ANA", "[NAME]"))
                                .build())
                .run(context -> {
                    ToolCallGuard guard = context.getBean(ToolCallGuard.class);
                    assertThat(guard.redactionRules())
                            .extracting(RedactionRule::id)
                            .containsExactly("shout", "from-code");

                    Verdict.Proceed proceed = (Verdict.Proceed) guard.check(crmCall());

                    assertThat(proceed.resultRedaction().apply(JsonString.of("ana pérez")))
                            .isEqualTo(JsonString.of("[NAME] PÉREZ"));
                });
    }

    @Test
    void refusesToStartWithAnUnknownRedactor() {
        runner.withPropertyValues(
                        "countersign.redactions[0].id=no-cards",
                        "countersign.redactions[0].tools=crm_*",
                        "countersign.redactions[0].target=result",
                        "countersign.redactions[0].redactors=card-numbr")
                .run(context -> assertThat(policyError(context.getStartupFailure()))
                        .hasMessageContaining("countersign.redactions[0]")
                        .hasMessageContaining("unknown redactor 'card-numbr'"));
    }

    @Test
    void refusesToStartWithAnInvalidPattern() {
        runner.withPropertyValues("countersign.redactors.card-number.pattern=(\\d{16}")
                .run(context -> assertThat(policyError(context.getStartupFailure()))
                        .hasMessageContaining("countersign.redactors.card-number")
                        .hasMessageContaining("not a valid regular expression"));
    }

    @Test
    void refusesToStartWithARedactorThatIsNeitherOrBoth() {
        runner.withPropertyValues(
                        "countersign.redactors.both.pattern=\\d{16}", "countersign.redactors.both.fields=email")
                .run(context -> assertThat(policyError(context.getStartupFailure()))
                        .hasMessageContaining("countersign.redactors.both")
                        .hasMessageContaining("either 'pattern' or 'fields'"));
    }

    @Test
    void refusesToStartWithArgumentRedactionRatherThanIgnoringIt() {
        runner.withPropertyValues(
                        "countersign.redactors.contact.fields=email",
                        "countersign.redactions[0].id=no-emails-to-search",
                        "countersign.redactions[0].tools=web_search",
                        "countersign.redactions[0].target=args",
                        "countersign.redactions[0].redactors=contact")
                .run(context -> assertThat(policyError(context.getStartupFailure()))
                        .hasMessageContaining("countersign.redactions[0]")
                        .hasMessageContaining("redacting arguments is not supported yet"));
    }

    @Test
    void refusesToStartWithARuleWithoutTarget() {
        runner.withPropertyValues(
                        "countersign.redactors.contact.fields=email",
                        "countersign.redactions[0].id=no-contact",
                        "countersign.redactions[0].tools=crm_*",
                        "countersign.redactions[0].redactors=contact")
                .run(context ->
                        assertThat(policyError(context.getStartupFailure())).hasMessageContaining("needs a 'target'"));
    }

    @Test
    void refusesToStartWhenARuleReusesAPolicyId() {
        runner.withPropertyValues(
                        "countersign.policies[0].id=crm",
                        "countersign.policies[0].tools=crm_*",
                        "countersign.policies[0].decision=allow",
                        "countersign.redactors.contact.fields=email",
                        "countersign.redactions[0].id=crm",
                        "countersign.redactions[0].tools=crm_*",
                        "countersign.redactions[0].target=result",
                        "countersign.redactions[0].redactors=contact")
                .run(context -> assertThat(policyError(context.getStartupFailure()))
                        .hasMessageContaining("already a policy id"));
    }

    @Test
    void explainsARedactionErrorWithoutAStackTrace() {
        FailureAnalysis analysis = new CountersignFailureAnalyzer()
                .analyze(new IllegalStateException(
                        "wrapper",
                        new InvalidPolicyException("countersign.redactions[0]: unknown redactor 'card-numbr'")));

        assertThat(analysis.getDescription()).contains("redaction rules", "countersign.redactions[0]");
        assertThat(analysis.getAction()).contains("countersign.redactors", "target: result");
    }
}
