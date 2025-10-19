package com.mellonita.magicki

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOException

interface MagickBridge : AutoCloseable {
    data class IdentifyInfo(val width: Int, val height: Int)
    data class BasicMeta(
        val mmPerPixelX: Double?,
        val mmPerPixelY: Double?,
        val orientationStd: String?,                     // javax_imageio_1.0 vocabulary
        val text: Map<String, String>                    // EXIF fields presented as TextEntry
    )

    fun canIdentify(): Boolean
    fun identify(): IdentifyInfo
    fun readBasicMetadata(): BasicMeta
    fun convertToRawABGR(w: Int, h: Int, outABGR: ByteArray, forceSRGB: Boolean, autoOrient: Boolean)
}

/** Process wrapper + probing helpers shared by all ImageMagick major versions. */
internal class DefaultMagickBridge(
    private val tempFile: Path,
    private val deleteOnClose: Boolean,
    private val cli: MagickCli
) : MagickBridge {

    override fun canIdentify(): Boolean = runCatching {
        runText(cli.identify("-ping", "-quiet", tempFile.toString()))
    }.isSuccess

    override fun identify(): MagickBridge.IdentifyInfo {
        val out = try {
            runText(cli.identify("-ping", "-quiet", "-format", "%w %h", tempFile.toString())).trim()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw IIOException("identify interrupted", e)
        } catch (e: IOException) {
            throw IIOException("identify failed", e)
        }
        val p = out.split(Regex("\\s+"))
        if (p.size < 2) throw IIOException("identify parse failed: '$out'")
        return MagickBridge.IdentifyInfo(p[0].toInt(), p[1].toInt())
    }

    override fun readBasicMetadata(): MagickBridge.BasicMeta {
        val (mmX, mmY) = readResolutionMmPerPixel()
        val orientStd = mapOrientation(readExif("Orientation"))
        val text = buildMap {
            readExif("DateTimeOriginal")?.let { put("EXIF:DateTimeOriginal", it) }
            readExif("Make")?.let { put("EXIF:Make", it) }
            readExif("Model")?.let { put("EXIF:Model", it) }
            readExif("Software")?.let { put("EXIF:Software", it) }
        }
        return MagickBridge.BasicMeta(mmX, mmY, orientStd, text)
    }

    private fun readResolutionMmPerPixel(): Pair<Double?, Double?> {
        val fmt = "%x %y %U"
        val out = runCatching {
            runText(cli.identify("-ping", "-quiet", "-format", fmt, tempFile.toString())).trim()
        }.getOrNull() ?: return null to null
        val parts = out.split(Regex("\\s+"))
        if (parts.size < 3) return null to null
        val rx = parts[0].toDoubleOrNull()
        val ry = parts[1].toDoubleOrNull()
        val unit = parts[2]
        if (rx == null || ry == null) return null to null
        return when (unit) {
            "PixelsPerInch" -> (25.4 / rx) to (25.4 / ry)
            "PixelsPerCentimeter" -> (10.0 / rx) to (10.0 / ry)
            else -> null to null
        }
    }

    private fun readExif(tag: String): String? {
        val value = runCatching {
            runText(
                cli.identify(
                    "-ping", "-quiet",
                    "-format", "%[EXIF:$tag]", tempFile.toString()
                )
            )
        }.getOrNull()?.trim().orEmpty()
        return value.ifBlank { null }
    }

    private fun mapOrientation(exif: String?): String? = when (exif?.trim()) {
        "1", "" -> "Normal"
        "2" -> "FlipH"
        "3" -> "Rotate180"
        "4" -> "FlipV"
        "5" -> "FlipHRotate90"
        "6" -> "Rotate90"
        "7" -> "FlipHRotate270"
        "8" -> "Rotate270"
        else -> null
    }

    override fun convertToRawABGR(
        w: Int,
        h: Int,
        outABGR: ByteArray,
        forceSRGB: Boolean,
        autoOrient: Boolean
    ) {
        val needLong = 4L * w.toLong() * h.toLong()
        require(needLong <= Int.MAX_VALUE) { "Image is too large to decode into a contiguous buffer (4*w*h overflow)" }
        require(outABGR.size.toLong() >= needLong) {
            "Output buffer too small: need $needLong, have ${outABGR.size}"
        }
        val need = needLong.toInt()

        val args = mutableListOf<String>()
        args += tempFile.toString()
        if (autoOrient) {
            args += "-auto-orient"
        }
        args += listOf("-alpha", "on")
        args += listOf("-depth", "8")
        if (forceSRGB) {
            args += listOf("-colorspace", "sRGB")
        }
        args += "abgr:-"

        val cmd = cli.convert(*args.toTypedArray())
        val p = ProcessBuilder(cmd).start()
        p.outputStream.close()
        val errBuffer = StringBuilder()
        val errThread = kotlin.concurrent.thread(name = "magick-stderr", isDaemon = true) {
            p.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val chunk = reader.readLine() ?: break
                    if (errBuffer.isNotEmpty()) errBuffer.appendLine()
                    errBuffer.append(chunk)
                }
            }
        }
        var copied = 0
        p.inputStream.use { ins ->
            val buf = ByteArray(1 shl 20)
            while (copied < need) {
                val n = ins.read(buf); if (n < 0) break
                val toCopy = minOf(n, need - copied)
                System.arraycopy(buf, 0, outABGR, copied, toCopy)
                copied += toCopy
            }
        }
        val code = p.waitFor()
        errThread.join()
        val stderr = errBuffer.toString().trim()
        if (code != 0 || stderr.isNotEmpty()) {
            throw IIOException(
                buildString {
                    append("magick convert failed ($code)")
                    if (stderr.isNotBlank()) {
                        append(": ")
                        append(stderr)
                    }
                }
            )
        }
        if (copied != need) throw IIOException("Short read: expected $need bytes, got $copied")
    }

    override fun close() {
        if (deleteOnClose) runCatching { Files.deleteIfExists(tempFile) }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun runText(cmd: List<String>): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val bytes = p.inputStream.use(InputStream::readAllBytes)
        val code = p.waitFor()
        if (code != 0) throw IOException("Command failed: ${cmd.joinToString(" ")}\n${String(bytes)}")
        return String(bytes)
    }
}

internal sealed interface MagickCli {
    fun identify(vararg args: String): List<String>
    fun convert(vararg args: String): List<String>
    val description: String
}

internal data class ImageMagick7Cli(private val executable: String) : MagickCli {
    override fun identify(vararg args: String): List<String> =
        buildList {
            add(executable)
            add("identify")
            addAll(args)
        }

    override fun convert(vararg args: String): List<String> =
        buildList {
            add(executable)
            addAll(args)
        }

    override val description: String get() = executable
}

internal data class ImageMagick6Cli(
    private val convertExecutable: String,
    private val identifyExecutable: String
) : MagickCli {
    override fun identify(vararg args: String): List<String> =
        buildList {
            add(identifyExecutable)
            addAll(args)
        }

    override fun convert(vararg args: String): List<String> =
        buildList {
            add(convertExecutable)
            addAll(args)
        }

    override val description: String get() = "$identifyExecutable/$convertExecutable"
}
