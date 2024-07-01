plugins {
    id("java-common")
}

dependencies {
    implementation(project(":theta-analysis"))
    implementation(project(":theta-cfa"))
    implementation(project(":theta-common"))
    implementation(project(":theta-core"))
    testImplementation(project(":theta-solver-z3"))
    testImplementation(project(":theta-solver-smtlib"))
}
