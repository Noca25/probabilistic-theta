plugins {
    id("java-common")
    id("antlr-grammar")
    id("kotlin-common")
}

dependencies {
    implementation(project(":theta-common"))
    implementation(project(":theta-core"))
}
