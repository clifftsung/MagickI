package com.mellonita.magicki

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.ComponentSampleModel
import javax.imageio.IIOException
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.spi.ImageReaderSpi

class MagickImageReader(spi: ImageReaderSpi) : ImageReader(spi) {
    private var bridge: MagickBridge? = null
    private var width = -1
    private var height = -1
    private var meta: MagickMetadata? = null

    override fun setInput(input: Any?, seekForwardOnly: Boolean, ignoreMetadata: Boolean) {
        super.setInput(input, seekForwardOnly, ignoreMetadata)
        bridge?.close()
        bridge = null
        width = -1; height = -1
        meta = null
        if (input != null) {
            bridge = MagickBridgeFactory.fromSource(input)
        }
    }

    private fun ensureHeader() {
        if (width >= 0) return
        val b = bridge ?: throw IIOException("No input")
        val id = b.identify()
        width = id.width; height = id.height
        // metadata (lazy but coupled to identify to avoid extra round-trips)
        val md = b.readBasicMetadata()
        meta = MagickMetadata(md)
    }

    override fun getNumImages(allowSearch: Boolean) = 1
    override fun getWidth(imageIndex: Int): Int {
        checkIndex(imageIndex); ensureHeader(); return width
    }

    override fun getHeight(imageIndex: Int): Int {
        checkIndex(imageIndex); ensureHeader(); return height
    }

    override fun getImageTypes(imageIndex: Int): Iterator<ImageTypeSpecifier?> {
        checkIndex(imageIndex)
        val spec = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_4BYTE_ABGR)
        return listOf(spec).iterator()
    }

    override fun read(imageIndex: Int, param: ImageReadParam?): BufferedImage {
        checkIndex(imageIndex); ensureHeader()
        val readParam = param ?: getDefaultReadParam()
        val dst = getDestination(readParam, getImageTypes(0), width, height)
            ?: BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)

        if (dst.type != BufferedImage.TYPE_4BYTE_ABGR || dst.width != width || dst.height != height)
            throw IIOException("Destination must be TYPE_4BYTE_ABGR ${width}x$height")

        val raster = dst.raster
        val sampleModel = raster.sampleModel as? ComponentSampleModel
            ?: throw IIOException("Unsupported destination sample model: ${raster.sampleModel}")
        if (sampleModel.pixelStride != 4 || sampleModel.numBands != 4) {
            throw IIOException("Destination raster not compatible (pixel stride ${sampleModel.pixelStride}, bands ${sampleModel.numBands})")
        }
        val bandOffsets = sampleModel.bandOffsets
        if (bandOffsets.size != 4 || bandOffsets.toSet() != setOf(0, 1, 2, 3)) {
            throw IIOException("Destination band layout must contain offsets 0..3: offsets=${bandOffsets.contentToString()}")
        }

        val srcRegion = Rectangle(0, 0, width, height)
        val destRegion = Rectangle(0, 0, dst.width, dst.height)
        computeRegions(readParam, width, height, dst, srcRegion, destRegion)
        val periodX = readParam.sourceXSubsampling
        val periodY = readParam.sourceYSubsampling
        val xOffset = readParam.subsamplingXOffset
        val yOffset = readParam.subsamplingYOffset
        val sourceBands = readParam.sourceBands
        val destinationBands = readParam.destinationBands
        if (sourceBands != null || destinationBands != null) {
            throw IIOException("Band selection is not supported")
        }

        val effectiveWidth = destRegion.width
        val effectiveHeight = destRegion.height
        if (effectiveWidth <= 0 || effectiveHeight <= 0) {
            throw IIOException("Requested source region produces empty image")
        }

        val bridgeInstance = bridge ?: throw IIOException("No input")
        clearAbortRequest()
        processImageStarted(imageIndex)

        val rawSize = 4L * width.toLong() * height.toLong()
        if (rawSize > Int.MAX_VALUE) throw IIOException("Image is too large to buffer in memory")
        val raw = ByteArray(rawSize.toInt())
        bridgeInstance.convertToRawRGBA(width, height, raw, forceSRGB = true, autoOrient = true)

        val dataBuffer = raster.dataBuffer as? DataBufferByte
            ?: throw IIOException("Unexpected destination buffer type: ${raster.dataBuffer.javaClass.name}")
        val destData = dataBuffer.data
        val bankOffset = dataBuffer.offsets.firstOrNull() ?: 0
        val baseOffset = bankOffset + destRegion.y * sampleModel.scanlineStride + destRegion.x * sampleModel.pixelStride
        val srcRowStride = width * 4
        var destRowPtr = baseOffset
        var srcY = srcRegion.y + yOffset
        val totalRows = effectiveHeight

        for (row in 0 until totalRows) {
            if (abortRequested()) {
                processReadAborted()
                throw IIOException("Read aborted")
            }

            val srcRowIndex = srcY * srcRowStride
            var srcX = srcRegion.x + xOffset
            var destPixelPtr = destRowPtr
            val rowLimit = destRegion.width
            val alphaOffset = bandOffsets[0]
            val blueOffset = bandOffsets[1]
            val greenOffset = bandOffsets[2]
            val redOffset = bandOffsets[3]
            repeat(rowLimit) {
                val srcIndex = srcRowIndex + srcX * 4
                destData[destPixelPtr + alphaOffset] = raw[srcIndex + 3]   // A
                destData[destPixelPtr + blueOffset] = raw[srcIndex + 2]    // B
                destData[destPixelPtr + greenOffset] = raw[srcIndex + 1]   // G
                destData[destPixelPtr + redOffset] = raw[srcIndex]         // R
                destPixelPtr += sampleModel.pixelStride
                srcX += periodX
            }
            srcY += periodY
            destRowPtr += sampleModel.scanlineStride
            processImageProgress(row * 100f / totalRows)
        }

        processImageProgress(100f)
        processImageComplete()
        return dst
    }

    override fun getImageMetadata(imageIndex: Int): IIOMetadata? {
        checkIndex(imageIndex); ensureHeader()
        return meta
    }

    override fun getStreamMetadata(): IIOMetadata? = null

    override fun dispose() {
        super.dispose()
        bridge?.close(); bridge = null
        meta = null
    }

    private fun checkIndex(idx: Int) {
        if (idx != 0) throw IndexOutOfBoundsException("imageIndex: $idx")
    }
}
