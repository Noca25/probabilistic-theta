plugins {
    id("java-common")
    id("cli-tool")
}

dependencies {
    implementation(project(":theta-xsts"))
    implementation(project(":theta-xsts-analysis"))
    implementation(project(":theta-solver-z3"))
}

application {
    mainClassName = "hu.bme.mit.theta.xsts.cli.XstsCli"
}
