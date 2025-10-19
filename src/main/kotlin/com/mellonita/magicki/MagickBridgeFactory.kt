package com.mellonita.magicki

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.imageio.IIOException
import javax.imageio.stream.ImageInputStream
import kotlin.text.Charsets

object MagickBridgeFactory {
    private data class DetectionResult(val cli: MagickCli, val versionOutput: String, val majorVersion: Int)

    private val detection: DetectionResult by lazy { detectInstallation() }

    fun fromSource(input: Any?): MagickBridge {
        val detected = detection
        return when (input) {
            is File -> DefaultMagickBridge(input.toPath(), false, detected.cli)
            is Path -> DefaultMagickBridge(input, false, detected.cli)
            is ImageInputStream -> fromStream(input, detected.cli)
            null -> throw IIOException("Unsupported input: null")
            else -> throw IIOException("Unsupported input: ${input.javaClass}")
        }
    }

    private fun fromStream(stream: ImageInputStream, cli: MagickCli): MagickBridge {
        val tmp = Files.createTempFile("magick-io-", ".bin")
        return try {
            stream.seek(0)
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            DefaultMagickBridge(tmp, true, cli)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw IIOException("Failed to buffer stream for ImageMagick", e)
        }
    }

    private fun detectInstallation(): DetectionResult {
        val override = System.getProperty("magick.exe")?.takeIf { it.isNotBlank() }
        val magickHome = System.getenv("MAGICK_HOME")?.takeIf { it.isNotBlank() }
        val envCandidates = collectExecutablesFromMagickHome(magickHome)
        val candidates = buildList {
            if (override != null) add(override)
            addAll(envCandidates)
            add("magick")
            add("convert")
            add("identify")
        }.distinct()
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            val attempt = runCatching { probeCandidate(candidate) }
            attempt.onSuccess { return it }
            val failure = attempt.exceptionOrNull()
            val message = failure?.message ?: failure?.javaClass?.simpleName ?: "unknown error"
            errors += "$candidate -> $message"
        }
        val hint = override?.let { " (override: $it)" } ?: ""
        val envHint = magickHome?.let { " (MAGICK_HOME=$it)" } ?: ""
        throw IIOException(
            buildString {
                append("ImageMagick 6 or 7 was not detected$hint$envHint. ")
                if (errors.isNotEmpty()) {
                    append("Tried commands: ")
                    append(errors.joinToString("; "))
                    append(". ")
                }
                append("Install ImageMagick and ensure the binaries are on PATH, or set -Dmagick.exe=/path/to/magick.")
            }
        )
    }

    @Throws(DetectionFailed::class)
    private fun probeCandidate(executable: String): DetectionResult {
        val cmd = listOf(executable, "-version")
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw DetectionFailed("failed to start (${e.javaClass.simpleName}: ${e.message})", e)
        }
        val outputBytes = process.inputStream.use(InputStream::readAllBytes)
        val output = outputBytes.toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code != 0) {
            throw DetectionFailed("exit $code\n$output")
        }
        val versionLine = output.lineSequence().firstOrNull { it.contains("ImageMagick") } ?: output
        val versionMatch = Regex("ImageMagick\\s+(\\d+)").find(versionLine)
        val major = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: throw DetectionFailed("unrecognized version output:\n$output")

        val cli = when {
            major >= 7 -> ImageMagick7Cli(executable)
            major == 6 -> createMagick6Cli(executable)
            else -> throw DetectionFailed("unsupported ImageMagick major version $major\n$output")
        }

        verifyIdentify(cli)
        verifyConvert(cli)
        return DetectionResult(cli, output.trim(), major)
    }

    @Throws(DetectionFailed::class)
    private fun verifyIdentify(cli: MagickCli) {
        val cmd = cli.identify("-version")
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw DetectionFailed("failed to execute ${cmd.firstOrNull() ?: "identify"} for verification (${e.javaClass.simpleName}: ${e.message})", e)
        }
        val outputBytes = process.inputStream.use(InputStream::readAllBytes)
        val output = outputBytes.toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code != 0) {
            throw DetectionFailed("${cmd.firstOrNull() ?: "identify"} exited $code\n$output")
        }
        if (!output.contains("ImageMagick")) {
            throw DetectionFailed("unexpected identify output:\n$output")
        }
    }

    @Throws(DetectionFailed::class)
    private fun verifyConvert(cli: MagickCli) {
        val cmd = cli.convert("-version")
        val process = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw DetectionFailed("failed to execute ${cmd.firstOrNull() ?: "convert"} for verification (${e.javaClass.simpleName}: ${e.message})", e)
        }
        val outputBytes = process.inputStream.use(InputStream::readAllBytes)
        val output = outputBytes.toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code != 0) {
            throw DetectionFailed("${cmd.firstOrNull() ?: "convert"} exited $code\n$output")
        }
        if (!output.contains("ImageMagick")) {
            throw DetectionFailed("unexpected convert output:\n$output")
        }
    }

    private fun createMagick6Cli(candidate: String): MagickCli {
        val candidateName = runCatching { Path.of(candidate).fileName?.toString() }
            .getOrElse { candidate.substringAfterLast('\\').substringAfterLast('/') }
        val lower = candidateName?.lowercase() ?: candidate.lowercase()

        val convertExecutable = if (lower.contains("identify")) {
            guessSiblingExecutable(candidate, "convert")
        } else {
            candidate
        }
        val identifyExecutable = guessSiblingExecutable(candidate, "identify")
        return ImageMagick6Cli(convertExecutable, identifyExecutable)
    }

    private fun guessSiblingExecutable(sourceExecutable: String, siblingName: String): String {
        return try {
            val sourcePath = Path.of(sourceExecutable)
            val fileName = sourcePath.fileName?.toString().orEmpty()
            val extension = when {
                fileName.endsWith(".exe", ignoreCase = true) -> ".exe"
                fileName.endsWith(".bat", ignoreCase = true) -> ".bat"
                fileName.endsWith(".cmd", ignoreCase = true) -> ".cmd"
                else -> ""
            }
            val siblingCandidate = sourcePath.resolveSibling("$siblingName$extension")
            if (Files.exists(siblingCandidate)) siblingCandidate.toString() else siblingName
        } catch (_: InvalidPathException) {
            siblingName
        }
    }

    private class DetectionFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun collectExecutablesFromMagickHome(magickHome: String?): List<String> {
        if (magickHome.isNullOrBlank()) return emptyList()
        val base = runCatching { Path.of(magickHome) }.getOrNull() ?: return emptyList()
        val binDir = base.resolve("bin")
        if (!Files.exists(binDir) || !Files.isDirectory(binDir)) return emptyList()

        val names = listOf("magick", "convert", "identify")
        val extensions = listOf("", ".exe", ".bat", ".cmd")
        val results = mutableListOf<String>()
        for (name in names) {
            for (ext in extensions) {
                val candidate = binDir.resolve(name + ext)
                if (Files.exists(candidate)) {
                    results += candidate.toString()
                }
            }
        }
        return results
    }
}
