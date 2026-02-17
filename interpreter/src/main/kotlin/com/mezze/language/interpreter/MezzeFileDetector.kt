package com.mezze.language.interpreter

import com.oracle.truffle.api.TruffleFile
import com.oracle.truffle.api.TruffleLanguage
import java.io.IOException
import java.nio.charset.Charset

/**
 * File type detector for Mezze language files.
 * Detects .mz files and associates them with the Mezze language.
 */
class MezzeFileDetector : TruffleFile.FileTypeDetector {
    
    override fun findMimeType(file: TruffleFile): String? {
        val fileName = file.name
        return if (fileName != null && fileName.endsWith(".mz")) {
            MezzeLanguage.MIME_TYPE
        } else {
            null
        }
    }
    
    override fun findEncoding(file: TruffleFile): Charset? {
        // Use UTF-8 encoding for all Mezze files
        return if (findMimeType(file) != null) {
            Charsets.UTF_8
        } else {
            null
        }
    }
}
