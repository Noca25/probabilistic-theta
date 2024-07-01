plugins {
    id("kotlin-common")
}
dependencies {
    implementation(project(":theta-core"))
    implementation(project(":theta-prob-core"))
    implementation(project(":theta-analysis"))
    implementation(project(":theta-xta-analysis"))
    implementation(project(":theta-solver"))
    implementation(project(":theta-common"))
    testImplementation(project(":theta-solver-z3"))

    api("com.fasterxml.jackson.core:jackson-databind:2.9.7")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.7")
}

//tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
//    kotlinOptions {
//        freeCompilerArgs += "-Xself-upper-bound-inference"
//    }
//}