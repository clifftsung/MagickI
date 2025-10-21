package com.mellonita.magicki

import java.awt.image.BufferedImage
import java.nio.file.Paths
import javax.imageio.IIOException
import javax.imageio.ImageIO
import kotlin.test.*

class MagickImageReaderTest {
    private val heicImagePath = Paths.get("src", "test", "resources", "image1.heic")

    @Test
    fun readHeicImageViaImageIoRegistration() {
        val inputFile = heicImagePath.toFile()
        val magickAvailable = runCatching {
            MagickBridgeFactory.fromSource(inputFile).use { it.canIdentify() }
        }.getOrDefault(false)

        assertTrue(magickAvailable, "ImageMagick identify cannot decode HEIC test image; magick CLI unavailable")

        ImageIO.scanForPlugins()

        val input = ImageIO.createImageInputStream(inputFile)
        assertNotNull(input, "ImageIO.createImageInputStream should produce an input stream for the test file")
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            var reader: MagickImageReader? = null
            while (readers.hasNext() && reader == null) {
                val candidate = readers.next()
                if (candidate is MagickImageReader) {
                    reader = candidate
                } else {
                    candidate.dispose()
                }
            }

            assertNotNull(reader, "MagickImageReader was not discovered via ImageIO service registry")
            val magickReader = reader

            try {
                stream.seek(0)
                magickReader.setInput(stream)

                assertEquals(3992, magickReader.getWidth(0))
                assertEquals(2992, magickReader.getHeight(0))

                val image = try {
                    magickReader.read(0, null)
                } catch (ex: IIOException) {
                    if (ex.message?.contains("no encode delegate", ignoreCase = true) == true ||
                        ex.message?.contains("no decode delegate", ignoreCase = true) == true
                    ) {
                        fail("ImageMagick lacks HEIC delegate: ${ex.message}")
                    }
                    throw ex
                }
                assertEquals(BufferedImage.TYPE_4BYTE_ABGR, image.type)
                assertEquals(3992, image.width)
                assertEquals(2992, image.height)

                val metadata = magickReader.getImageMetadata(0)
                assertNotNull(metadata)
                assertTrue(metadata is MagickMetadata)
            } finally {
                magickReader.dispose()
            }
        }
    }

}
