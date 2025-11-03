package com.funk.language.interpreter

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection

/**
 * Native Image feature for Funk language
 * This registers necessary classes for reflection and ensures proper initialization
 * Configured to work with Truffle in interpreted mode only
 */
class FunkNativeImageFeature : Feature {
    
    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        println("🔧 Funk Native Image Feature: Configuring Truffle compatibility...")
        
        // Register Funk language classes for reflection
        RuntimeReflection.register(FunkLanguage::class.java)
        RuntimeReflection.register(FunkContext::class.java)
        RuntimeReflection.register(FunkFileDetector::class.java)
        
        // Register all constructors and methods (GraalVM 17 API)
        RuntimeReflection.register(*FunkLanguage::class.java.declaredConstructors)
        RuntimeReflection.register(*FunkLanguage::class.java.declaredMethods)
        RuntimeReflection.register(*FunkLanguage::class.java.declaredFields)
        
        // Register parser classes
        try {
            val parserClass = Class.forName("com.funk.language.parserv2.FunkParser")
            RuntimeReflection.register(parserClass)
            RuntimeReflection.register(*parserClass.declaredConstructors)
            RuntimeReflection.register(*parserClass.declaredMethods)
        } catch (e: ClassNotFoundException) {
            // Parser not available in this build
        }
        
        // Register ANTLR generated classes
        try {
            val lexerClass = Class.forName("com.funk.language.parser.generated.FunkLexer")
            val parserClass = Class.forName("com.funk.language.parser.generated.FunkParser")
            
            RuntimeReflection.register(lexerClass)
            RuntimeReflection.register(parserClass)
            RuntimeReflection.register(*lexerClass.declaredConstructors)
            RuntimeReflection.register(*parserClass.declaredConstructors)
            RuntimeReflection.register(*lexerClass.declaredMethods)
            RuntimeReflection.register(*parserClass.declaredMethods)
        } catch (e: ClassNotFoundException) {
            // ANTLR classes not available
        }
        
        println("✅ Funk Native Image Feature: Configuration complete with Truffle substitutions")
    }
    
    override fun duringSetup(access: Feature.DuringSetupAccess) {
        // Additional setup if needed
    }
}
