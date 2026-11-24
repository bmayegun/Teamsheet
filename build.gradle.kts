plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.biz"
version = "0.0.1-SNAPSHOT"
description = "teamsheet"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    inputs.property("authTestDb", providers.environmentVariable("TEAMSHEET_AUTH_TEST_JDBC").isPresent)
    // A configured run must execute against the supplied database, not reuse another database's results.
    outputs.upToDateWhen { !providers.environmentVariable("TEAMSHEET_AUTH_TEST_JDBC").isPresent }
    outputs.doNotCacheIf("Database integration tests use external state") {
        providers.environmentVariable("TEAMSHEET_AUTH_TEST_JDBC").isPresent
    }
}

tasks.bootJar {
    archiveFileName.set("teamsheet.jar")
    from(layout.projectDirectory.dir("frontend/dist")) {
        into("BOOT-INF/classes/static")
    }
}
