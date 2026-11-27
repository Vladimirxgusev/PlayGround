package app.pomoshnik

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object WakeSpeechModel {
    private const val NAME = "vosk-model-small-ru-0.22"
    private const val SHA256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"

    @Synchronized
    fun directory(filesDir: File): File {
        val root = File(filesDir, "wake-model")
        val installed = File(root, NAME)
        if (File(installed, "conf/model.conf").isFile) return installed
        if (!root.mkdirs() && !root.isDirectory) throw IOException("Cannot create speech model directory")

        val archive = File.createTempFile("download-", ".zip", root)
        val staging = File(root, archive.nameWithoutExtension)
        try {
            download(archive)
            ZipFile(archive).use { zip ->
                val stagingPath = staging.canonicalPath + File.separator
                for (entry in zip.entries().asSequence()) {
                    val target = File(staging, entry.name)
                    if (!target.canonicalPath.startsWith(stagingPath)) throw IOException("Invalid model entry")
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
                    }
                }
            }
            if (!File(staging, NAME).renameTo(installed)) throw IOException("Cannot install speech model")
            return installed
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun download(archive: File) {
        val connection = URL("https://alphacephei.com/vosk/models/$NAME.zip").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            connection.inputStream.use { source ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > 50L * 1024 * 1024) throw IOException("Speech model exceeds download limit")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != SHA256) {
            throw IOException("Speech model checksum mismatch")
        }
    }
}
