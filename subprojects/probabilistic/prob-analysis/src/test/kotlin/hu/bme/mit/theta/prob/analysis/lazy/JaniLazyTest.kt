package hu.bme.mit.theta.prob.analysis.lazy

import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.prob.analysis.direct.DirectChecker
import hu.bme.mit.theta.prob.analysis.direct.SMDPDirectChecker
import hu.bme.mit.theta.prob.analysis.jani.SMDPProperty
import hu.bme.mit.theta.prob.analysis.jani.SMDPState
import hu.bme.mit.theta.prob.analysis.jani.extractSMDPTask
import hu.bme.mit.theta.prob.analysis.jani.model.Model
import hu.bme.mit.theta.prob.analysis.jani.model.json.JaniModelMapper
import hu.bme.mit.theta.prob.analysis.jani.toSMDP
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.BRTDPStrategy
import hu.bme.mit.theta.probabilistic.*
import hu.bme.mit.theta.probabilistic.gamesolvers.MDPBRTDPSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.SGSolutionInitializer
import hu.bme.mit.theta.probabilistic.gamesolvers.randomSelection
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

class JaniLazyTest {

    @Test
    fun runOne() {
        val f = Paths.get("E:\\egyetem\\dipterv\\qcomp\\benchmarks\\mdp\\consensus\\consensus.2.jani")
        println(f.fileName)
        val model = JaniModelMapper().readValue(f.toFile(), Model::class.java).toSMDP(
            mapOf(
                "N" to "3", "K" to "2", "reset" to "true",
                "delay" to "3", "deadline" to "50", "COL" to "1",
                "ITERATIONS" to "100"
            )
        )
        val solver = Z3SolverFactory.getInstance().createSolver()
        val itpSolver = Z3SolverFactory.getInstance().createItpSolver()
        for (property in model.properties) {
            if (property is SMDPProperty.ProbabilityProperty || property is SMDPProperty.ProbabilityThresholdProperty) {
                val task = extractSMDPTask(property)
                if (task.goal == Goal.MIN) continue
                if (true) {
                    val directChecker = SMDPDirectChecker(
                        solver = solver,
                        verboseLogging = true,
                        threshold = 1e-7
                    )
                    //val directResult = directChecker.check(model, task, ::VISolver)

                    val directResult = directChecker.check(model, task) { threshold: Double,
                                                                          reward: GameRewardFunction<DirectChecker.Node<SMDPState<ExplState>>, FiniteDistribution<DirectChecker.Node<SMDPState<ExplState>>>>,
                                                                          initializer: SGSolutionInitializer<DirectChecker.Node<SMDPState<ExplState>>, FiniteDistribution<DirectChecker.Node<SMDPState<ExplState>>>> ->
                        val targetRewardFunction =
                            reward as? TargetRewardFunction<DirectChecker.Node<SMDPState<ExplState>>, FiniteDistribution<DirectChecker.Node<SMDPState<ExplState>>>>
                                ?: throw UnsupportedOperationException("BRTDP unsupported for general rewards")
                        MDPBRTDPSolver(threshold, targetRewardFunction, StochasticGame<DirectChecker.Node<SMDPState<ExplState>>, FiniteDistribution<DirectChecker.Node<SMDPState<ExplState>>>>::randomSelection)
                    }

                    println("${property.name}: $directResult")
                    break
                } else {
                    val result = SMDPLazyChecker(
                        smtSolver = solver,
                        itpSolver = itpSolver,
                        algorithm = SMDPLazyChecker.Algorithm.BVI,
                        verboseLogging = true,
                        useMay = true,
                        useMust = false,
                        useSeq = false,
                        useGameRefinement = true,
                        brtdpStrategy = BRTDPStrategy.RANDOM,
                        useQualitativePreprocessing = false
                    ).checkExpl(model, task)
                    println("${property.name}: $result")
                }
            } else {
                println("Non-probability property found")
            }
        }
    }

    @Ignore @Test
    fun runAll() {
        val dir2 = Paths.get("E:\\egyetem\\dipterv\\qcomp\\benchmarks\\mdp")
        for (d in dir2.listDirectoryEntries()) {
            if (d.isDirectory()) {
                if (d.fileName.toString() == "blocksworld")
                    continue // skip these for now - bw.5 is qualitative, bw.10 has 2000+ commands and storm cannot solve it...
                for (f in d.listDirectoryEntries("*.jani")) {
                    println(f.fileName)
                    val model = JaniModelMapper().readValue(f.toFile(), Model::class.java).toSMDP(
                        mapOf(
                            "N" to "3", "K" to "4", "B" to "1", "ITERATIONS" to "1", "MAXSTEPS" to "1",
                            "energy_capacity" to "1", "deadline" to "1", "delay" to "1", "GOLD_TO_COLLECT" to "1",
                            "GEM_TO_COLLECT" to "1", "reset" to "false"
                        )
                    )
                    val solver = Z3SolverFactory.getInstance().createSolver()
                    val itpSolver = Z3SolverFactory.getInstance().createItpSolver()
                    for (property in model.properties) {
                        if (property is SMDPProperty.ProbabilityProperty) {
                            try {
                                val task = extractSMDPTask(property)
                                val result = SMDPLazyChecker(
                                    solver, itpSolver, SMDPLazyChecker.Algorithm.BRTDP,
                                    brtdpStrategy = BRTDPStrategy.DIFF_BASED,
                                    useMay = true, useMust = false
                                ).checkExpl(model, task,)
                                println("${property.name}: $result")
                            } catch (e: IllegalArgumentException) {
                                println(e.message)
                                println("Problematic property: ${property.name}")
                            }
                        } else {
                            println("Non-probability property found")
                        }

                    }
                }
            }
        }
    }
}