plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    `java-library`
    `maven-publish`
    signing
}

group = "ru.fiddlededee.mycopilot"
version = "1.2.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Empty javadoc jar — Maven Central requires it, but we don't generate real docs
tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
}

dependencies {
    // api — because @Serializable and @LLMDescription are part of public API
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("ai.koog:agents-core-jvm:1.3.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(tasks["javadocJar"])

            pom {
                name.set("My Copilot Models")
                description.set("Serializable models and JSON utilities for my-copilot")
                url.set("https://github.com/fiddlededee/my-copilot/my-copilot-modles")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("fiddlededee")
                        name.set("Fiddlededee")
                    }
                }
                scm {
                    url.set("https://github.com/fiddlededee/my-copilot/my-copilot-modles")
                }
            }
        }
    }

    repositories {
        maven {
            name = "staging"
            url = uri("${layout.buildDirectory.get().asFile}/staging-deploy")
        }
    }
}

signing {
    if (project.hasProperty("signing.keyId")) {
        sign(publishing.publications["maven"])
    }
}

// Zip the 'ru' folder from staging-deploy for manual upload
tasks.register<Zip>("zipStaging") {
    dependsOn("publishMavenPublicationToStagingRepository")
    from("${layout.buildDirectory.get().asFile}/staging-deploy/ru")
    into("ru")
    archiveFileName.set("my-copilot-models-${version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dists"))
}
