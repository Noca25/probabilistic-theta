plugins {
    id("kotlin-common")
    id("cli-tool")
}
dependencies {
    implementation(project(":theta-solver-z3"))
    implementation(project(":theta-prob-analysis"))
    implementation(project(":theta-prob-core"))

    implementation("com.github.ajalt.clikt:clikt:3.4.0")
}

application {
    mainClassName = "hu.bme.mit.theta.prob.cli.JaniCliKt"
}