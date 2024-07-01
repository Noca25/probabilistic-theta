plugins {
    id("java-common")
    id("cli-tool")
}

dependencies {
    implementation(project(":theta-cfa"))
    implementation(project(":theta-cfa-analysis"))
    implementation(project(":theta-solver-z3"))
    implementation(project(":theta-solver-smtlib"))
}

application {
    mainClassName = "hu.bme.mit.theta.cfa.cli.CfaCli"
}
