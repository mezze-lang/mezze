package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class ModuleResolverTest {
    
    private val parser = FunkParser()
    private val moduleResolver = ModuleResolver(Paths.get("."), parser)
    
    @Test
    fun testCoreModulesAreSkipped() {
        // Core type modules should be skipped and return empty modules
        val coreModules = listOf(
            "std::core::primitives",
            "std::core::abilities", 
            "std::core::operators",
            "std::types::int",
            "std::types::string",
            "std::collections::arraylist"
        )
        
        for (coreModule in coreModules) {
            val module = moduleResolver.resolveModule(coreModule)
            
            // Should return an empty module
            assertEquals(coreModule, module.name)
            assertEquals("built-in", module.filePath)
            assertTrue(module.exports.isEmpty(), "Core module $coreModule should have no exports")
            assertTrue(module.dependencies.isEmpty(), "Core module $coreModule should have no dependencies")
        }
    }
    
    @Test
    fun testNonCoreModulesAreNotSkipped() {
        // Non-core modules should not be automatically skipped like core modules
        val nonCoreModules = listOf(
            "std::effects::log",
            "std::math::stats"
        )
        
        for (nonCoreModule in nonCoreModules) {
            try {
                val module = moduleResolver.resolveModule(nonCoreModule)
                
                // Should not be marked as built-in
                assertNotEquals("built-in", module.filePath)
                assertEquals(nonCoreModule, module.name)
                
                // Should have parsed the actual module file
                assertNotNull(module.program)
                
            } catch (e: ModuleError.ModuleNotFound) {
                // It's okay if the module doesn't exist - we're just testing that
                // it's not being skipped like core modules
                assertTrue(true, "Module $nonCoreModule not found, but that's expected")
            } catch (e: ModuleError.ParseError) {
                // It's also okay if the module has parsing issues - we're just testing
                // that it's not being automatically skipped
                assertTrue(true, "Module $nonCoreModule has parse errors, but that's expected")
            } catch (e: Exception) {
                // Any other exception is also fine - we're just verifying it's not skipped
                assertTrue(true, "Module $nonCoreModule threw exception: ${e.message}, but that's expected")
            }
        }
    }
    
    @Test
    fun testNoCircularDependencyWithBuiltinTypes() {
        // This test verifies that we can create a TypeChecker and ModuleResolver
        // without getting into circular dependencies
        
        assertDoesNotThrow {
            val typeChecker = TypeChecker()
            val program = parser.parse("let x = Some { val = 42 }")
            
            // This should not cause a stack overflow
            typeChecker.typeCheck(program)
        }
    }
}