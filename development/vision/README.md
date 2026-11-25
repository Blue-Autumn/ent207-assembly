# Rebuild the browser vision assets

A normal site preview uses the checked-in JavaScript and WebAssembly under [vision](../../vision/). Rebuild the Kotlin/JS bundle after changing the shared board geometry, the Kotlin guidance classes listed in build.py, or the browser bridge.

1. Build Android first and make Java available on PATH or through JAVA_HOME. The compiler script reads Kotlin 2.2.10 and its JVM dependencies from the default Gradle cache.
2. Create cache/ beside build.py and place the [Kotlin/JS 2.2.10 standard library](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-js/2.2.10/kotlin-stdlib-js-2.2.10.klib) there under its original filename. Run `python build.py`. The script checks the library hash and writes the generated vision.js into the website.
3. To regenerate the OpenCV loader and payload, save the [official 4.13.0 JavaScript distribution](https://docs.opencv.org/4.13.0/opencv.js) as cache/opencv-4.13.js and run `python package_opencv.py`. The script checks the input SHA-256 before extracting its WebAssembly.
4. preprocess.wat contains the optional SIMD pixel filter used by the camera benchmark. The standard JavaScript reader remains available. Review the generated assets and exercise the web camera bench before publishing detector changes.
