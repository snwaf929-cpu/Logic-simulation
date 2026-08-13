plugins {
    java
}

group = "com.foreverspark"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "Runs dependency-free core logic self-tests."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.foreverspark.logicsim.tools.LogicSelfTest")
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Runs an early event-driven logic benchmark."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.foreverspark.logicsim.tools.BenchmarkMain")
}
