package kyle.poultry.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaModifier.PUBLIC;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Enforces the modular monolith's boundaries against the compiled production classes (test classes are excluded).
 * It is run by the build like any other test, so a module that starts depending on a module it must not know fails
 * {@code ./mvnw verify}. Rules for a module whose package does not exist yet (for example {@code report}) pass
 * vacuously, so the test is valid before and after that module is added.
 */
@AnalyzeClasses(packages = "kyle.poultry", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTests {

    /** Each module mapped to the only other modules it may depend on; anything not listed is forbidden. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "shared", Set.of(),
            "audit", Set.of("shared"),
            "identity", Set.of("audit", "shared"),
            "catalog", Set.of("audit", "identity", "shared"),
            "finance", Set.of("identity", "shared"),
            "stock", Set.of("catalog", "finance", "identity", "shared"),
            "delivery", Set.of("catalog", "stock", "finance", "identity", "shared"),
            "report", Set.of("finance", "stock", "identity", "shared"));

    /**
     * Returns the ArchUnit package pattern covering a module and all its sub-packages.
     *
     * @param module the module's top-level package name, such as {@code stock}
     * @return the pattern, such as {@code kyle.poultry.stock..}
     */
    private static String pkg(String module) { return "kyle.poultry." + module + ".."; }

    /**
     * Combines several rules into one that fails if any of them fails, reporting every violation.
     *
     * @param rules the rules to combine; must not be empty
     * @return a single rule equivalent to all of them
     */
    private static ArchRule allOf(List<ArchRule> rules) {
        // CompositeArchRule is ArchUnit's way to AND rules; ArchRule itself has no and() for arbitrary rules.
        var composite = CompositeArchRule.of(rules.getFirst());
        for (ArchRule rule : rules.subList(1, rules.size())) composite = composite.and(rule);
        return composite;
    }

    /** Rule (a): every module depends only on the modules listed for it in {@link #ALLOWED}. */
    @ArchTest
    static final ArchRule modulesDependOnlyOnAllowedModules = allOf(ALLOWED.keySet().stream().sorted().map(module -> {
        // Forbidden = every module except itself and the ones it is allowed to use.
        var forbidden = new TreeSet<>(ALLOWED.keySet());
        forbidden.remove(module);
        forbidden.removeAll(ALLOWED.get(module));
        return (ArchRule) noClasses().that().resideInAPackage(pkg(module))
                .should().dependOnClassesThat()
                .resideInAnyPackage(forbidden.stream().map(ModuleBoundaryTests::pkg).toArray(String[]::new))
                .allowEmptyShould(true)
                .as("module '%s' may only depend on %s".formatted(module, ALLOWED.get(module)));
    }).toList());

    /** Rule (b): the top-level module packages form a directed acyclic graph. */
    @ArchTest
    static final ArchRule noCyclesBetweenModules = slices().matching("kyle.poultry.(*)..")
            .should().beFreeOfCycles().allowEmptyShould(true);

    /**
     * Rule (c): code outside a module may only use its public types, and never a repository or controller even if
     * one were made public. Most violations are already impossible because those types are package-private.
     */
    @ArchTest
    static final ArchRule onlyPublicTypesCrossModuleBoundaries = allOf(ALLOWED.keySet().stream().sorted().map(module -> {
        // "Internal" = repositories, controllers and anything that is not public, inside this module.
        DescribedPredicate<JavaClass> internal = resideInAPackage(pkg(module))
                .and(JavaClass.Predicates.simpleNameEndingWith("Repository")
                        .or(JavaClass.Predicates.simpleNameEndingWith("Controller"))
                        .or(not(modifier(PUBLIC))))
                .as("internal types of module '%s' (repositories, controllers, non-public types)".formatted(module));
        return (ArchRule) noClasses().that().resideOutsideOfPackage(pkg(module))
                .should().dependOnClassesThat(internal).allowEmptyShould(true);
    }).toList());
}
