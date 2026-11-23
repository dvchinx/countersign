package io.github.dvchinx.countersign.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.model.Receipt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;

/**
 * Runs the README quickstart exactly as written: the YAML is read from {@code README.md} itself, so the README cannot
 * drift from what works. The tools are plain {@code @Tool} methods passed with {@code .tools(...)}, as in the README.
 */
class ReadmeQuickstartTest {

    private final DemoTools tools = new DemoTools();

    private static String readmeQuickstartYaml() throws IOException {
        String readme = Files.readString(Path.of("..", "README.md"));
        int step = readme.indexOf("**2. Write a few policies**");
        int start = readme.indexOf("```yaml\n", step) + "```yaml\n".length();
        return readme.substring(start, readme.indexOf("```", start));
    }

    private ApplicationContextRunner runner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load(
                                        "README quickstart",
                                        new ByteArrayResource(
                                                readmeQuickstartYaml().getBytes()))
                                .forEach(context.getEnvironment().getPropertySources()::addFirst);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class,
                        ChatClientAutoConfiguration.class,
                        CountersignAutoConfiguration.class))
                .withBean(ChatModel.class, () -> model);
    }

    private static Receipt onlyReceipt(org.springframework.context.ApplicationContext context) {
        return context.getBean(InMemoryAuditSink.class).receipts().getFirst();
    }

    private void ask(org.springframework.context.ApplicationContext context) {
        context.getBean(ChatClient.Builder.class)
                .build()
                .prompt("go")
                .tools(tools)
                .call()
                .content();
    }

    @Test
    void deniesDestructiveSql() {
        ScriptedChatModel model = new ScriptedChatModel("db_query", "{\"query\":\"DROP TABLE accounts\"}");

        runner(model).run(context -> {
            ask(context);

            assertThat(tools.queries).isZero();
            assertThat(model.toolResult()).contains("REFUSED", "DENY", "no-destructive-sql");
            assertThat(onlyReceipt(context).matchedPolicies()).containsExactly("no-destructive-sql");
        });
    }

    @Test
    void sendsLargeTransfersForApproval() {
        ScriptedChatModel model = new ScriptedChatModel("payments_transfer", "{\"to\":\"ACME\",\"amount\":5000}");

        runner(model).run(context -> {
            ask(context);

            assertThat(tools.transfers).isZero();
            assertThat(onlyReceipt(context).decision()).isEqualTo(Decision.Type.REQUIRE_APPROVAL);
        });
    }

    @Test
    void deniesWhatNoPolicyAllows() {
        ScriptedChatModel model = new ScriptedChatModel("payments_transfer", "{\"to\":\"ACME\",\"amount\":50}");

        runner(model).run(context -> {
            ask(context);

            assertThat(tools.transfers).isZero();
            assertThat(model.toolResult()).contains("No policy allows this call");
        });
    }
}
