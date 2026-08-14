package cn.bitcss.arctra.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture tests for Arctra Core.
 *
 * <p>Protects long-term architectural invariants such as dependency direction and module
 * boundaries.
 *
 * @author lov3r
 */
@AnalyzeClasses(packages = "cn.bitcss.arctra", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

  @ArchTest
  static final ArchRule core_does_not_depend_on_spring =
      noClasses()
          .that()
          .resideInAnyPackage("cn.bitcss.arctra..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..")
          .because("Core must remain Spring-agnostic");

  @ArchTest
  static final ArchRule core_does_not_depend_on_jakarta_persistence =
      noClasses()
          .that()
          .resideInAnyPackage("cn.bitcss.arctra..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("jakarta.persistence..")
          .because("Core must not depend on JPA");

  @ArchTest
  static final ArchRule core_does_not_depend_on_elasticsearch =
      noClasses()
          .that()
          .resideInAnyPackage("cn.bitcss.arctra..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.elasticsearch..")
          .because("Core must not depend on Elasticsearch");

  @ArchTest
  static final ArchRule core_does_not_depend_on_redis =
      noClasses()
          .that()
          .resideInAnyPackage("cn.bitcss.arctra..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("redis.clients..")
          .because("Core must not depend on Redis");

  @ArchTest
  static final ArchRule runtime_does_not_depend_on_client =
      noClasses()
          .that()
          .resideInAnyPackage("cn.bitcss.arctra.runtime..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("cn.bitcss.arctra.client..")
          .because("Runtime should not depend on Client");

  @ArchTest
  static final ArchRule agent_models_do_not_depend_on_runtime_or_client =
      noClasses()
          .that()
          .resideInAnyPackage("cn.bitcss.arctra.agent..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("cn.bitcss.arctra.runtime..", "cn.bitcss.arctra.client..")
          .because("Agent models should not depend on Runtime or Client");
}
