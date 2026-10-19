import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Logging
import java.sql.DriverManager

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("nu.studer.jooq") version "9.0"
}

group = "com.navan"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

val sqliteJdbcVersion = "3.46.1.3"
val jooqVersion = "3.19.11"

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.xerial:sqlite-jdbc:3.46.1.3")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jooq") {
        exclude(group = "org.jooq", module = "jooq")
    }
    implementation("org.jooq:jooq:$jooqVersion")

    runtimeOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")

    jooqGenerator("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    jooqGenerator("org.jooq:jooq-meta-extensions:$jooqVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito", module = "mockito-core")
    }
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

val generatedDbDir = layout.buildDirectory.dir("generated-db")
val generatedDbFile = generatedDbDir.map { it.file("navan-codegen.db") }
val generatedDbUrl = generatedDbFile.map { "jdbc:sqlite:${it.asFile.absolutePath}" }

val prepareJooqDb by tasks.registering {
    val schemaFile = project.file("src/main/resources/schema.sql")
    inputs.file(schemaFile)
    outputs.file(generatedDbFile)
    doLast {
        val f = generatedDbFile.get().asFile
        if (f.exists()) f.delete()
        f.parentFile.mkdirs()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            c.createStatement().use { st ->
                schemaFile.readText()
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { st.execute(it) }
            }
        }
    }
}

jooq {
    version.set(jooqVersion)
    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(true)
            jooqConfiguration.apply {
                logging = Logging.WARN
                jdbc.apply {
                    driver = "org.sqlite.JDBC"
                    url = generatedDbUrl.get()
                }
                generator.apply {
                    name = "org.jooq.codegen.JavaGenerator"
                    database.apply {
                        name = "org.jooq.meta.sqlite.SQLiteDatabase"
                        inputSchema = ""
                        forcedTypes = listOf(
                            ForcedType()
                                .withUserType("java.util.UUID")
                                .withConverter("com.navan.assignment.common.UuidBinaryConverter")
                                .withIncludeTypes("BLOB")
                                .withIncludeExpression(".*\\.id|.*_id")
                        )
                    }
                    generate.apply {
                        isRecords = true
                        isDeprecated = false
                        isFluentSetters = true
                    }
                    target.apply {
                        packageName = "com.navan.assignment.jooq"
                        directory = layout.buildDirectory.dir("generated-src/jooq/main").get().asFile.absolutePath
                    }
                }
            }
        }
    }
}

tasks.named("generateJooq") {
    dependsOn(prepareJooqDb)
    inputs.files(fileTree("src/main/resources") { include("schema.sql") })
    inputs.property("jooqVersion", jooqVersion)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
