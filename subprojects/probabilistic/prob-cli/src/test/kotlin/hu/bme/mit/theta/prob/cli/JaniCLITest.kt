package hu.bme.mit.theta.prob.cli

import org.junit.Test

class JaniCLITest {
    @Test
    fun test() {
        JaniCLI().parse(listOf(
            "-i", "../models/jamini.jani",
            "-p", "",
            "--property", "goal",
            "--domain", "EXPL",
            "--approximation", "EXACT",
            "--seq"
        ))
    }
}