package io.github.dvchinx.countersign.springai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that every call to this {@code @Tool} method needs a human's approval.
 *
 * <pre>{@code
 * @Tool(name = "payments_transfer", description = "Transfer money between accounts")
 * @RequiresApproval(reason = "Moves real money")
 * public TransferResult transfer(String from, String to, BigDecimal amount) { ... }
 * }</pre>
 *
 * <p>The rule lives with the code it protects, so it cannot be dropped by a configuration change. It becomes a
 * mandatory policy (see {@code PolicyEngine}):
 *
 * <ul>
 *   <li>a configured {@code allow} for the tool cannot skip the approval;
 *   <li>a configured {@code deny} still wins;
 *   <li>with no configured policy for the tool, the tool can be used with approval, even under the default
 *       {@code deny}.
 * </ul>
 *
 * <p>The Countersign starter finds these annotations at startup by scanning the application's packages, so it works
 * for tool objects that are not Spring beans, such as {@code chatClient.prompt().tools(new BankTools())}. Placing it
 * on a method without {@code @Tool} stops the application at startup, because it would have no effect.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresApproval {

    /**
     * Why approval is needed, shown to the approver. Defaults to a generic text naming the tool.
     *
     * @return the reason
     */
    String reason() default "";
}
