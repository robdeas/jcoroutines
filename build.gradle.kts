/*
 * Copyright (c) 2025 Rob Deas Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.spotbugs.snom.SpotBugsTask
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("java")
    `java-library`
    `maven-publish`

    id("net.ltgt.errorprone") version "4.2.0"
    id("com.github.spotbugs") version "6.0.7"
    id("pmd")

    // JReleaser for Central Publishing Portal
    id("org.jreleaser") version "1.20.0"
}

group = "tech.robd"
version = "0.1.1-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Build-failing null safety
    errorprone("com.google.errorprone:error_prone_core:2.30.0")
    errorprone("com.uber.nullaway:nullaway:0.12.0")

    compileOnly("org.jspecify:jspecify:1.0.0")
    implementation("org.jetbrains:annotations:26.0.2")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jspecify:jspecify:1.0.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

// Add source and javadoc jars
java {
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<SpotBugsTask>().configureEach {
    ignoreFailures = true
    dependsOn("classes")
    val excludeFile = file("config/spotbugs/exclude.xml")
    if (excludeFile.exists()) {
        excludeFilter.set(excludeFile)
    }
    classDirs.setFrom(sourceSets.main.get().output)
    auxClassPaths.setFrom(configurations.compileClasspath)

    reports {
        create("text") {
            required.set(true)
            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/${name}.txt"))
        }
    }

    doLast {
        val txt = reports["text"].outputLocation.get().asFile
        println("SpotBugs text report: file://${txt.absolutePath}")
    }
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain")
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest")

pmd {
    toolVersion = "7.5.0"
    isConsoleOutput = true
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = emptyList()
}

tasks.withType<Pmd>().configureEach {
    ignoreFailures = true
    exclude("**/generated/**", "**/build/**")

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/pmd/${name}.html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/pmd/${name}.xml"))
    }

    doLast {
        val html = reports.html.outputLocation.get().asFile
        if (html.exists()) {
            println("PMD HTML report: file://${html.absolutePath}")
        }
        val xml = reports.xml.outputLocation.get().asFile
        if (xml.exists()) {
            println("PMD XML report: file://${xml.absolutePath}")
        }
    }
}

tasks.named("check") {
    dependsOn("spotbugsMain", "spotbugsTest", "pmdMain", "pmdTest")
}

tasks.register("lintAll") {
    group = "verification"
    description = "Runs all static analysis (NullAway, PMD, SpotBugs, tests)"
    dependsOn("check")
}

// ErrorProne configuration
tasks.named<JavaCompile>("compileJava") {
    options.errorprone.isEnabled.set(false)
}
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.isEnabled.set(true)
    options.errorprone {
        option("-Xep:NullAway:ERROR")
        option("-XepOpt:NullAway:AnnotatedPackages=tech.robd")
        option("-XepOpt:NullAway:JSpecifyMode=true")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
    systemProperty("jcoroutines.diag", "true")
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "tech.robd.jcoroutines")
    }
}

tasks.register("uberJar", Jar::class) {
    dependsOn(tasks.jar)
    archiveClassifier.set("uber")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    configurations.runtimeClasspath.get().forEach { file ->
        from(zipTree(file))
    }
}

// Maven publishing configuration for JReleaser
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("J Coroutines")
                description.set("A coroutines library for Java.")
                url.set("https://robd.tech/jcoroutines")
                inceptionYear.set("2025")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("robd")
                        name.set("Robert Deas")
                        email.set("rob@robd.tech")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/robdeas/jcoroutines.git")
                    developerConnection.set("scm:git:ssh://git@github.com:robdeas/jcoroutines.git")
                    url.set("https://github.com/robdeas/jcoroutines")
                }
            }
        }
    }

    // Publish to local staging directory for JReleaser
    repositories {
        maven {
            name = "staging"
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

// JReleaser configuration
jreleaser {
    // Project metadata
    project {
        name.set("jcoroutines")
        description.set("A coroutines library for Java.")
        inceptionYear.set("2025")
        authors.set(listOf("Robert Deas"))
        license.set("Apache-2.0")
        copyright.set("Copyright (c) 2025 Rob Deas Ltd.")

        links {
            homepage.set("https://robd.tech/jcoroutines")
            bugTracker.set("https://github.com/robdeas/jcoroutines/issues")
            documentation.set("https://robd.tech/jcoroutines")
        }
    }

    // Disable GitHub release since we only want Maven Central
    release {
        github {
            enabled.set(true)  // Must be enabled but we'll skip the actual release
            skipRelease.set(true)  // Skip the release step
            skipTag.set(true)      // Skip tagging
            token.set("dummy")     // Dummy token to satisfy validation
        }
    }

    // Enable signing - use environment variables
    signing {
        active.set(org.jreleaser.model.Active.ALWAYS)
        armored.set(true)

        // JReleaser will read from environment variables:
        // JRELEASER_GPG_PUBLIC_KEY, JRELEASER_GPG_SECRET_KEY, JRELEASER_GPG_PASSPHRASE
        passphrase.set(findProperty("signing.password") as String?)
    }

    // Deploy to Maven Central
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active.set(org.jreleaser.model.Active.ALWAYS)
                    url.set("https://central.sonatype.com/api/v1/publisher")

                    // Credentials from gradle.properties or environment variables
                    username.set(findProperty("centralUsername") as String? ?: System.getenv("CENTRAL_USERNAME"))
                    password.set(findProperty("centralPassword") as String? ?: System.getenv("CENTRAL_PASSWORD"))

                    // Staging repository
                    stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.path)
                }
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}