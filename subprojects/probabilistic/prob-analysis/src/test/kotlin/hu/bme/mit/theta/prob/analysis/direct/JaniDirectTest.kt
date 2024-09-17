package hu.bme.mit.theta.prob.analysis.direct

import hu.bme.mit.theta.prob.analysis.jani.SMDPProperty
import hu.bme.mit.theta.prob.analysis.jani.extractSMDPExpectedRewardTask
import hu.bme.mit.theta.prob.analysis.jani.extractSMDPReachabilityTask
import hu.bme.mit.theta.prob.analysis.jani.model.Model
import hu.bme.mit.theta.prob.analysis.jani.model.json.JaniModelMapper
import hu.bme.mit.theta.prob.analysis.jani.toSMDP
import hu.bme.mit.theta.probabilistic.gamesolvers.MDPBRTDPSolver
import hu.bme.mit.theta.probabilistic.gamesolvers.MDPBVISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.VISolver
import hu.bme.mit.theta.probabilistic.gamesolvers.diffBasedSelection
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import org.junit.Test
import java.nio.file.Paths

class JaniDirectTest {

    @Test
    fun runOne() {
        val f = Paths.get("F:\\egyetem\\dipterv\\qcomp\\benchmarks\\mdp\\csma\\csma.2-2.jani")
        println(f.fileName)
        val model = JaniModelMapper().readValue(f.toFile(), Model::class.java).toSMDP(
            modelParameterStrings = mapOf(
                "N" to "3", "K" to "2", "reset" to "false", "deadline" to "10",
            )
        )
        val solver = Z3SolverFactory.getInstance().createSolver()
        for (property in model.properties) {
            if(property is SMDPProperty.ExpectationProperty) {
                val task = extractSMDPExpectedRewardTask(property)
                if (property.name != "time_max") continue
                val useBRTDP = false
                val directChecker = SMDPDirectChecker(
                    solver = solver,
                    verboseLogging = true,
                    useQualitativePreprocessing = !useBRTDP
                )
                val directResult = directChecker.check(
                    model, task,
                    quantSolver = if (useBRTDP)
                        MDPBRTDPSolver(
                            SMDPDirectCheckerGame::diffBasedSelection,
                            1e-7,
                            true,
                            MDPBRTDPSolver.UpdateStrategy.DYNAMIC
                        ) { iteration, reachedSet, linit, uinit ->
                            println("$iteration: ${reachedSet.size} [$linit, $uinit]")
                        } else VISolver(1e-7)
                )
                println("${property.name}: $directResult")
            }
            else if (property is SMDPProperty.ProbabilityProperty || property is SMDPProperty.ProbabilityThresholdProperty) {
                val task = extractSMDPReachabilityTask(property)
                if (property.name != "c2") continue
                val useBRTDP = true
                val directChecker = SMDPDirectChecker(
                    solver = solver,
                    verboseLogging = true,
                    useQualitativePreprocessing = !useBRTDP
                )
                val directResult = directChecker.check(
                    model, task,
                    quantSolver = if (useBRTDP)
                        MDPBRTDPSolver(
                            SMDPDirectCheckerGame::diffBasedSelection,
                            1e-7,
                            true,
                            MDPBRTDPSolver.UpdateStrategy.DYNAMIC
                        ) { iteration, reachedSet, linit, uinit ->
                            println("$iteration: ${reachedSet.size} [$linit, $uinit]")
                        } else MDPBVISolver(1e-7)
                )
                println("${property.name}: $directResult")
            }
        }
    }

}