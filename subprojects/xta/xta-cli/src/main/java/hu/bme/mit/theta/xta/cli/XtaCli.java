/*
 *  Copyright 2017 Budapest University of Technology and Economics
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package hu.bme.mit.theta.xta.cli;

import java.io.*;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import hu.bme.mit.theta.analysis.Action;
import hu.bme.mit.theta.analysis.Counterexample;
import hu.bme.mit.theta.analysis.State;
import hu.bme.mit.theta.analysis.Trace;
import hu.bme.mit.theta.analysis.algorithm.*;
import hu.bme.mit.theta.analysis.unit.UnitPrec;
import hu.bme.mit.theta.analysis.utils.ArgVisualizer;
import hu.bme.mit.theta.analysis.utils.TraceVisualizer;
import hu.bme.mit.theta.analysis.utils.Visualizer;
import hu.bme.mit.theta.common.CliUtils;
import hu.bme.mit.theta.common.logging.Logger;
import hu.bme.mit.theta.common.table.BasicTableWriter;
import hu.bme.mit.theta.common.table.TableWriter;
import hu.bme.mit.theta.common.visualization.Graph;
import hu.bme.mit.theta.common.visualization.writer.GraphvizWriter;
import hu.bme.mit.theta.xta.XtaSystem;
import hu.bme.mit.theta.xta.analysis.XtaAction;
import hu.bme.mit.theta.xta.analysis.XtaState;
import hu.bme.mit.theta.xta.analysis.lazy.ClockStrategy;
import hu.bme.mit.theta.xta.analysis.lazy.DataStrategy;
import hu.bme.mit.theta.xta.analysis.lazy.LazyXtaCheckerFactory;
import hu.bme.mit.theta.xta.analysis.lazy.LazyXtaStatistics;
import hu.bme.mit.theta.xta.dsl.XtaDslManager;

public final class XtaCli {
	private static final String JAR_NAME = "theta-xta.jar";
	private final String[] args;
	private final TableWriter writer;

	@Parameter(names = {"--model", "-m"}, description = "Path of the input model", required = true)
	String model;

	@Parameter(names = {"--discrete", "-d"}, description = "Refinement strategy for discrete variables", required = false)
	DataStrategy dataStrategy = DataStrategy.NONE;

	@Parameter(names = {"--clock", "-c"}, description = "Refinement strategy for clock variables", required = true)
	ClockStrategy clockStrategy;

	@Parameter(names = {"--search", "-s"}, description = "Search strategy", required = true)
	SearchStrategy searchStrategy;

	@Parameter(names = {"--benchmark", "-b"}, description = "Benchmark mode (only print metrics)")
	Boolean benchmarkMode = false;

	@Parameter(names = {"--visualize", "-v"}, description = "Write proof or counterexample to file in dot format")
	String dotfile = null;

	@Parameter(names = {"--header", "-h"}, description = "Print only a header (for benchmarks)", help = true)
	boolean headerOnly = false;

	@Parameter(names = "--stacktrace", description = "Print full stack trace in case of exception")
	boolean stacktrace = false;

	@Parameter(names = "--version", description = "Display version", help = true)
	boolean versionInfo = false;

	public XtaCli(final String[] args) {
		this.args = args;
		this.writer = new BasicTableWriter(System.out, ",", "\"", "\"");
	}

	public static void main(final String[] args) {
		final XtaCli mainApp = new XtaCli(args);
		mainApp.run();
	}

	private void run() {
		try {
			JCommander.newBuilder().addObject(this).programName(JAR_NAME).build().parse(args);
		} catch (final ParameterException ex) {
			System.out.println("Invalid parameters, details:");
			System.out.println(ex.getMessage());
			ex.usage();
			return;
		}

		if (headerOnly) {
			LazyXtaStatistics.writeHeader(writer);
			return;
		}

		if (versionInfo) {
			CliUtils.printVersion(System.out);
			return;
		}

		try {
			final XtaSystem system = loadModel();
			final var checker =
					LazyXtaCheckerFactory.create(system, dataStrategy,
					clockStrategy, searchStrategy);
			final SafetyResult<? extends State, ? extends Action, ? extends ARG<? extends State, ? extends Action>,
					? extends Trace<? extends State, ? extends Action>>
					result = check(checker);
			printResult(result);
			if (dotfile != null) {
				writeVisualStatus(result, dotfile, ArgVisualizer.getDefault(), TraceVisualizer.getDefault());
			}
		} catch (final Throwable ex) {
			printError(ex);
			System.exit(1);
		}
	}

	private <S extends State, A extends Action, AA extends Abstraction<? extends S, ? extends A>, C extends Counterexample<? extends S, ? extends A>>
	SafetyResult<? extends S, ? extends A, AA, C> check(SafetyChecker<? extends S, ? extends A, UnitPrec, AA, C> checker) throws Exception {
		try {
			return checker.check(UnitPrec.getInstance());
		} catch (final Exception ex) {
			String message = ex.getMessage() == null ? "(no message)" : ex.getMessage();
			throw new Exception("Error while running algorithm: " + ex.getClass().getSimpleName() + " " + message, ex);
		}
	}

	private XtaSystem loadModel() throws Exception {
		try {
			try (InputStream inputStream = new FileInputStream(model)) {
				return XtaDslManager.createSystem(inputStream);
			}
		} catch (Exception ex) {
			throw new Exception("Could not parse XTA: " + ex.getMessage(), ex);
		}
	}

	private void printResult(final SafetyResult<?, ?, ?, ?> result) {
		final LazyXtaStatistics stats = (LazyXtaStatistics) result.getStats().get();
		if (benchmarkMode) {
			stats.writeData(writer);
		} else {
			System.out.println(stats.toString());
		}
	}

	private void printError(final Throwable ex) {
		final String message = ex.getMessage() == null ? "" : ": " + ex.getMessage();
		if (benchmarkMode) {
			writer.cell("[EX] " + ex.getClass().getSimpleName() + message);
		} else {
			System.out.println(ex.getClass().getSimpleName() + " occurred, message: " + message);
			if (stacktrace) {
				final StringWriter errors = new StringWriter();
				ex.printStackTrace(new PrintWriter(errors));
				System.out.println("Trace:");
				System.out.println(errors);
			}
			else {
				System.out.println("Use --stacktrace for stack trace");
			}
		}
	}

	private <S extends State, A extends Action,
			AA extends Abstraction<? extends S, ? extends A>,
			C extends Counterexample<? extends S, ? extends A>>
	void writeVisualStatus(
			final SafetyResult<? extends S, ? extends A, ? extends AA, ? extends C> status, final String filename,
			Visualizer<AA> abstractionVisualizer, Visualizer<C> cexVisualizer
	)
			throws FileNotFoundException {
		final Graph graph = status.isSafe() ? abstractionVisualizer.visualize(status.asSafe().getAbstraction())
				: cexVisualizer.visualize(status.asUnsafe().getTrace());
		GraphvizWriter.getInstance().writeFile(graph, filename);
	}

}
