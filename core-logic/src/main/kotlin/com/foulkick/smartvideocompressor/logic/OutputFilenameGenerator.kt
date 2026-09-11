package com.foulkick.smartvideocompressor.logic

/**
 * Generates a collision-free output filename in the same directory as the source:
 * "OriginalName_1080.mp4", then "OriginalName_1080_1.mp4", "OriginalName_1080_2.mp4", etc.
 * Never overwrites an existing file.
 */
object OutputFilenameGenerator {

    const val SUFFIX = "_1080"
    const val EXTENSION = ".mp4"

    /**
     * @param originalFileName the source file's name, with or without extension (only the base
     *   name before the last '.' is used; the extension is always forced to [EXTENSION]).
     * @param exists predicate returning true if a file with the given name already exists in the
     *   target directory.
     */
    fun generate(originalFileName: String, exists: (String) -> Boolean): String {
        val baseName = stripExtension(originalFileName)
        val candidateBase = "$baseName$SUFFIX"

        val first = "$candidateBase$EXTENSION"
        if (!exists(first)) return first

        var index = 1
        while (true) {
            val candidate = "${candidateBase}_$index$EXTENSION"
            if (!exists(candidate)) return candidate
            index++
        }
    }

    private fun stripExtension(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    }
}
