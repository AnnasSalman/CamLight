package com.camlight.app

import com.camlight.app.remote.RemoteApi
import com.camlight.app.remote.RemoteServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class RemoteServerTest {
    private val received = mutableListOf<String>()
    private val api = object : RemoteApi {
        override fun frame(debug: Boolean): ByteArray? = if (debug) null else byteArrayOf(1, 2, 3)
        override fun state(): String = """{"ok":true}"""
        override fun command(json: String): String { received += json; return """{"ok":true}""" }
    }
    private lateinit var server: RemoteServer
    private var port = 0

    @Before
    fun start() {
        server = RemoteServer(api, "secret12", "<html>page</html>".toByteArray())
        port = server.start(18080)!!
    }

    @After
    fun stop() = server.stop()

    private fun get(path: String): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return try {
            c.responseCode to (if (c.responseCode < 400) c.inputStream.readBytes() else ByteArray(0))
        } finally { c.disconnect() }
    }

    @Test
    fun rejectsRequestsWithoutTheKey() {
        assertEquals(403, get("/").first)
        assertEquals(403, get("/state?k=wrong").first)
        assertEquals(403, get("/frame.jpg").first)
    }

    @Test
    fun servesPageStateAndFrameWithTheKey() {
        val page = get("/?k=secret12")
        assertEquals(200, page.first)
        assertTrue(String(page.second).contains("page"))
        assertEquals("""{"ok":true}""", String(get("/state?k=secret12").second))
        assertEquals(listOf<Byte>(1, 2, 3), get("/frame.jpg?k=secret12&t=5").second.toList())
        assertEquals(204, get("/frame.jpg?k=secret12&debug=1").first)
        assertEquals(404, get("/nope?k=secret12").first)
    }

    @Test
    fun postsCommandBodiesToTheApi() {
        val c = URL("http://127.0.0.1:$port/cmd?k=secret12").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write("""{"a":"detect"}""".toByteArray()) }
        assertEquals(200, c.responseCode)
        assertEquals("""{"ok":true}""", String(c.inputStream.readBytes()))
        assertEquals(listOf("""{"a":"detect"}"""), received)
    }

    @Test
    fun fallsBackToTheNextPortWhenBusy() {
        val second = RemoteServer(api, "x", ByteArray(0))
        val p = second.start(port)
        assertEquals(port + 1, p)
        second.stop()
    }
}
