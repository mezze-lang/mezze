package com.funk.language.interpreter

import com.oracle.truffle.api.TruffleFile
import com.oracle.truffle.api.TruffleLanguage
import java.io.IOException
import java.nio.charset.Charset

/**
 * File type detector for Funk language files.
 * Detects .funk files and associates them with the Funk language.
 */
class FunkFileDetector : TruffleFile.FileTypeDetector {
    
    override fun findMimeType(file: TruffleFile): String? {
        val fileName = file.name
        return if (fileName != null && fileName.endsWith(".funk")) {
            FunkLanguage.MIME_TYPE
        } else {
            null
        }
    }
    
    override fun findEncoding(file: TruffleFile): Charset? {
        // Use UTF-8 encoding for all Funk files
        return if (findMimeType(file) != null) {
            Charsets.UTF_8
        } else {
            null
        }
    }
}
