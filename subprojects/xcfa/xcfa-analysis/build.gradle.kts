plugins {
    id("java-common")
}

dependencies {
    implementation(project(":theta-cfa-analysis"))
    implementation(project(":theta-xcfa"))
    implementation(project(":theta-core"))
    implementation(project(":theta-cat"))
    implementation(project(":theta-common"))
    implementation(project(":theta-solver-smtlib"))
}
