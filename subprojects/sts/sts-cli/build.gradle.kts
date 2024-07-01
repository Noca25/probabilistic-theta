plugins {
    id("java-common")
    id("cli-tool")
}

dependencies {
    implementation(project(":theta-sts"))
    implementation(project(":theta-sts-analysis"))
    implementation(project(":theta-solver-z3"))
}

application {
    mainClassName = "hu.bme.mit.theta.sts.cli.StsCli"
}
