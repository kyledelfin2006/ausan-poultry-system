package kyle.poultry.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Code-level rules for the whole application, in addition to the module-boundary rules in
 * {@link ModuleBoundaryTests}. They protect the layering (web, service, persistence), the testability of time, and a few
 * habits that cause production incidents. Every rule works on the package pattern {@code kyle.poultry..}, so a module added
 * later is covered without editing this file.
 * <p>
 * A failing rule names the offending class and line: fix the code rather than weakening the rule, and add the reason here if a
 * rule ever has to gain an exception.
 */
@AnalyzeClasses(packages = "kyle.poultry", importOptions = ImportOption.DoNotIncludeTests.class)
class CodingRulesTests {

    /** Web code reaches data only through a module's services, so a controller never touches a repository directly. */
    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat(simpleNameEndingWith("Repository"))
            .because("controllers talk to services; persistence is behind them");

    /** Persistence entities are internal: the API returns views, so a controller never depends on an entity. */
    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .because("entities would leak columns and lazy state into the JSON contract");

    /** A web controller is the only kind of class that serves HTTP, and it is named so. */
    @ArchTest
    static final ArchRule restControllersAreNamedController = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .allowEmptyShould(true);

    /** The reverse: a class named Controller really is a REST controller, so the name never lies. */
    @ArchTest
    static final ArchRule controllersAreRestControllers = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().beAnnotatedWith(RestController.class)
            .allowEmptyShould(true);

    /** Transactions belong to the service layer; a web method must not decide where a transaction starts. */
    @ArchTest
    static final ArchRule controllersAreNotTransactional = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().beAnnotatedWith(Transactional.class)
            .because("the unit of work is a service call");

    /** Same rule at method level. */
    @ArchTest
    static final ArchRule controllerMethodsAreNotTransactional = noMethods()
            .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
            .should().beAnnotatedWith(Transactional.class);

    /** Entities are package-private so only their own module can change them; other modules see views. */
    @ArchTest
    static final ArchRule entitiesAreNotPublic = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().notBePublic()
            .allowEmptyShould(true);

    /** Repositories are internal to their module, whatever the module is. */
    @ArchTest
    static final ArchRule repositoriesAreNotPublic = classes()
            .that().haveSimpleNameEndingWith("Repository")
            .should().notBePublic()
            .allowEmptyShould(true);

    /** A module's public face is an interface named {@code ...Api}. */
    @ArchTest
    static final ArchRule apiTypesAreInterfaces = classes()
            .that().haveSimpleNameEndingWith("Api")
            .should().beInterfaces()
            .allowEmptyShould(true);

    /** The class behind an {@code Api} interface is package-private, so other modules cannot bypass the interface. */
    @ArchTest
    static final ArchRule apiImplementationsAreNotPublic = classes()
            .that().implement(simpleNameEndingWith("Api"))
            .and().areNotInterfaces()
            .should().notBePublic()
            .allowEmptyShould(true);

    /** Dependencies are injected through constructors (immutable, testable without Spring), never into fields. */
    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    /** Output goes through logging, never to the process's console. */
    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    /** One logging facade is used (SLF4J through Spring Boot), not the JDK's. */
    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /**
     * The business date is "today in Manila", which is not the server's date. All code must take time from the injected
     * {@link java.time.Clock}, so no production class may call the no-argument {@code now()} of the java.time types.
     * (Tests also depend on this: they pin the clock to prove the day-boundary behaviour.)
     */
    @ArchTest
    static final ArchRule timeComesFromTheInjectedClock = CodingRulesTests.noArgumentNowIsForbidden();

    /**
     * Builds the "no no-argument now()" rule over every java.time type that has one.
     *
     * @return one rule that fails if any production class calls any of them
     */
    private static ArchRule noArgumentNowIsForbidden() {
        return noClasses().should().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .orShould().callMethod(Instant.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(OffsetDateTime.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .because("time must come from the injected Clock so it is testable and uses the business time zone");
    }
}
