# MagickI

MagickI is an ImageIO reader that delegates decoding work to the locally installed ImageMagick CLI. Drop the jar on the classpath and `ImageIO` gains support for formats such as HEIC, CR2, and anything else your ImageMagick build can convert to ABGR.

## Features
- Registers `MagickImageReader` via `META-INF/services`, so ImageIO discovers it automatically.
- Streams image data through ImageMagick to produce `BufferedImage.TYPE_4BYTE_ABGR` outputs with standard metadata.
- Works with ImageMagick 6 and 7, locating executables via `magick`/`convert` on `PATH`, `-Dmagick.exe`, or `$MAGICK_HOME/bin`.

## Requirements
- Java 11+ (matches the Gradle toolchain in this project).
- ImageMagick CLI installed on the host machine and reachable on `PATH`, or expose it via `-Dmagick.exe=/path/to/magick` or `MAGICK_HOME`.

## Gradle source dependency
This project is not yet published to Maven Central. You can depend on it directly from git using Gradle source dependencies (requires Gradle 6.8+).

`settings.gradle.kts`:

```kotlin
sourceControl {
    gitRepository("https://github.com/clifftsung/MagickI.git") {
        producesModule("com.mellonita.magicki:magicki")
        // Uncomment one of the following lines to pin the revision you want to build:
        // tag("v0.1")
        // branch("master")
        // commit("abcdef1234567890abcdef1234567890abcdef12")
    }
}
```

Project build script:

```kotlin
dependencies {
    implementation("com.mellonita.magicki:magicki")
}
```

Running `./gradlew build` will clone this repository, compile the plugin, and make it available to your project.

## Usage
After the dependency is on the classpath, ImageIO loads the SPI the first time it needs a reader. No extra registration is necessary.

### Kotlin

```kotlin
import java.io.File
import javax.imageio.ImageIO

fun readHeicSample() {
    val image = ImageIO.read(File("samples/photo.heic"))
    println("Loaded ${image.width}x${image.height} (${image.type})")
}
```

### Java

```java
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

BufferedImage image = ImageIO.read(new File("samples/raw.cr2"));
System.out.printf("Loaded %dx%d%n", image.getWidth(), image.getHeight());
```

Supported formats depend entirely on the ImageMagick installation. Ensure the necessary delegates (HEIC, RAW, etc.) are available in the CLI build you ship with your application.

## GraalVM native image
MagickI works in native images with a few additional steps:
- Keep `java.desktop` in the image (`--add-modules=java.desktop` if you build with a custom module list).
- Include service descriptors: add `-H:IncludeResources=META-INF/services/.*` (or the equivalent configuration) so the SPI file is packaged.
- At runtime, make the ImageMagick executables available on `PATH`, via `MAGICK_HOME/bin`, or by passing `-Dmagick.exe`.
- Call `ImageIO.scanForPlugins()` once during startup to ensure the registry pulls in the precompiled service list.

## Troubleshooting
- `magick convert failed (1): no encode delegate...` – the ImageMagick build you are using does not include support for that format. Install the appropriate delegate or ship a build that has it enabled.
- `ImageMagick 6 or 7 was not detected` – verify `magick`/`convert` are on `PATH`, set `-Dmagick.exe=/full/path/to/magick`, or export `MAGICK_HOME` so the factory can look under `$MAGICK_HOME/bin`.

