package hu.bme.mit.theta.probabilistic.gamesolvers

interface ExpandableNode<N: ExpandableNode<N>> {
    fun isExpanded(): Boolean

    /**
     * Returns: (newlyExpanded, revisited)
     * Ensures: receiver.isExpanded
     */
    fun expand(): Pair<List<N>, List<N>>
}