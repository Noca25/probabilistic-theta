package hu.bme.mit.theta.analysis.ltl

import hu.bme.mit.theta.analysis.buchi.AutomatonBuilder
import hu.bme.mit.theta.analysis.buchi.BuchiAutomaton
import hu.bme.mit.theta.analysis.dsl.gen.LTLGrammarLexer
import hu.bme.mit.theta.analysis.dsl.gen.LTLGrammarParser
import hu.bme.mit.theta.core.decl.VarDecl
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Scanner


class LTL2Buchi(
    val converterCommand: String = "ltl2tgba"
) {

    fun convert(formula: String, vars: HashMap<String, VarDecl<*>>, literalToIntMap: HashMap<String, Int>): BuchiAutomaton {
        val ltlLexer = LTLGrammarLexer(CharStreams.fromString(formula))
        val ltlTokenStream = CommonTokenStream(ltlLexer)
        val ltlParser = LTLGrammarParser(ltlTokenStream)
        val ltlModel: LTLGrammarParser.ModelContext = ltlParser.model()
        val toStringVisitor = ToStringVisitor(APGeneratorVisitor(vars, literalToIntMap))
        val ltlExpr = toStringVisitor.visitModel(ltlModel)

        val processBuilder = ProcessBuilder(converterCommand, "-CB", "-f !($ltlExpr)")
        val process = processBuilder.start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val stringbuilder = StringBuilder()
        var line: String? = null
        while (reader.readLine().also { line = it } != null) {
            stringbuilder.append(line)
            stringbuilder.append(System.lineSeparator())
        }
        val result = stringbuilder.toString()

        val builder = AutomatonBuilder()
        builder.setAps(toStringVisitor.getAps())
        val automaton: BuchiAutomaton = builder.parseAutomaton(ByteArrayInputStream(result.toByteArray()))
        process.waitFor()
        return automaton
    }

}