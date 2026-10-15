# spring-testcontainers-minimal

A minimal Spring Boot project that runs a repository test against a real
database inside a Docker container, started on demand by Testcontainers. One
entity, one repository, one test.

It uses **PostgreSQL** as the example database, but the wiring it teaches (a
`@ServiceConnection` container bean plus a `@SpringBootTest` base class) is
database-agnostic. To target another database (MySQL, MSSQL, etc.) you swap the
JDBC driver, the `testcontainers-*` module, and the container class; everything
else stays. See [How to add Testcontainers to your Spring Boot
project](#how-to-add-testcontainers-to-your-spring-boot-project) for the
pattern.

Use this repo as a reference for wiring Testcontainers into your own Spring Boot
project. Every file is small enough to read in one glance. By the end you will
know why `@ServiceConnection` exists, when the `@Bean` pattern beats the JUnit
extension, and how to wire a real database into your own tests.

> Check out the expanded version (Flyway migrations, JPA auditing,
>  a REST API, multiple tests, CI):
> [spring-testcontainers-demo](https://github.com/iakovos3/spring-testcontainers-demo).

## Prerequisites

- **JDK 25** on `JAVA_HOME`.
- **Docker** running locally. Testcontainers drives the Docker daemon to start
  a real database container, so without Docker the tests cannot run. Check with:

  ```bash
  docker run --rm alpine:3.17 echo ok
  # expected: ok (returns within a few seconds)
  ```

- **No local Maven install needed.** The Maven wrapper (`./mvnw`) is committed.
  On Windows use `./mvnw.cmd`; on macOS/Linux use `./mvnw`.

## Run the tests

```bash
./mvnw.cmd test      # Windows
./mvnw test          # macOS / Linux
```

The first run pulls the `postgres:18.4` image (a few hundred MB); later runs
reuse the cached image. A green run ends with:

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## What just happened?

Five things, in order, on every test run:

1. Testcontainers asks the Docker daemon for `postgres:18.4` (first run pulls
   it: `Pulling docker image: postgres:18.4...`), then starts a container on a
   **random host port**.
2. Spring Boot starts the test `ApplicationContext`; the `@ServiceConnection`
   bean is created first, before everything else.
3. `@ServiceConnection` reads the container's actual URL, username, and password
   and hands them to the auto-configured `DataSource` — nothing is hardcoded.
4. Hibernate sees `ddl-auto: create-drop` and issues `create table customer ...`
   against the container.
5. The test saves `Ada Lovelace`, reads her back, and the container is stopped
   and removed when the JVM exits. Nothing is left behind in Docker.

## How to add Testcontainers to your Spring Boot project

These are the steps this repository follows. Apply them to any Spring Boot 4.x
project that uses Spring Data JPA and wants real-database integration tests.
The snippets below use PostgreSQL (as this repo does); for another database,
swap the items marked **swap me**.

### 1. Add the dependencies

In `pom.xml`, under the Spring Boot 4.1 parent, add:

```xml
<!-- production dependencies -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>            <!-- swap me: the JDBC driver for your DB -->
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- test dependencies -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>   <!-- swap me: testcontainers-<yourdb> -->
    <scope>test</scope>
</dependency>
```

Notes:

- Do **not** set a `<version>` on the `org.testcontainers` artifacts. The
  Spring Boot 4.1 BOM pins Testcontainers 2.x for you. Overriding it by hand
  is a common way to break the wiring.
- `testcontainers-junit-jupiter` is not actually used by this project (that
  module only matters if you use the `@Testcontainers` JUnit extension or
  `@ImportTestcontainers`; this repo uses neither). It is kept because the
  official examples list it, and it costs nothing on the classpath.
- The JDBC driver (`org.postgresql:postgresql` here) is mandatory.
  `@ServiceConnection` supplies the database URL, but the driver class still
  has to be on the classpath for the connection to actually open. Forgetting
  this driver is the most common reason a wired container still fails to
  connect.
- The real `pom.xml` also carries a surefire `<argLine>` block that attaches
  the byte-buddy agent explicitly. That is **Java 25 plumbing, not
  Testcontainers wiring**: `spring-boot-starter-test` brings Mockito, whose
  inline mock maker attaches its agent dynamically at runtime. Recent JDKs
  (JEP 451) warn about that self-attach and plan to disallow it, so the pom
  passes `-javaagent:...byte-buddy-agent...` up front instead, plus
  `-Dnet.bytebuddy.experimental=true` so byte-buddy accepts Java 25's
  class-file version. Harmless to copy, safe to delete on older JDKs.

### 2. Declare the container as a `@ServiceConnection` bean

Create a test-only configuration class that exposes the container as a bean
annotated with `@ServiceConnection`. Spring Boot reads the container's dynamic
JDBC URL, username, and password and injects them into the auto-configured
`DataSource` for you, so no hardcoded ports and no `spring.datasource.*` properties.

```java
// src/test/java/.../IntegrationTestContainers.java
package io.github.iakovos3.springtestcontainersminimal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;   // swap me: your DB's container class
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestContainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.4"));  // swap me: your DB image
    }
}
```

Use the container class from the matching `testcontainers-<db>` module (for
PostgreSQL that's `org.testcontainers.postgresql.PostgreSQLContainer`, the
Testcontainers 2.x home for it, not the deprecated
`org.testcontainers.containers.*` shim). Pass a `DockerImageName`, not a raw
`String`, so the image name is validated and substituted correctly. Pin a
specific image tag (`postgres:18.4`), never `:latest`.

> Prefer this `@Bean @ServiceConnection` pattern over the older
> `static @Container` field with `@Testcontainers`. The bean pattern plays well
> with Spring's test context caching; the static-field pattern does not.

### 3. Create a base integration test class

A small abstract base class keeps the container wiring in one place so every
integration test reuses the same Spring context (and the same container).

```java
// src/test/java/.../BaseIntegrationTest.java
package io.github.iakovos3.springtestcontainersminimal;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(IntegrationTestContainers.class)
public abstract class BaseIntegrationTest {
}
```

`webEnvironment = NONE` because these tests exercise the repository layer, not
the web layer. If your tests hit controllers, use the default web environment
(`MOCK`, no real server) or `RANDOM_PORT` if you need a live port.

### 4. Let Hibernate create the schema in tests

Put a test-scoped `application.yml` in `src/test/resources/`. It is loaded
automatically for tests and overrides the main `application.yml`.

```yaml
# src/test/resources/application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

`create-drop` builds the tables from your entity mappings when the context
starts and drops them when it stops, so no Flyway or Liquibase needed for the
test to run. (In a production-shaped project you would use Flyway and set
`ddl-auto: validate`; this minimal project skips that.)

### 5. Write a test that extends the base class

```java
// src/test/java/.../CustomerRepositoryTest.java
package io.github.iakovos3.springtestcontainersminimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRepositoryTest extends BaseIntegrationTest {

    @Autowired
    CustomerRepository repository;

    @Test
    void should_persist_customer_when_saved() {
        Customer ada = new Customer();
        ada.setName("Ada Lovelace");
        repository.save(ada);

        assertThat(repository.findAll())
                .extracting(Customer::getName)
                .contains("Ada Lovelace");
    }
}
```

### 6. Start Docker and run

```bash
docker run --rm alpine:3.17 echo ok   # confirm Docker is up
./mvnw test
```

That is the whole integration. The container starts, `@ServiceConnection` wires
it into the `DataSource`, Hibernate creates the schema, your test runs against a
real database, and the container is removed when the JVM exits.

## Why `@Bean @ServiceConnection` (and not the alternatives)

The Spring Boot documentation describes three ways to wire a Testcontainers
container into a test. They all start the same real database in Docker; they
differ in **who owns the container's lifecycle** (start/stop) and how it gets
into the Spring context. The official docs are at
[Testing > Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).

1. **Using Spring Beans.** The container is a `@Bean` in a
   `@TestConfiguration`, imported into the test with `@Import`. Spring owns the
   lifecycle: the container starts before other beans and stops after them.
   Plays well with Spring's test context caching, because the container stays
   alive as long as the cached `ApplicationContext` does, so one container can
   serve many test classes. This is what this repo uses.

2. **Using the JUnit Extension.** The container is a `static @Container` field
   on the test class, activated by `@Testcontainers`. Testcontainers (not
   Spring) owns the lifecycle: the container starts before the test class and
   stops after it. Simpler to write, but it fights Spring's context caching.
   The cached `ApplicationContext` can outlive the container, so a later test
   reusing that context finds a stopped database. The official docs warn about
   exactly this and recommend the bean approach when used with `@SpringBootTest`.

3. **Importing Container Configuration Interfaces.** A middle ground. You
   declare containers as `static @Container` fields in an interface, then
   import that interface into a `@TestConfiguration` with
   `@ImportTestcontainers`. You get the Spring-managed lifecycle from option 1
   but keep the declarations in a reusable interface shared across test
   classes. Worth it when many tests share the same container setup; overkill
   for a single test.

A separate, orthogonal feature is `@ServiceConnection`. It auto-wires the
container's URL, username, and password into Spring's auto-configuration, so
you write no `spring.datasource.*` properties. It works with any of the three
options above. This repo combines **option 1 + `@ServiceConnection`**: the
smallest setup that is still correct under Spring's context caching.

| Option | Lifecycle owner | Safe with `@SpringBootTest` context caching | Fits this repo |
| --- | --- | --- | --- |
| Spring Beans (`@Bean @ServiceConnection`) | Spring | yes | yes (used) |
| JUnit Extension (`@Testcontainers`) | Testcontainers | no | no |
| `@ImportTestcontainers` interface | Spring | yes | overkill for one test |

**Why not the JUnit Extension here.** This project uses `@SpringBootTest`, so
the `ApplicationContext` is cached and reused. With the JUnit extension the
container is stopped after the test class while the context may still be
cached, which leads to failures on later tests that reuse the same context.
The official Spring Boot docs call this out and recommend managing containers
as Spring beans instead, which is what step 2 does.

## Swapping the database

The wiring in steps 1-2 is the same for any database Testcontainers supports.
To target a different one, change three things and leave the rest alone:

| Change | PostgreSQL (this repo) | Your database |
| --- | --- | --- |
| JDBC driver dependency | `org.postgresql:postgresql` | your driver |
| Testcontainers module | `testcontainers-postgresql` | `testcontainers-<db>` |
| Container class + image | `PostgreSQLContainer`, `postgres:18.4` | your container class, your image |

`@ServiceConnection`, the base class, `ddl-auto: create-drop`, and the test
itself do not change. Be aware that some databases bring extra weight or
acceptance steps. For example, the MSSQL image is ~1.5 GB, takes 30-60 s to start, and
requires `ACCEPT_EULA=Y`, so the "minimal/fast" character of this project is
specific to the lightweight Postgres image.

## Project structure

```
spring-testcontainers-minimal/
├── .mvn/wrapper/maven-wrapper.properties   # Maven 3.9.16 pinned
├── mvnw, mvnw.cmd                          # Maven wrapper scripts
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/io/github/iakovos3/springtestcontainersminimal/
    │   │   ├── SpringTestcontainersMinimalApplication.java  # @SpringBootApplication
    │   │   ├── Customer.java                                # @Entity { id, name }
    │   │   └── CustomerRepository.java                      # ListCrudRepository
    │   └── resources/
    │       └── application.yml                              # spring.application.name
    └── test/
        ├── java/io/github/iakovos3/springtestcontainersminimal/
        │   ├── IntegrationTestContainers.java               # @Bean @ServiceConnection
        │   ├── BaseIntegrationTest.java                     # @SpringBootTest + @Import
        │   └── CustomerRepositoryTest.java                  # the one test
        └── resources/
            └── application.yml                              # ddl-auto: create-drop
```

## See also

- [spring-testcontainers-demo](https://github.com/iakovos3/spring-testcontainers-demo):
   the expanded companion with Flyway migrations, ddl-auto: validate, JPA auditing, a REST API, eight tests, and CI.
- [Spring Boot testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Testcontainers documentation](https://java.testcontainers.org/)
