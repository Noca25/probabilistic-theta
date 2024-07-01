plugins {
    id("java-common")
    id("cli-tool")
}

dependencies {
    implementation(project(":theta-xcfa"))
    implementation(project(":theta-xcfa-analysis"))
    implementation(project(":theta-solver-z3"))
    implementation(project(":theta-cfa-analysis"))
    implementation(project(":theta-cfa"))
    implementation(project(":theta-cfa-cli"))
}

application {
    mainClassName = "hu.bme.mit.theta.xcfa.cli.stateless.XcfaCli"
}
