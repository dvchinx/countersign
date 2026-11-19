package io.github.dvchinx.countersign.springai.annotation;

import io.github.dvchinx.countersign.core.model.Decision;
import io.github.dvchinx.countersign.core.policy.Condition;
import io.github.dvchinx.countersign.core.policy.InvalidPolicyException;
import io.github.dvchinx.countersign.core.policy.Policy;
import io.github.dvchinx.countersign.core.policy.ToolPattern;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Turns {@link RequiresApproval} annotations into mandatory policies, one per tool.
 *
 * <p>The policy id is {@value #ID_PREFIX} followed by the tool name, so receipts show that the approval came from the
 * code. The tool name is the one Spring AI uses: {@code @Tool(name = ...)}, or the method name.
 */
public final class RequiresApprovalPolicies {

    /** Prefix of the ids of policies created from {@link RequiresApproval}. */
    public static final String ID_PREFIX = "requires-approval.";

    private static final Logger LOG = LoggerFactory.getLogger(RequiresApprovalPolicies.class);

    private RequiresApprovalPolicies() {}

    /**
     * Finds annotated tool methods in packages, reading class files without loading the classes.
     *
     * <p>This is how the starter finds tools that are never Spring beans.
     *
     * @param basePackages the packages to scan, sub-packages included
     * @param classLoader the class loader whose class path is scanned
     * @return one mandatory policy per annotated tool
     * @throws InvalidPolicyException if {@code @RequiresApproval} is on a method without {@code @Tool}
     */
    public static List<Policy> scan(Collection<String> basePackages, ClassLoader classLoader) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        for (String basePackage : basePackages) {
            String pattern = "classpath*:" + ClassUtils.convertClassNameToResourcePath(basePackage) + "/**/*.class";
            try {
                for (Resource resource : resolver.getResources(pattern)) {
                    MetadataReader reader = readers.getMetadataReader(resource);
                    for (MethodMetadata method :
                            reader.getAnnotationMetadata().getAnnotatedMethods(RequiresApproval.class.getName())) {
                        String where = method.getDeclaringClassName() + "." + method.getMethodName() + "()";
                        MergedAnnotation<Tool> tool = method.getAnnotations().get(Tool.class);
                        if (!tool.isPresent()) {
                            throw notATool(where);
                        }
                        String name = tool.getString("name");
                        add(
                                declarations,
                                StringUtils.hasText(name) ? name : method.getMethodName(),
                                method.getAnnotations()
                                        .get(RequiresApproval.class)
                                        .getString("reason"),
                                where);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not scan package " + basePackage + " for @RequiresApproval", e);
            }
        }
        return toPolicies(declarations);
    }

    /**
     * Finds annotated tool methods in the given classes, by reflection.
     *
     * <p>For applications that list their tool classes explicitly, outside the starter.
     *
     * @param toolClasses classes with {@code @Tool} methods
     * @return one mandatory policy per annotated tool
     * @throws InvalidPolicyException if {@code @RequiresApproval} is on a method without {@code @Tool}
     */
    public static List<Policy> from(Class<?>... toolClasses) {
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        for (Class<?> toolClass : toolClasses) {
            for (Method method : ReflectionUtils.getDeclaredMethods(toolClass)) {
                RequiresApproval requiresApproval =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequiresApproval.class);
                if (requiresApproval == null) {
                    continue;
                }
                String where = toolClass.getName() + "." + method.getName() + "()";
                Tool tool = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
                if (tool == null) {
                    throw notATool(where);
                }
                add(
                        declarations,
                        StringUtils.hasText(tool.name()) ? tool.name() : method.getName(),
                        requiresApproval.reason(),
                        where);
            }
        }
        return toPolicies(declarations);
    }

    private record Declaration(String toolName, String reason, String where) {}

    private static void add(Map<String, Declaration> declarations, String toolName, String reason, String where) {
        Declaration declaration = new Declaration(toolName, reason, where);
        Declaration existing = declarations.putIfAbsent(toolName, declaration);
        if (existing != null && !existing.reason().equals(reason)) {
            LOG.warn(
                    "Tool '{}' has @RequiresApproval on both {} and {} with different reasons; using the first",
                    toolName,
                    existing.where(),
                    where);
        }
    }

    private static List<Policy> toPolicies(Map<String, Declaration> declarations) {
        List<Policy> policies = new ArrayList<>(declarations.size());
        for (Declaration declaration : declarations.values()) {
            String toolName = declaration.toolName();
            String reason = StringUtils.hasText(declaration.reason())
                    ? declaration.reason()
                    : "Tool '" + toolName + "' requires human approval";
            policies.add(new Policy(
                    ID_PREFIX + toolName.replaceAll("[^A-Za-z0-9._-]", "_"),
                    List.of(ToolPattern.of(toolName)),
                    Condition.always(),
                    Decision.requireApproval(reason),
                    Policy.DEFAULT_PRIORITY));
        }
        return List.copyOf(policies);
    }

    private static InvalidPolicyException notATool(String where) {
        return new InvalidPolicyException(
                "@RequiresApproval on " + Objects.requireNonNull(where) + " has no effect: the method is not a @Tool");
    }
}
