package kyle.poultry.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaModifier.PUBLIC;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import jakarta.persistence.Entity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

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

    /** The shared kernel: it is public by design and has no api/internal split. */
    private static final String SHARED = "shared";

    /** @param module a module's top-level package name @return the pattern for its public face, {@code kyle.poultry.<module>.api..} */
    private static String api(String module) { return "kyle.poultry." + module + ".api.."; }

    /** @param module a module's top-level package name @return the pattern for its private part, {@code kyle.poultry.<module>.internal..} */
    private static String internal(String module) { return "kyle.poultry." + module + ".internal.."; }

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
     * Rule (c): code outside a module uses only its {@code api} package (and, for the shared kernel, its public types).
     * Everything else in a module, that is {@code internal} and anything unexpected next to it, is off limits.
     */
    @ArchTest
    static final ArchRule onlyTheApiCrossesModuleBoundaries = allOf(ALLOWED.keySet().stream().sorted().map(module -> {
        DescribedPredicate<JavaClass> internal = SHARED.equals(module)
                // The shared kernel has no api/internal split; only its non-public types, repositories and controllers are private.
                ? resideInAPackage(pkg(module)).and(JavaClass.Predicates.simpleNameEndingWith("Repository")
                        .or(JavaClass.Predicates.simpleNameEndingWith("Controller"))
                        .or(not(modifier(PUBLIC))))
                : resideInAPackage(pkg(module)).and(not(resideInAPackage(api(module))));
        return (ArchRule) noClasses().that().resideOutsideOfPackage(pkg(module))
                .should().dependOnClassesThat(internal.as("non-api types of module '%s'".formatted(module)))
                .allowEmptyShould(true);
    }).toList());

    /** Rule (d): a module's public face ({@code ..api..}) never depends on its own {@code ..internal..} packages. */
    @ArchTest
    static final ArchRule apiDoesNotLeakInternals = allOf(ALLOWED.keySet().stream().sorted().map(module ->
            (ArchRule) noClasses().that().resideInAPackage(api(module))
                    .should().dependOnClassesThat().resideInAPackage(internal(module))
                    .allowEmptyShould(true)).toList());

    /** Rule (e): a module's package holds only {@code api} and {@code internal}; no class sits loose at the module root. */
    @ArchTest
    static final ArchRule modulesHaveOnlyApiAndInternal = allOf(ALLOWED.keySet().stream().sorted()
            .filter(module -> !SHARED.equals(module)).map(module ->
                    (ArchRule) classes().that().resideInAPackage(pkg(module))
                            .should().resideInAnyPackage(api(module), internal(module))
                            .allowEmptyShould(true)).toList());

    /** Rule (f): the public face is contracts and data only: no controllers, services, entities or repositories. */
    @ArchTest
    static final ArchRule apiHoldsNoImplementationTypes = noClasses().that().resideInAPackage("kyle.poultry..api..")
            .should().beAnnotatedWith(Entity.class)
            .orShould().beAnnotatedWith(RestController.class)
            .orShould().beAnnotatedWith(Service.class)
            .orShould().haveSimpleNameEndingWith("Repository")
            .orShould().haveSimpleNameEndingWith("Controller")
            .allowEmptyShould(true);

    /** Rule (g): inside {@code internal}, {@code model} is the bottom layer and depends on no other internal layer. */
    @ArchTest
    static final ArchRule modelDependsOnNoOtherLayer = noClasses().that().resideInAPackage("..internal.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..internal.web..", "..internal.service..",
                    "..internal.persistence..", "..internal.security..", "..internal.export..")
            .allowEmptyShould(true);
}
