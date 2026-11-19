package io.github.dvchinx.countersign.springai.spel;

import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.policy.Condition;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import java.util.Objects;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * A policy {@link Condition} written in SpEL, such as {@code #args['amount'] > 1000}.
 *
 * <p>Policies often come from configuration files, so an expression must not be able to do anything except read
 * the invocation. Expressions run in a {@link SimpleEvaluationContext} built for read-only data binding, with
 * assignment disabled and no method resolvers. In practice that means:
 *
 * <ul>
 *   <li>no type references ({@code T(java.lang.Runtime)}), constructors ({@code new ...}) or bean references
 *       ({@code @bean});
 *   <li>no method calls, not even on strings; use the {@code matches} operator for text checks;
 *   <li>no assignment to variables or arguments.
 * </ul>
 *
 * <p>Never replace this context with a {@code StandardEvaluationContext}: that would let anyone who can edit a
 * policy run arbitrary code in the application.
 *
 * <p>Expressions see these variables:
 *
 * <ul>
 *   <li>{@code #args}: the arguments as a map; read them with the indexer, {@code #args['amount']}. Numbers are
 *       {@link java.math.BigDecimal}, nested objects are maps and arrays are lists.
 *   <li>{@code #tool}: the tool name.
 *   <li>{@code #principal} and {@code #conversationId}: may be {@code null}.
 * </ul>
 *
 * <p>The expression must evaluate to a {@code boolean}. Any other result, or any evaluation error, makes
 * {@link #matches} throw, and the policy engine then denies the call. Instances are immutable and thread-safe.
 */
public final class SpelCondition implements Condition {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private final String source;
    private final Expression expression;

    private SpelCondition(String source, Expression expression) {
        this.source = source;
        this.expression = expression;
    }

    /**
     * Parses an expression. Parse errors are reported here, at startup, not on the first tool call.
     *
     * @param expression the SpEL expression, for example {@code #args['amount'] > 1000}
     * @return the condition
     * @throws InvalidPolicyException if the expression is blank or does not parse
     */
    public static SpelCondition parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank()) {
            throw new InvalidPolicyException("Condition expression must not be blank");
        }
        try {
            return new SpelCondition(expression, PARSER.parseExpression(expression));
        } catch (ParseException e) {
            throw new InvalidPolicyException("Condition is not valid SpEL: " + e.getSimpleMessage(), e);
        }
    }

    /**
     * Evaluates the expression against an invocation.
     *
     * @param invocation the invocation being evaluated
     * @return the expression's result
     * @throws EvaluationException if the expression fails or uses a feature the sandbox forbids
     * @throws IllegalStateException if the expression does not evaluate to a boolean
     */
    @Override
    public boolean matches(ToolInvocation invocation) {
        // A new context per call: contexts hold variables and are not thread-safe. Building one is cheap.
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withAssignmentDisabled()
                .build();
        context.setVariable("args", invocation.arguments().toJava());
        context.setVariable("tool", invocation.toolName());
        context.setVariable("principal", invocation.principal());
        context.setVariable("conversationId", invocation.conversationId());

        Object result = expression.getValue(context);
        if (result instanceof Boolean matches) {
            return matches;
        }
        throw new IllegalStateException("Condition must evaluate to a boolean, but got "
                + (result == null ? "null" : result.getClass().getSimpleName()));
    }

    /**
     * Returns the expression as written.
     *
     * @return the expression source
     */
    public String expression() {
        return source;
    }

    @Override
    public String toString() {
        return "SpelCondition[" + source + "]";
    }
}
