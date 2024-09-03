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

    @Test
    fun fullTest() {
        JaniCLI().parse(listOf(
            "-i", "F:\\egyetem\\dipterv\\qcomp\\benchmarks\\mdp\\beb\\beb.3-4.jani",
            "-p",
            "N=3,K=2,reset=false,deadline=10",
            "--abstraction", "BT",
            "--algorithm", "VI",
            "--domain", "PRED",
            "--approximation", "UPPER",
            "--elim"
        ))
    }
}