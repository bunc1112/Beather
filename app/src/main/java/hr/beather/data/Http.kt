package hr.beather.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    // MET Norway (Yr) requires an identifying User-Agent with a contact/site.
    private const val USER_AGENT = "Beather/2.0 github.com/bunc1112/Beather"

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/json, application/xml, */*")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
