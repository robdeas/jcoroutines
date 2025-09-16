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
    kotlin("jvm")
    `java-library`
    `maven-publish`

    id("net.ltgt.errorprone") version "4.2.0"
    id("com.github.spotbugs") version "6.0.7"
    id("pmd")
}

group = "tech.robd"
version = "0.1.0-SNAPSHOT"

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
    implementation("org.slf4j:slf4j-api:2.0.13") // or latest
    implementation(kotlin("stdlib-jdk8"))

    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
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
        // define a text report explicitly
        create("text") {
            required.set(true)
            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/${name}.txt"))
        }
    }

    // Nice clickable path at the end
    doLast {
        val txt = reports["text"].outputLocation.get().asFile
        println("SpotBugs text report: file://${txt.absolutePath}")
    }
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain")
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest")

// ---- PMD (report-only) ----
pmd {
    toolVersion = "7.5.0"     // fallback to "6.55.0" if needed
    isConsoleOutput = true
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = emptyList()
    ruleSetFiles = files("config/pmd/ruleset.xml")
}

tasks.withType<Pmd>().configureEach {
    ignoreFailures = true      // <- correct property

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
// Ensure both run with the standard verification phase
tasks.named("check") {
    dependsOn("spotbugsMain", "spotbugsTest", "pmdMain", "pmdTest")
}

tasks.register("lintAll") {
    group = "verification"
    description = "Runs all static analysis (NullAway, PMD, SpotBugs, tests)"
    dependsOn("check")
}


//
// Disable EP on main, enable on tests
tasks.named<JavaCompile>("compileJava") {
    options.errorprone.isEnabled.set(false)
}
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.isEnabled.set(true)
    options.errorprone {
        option("-Xep:NullAway:ERROR")
        option("-XepOpt:NullAway:AnnotatedPackages=tech.robd") // <- yours
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "tech.robd"
            artifactId = "jcoroutines"
            version = "0.1.0-SNAPSHOT"
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}