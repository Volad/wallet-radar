plugins {
    `java-library`
    checkstyle
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.5")
        // Spring Boot 3.2.5's managed Testcontainers version (1.19.7) predates docker-java fixes
        // needed for current Docker Desktop releases; override via a newer Testcontainers BOM so the
        // gated BybitRepairChainIdempotencyIntegrationTest can actually start a container locally.
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
    }
}

dependencies {
    api(project(":backend:domain"))
    api(project(":backend:canonical"))
    implementation(project(":backend:platform"))

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.2.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    checkstyle("com.puppycrawl.tools:checkstyle:10.14.2")
}

checkstyle {
    toolVersion = "10.14.2"
    configDirectory.set(rootProject.file("backend/config/checkstyle"))
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.named<Checkstyle>("checkstyleMain") {
    source = fileTree("src/main/java")
}

tasks.named<Checkstyle>("checkstyleTest") {
    source = fileTree("src/test/java")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":backend:domain:test", ":backend:canonical:test", ":backend:platform:test")
    // Forward wr.test.* system properties (e.g. wr.test.bybit.dumpPath) to the forked test JVM so
    // gated, manual-only integration tests like BybitRepairChainIdempotencyIntegrationTest can be
    // invoked locally via -Dwr.test.bybit.dumpPath=... without affecting default/CI runs, where
    // these properties are unset and the corresponding tests self-skip.
    System.getProperties().forEach { (key, value) ->
        val name = key.toString()
        if (name.startsWith("wr.test.") || name.startsWith("walletradar.")) {
            systemProperty(name, value)
        }
    }
}
