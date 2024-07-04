package hu.bme.mit.theta.probabilistic

enum class Goal(
    val select: (Collection<Double>) -> Double?,
) {
    MAX(Collection<Double>::maxOrNull) {
        override fun <T> argSelect(values: Map<T, Double>): T? = values.maxByOrNull { it.value }?.key
    },
    MIN(Collection<Double>::minOrNull) {
        override fun <T> argSelect(values: Map<T, Double>): T? = values.minByOrNull { it.value }?.key
    };

    fun opposite() = when(this) {
        MAX -> MIN
        MIN -> MAX
    }

    abstract fun <T> argSelect(values: Map<T, Double>): T?
}

fun setGoal(vararg mapping: Pair<Int, Goal>): ((Int)-> Goal) {
    val map = mapping.toMap()
    return { map.getOrElse(it) {throw IllegalStateException("Unknown goal for player $it")} }
}

class AnalysisTask<N, A>(
    val game: StochasticGame<N, A>,
    val goal: (Int) -> Goal,
    val discountFactor: Double = 1.0
)

interface StochasticGameSolver<N, A> {
    fun solve(analysisTask: AnalysisTask<N, A>): Map<N, Double>
    fun solveWithStrategy(analysisTask: AnalysisTask<N, A>): Pair<Map<N, Double>, Map<N, A>>
}

data class RangeSolution<N>(
    val lower: Map<N, Double>,
    val upper: Map<N, Double>
)

