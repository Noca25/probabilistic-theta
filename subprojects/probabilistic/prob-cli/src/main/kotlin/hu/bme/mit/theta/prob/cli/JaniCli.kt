package hu.bme.mit.theta.prob.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.enum
import hu.bme.mit.theta.prob.analysis.direct.SMDPDirectChecker
import hu.bme.mit.theta.prob.analysis.direct.SMDPDirectCheckerGame
import hu.bme.mit.theta.prob.analysis.jani.SMDPProperty
import hu.bme.mit.theta.prob.analysis.jani.extractSMDPTask
import hu.bme.mit.theta.prob.analysis.jani.model.Model
import hu.bme.mit.theta.prob.analysis.jani.model.json.JaniModelMapper
import hu.bme.mit.theta.prob.analysis.jani.toSMDP
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker
import hu.bme.mit.theta.prob.analysis.lazy.SMDPLazyChecker.Algorithm
import hu.bme.mit.theta.prob.cli.JaniCLI.Domain.*
import hu.bme.mit.theta.probabilistic.gamesolvers.*
import hu.bme.mit.theta.solver.z3.Z3SolverFactory
import kotlin.io.path.Path

class JaniCLI : CliktCommand() {

    enum class Domain {
        PRED, EXPL, NONE
    }
    enum class Approximation(val useMay: Boolean, val useMust: Boolean) {
        LOWER(false, true), UPPER(true, false), EXACT(true, true)
    }

    val model: String by option("-m", "--model", "-i", "--input",
        help = "Path to the input JANI file."
    ).required()
    val parameters by option( "-p", "--parameters",
        help = "Specifies model parameters - constants which do not have values defined in the model."
    )
    val threshold by option(
        help = "Threshold used for convergence checking."
    ).double().default(1e-7)
    val algorithm by option(
        help = "MDP solver algorithm to use."
    ).enum<Algorithm>().default(Algorithm.BVI)
    val domain by option(
        help = "Abstract domain to use. NONE means direct model checking without abstraction."
    ).enum<Domain>().required()
    val approximation by option(
        help = "Approximation direction to use."
    ).enum<Approximation>().required()
    val property: String? by option(
        help = "Name of the JANI property to check. All properties are checked in sequence if not given."
    )
    val strategy by option(
        help = "Successor computation strategy for BRTDP."
    ).enum<SMDPLazyChecker.BRTDPStrategy>().default(SMDPLazyChecker.BRTDPStrategy.DIFF_BASED)
    val verbose by option().flag()
    val preproc by option("--preproc",
        help = "Use qualitative preprocessing, i.e. precompute almost sure reachability and avoidance."
    ).flag("--nopreproc", default = true)
    val sequenceInterpolation by option("--seq",
        help = "Use sequence interpolation for refinement in lazy abstraction."
    ).flag("--noseq", default = true)
    val gameRefinement by option("--game",
        help = "Turns game-based abstraction-refinement on if lazy abstraction is used."
    ).flag("--nogame", default = false)

    override fun run() {

        val modelPath = Path(model)
        val parameters =
            parameters
                ?.split(",")
                ?.filter { it.isNotEmpty() }
                ?.map { it.split("=") }
                ?.associate { it[0] to it[1] }
                ?: mapOf()
        val model =
            JaniModelMapper()
                .readValue(modelPath.toFile(), Model::class.java)
                .toSMDP(parameters)
        val solver = Z3SolverFactory.getInstance().createSolver()
        val itpSolver = Z3SolverFactory.getInstance().createItpSolver()

        for (prop in model.properties) {
            if(this.property != null && this.property != prop.name)
                continue
            if (prop is SMDPProperty.ProbabilityProperty || prop is SMDPProperty.ProbabilityThresholdProperty) {
                val task =
                    try {
                        extractSMDPTask(prop)
                    } catch (e: UnsupportedOperationException) {
                        if(this.property != null)
                            throw RuntimeException("Error: property ${prop.name} unsupported")
                        println("Error: property ${prop.name} unsupported, moving on")
                        continue
                    }
                val lazyChecker = SMDPLazyChecker(
                    solver,
                    itpSolver,
                    algorithm,
                    verbose,
                    strategy,
                    approximation.useMay,
                    approximation.useMust,
                    threshold,
                    sequenceInterpolation,
                    gameRefinement,
                    preproc
                )
                val directChecker = SMDPDirectChecker(solver, verbose, preproc)
                val successorSelection = when(strategy) {
                    SMDPLazyChecker.BRTDPStrategy.DIFF_BASED -> SMDPDirectCheckerGame::diffBasedSelection
                    SMDPLazyChecker.BRTDPStrategy.RANDOM -> SMDPDirectCheckerGame::randomSelection
                    SMDPLazyChecker.BRTDPStrategy.ROUND_ROBIN -> TODO()
                    SMDPLazyChecker.BRTDPStrategy.WEIGHTED_RANDOM -> SMDPDirectCheckerGame::weightedRandomSelection
                }
                val quantSolverSupplier = when(algorithm) {
                    Algorithm.BVI -> MDPBVISolver.supplier(threshold)
                    Algorithm.VI -> VISolver.supplier(threshold)
                    Algorithm.BRTDP -> MDPBRTDPSolver.supplier(threshold, successorSelection) {
                      iteration, reachedSet, linit, uinit ->
                        if (verbose) {
                            println("Iteration $iteration: [$linit, $uinit], ${reachedSet.size} nodes")
                        }
                    }
                }
                val result = when(domain) {
                    PRED -> lazyChecker.checkPred(
                        model, task, )
                    EXPL -> lazyChecker.checkExpl(model, task)
                    NONE -> directChecker.check(model, task, quantSolverSupplier)
                }
                println("${prop.name}: $result")
            } else {
                if(this.property != null)
                    throw RuntimeException("Error: Non-probability property ${prop.name} unsupported")
                println("Non-probability property found")
            }
        }
    }
}

fun main(args: Array<String>) = JaniCLI().main(args)