package hu.bme.mit.theta.probabilistic.gamesolvers

interface ExpandableNode<N: ExpandableNode<N>> {
    fun isExpanded(): Boolean
    fun expand(): Pair<List<N>, List<N>> //returns: (newlyExpanded, revisited), ensures: reciever.isExpanded
}