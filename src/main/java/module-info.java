module com.mellonita.magicki {
    requires java.desktop; // ImageIO & AWT
    requires kotlin.stdlib;

    // Advertise provider to ServiceLoader as a module service
    provides javax.imageio.spi.ImageReaderSpi
            with com.mellonita.magicki.MagickImageReaderSpi;

    exports com.mellonita.magicki;
}
