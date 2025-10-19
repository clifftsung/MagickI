@file:Suppress("unused")

package com.mellonita.magicki


import org.w3c.dom.Node
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataFormatImpl.standardMetadataFormatName
import javax.imageio.metadata.IIOMetadataNode

/** Read-only, standard-tree metadata built from ImageMagick identify probes. */
class MagickMetadata(private val m: MagickBridge.BasicMeta) : IIOMetadata() {
    override fun isReadOnly() = true

    override fun getMetadataFormatNames(): Array<String> = arrayOf(standardMetadataFormatName)

    override fun getAsTree(formatName: String?): Node {
        require(formatName == standardMetadataFormatName) { "Only javax_imageio_1.0 supported" }
        val root = node("javax_imageio_1.0")

        // Chroma: declare sRGB, 4 bands (ABGR output)
        val chroma = node("Chroma").also { root.appendChild(it) }
        chroma.appendChild(node("ColorSpaceType").also { it.setAttribute("name", "RGB") })
        chroma.appendChild(node("NumChannels").also { it.setAttribute("value", "4") })

        // Dimension: physical pixel size (mm/pixel) + orientation
        val dim = node("Dimension").also { root.appendChild(it) }
        m.mmPerPixelX?.let {
            dim.appendChild(node("HorizontalPixelSize").also { n ->
                n.setAttribute(
                    "value",
                    it.toString()
                )
            })
        }
        m.mmPerPixelY?.let {
            dim.appendChild(node("VerticalPixelSize").also { n ->
                n.setAttribute(
                    "value",
                    it.toString()
                )
            })
        }
        m.orientationStd?.let { dim.appendChild(node("ImageOrientation").also { n -> n.setAttribute("value", it) }) }

        // Text: shove common EXIF into TextEntry nodes
        if (m.text.isNotEmpty()) {
            val text = node("Text").also { root.appendChild(it) }
            m.text.forEach { (k, v) ->
                text.appendChild(node("TextEntry").also { n ->
                    n.setAttribute("keyword", k); n.setAttribute("value", v)
                })
            }
        }
        return root
    }

    override fun mergeTree(formatName: String?, root: Node?) = throw UnsupportedOperationException()
    override fun reset() = Unit

    private fun node(name: String) = IIOMetadataNode(name)
}

