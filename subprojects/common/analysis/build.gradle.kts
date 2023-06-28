plugins {
    id("kotlin-common")
    id("antlr-grammar")
}

dependencies {
    implementation(project(":theta-common"))
    implementation(project(":theta-core"))
    implementation(project(":theta-solver"))
    testImplementation(project(":theta-solver-z3"))
    testImplementation(project(":theta-xsts"))
    implementation(files("lib/jhoafparser-1.1.1"))
}
