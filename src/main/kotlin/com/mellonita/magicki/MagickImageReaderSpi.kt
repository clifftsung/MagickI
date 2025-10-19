package com.mellonita.magicki

import java.io.File
import java.nio.file.Path
import java.util.*
import javax.imageio.ImageReader
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStream

class MagickImageReaderSpi : ImageReaderSpi(
    "YourOrg", "1.1",
    arrayOf("magick", "imagemagick"),
    arrayOf("*"),
    arrayOf("image/*"),
    MagickImageReader::class.java.name,
    arrayOf(ImageInputStream::class.java, File::class.java, Path::class.java),
    null,
    false, null, null, null, null,
    false, null, null, null, null
) {
    override fun canDecodeInput(source: Any?): Boolean {
        if (source !is ImageInputStream && source !is File && source !is Path) return false
        return runCatching { MagickBridgeFactory.fromSource(source).use { it.canIdentify() } }.getOrElse { false }
    }

    override fun createReaderInstance(extension: Any?): ImageReader = MagickImageReader(this)
    override fun getDescription(locale: Locale?): String =
        "ImageMagick-backed ImageReader (with basic metadata)"
}
