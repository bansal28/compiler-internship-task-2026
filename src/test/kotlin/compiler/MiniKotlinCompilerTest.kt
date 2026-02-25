//package org.example.compiler
//
//import MiniKotlinLexer
//import MiniKotlinParser
//import org.antlr.v4.runtime.CharStreams
//import org.antlr.v4.runtime.CommonTokenStream
//import org.junit.jupiter.api.Test
//import org.junit.jupiter.api.io.TempDir
//import java.nio.file.Files
//import java.nio.file.Path
//import java.nio.file.Paths
//import kotlin.test.assertIs
//import kotlin.test.assertTrue
//
//class MiniKotlinCompilerTest {
//
//    @TempDir
//    lateinit var tempDir: Path
//
//    private fun parseString(source: String): MiniKotlinParser.ProgramContext {
//        val input = CharStreams.fromString(source)
//        val lexer = MiniKotlinLexer(input)
//        val tokens = CommonTokenStream(lexer)
//        val parser = MiniKotlinParser(tokens)
//        return parser.program()
//    }
//
//    private fun parseFile(path: Path): MiniKotlinParser.ProgramContext {
//        val input = CharStreams.fromPath(path)
//        val lexer = MiniKotlinLexer(input)
//        val tokens = CommonTokenStream(lexer)
//        val parser = MiniKotlinParser(tokens)
//        return parser.program()
//    }
//
//    private fun resolveStdlibPath(): Path? {
//        val devPath = Paths.get("build", "stdlib")
//        if (devPath.toFile().exists()) {
//            val stdlibJar = devPath.toFile().listFiles()
//                ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
//            if (stdlibJar != null) return stdlibJar.toPath()
//        }
//        return null
//    }
//
//    @Test
//    fun `compile example_mini outputs 120 and 15`() {
//        val examplePath = Paths.get("samples/example.mini")
//        val program = parseFile(examplePath)
//
//        val compiler = MiniKotlinCompiler()
//        val javaCode = compiler.compile(program)
//
//        val javaFile = tempDir.resolve("MiniProgram.java")
//        Files.writeString(javaFile, javaCode)
//
//        val javaCompiler = JavaRuntimeCompiler()
//        val stdlibPath = resolveStdlibPath()
//        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)
//
//        assertIs<CompilationResult.Success>(compilationResult)
//        assertIs<ExecutionResult.Success>(executionResult)
//
//        val output = executionResult.stdout
//        assertTrue(output.contains("120"), "Expected output to contain factorial result 120, but got: $output")
//        assertTrue(output.contains("15"), "Expected output to contain arithmetic result 15, but got: $output")
//    }
//}


package org.example.compiler

import MiniKotlinLexer
import MiniKotlinParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MiniKotlinCompilerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun parseString(source: String): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromString(source)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun parseFile(path: Path): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromPath(path)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun resolveStdlibPath(): Path? {
        val devPath = Paths.get("build", "stdlib")
        if (devPath.toFile().exists()) {
            val stdlibJar = devPath.toFile().listFiles()
                ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            if (stdlibJar != null) return stdlibJar.toPath()
        }
        return null
    }

    private fun compileAndRun(program: MiniKotlinParser.ProgramContext): ExecutionResult.Success {
        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)

        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)

        if (compilationResult is CompilationResult.Failure) {
            println("=== Generated Java Code (COMPILATION FAILED) ===")
            println(javaCode)
            println("=== Javac Errors ===")
            compilationResult.errors.forEach { e ->
                println("${e.line}:${e.column}: ${e.message}")
            }
            throw AssertionError("Java compilation failed; see printed javac errors above.")
        }

        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)

        return executionResult
    }

    // -------------------------------
    // Existing sample test (unchanged logic)
    // -------------------------------

    @Test
    fun `compile example_mini outputs 120 and 15`() {
        val examplePath = Paths.get("samples/example.mini")
        val program = parseFile(examplePath)

        val result = compileAndRun(program)

        val output = result.stdout
        assertTrue(output.contains("120"), "Expected output to contain factorial result 120, but got: $output")
        assertTrue(output.contains("15"), "Expected output to contain arithmetic result 15, but got: $output")
    }

    // -------------------------------
    // New "break it" tests
    // -------------------------------

    @Test
    fun `short-circuit OR must not evaluate RHS`() {
        val src = """
            fun boom(d:Int): Boolean {
              println(999)
              return false
            }
            fun main(): Unit {
              var x: Boolean = true || boom(0)
              println(111)
              return
            }
        """.trimIndent()

        val out = compileAndRun(parseString(src)).stdout
        assertTrue(!out.contains("999"), "RHS evaluated but should not be. Output:\n$out")
        assertTrue(out.contains("111"), "Missing expected 111. Output:\n$out")
    }

    @Test
    fun `short-circuit AND must not evaluate RHS`() {
        val src = """
            fun boom(d:Int): Boolean {
              println(999)
              return true
            }
            fun main(): Unit {
              var x: Boolean = false && boom(0)
              println(111)
              return
            }
        """.trimIndent()

        val out = compileAndRun(parseString(src)).stdout
        assertTrue(!out.contains("999"), "RHS evaluated but should not be. Output:\n$out")
        assertTrue(out.contains("111"), "Missing expected 111. Output:\n$out")
    }

    @Test
    fun `argument evaluation order must be left-to-right`() {
        val src = """
    fun a(d: Int): Int {
      println(1)
      return 10
    }
    fun b(d: Int): Int {
      println(2)
      return 20
    }
    fun c(x: Int, y: Int): Int {
      println(x + y)
      return x + y
    }
    fun main(): Unit {
      var z: Int = c(a(0), b(0))
      println(z)
      return
    }
""".trimIndent()

        val out = compileAndRun(parseString(src)).stdout.trim().lines()
        val expected = listOf("1", "2", "30", "30")
        assertTrue(out == expected, "Expected $expected but got $out")
    }
    @Test
    fun `nested calls inside expressions`() {
        val src = """
            fun inc(x: Int): Int { return x + 1 }
            fun twice(x: Int): Int { return x * 2 }
            fun main(): Unit {
              var v: Int = twice(inc(3)) + inc(twice(2))
              println(v)
              return
            }
        """.trimIndent()

        val out = compileAndRun(parseString(src)).stdout
        assertTrue(out.contains("13"), "Expected 13, got:\n$out")
    }

    @Test
    fun `return must stop execution`() {
        val src = """
            fun main(): Unit {
              println(1)
              return
              println(999)
            }
        """.trimIndent()

        val out = compileAndRun(parseString(src)).stdout.trim().lines()
        assertTrue(out == listOf("1"), "Expected only 1, got $out")
    }


    @Test
    fun `return inside if branches`() {
        val src = """
            fun f(x: Int): Int {
              if (x > 0) { return 7 } else { return 9 }
            }
            fun main(): Unit {
              println(f(1))
              println(f(0))
              return
            }
        """.trimIndent()

        val out = compileAndRun(parseString(src)).stdout.trim().lines()
        val expected = listOf("7", "9")
        assertTrue(out == expected, "Expected $expected but got $out")
    }

    @Test
    fun `while loop with CPS call in body`() {
        val src = """
            fun main(): Unit {
              var i: Int = 0
              while (i < 3) {
                println(i)
                i = i + 1
              }
              println(777)
              return
            }
        """.trimIndent()

        val outLines = compileAndRun(parseString(src)).stdout.trim().lines()
        val expected = listOf("0", "1", "2", "777")
        assertTrue(outLines == expected, "Expected $expected but got $outLines")
    }

    @Test
    fun `while condition involving a call`() {
        val src = """
            fun cond(i: Int): Boolean {
              println(100 + i)
              return i < 2
            }
            fun main(): Unit {
              var i: Int = 0
              while (cond(i)) {
                println(i)
                i = i + 1
              }
              println(999)
              return
            }
        """.trimIndent()

        val out = compileAndRun(parseString(src)).stdout.trim().lines()
        val expected = listOf("100", "0", "101", "1", "102", "999")
        assertTrue(out == expected, "Expected $expected but got $out")
    }

    @Test
    fun `var mutation across CPS boundaries`() {
        val src = """
                
            fun add1(x: Int): Int { return x + 1 }

            fun main(): Unit {
              var x: Int = 0
              x = add1(x)
              x = add1(x)
              println(x)
              return
            }
        """.trimIndent()

        val out = compileAndRun(parseString(src)).stdout
        assertTrue(out.contains("2"), "Expected 2, got:\n$out")
    }
}