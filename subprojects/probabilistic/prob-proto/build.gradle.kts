plugins {
    id("kotlin-common")
    id("java-common")
    id("cli-tool")

    kotlin("plugin.serialization") version "1.5.31"
}

dependencies {
    implementation(project(":theta-solver"))
    implementation(project(":theta-core"))
    implementation(project(":theta-common"))
    implementation(project(":theta-analysis"))
    implementation(project(":theta-cfa-analysis"))
    implementation(project(":theta-solver-z3"))
    implementation(project(":theta-xcfa"))
    implementation(project(":theta-c-frontend"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.1.0-RC")
    implementation("io.insert-koin:koin-core:3.1.5")
}

application {
    mainClassName = "hu.bme.mit.theta.prob.ProbCli"
}
