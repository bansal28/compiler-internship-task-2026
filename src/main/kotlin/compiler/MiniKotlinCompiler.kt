//package org.example.compiler
//
//import MiniKotlinBaseVisitor
//import MiniKotlinParser
//
//class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
//
//    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
//        return """
//            public class $className {
//                public static void main(String[] args) {
//                  return;
//                }
//            }
//        """.trimIndent()
//    }
//
//}


package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private data class FuncSig(
        val name: String,
        val ret: String,
        val params: List<Pair<String, String>>
    )

    private data class VarInfo(val javaType: String, val isVar: Boolean)

    private class J {
        private val sb = StringBuilder()
        var ind = 0
        private fun pad() = "  ".repeat(ind)
        fun line(s: String = "") { sb.append(pad()).append(s).append('\n') }
        inline fun block(open: String, close: String = "}", body: () -> Unit) {
            line(open)
            ind++
            body()
            ind--
            line(close)
        }
        override fun toString() = sb.toString()
    }

    private val funcs = linkedMapOf<String, FuncSig>()
    private val scopes = ArrayDeque<MutableMap<String, VarInfo>>()
    private var id = 0
    private fun fresh(prefix: String = "arg") = "${prefix}${id++}"

    private fun push() = scopes.addLast(mutableMapOf())
    private fun pop() = scopes.removeLast()
    private fun declare(name: String, info: VarInfo) { scopes.last()[name] = info }
    private fun lookup(name: String): VarInfo? {
        for (i in scopes.size - 1 downTo 0) {
            val v = scopes.elementAt(i)[name]
            if (v != null) return v
        }
        return null
    }
    private fun isVar(name: String) = lookup(name)?.isVar == true

    // Unit -> Void (no Unit class needed in Java output)
    private fun javaType(t: MiniKotlinParser.TypeContext): String = when (t.text) {
        "Int" -> "Integer"
        "String" -> "String"
        "Boolean" -> "Boolean"
        "Unit" -> "Void"
        else -> "Object"
    }

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        funcs.clear()
        scopes.clear()
        id = 0

        // Collect signatures
        for (f in program.functionDeclaration()) {
            val name = f.IDENTIFIER().text
            val ret = javaType(f.type())
            val params = f.parameterList()?.parameter()?.map {
                it.IDENTIFIER().text to javaType(it.type())
            } ?: emptyList()
            funcs[name] = FuncSig(name, ret, params)
        }

        val j = J()
        j.line("import java.util.Objects;")
        j.line()
        j.block("public class $className {") {

            // Ref for 'var' across Java lambdas
            j.block("public static final class Ref<T> {") {
                j.line("public T v;")
                j.line("public Ref(T v) { this.v = v; }")
            }
            j.line()

            // Trampoline for CPS-safe while loops
            j.block("public static final class Trampoline {") {
                j.line("public Runnable next;")
                j.block("public void run() {") {
                    j.line("while (next != null) {")
                    j.ind++
                    j.line("Runnable r = next;")
                    j.line("next = null;")
                    j.line("r.run();")
                    j.ind--
                    j.line("}")
                }
            }
            j.line()

            // Emit CPS functions
            for (f in program.functionDeclaration()) {
                emitFunction(j, f)
                j.line()
            }

            // Java entrypoint calls MiniKotlin main() if present
            j.block("public static void main(String[] args) {") {
                val ms = funcs["main"]
                if (ms != null && ms.params.isEmpty()) {
                    val u = fresh("u")
                    j.line("main(($u) -> { });")
                }
                j.line("return;")
            }
        }

        return j.toString()
    }

    private fun emitFunction(j: J, f: MiniKotlinParser.FunctionDeclarationContext) {
        val name = f.IDENTIFIER().text
        val sig = funcs[name] ?: error("Missing signature for $name")

        val params = buildList {
            for ((pn, pt) in sig.params) add("$pt $pn")
            add("Continuation<${sig.ret}> __continuation")
        }.joinToString(", ")

        j.block("public static void $name($params) {") {
            push()
            for ((pn, pt) in sig.params) declare(pn, VarInfo(pt, isVar = false))

            emitBlock(j, f.block(), sig.ret, "__continuation") {
                if (sig.ret == "Void") {
                    j.line("__continuation.accept(null);")
                    j.line("return;")
                } else {
                    j.line("""throw new RuntimeException("Missing return in function $name");""")
                }
            }

            pop()
        }
    }

    private fun emitBlock(
        j: J,
        b: MiniKotlinParser.BlockContext,
        retType: String,
        kName: String,
        onFallthrough: () -> Unit
    ) {
        push()
        val stmts = b.statement()

        var cont: () -> Unit = onFallthrough
        for (s in stmts.asReversed()) {
            val next = cont
            cont = { emitStmt(j, s, retType, kName, next) }
        }

        cont()
        pop()
    }

    private fun emitStmt(
        j: J,
        s: MiniKotlinParser.StatementContext,
        retType: String,
        kName: String,
        next: () -> Unit
    ) {
        s.variableDeclaration()?.let { vd ->
            val name = vd.IDENTIFIER().text
            val t = javaType(vd.type())
            declare(name, VarInfo(t, isVar = true))

            if (isPure(vd.expression())) {
                j.line("final Ref<$t> $name = new Ref<>(${pure(vd.expression())});")
                next()
            } else {
                j.line("final Ref<$t> $name = new Ref<>(null);")
                emitExpr(j, vd.expression()) { v ->
                    j.line("$name.v = $v;")
                    next()
                }
            }
            return
        }

        s.variableAssignment()?.let { va ->
            val name = va.IDENTIFIER().text
            val target = if (isVar(name)) "$name.v" else name
            if (isPure(va.expression())) {
                j.line("$target = ${pure(va.expression())};")
                next()
            } else {
                emitExpr(j, va.expression()) { v ->
                    j.line("$target = $v;")
                    next()
                }
            }
            return
        }

        s.ifStatement()?.let { ifs ->
            val cond = ifs.expression()
            val thenB = ifs.block(0)
            val elseB = if (ifs.block().size > 1) ifs.block(1) else null

            fun emitThen() {
                j.block("{") { emitBlock(j, thenB, retType, kName, next) }
            }
            fun emitElse() {
                j.block("{") {
                    if (elseB != null) emitBlock(j, elseB, retType, kName, next) else next()
                }
            }

            if (isPure(cond)) {
                j.line("if (${pure(cond)})")
                emitThen()
                j.line("else")
                emitElse()
            } else {
                emitExpr(j, cond) { cv ->
                    j.line("if ($cv)")
                    emitThen()
                    j.line("else")
                    emitElse()
                }
            }
            return
        }

        // ✅ CPS-safe while via trampoline
        s.whileStatement()?.let { wh ->
            val tName = fresh("__t")
            val loopClass = fresh("__Loop")

            j.block("{") {
                j.line("final Trampoline $tName = new Trampoline();")

                j.block("class $loopClass {") {
                    j.block("void step() {") {
                        val cond = wh.expression()

                        val emitDecision: (String) -> Unit = { cv ->
                            j.line("if (!($cv)) {")
                            j.ind++
                            // loop ends -> run code after while
                            next()
                            j.line("return;")
                            j.ind--
                            j.line("} else {")
                            j.ind++

                            val bodyFallthrough = {
                                j.line("$tName.next = () -> this.step();")
                                j.line("return;")
                            }

                            emitBlock(j, wh.block(), retType, kName, bodyFallthrough)

                            j.ind--
                            j.line("}")
                        }

                        if (isPure(cond)) {
                            emitDecision(pure(cond))
                        } else {
                            emitExpr(j, cond) { cv -> emitDecision(cv) }
                        }
                    }
                }

                j.line("final $loopClass __loop = new $loopClass();")
                j.line("$tName.next = () -> __loop.step();")
                j.line("$tName.run();")
            }

            return
        }

        s.returnStatement()?.let { r ->
            val e = r.expression()
            if (e == null) {
                j.line("$kName.accept(null);")
                j.line("return;")
            } else {
                emitExpr(j, e) { v ->
                    j.line("$kName.accept($v);")
                    j.line("return;")
                }
            }
            return
        }

        s.expression()?.let { e ->
            if (isPure(e)) {
                // Kotlin allows pure expr statements; Java doesn't. Drop them.
                next()
            } else {
                emitExpr(j, e) { _ -> next() }
            }
            return
        }

        next()
    }

    // ---------------- Expressions (CPS) ----------------

    private fun emitExpr(j: J, e: MiniKotlinParser.ExpressionContext, use: (String) -> Unit) {
        if (isPure(e)) { use(pure(e)); return }

        when (e) {
            is MiniKotlinParser.FunctionCallExprContext -> emitCall(j, e, use)

            is MiniKotlinParser.NotExprContext ->
                emitExpr(j, e.expression()) { v -> use("(!($v))") }

            is MiniKotlinParser.MulDivExprContext -> emitBin(j, e.expression(0), e.expression(1), e.getChild(1).text, use)
            is MiniKotlinParser.AddSubExprContext -> emitBin(j, e.expression(0), e.expression(1), e.getChild(1).text, use)
            is MiniKotlinParser.ComparisonExprContext -> emitBin(j, e.expression(0), e.expression(1), e.getChild(1).text, use)

            is MiniKotlinParser.EqualityExprContext -> {
                val op = e.getChild(1).text
                emitExpr(j, e.expression(0)) { lv ->
                    emitExpr(j, e.expression(1)) { rv ->
                        val eq = "Objects.equals($lv, $rv)"
                        use(if (op == "==") eq else "(!($eq))")
                    }
                }
            }

            is MiniKotlinParser.AndExprContext -> {
                emitExpr(j, e.expression(0)) { lv ->
                    j.line("if (!($lv)) {")
                    j.ind++
                    use("false")
                    j.ind--
                    j.line("} else {")
                    j.ind++
                    emitExpr(j, e.expression(1), use)
                    j.ind--
                    j.line("}")
                }
            }

            is MiniKotlinParser.OrExprContext -> {
                emitExpr(j, e.expression(0)) { lv ->
                    j.line("if (($lv)) {")
                    j.ind++
                    use("true")
                    j.ind--
                    j.line("} else {")
                    j.ind++
                    emitExpr(j, e.expression(1), use)
                    j.ind--
                    j.line("}")
                }
            }

            else -> use(pure(e))
        }
    }

    private fun emitBin(
        j: J,
        l: MiniKotlinParser.ExpressionContext,
        r: MiniKotlinParser.ExpressionContext,
        op: String,
        use: (String) -> Unit
    ) {
        emitExpr(j, l) { lv ->
            emitExpr(j, r) { rv ->
                use("(($lv) $op ($rv))")
            }
        }
    }

    private fun emitCall(j: J, call: MiniKotlinParser.FunctionCallExprContext, use: (String) -> Unit) {
        val fn = call.IDENTIFIER().text
        val target = if (funcs.containsKey(fn)) fn else "Prelude.$fn"
        val args = call.argumentList().expression()

        emitArgs(j, args, 0, mutableListOf()) { av ->
            val res = fresh("arg")
            j.line("$target(${av.joinToString(", ")}, ($res) -> {")
            j.ind++
            use(res)
            j.ind--
            j.line("});")
            // ✅ Important CPS hygiene: stop current frame; rest is in continuation
            j.line("return;")
        }
    }

    private fun emitArgs(
        j: J,
        args: List<MiniKotlinParser.ExpressionContext>,
        i: Int,
        acc: MutableList<String>,
        done: (List<String>) -> Unit
    ) {
        if (i >= args.size) { done(acc); return }
        emitExpr(j, args[i]) { v ->
            acc.add(v)
            emitArgs(j, args, i + 1, acc, done)
        }
    }

    // ---------------- Pure expressions ----------------

    private fun isPure(e: MiniKotlinParser.ExpressionContext): Boolean = when (e) {
        is MiniKotlinParser.FunctionCallExprContext -> false
        is MiniKotlinParser.PrimaryExprContext -> isPurePrimary(e.primary())
        is MiniKotlinParser.NotExprContext -> isPure(e.expression())
        is MiniKotlinParser.MulDivExprContext -> isPure(e.expression(0)) && isPure(e.expression(1))
        is MiniKotlinParser.AddSubExprContext -> isPure(e.expression(0)) && isPure(e.expression(1))
        is MiniKotlinParser.ComparisonExprContext -> isPure(e.expression(0)) && isPure(e.expression(1))
        is MiniKotlinParser.EqualityExprContext -> isPure(e.expression(0)) && isPure(e.expression(1))
        is MiniKotlinParser.AndExprContext -> isPure(e.expression(0)) && isPure(e.expression(1))
        is MiniKotlinParser.OrExprContext -> isPure(e.expression(0)) && isPure(e.expression(1))
        else -> false
    }

    private fun isPurePrimary(p: MiniKotlinParser.PrimaryContext): Boolean = when (p) {
        is MiniKotlinParser.ParenExprContext -> isPure(p.expression())
        is MiniKotlinParser.IntLiteralContext -> true
        is MiniKotlinParser.StringLiteralContext -> true
        is MiniKotlinParser.BoolLiteralContext -> true
        is MiniKotlinParser.IdentifierExprContext -> true
        else -> false
    }

    private fun pure(e: MiniKotlinParser.ExpressionContext): String = when (e) {
        is MiniKotlinParser.PrimaryExprContext -> purePrimary(e.primary())
        is MiniKotlinParser.NotExprContext -> "(!(${pure(e.expression())}))"
        is MiniKotlinParser.MulDivExprContext -> "((${pure(e.expression(0))}) ${e.getChild(1).text} (${pure(e.expression(1))}))"
        is MiniKotlinParser.AddSubExprContext -> "((${pure(e.expression(0))}) ${e.getChild(1).text} (${pure(e.expression(1))}))"
        is MiniKotlinParser.ComparisonExprContext -> "((${pure(e.expression(0))}) ${e.getChild(1).text} (${pure(e.expression(1))}))"
        is MiniKotlinParser.EqualityExprContext -> {
            val op = e.getChild(1).text
            val eq = "Objects.equals(${pure(e.expression(0))}, ${pure(e.expression(1))})"
            if (op == "==") eq else "(!($eq))"
        }
        is MiniKotlinParser.AndExprContext -> "((${pure(e.expression(0))}) && (${pure(e.expression(1))}))"
        is MiniKotlinParser.OrExprContext -> "((${pure(e.expression(0))}) || (${pure(e.expression(1))}))"
        else -> error("pure() called on non-pure: ${e.text}")
    }

    private fun purePrimary(p: MiniKotlinParser.PrimaryContext): String = when (p) {
        is MiniKotlinParser.ParenExprContext -> "(${pure(p.expression())})"
        is MiniKotlinParser.IntLiteralContext -> p.INTEGER_LITERAL().text
        is MiniKotlinParser.StringLiteralContext -> p.STRING_LITERAL().text
        is MiniKotlinParser.BoolLiteralContext -> p.BOOLEAN_LITERAL().text
        is MiniKotlinParser.IdentifierExprContext -> {
            val name = p.IDENTIFIER().text
            if (isVar(name)) "$name.v" else name
        }
        else -> error("Unknown primary: ${p.text}")
    }
}