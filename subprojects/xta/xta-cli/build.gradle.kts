plugins {
    id("java-common")
    id("cli-tool")
}

dependencies {
    implementation(project(":theta-xta"))
    implementation(project(":theta-xta-analysis"))
    implementation(project(":theta-solver-z3"))
}

application {
    mainClassName = "hu.bme.mit.theta.xta.cli.XtaCli"
}
