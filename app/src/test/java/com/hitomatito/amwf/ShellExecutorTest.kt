package com.hitomatito.amwf

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class ShellExecutorTest {

    private class FailAfterOneLineReader : BufferedReader(StringReader("unused")) {
        private var returnedFirst = false

        override fun readLine(): String? {
            if (!returnedFirst) {
                returnedFirst = true
                return "primera"
            }
            throw IOException("read interrupted by close() on another thread")
        }
    }

    @Test
    fun `reader IOException is swallowed and partial output kept`() {
        val sink = StringBuffer()

        ShellExecutor.drainLines(FailAfterOneLineReader(), sink)

        assertEquals("primera${System.lineSeparator()}", sink.toString())
    }

    @Test
    fun `normal stdout and stderr are captured`() {
        Assume.assumeTrue(hasShell())
        val result = ShellExecutor.execNoRoot("printf 'out\\n'; printf 'err\\n' >&2")

        assertEquals(0, result.exitCode)
        assertEquals("out", result.output)
        assertEquals("err", result.error)
    }

    @Test
    fun `timeout returns an error result without crashing reader threads`() {
        Assume.assumeTrue(hasShell())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            synchronized(uncaught) { uncaught.add(throwable) }
        }
        try {
            val result = ShellExecutor.execNoRoot("sleep 3", timeoutMs = 100)

            assertEquals(-1, result.exitCode)
            assertEquals("Command timeout", result.error)
            synchronized(uncaught) { assertTrue(uncaught.isEmpty()) }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun `reader cleanup does not wait for a descendant holding the pipe open`() {
        Assume.assumeTrue(hasShell())
        val start = System.nanoTime()
        val result = ShellExecutor.execNoRoot("sleep 3 & exit 0")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(0, result.exitCode)
        assertTrue(
            "closing the readers waited ${elapsedMs}ms for the background process",
            elapsedMs < 2000
        )
    }

    private fun hasShell(): Boolean =
        System.getProperty("os.name").lowercase() !in setOf("windows 10", "windows 11")
}
