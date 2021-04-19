package hu.bme.mit.theta.analysis.utils;

import hu.bme.mit.theta.common.visualization.Graph;

public interface Visualizer<T> {
    Graph visualize(T inp);
}
