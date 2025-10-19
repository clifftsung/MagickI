plugins {
    kotlin("jvm") version "2.2.20"
    `java-library`
}

group = "com.mellonita.magicki"
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(11))
    modularity.inferModulePath.set(true)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(tasks.named("compileKotlin"))
    val kotlinOutput = layout.buildDirectory.dir("classes/kotlin/main")
    inputs.dir(kotlinOutput)
    doFirst {
        val patchedPath = kotlinOutput.get().asFile.absolutePath.replace('\\', '/')
        val preserved = options.compilerArgs.filterNot {
            it == "--patch-module" || it.startsWith("com.mellonita.magicki=")
        }
        options.compilerArgs.clear()
        options.compilerArgs.addAll(preserved)
        options.compilerArgs.addAll(
            listOf("--patch-module", "com.mellonita.magicki=$patchedPath")
        )
    }
}
