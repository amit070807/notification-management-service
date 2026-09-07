plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.notification"
version = "1.0.0"

// ADR-001: Java 21. The toolchain is declared so the build is reproducible
// regardless of the JDK on the developer's PATH.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "1.21.4"
extra["archunitVersion"] = "1.3.0"
extra["swaggerValidatorVersion"] = "2.43.0"
extra["logstashEncoderVersion"] = "8.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // ADR-003: PostgreSQL is the single store. Flyway migrations are forward-only.
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // Constitution Observability: structured JSON logs, metrics.
    implementation("net.logstash.logback:logstash-logback-encoder:${property("logstashEncoderVersion")}")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")

    // Principle VII: the domain/framework boundary is machine-checked, not conventional.
    testImplementation("com.tngtech.archunit:archunit-junit5:${property("archunitVersion")}")

    // U-1 (ADR-013): controllers are hand-written; this asserts they conform to
    // contracts/openapi.yaml, which stays the authoritative document (Principle I).
    testImplementation("com.atlassian.oai:swagger-request-validator-mockmvc:${property("swaggerValidatorVersion")}")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Docker Desktop on macOS exposes its socket under $HOME/.docker/run/docker.sock, but
    // Testcontainers only probes the classic /var/run/docker.sock. Point it at the real socket
    // when the classic path is absent, so integration tests run without per-machine setup.
    // Derived from user.home rather than hardcoded, and skipped if DOCKER_HOST is already set.
    val defaultSocket = File("/var/run/docker.sock")
    val userSocket = File(System.getProperty("user.home"), ".docker/run/docker.sock")
    if (System.getenv("DOCKER_HOST") == null && !defaultSocket.exists() && userSocket.exists()) {
        environment("DOCKER_HOST", "unix://" + userSocket.absolutePath)
    }
    // docker-java negotiates Docker API 1.32 by default, which Engine 25+ rejects outright
    // ("client version 1.32 is too old. Minimum supported API version is 1.40"). It reads the
    // system property `api.version`, not the DOCKER_API_VERSION env var. 1.43 is accepted by
    // every Engine from 24 onward, including the GitHub-hosted runners.
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.43")
    // Principle VI: tests are deterministic. Fail fast on the ordering assumptions
    // that make a suite flaky.
    systemProperty("junit.jupiter.execution.order.random.seed", "1")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Constitution Declared Operating Defaults: 90% domain, 80% overall.
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com.notification.domain.*")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

// Principle VII gate, runnable on its own so a boundary violation is diagnosable
// without running the whole suite.
tasks.register<Test>("archTest") {
    description = "Runs the ArchUnit architecture rules (Principle VII gate)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.notification.architecture.*") }
}

// U-2 (ADR-011): CI invokes this same aggregate, so every gate is reproducible
// on a laptop and in CI with no duplicated tool configuration.
tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
