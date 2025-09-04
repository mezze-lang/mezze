package com.funk.language.typechecker

import com.funk.language.parser.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Comprehensive tests for the module system type checking
 */
class ModuleSystemTest {
    
    private lateinit var moduleChecker: ModuleSystemChecker
    
    @BeforeEach
    fun setup() {
        moduleChecker = ModuleSystemChecker()
    }
    
    // ========== BASIC MODULE CHECKING ==========
    
    @Test
    fun `test simple module with exports`() {
        val module = ModuleDeclaration(
            "TestModule",
            listOf(
                LetBinding("x", SimpleType("Int"), Literal(42)),
                ExposeDeclaration(listOf(
                    ExposeItem(null, "x")
                ))
            )
        )
        
        val moduleEnv = ModuleEnvironment("TestModule")
        val (moduleInfo, typeEnv) = moduleChecker.checkModule(module, moduleEnv)
        
        assertEquals("TestModule", moduleInfo.name)
        assertTrue(moduleInfo.isExported("x"))
        assertEquals(FunkType.IntType, moduleInfo.getExportType("x"))
    }
    
    @Test
    fun `test module with type exports`() {
        val module = ModuleDeclaration(
            "TypeModule",
            listOf(
                TypeDeclaration("Person", emptyList(), RecordType(listOf(
                    RecordTypeEntry("name", SimpleType("String")),
                    RecordTypeEntry("age", SimpleType("Int"))
                ))),
                ExposeDeclaration(listOf(
                    ExposeItem(ExposeKind.TYPE, "Person")
                ))
            )
        )
        
        val moduleEnv = ModuleEnvironment("TypeModule")
        val (moduleInfo, typeEnv) = moduleChecker.checkModule(module, moduleEnv)
        
        assertTrue(moduleInfo.isExported("Person"))
        val exportInfo = moduleInfo.exports["Person"]
        assertNotNull(exportInfo)
        assertEquals(ExportKind.TYPE, exportInfo!!.kind)
    }
    
    @Test
    fun `test module with effect exports`() {
        val module = ModuleDeclaration(
            "EffectModule",
            listOf(
                EffectDeclaration(
                    "IO",
                    RecordType(listOf(RecordTypeEntry("path", SimpleType("String")))),
                    VariantType(listOf(VariantConstructor("Success", RecordType(listOf(
                        RecordTypeEntry("content", SimpleType("String"))
                    )))))
                ),
                ExposeDeclaration(listOf(
                    ExposeItem(ExposeKind.EFFECT, "IO")
                ))
            )
        )
        
        val moduleEnv = ModuleEnvironment("EffectModule")
        val (moduleInfo, typeEnv) = moduleChecker.checkModule(module, moduleEnv)
        
        assertTrue(moduleInfo.isExported("IO"))
        val exportInfo = moduleInfo.exports["IO"]
        assertEquals(ExportKind.EFFECT, exportInfo!!.kind)
    }
    
    // ========== MODULE IMPORTS ==========
    
    @Test
    fun `test module with imports`() {
        // First, create a module to import from
        val sourceModule = ModuleInfo(
            "SourceModule",
            "source.funk",
            mapOf("helper" to ExportInfo("helper", FunkType.IntType, ExportKind.VALUE))
        )
        
        val moduleEnv = ModuleEnvironment("TestModule")
            .withModule(sourceModule)
        
        val importingModule = ModuleDeclaration(
            "TestModule",
            listOf(
                AccessDeclaration("SourceModule", listOf(
                    AccessItem("helper", null)
                )),
                LetBinding("result", null, 
                    BinaryOperation(Identifier("helper"), "+", Literal(1))
                )
            )
        )
        
        val (moduleInfo, typeEnv) = moduleChecker.checkModule(importingModule, moduleEnv)
        
        assertTrue(moduleInfo.dependencies.contains("SourceModule"))
        assertTrue(moduleInfo.imports.containsKey("helper"))
        assertEquals("SourceModule", moduleInfo.imports["helper"]!!.moduleName)
    }
    
    @Test
    fun `test module import with alias`() {
        val sourceModule = ModuleInfo(
            "MathModule",
            "math.funk",
            mapOf("add" to ExportInfo("add", FunkType.IntType, ExportKind.VALUE))
        )
        
        val moduleEnv = ModuleEnvironment("TestModule")
            .withModule(sourceModule)
        
        val importingModule = ModuleDeclaration(
            "TestModule",
            listOf(
                AccessDeclaration("MathModule", listOf(
                    AccessItem("add", "plus")
                )),
                LetBinding("result", null, Identifier("plus"))
            )
        )
        
        val (moduleInfo, typeEnv) = moduleChecker.checkModule(importingModule, moduleEnv)
        
        assertTrue(moduleInfo.imports.containsKey("plus"))
        assertEquals("add", moduleInfo.imports["plus"]!!.importedName)
        assertEquals("plus", moduleInfo.imports["plus"]!!.localName)
    }
    
    @Test
    fun `test import of non-existent module fails`() {
        val moduleEnv = ModuleEnvironment("TestModule")
        
        val importingModule = ModuleDeclaration(
            "TestModule",
            listOf(
                AccessDeclaration("NonExistentModule", listOf(
                    AccessItem("something", null)
                ))
            )
        )
        
        assertThrows<StructuredTypeError> {
            moduleChecker.checkModule(importingModule, moduleEnv)
        }
    }
    
    @Test
    fun `test import of non-exported item fails`() {
        val sourceModule = ModuleInfo(
            "SourceModule",
            "source.funk",
            mapOf("exported" to ExportInfo("exported", FunkType.IntType, ExportKind.VALUE))
            // "private" is not exported
        )
        
        val moduleEnv = ModuleEnvironment("TestModule")
            .withModule(sourceModule)
        
        val importingModule = ModuleDeclaration(
            "TestModule",
            listOf(
                AccessDeclaration("SourceModule", listOf(
                    AccessItem("private", null) // Not exported
                ))
            )
        )
        
        assertThrows<StructuredTypeError> {
            moduleChecker.checkModule(importingModule, moduleEnv)
        }
    }
    
    // ========== DEPENDENCY RESOLUTION ==========
    
    @Test
    fun `test dependency cycle detection`() {
        val moduleA = ModuleInfo("A", "a.funk", dependencies = setOf("B"))
        val moduleB = ModuleInfo("B", "b.funk", dependencies = setOf("C"))
        val moduleC = ModuleInfo("C", "c.funk", dependencies = setOf("A")) // Cycle!
        
        val modules = mapOf(
            "A" to moduleA,
            "B" to moduleB,
            "C" to moduleC
        )
        
        val result = moduleChecker.checkDependencyCycles(modules)
        
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as StructuredTypeError
        assertTrue(error.errorInfo.kind is TypeErrorKind.ModuleError)
        val moduleError = error.errorInfo.kind as TypeErrorKind.ModuleError
        assertEquals(ModuleErrorKind.CIRCULAR_DEPENDENCY, moduleError.kind)
    }
    
    @Test
    fun `test valid dependency order`() {
        val moduleA = ModuleInfo("A", "a.funk", dependencies = setOf("B", "C"))
        val moduleB = ModuleInfo("B", "b.funk", dependencies = setOf("C"))
        val moduleC = ModuleInfo("C", "c.funk", dependencies = emptySet())
        
        val modules = mapOf(
            "A" to moduleA,
            "B" to moduleB,
            "C" to moduleC
        )
        
        val result = moduleChecker.checkDependencyCycles(modules)
        
        assertTrue(result.isSuccess)
        val order = result.getOrNull()!!
        
        // C should come before B, B should come before A
        assertTrue(order.indexOf("C") < order.indexOf("B"))
        assertTrue(order.indexOf("B") < order.indexOf("A"))
    }
    
    // ========== QUALIFIED IDENTIFIERS ==========
    
    @Test
    fun `test qualified identifier resolution`() {
        val mathModule = ModuleInfo(
            "Math",
            "math.funk",
            mapOf("PI" to ExportInfo("PI", FunkType.FloatType, ExportKind.VALUE))
        )
        
        val moduleEnv = ModuleEnvironment("TestModule")
            .withModule(mathModule)
        
        val context = TypeContext()
        
        val qualifiedType = moduleChecker.resolveQualifiedIdentifier(
            "Math.PI",
            moduleEnv,
            context
        )
        
        assertEquals(FunkType.FloatType, qualifiedType)
    }
    
    @Test
    fun `test unqualified identifier resolution`() {
        val context = TypeContext()
        context.env.extend("localVar", TypeScheme.monomorphic(FunkType.IntType))
        
        val moduleEnv = ModuleEnvironment("TestModule")
        
        val localType = moduleChecker.resolveQualifiedIdentifier(
            "localVar",
            moduleEnv,
            context
        )
        
        assertEquals(FunkType.IntType, localType)
    }
    
    @Test
    fun `test qualified identifier with non-existent module`() {
        val moduleEnv = ModuleEnvironment("TestModule")
        val context = TypeContext()
        
        assertThrows<StructuredTypeError> {
            moduleChecker.resolveQualifiedIdentifier(
                "NonExistent.item",
                moduleEnv,
                context
            )
        }
    }
    
    @Test
    fun `test qualified identifier with non-exported item`() {
        val mathModule = ModuleInfo(
            "Math",
            "math.funk",
            mapOf("PI" to ExportInfo("PI", FunkType.FloatType, ExportKind.VALUE))
            // "E" is not exported
        )
        
        val moduleEnv = ModuleEnvironment("TestModule")
            .withModule(mathModule)
        
        val context = TypeContext()
        
        assertThrows<StructuredTypeError> {
            moduleChecker.resolveQualifiedIdentifier(
                "Math.E",
                moduleEnv,
                context
            )
        }
    }
    
    // ========== ABILITY DECLARATIONS ==========
    
    @Test
    fun `test ability declaration processing`() {
        val module = ModuleDeclaration(
            "EffectModule",
            listOf(
                AbilityDeclaration(
                    "FileSystem",
                    listOf(
                        EffectSignature(
                            "read",
                            RecordType(listOf(RecordTypeEntry("path", SimpleType("String")))),
                            VariantType(listOf(VariantConstructor("Success", RecordType(listOf(
                                RecordTypeEntry("content", SimpleType("String"))
                            )))))
                        ),
                        EffectSignature(
                            "write",
                            RecordType(listOf(
                                RecordTypeEntry("path", SimpleType("String")),
                                RecordTypeEntry("content", SimpleType("String"))
                            )),
                            VariantType(listOf(VariantConstructor("Success")))
                        )
                    )
                )
            )
        )
        
        val moduleEnv = ModuleEnvironment("EffectModule")
        val (moduleInfo, typeEnv) = moduleChecker.checkModule(module, moduleEnv)
        
        // Ability should register its effects in the environment
        assertNotNull(typeEnv.lookupEffect("read"))
        assertNotNull(typeEnv.lookupEffect("write"))
    }
    
    // ========== CONTENT-ADDRESSABLE MODULES ==========
    
    @Test
    fun `test module hash computation`() {
        val module = ModuleDeclaration(
            "TestModule",
            listOf(
                LetBinding("x", null, Literal(42))
            )
        )
        
        val dependencies = mapOf("Dep1" to "hash1", "Dep2" to "hash2")
        
        val hash1 = ContentAddressableModules.computeModuleHash(module, dependencies)
        val hash2 = ContentAddressableModules.computeModuleHash(module, dependencies)
        
        // Same input should produce same hash
        assertEquals(hash1, hash2)
        
        // Different dependencies should produce different hash
        val differentDeps = mapOf("Dep1" to "hash1", "Dep2" to "differentHash")
        val hash3 = ContentAddressableModules.computeModuleHash(module, differentDeps)
        
        assertNotEquals(hash1, hash3)
    }
    
    @Test
    fun `test module compatibility checking`() {
        val oldHash = "old_hash"
        val newHash = "new_hash"
        val compatibilityRules = mapOf<String, (String, String) -> Boolean>(
            "semantic_versioning" to { old, new -> old != new } // Simplified rule
        )
        
        val compatible = ContentAddressableModules.isModuleCompatible(
            oldHash,
            newHash,
            compatibilityRules
        )
        
        assertTrue(compatible)
    }
    
    @Test
    fun `test module evolution tracking`() {
        val oldModule = ModuleInfo(
            "TestModule",
            "test.funk",
            mapOf(
                "oldFunc" to ExportInfo("oldFunc", FunkType.IntType, ExportKind.VALUE),
                "changedFunc" to ExportInfo("changedFunc", FunkType.IntType, ExportKind.VALUE)
            ),
            hash = "old_hash"
        )
        
        val newModule = ModuleInfo(
            "TestModule",
            "test.funk",
            mapOf(
                "changedFunc" to ExportInfo("changedFunc", FunkType.StringType, ExportKind.VALUE),
                "newFunc" to ExportInfo("newFunc", FunkType.BooleanType, ExportKind.VALUE)
            ),
            hash = "new_hash"
        )
        
        val evolution = ContentAddressableModules.evolveModule(oldModule, newModule)
        
        assertEquals("old_hash", evolution.oldHash)
        assertEquals("new_hash", evolution.newHash)
        assertTrue(evolution.addedExports.contains("newFunc"))
        assertTrue(evolution.removedExports.contains("oldFunc"))
        assertTrue(evolution.changedExports.containsKey("changedFunc"))
        
        // Should not be backward compatible due to removed export
        assertFalse(evolution.isBackwardCompatible)
    }
    
    // ========== DEPENDENCY RESOLVER UTILITIES ==========
    
    @Test
    fun `test dependency graph building`() {
        val moduleA = ModuleInfo("A", "a.funk", dependencies = setOf("B", "C"))
        val moduleB = ModuleInfo("B", "b.funk", dependencies = setOf("C"))
        val moduleC = ModuleInfo("C", "c.funk", dependencies = emptySet())
        
        val modules = mapOf(
            "A" to moduleA,
            "B" to moduleB,
            "C" to moduleC
        )
        
        val graph = ModuleDependencyResolver.buildDependencyGraph(modules)
        
        assertEquals(setOf("B", "C"), graph["A"])
        assertEquals(setOf("C"), graph["B"])
        assertEquals(emptySet<String>(), graph["C"])
    }
    
    @Test
    fun `test transitive dependency finding`() {
        val moduleA = ModuleInfo("A", "a.funk", dependencies = setOf("B"))
        val moduleB = ModuleInfo("B", "b.funk", dependencies = setOf("C"))
        val moduleC = ModuleInfo("C", "c.funk", dependencies = setOf("D"))
        val moduleD = ModuleInfo("D", "d.funk", dependencies = emptySet())
        
        val modules = mapOf(
            "A" to moduleA,
            "B" to moduleB,
            "C" to moduleC,
            "D" to moduleD
        )
        
        val transitive = ModuleDependencyResolver.findTransitiveDependencies("A", modules)
        
        assertEquals(setOf("B", "C", "D"), transitive)
    }
    
    // ========== INTEGRATION TESTS ==========
    
    @Test
    fun `test complex module system with multiple features`() {
        // Create a math utility module
        val mathModule = ModuleDeclaration(
            "Math",
            listOf(
                LetBinding("PI", SimpleType("Float"), Literal(3.14f)),
                LetBinding("add", null, LambdaExpression(
                    RecordType(listOf(
                        RecordTypeEntry("x", SimpleType("Int")),
                        RecordTypeEntry("y", SimpleType("Int"))
                    )),
                    BinaryOperation(Identifier("x"), "+", Identifier("y"))
                )),
                ExposeDeclaration(listOf(
                    ExposeItem(null, "PI"),
                    ExposeItem(null, "add")
                ))
            )
        )
        
        // Create a main module that uses the math module
        val mainModule = ModuleDeclaration(
            "Main",
            listOf(
                AccessDeclaration("Math", listOf(
                    AccessItem("PI", "pi"),
                    AccessItem("add", null)
                )),
                LetBinding("result", null, 
                    FunctionCall(
                        Identifier("add"),
                        RecordLiteral(listOf(
                            RecordEntry("x", Literal(1)),
                            RecordEntry("y", Literal(2))
                        ))
                    )
                ),
                LetBinding("circle", null, 
                    BinaryOperation(
                        Literal(2.0f),
                        "*",
                        Identifier("pi")
                    )
                )
            )
        )
        
        // Check math module first
        val mathModuleEnv = ModuleEnvironment("Math")
        val (mathModuleInfo, mathTypeEnv) = moduleChecker.checkModule(mathModule, mathModuleEnv)
        
        // Check main module with math module available
        val mainModuleEnv = ModuleEnvironment("Main")
            .withModule(mathModuleInfo)
            .withModuleTypes("Math", mathTypeEnv)
        
        val (mainModuleInfo, mainTypeEnv) = moduleChecker.checkModule(mainModule, mainModuleEnv)
        
        // Verify dependencies
        assertTrue(mainModuleInfo.dependencies.contains("Math"))
        
        // Verify imports
        assertTrue(mainModuleInfo.imports.containsKey("pi"))
        assertTrue(mainModuleInfo.imports.containsKey("add"))
        assertEquals("PI", mainModuleInfo.imports["pi"]!!.importedName)
        
        // Verify type checking worked
        assertNotNull(mainTypeEnv.lookup("result"))
        assertNotNull(mainTypeEnv.lookup("circle"))
    }
    
    // ========== ERROR SCENARIOS ==========
    
    @Test
    fun `test module system error propagation`() {
        val module = ModuleDeclaration(
            "ErrorModule",
            listOf(
                LetBinding("x", SimpleType("Int"), Literal("wrong type")), // Type error
                ExposeDeclaration(listOf(
                    ExposeItem(null, "x")
                ))
            )
        )
        
        val moduleEnv = ModuleEnvironment("ErrorModule")
        
        // Should propagate type checking errors
        assertThrows<StructuredTypeError> {
            moduleChecker.checkModule(module, moduleEnv)
        }
    }
    
    @Test
    fun `test export of undefined item`() {
        val module = ModuleDeclaration(
            "BadModule",
            listOf(
                ExposeDeclaration(listOf(
                    ExposeItem(null, "undefined") // Not defined in module
                ))
            )
        )
        
        val moduleEnv = ModuleEnvironment("BadModule")
        
        assertThrows<StructuredTypeError> {
            moduleChecker.checkModule(module, moduleEnv)
        }
    }
}
