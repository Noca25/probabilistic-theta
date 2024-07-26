package hu.bme.mit.theta.probabilistic.gamesolvers

interface ExpandableNode<N: ExpandableNode<N>> {
    fun isExpanded(): Boolean

    /**
     * Returns: (newlyDiscovered, revisited)
     * Ensures: receiver.isExpanded
     */
    fun expand(): ExpansionResult<N>
}

data class ExpansionResult<N>(
    val newlyDiscovered: List<N>,
    val revisited: List<N>
)