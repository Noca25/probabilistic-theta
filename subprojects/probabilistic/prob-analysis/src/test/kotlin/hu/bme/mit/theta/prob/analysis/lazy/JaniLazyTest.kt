package hu.bme.mit.theta.prob.analysis.lazy

import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.prob.analysis.jani.SMDPProperty
import hu.bme.mit.theta.prob.analysis.jani.extractSMDPReachabilityTask
import hu.bme.mit.theta.prob.analysis.jani.model.Model
import hu.bme.mit.theta.prob.analysis.jani.model.json.JaniModelMapper
import hu.bme.mit.theta.prob.analysis.jani.toSMDP
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.BRTDPStrategy
import hu.bme.mit.theta.probabilistic.Goal
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

class JaniLazyTest {

    @Test
    fun runOne() {
        val f = Paths.get("F:\\egyetem\\dipterv\\qcomp\\benchmarks\\mdp\\consensus\\consensus.2.jani")
        println(f.fileName)
        val model = JaniModelMapper().readValue(f.toFile(), Model::class.java).toSMDP(
            mapOf(
                "N" to "3", "K" to "2", "reset" to "false", "deadline" to "10",
            )
        )
        val solver = Z3SolverFactory.getInstance().createSolver()
        val itpSolver = Z3SolverFactory.getInstance().createItpSolver()
        val ucSolver = Z3SolverFactory.getInstance().createUCSolver()
        for (property in model.properties) {
            if (property is SMDPProperty.ProbabilityProperty || property is SMDPProperty.ProbabilityThresholdProperty) {
                val task = extractSMDPReachabilityTask(property)
                //if (property.name != "c2") continue
                if(model.getAllVars().size < 30) {
                    ImmutableValuation.experimental = true
                    ImmutableValuation.declOrder = model.getAllVars().toTypedArray()
                }

                val usePred = false
                val exact = false
                val exactError = true
                val game = true
                val merge = false
                val algorithm = SMDPLazyChecker.Algorithm.BRTDP
                val useQualitativePreprocessing = true

                val result = SMDPLazyChecker(
                    smtSolver = solver,
                    itpSolver = itpSolver,
                    ucSolver = ucSolver,
                    algorithm = algorithm,
                    verboseLogging = true,
                    useMayStandard = true,
                    useMustStandard = exact,
                    useMayTarget =  exactError || exact || task.goal == Goal.MAX,
                    useMustTarget = exactError || exact || task.goal == Goal.MIN,
                    useSeq = true,
                    useGameRefinement = game,
                    brtdpStrategy = BRTDPStrategy.DIFF_BASED,
                    useQualitativePreprocessing = useQualitativePreprocessing,
                    mergeSameSCNodes = merge
                ).let {
                    if (usePred) it.checkPred(model, task)
                    else it.checkExpl(model, task)
                }
                println("${property.name}: $result")
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
                    val ucSolver = Z3SolverFactory.getInstance().createUCSolver()
                    for (property in model.properties) {
                        if (property is SMDPProperty.ProbabilityProperty) {
                            try {
                                val task = extractSMDPReachabilityTask(property)
                                val result = SMDPLazyChecker(
                                    solver, itpSolver, ucSolver, SMDPLazyChecker.Algorithm.BRTDP,
                                    brtdpStrategy = BRTDPStrategy.DIFF_BASED,
                                    useMayStandard = true,
                                    useMustStandard = false,
                                    useMayTarget = true,
                                    useMustTarget = false,
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