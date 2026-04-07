package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class DebugRecordClosureTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `debug record closure inference`() {
        val code = """
            let accessField = {v} -> match v is
              Record {data} -> data.x
              _ -> 0

            accessField
        """.trimIndent()

        println("Code: $code")
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        println("Parsed successfully")

        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        println("Type checked successfully!")

        // Get the type of accessField via v2 API
        val accessFieldType = result.getVariableType("accessField")

        println("\n===== TYPE INFORMATION =====")
        println("accessField type: $accessFieldType")

        // If it's a function type, extract parameter type
        val funcType = accessFieldType as? MezzeType.FunctionType
        if (funcType != null) {
            println("\nParameter type: ${funcType.paramType}")
            val paramRecord = funcType.paramType as? MezzeType.RecordType
            if (paramRecord != null) {
                println("Parameter fields: ${paramRecord.fields}")
                val vType = paramRecord.fields["v"]
                println("v field type: $vType")

                // If v is a variant type, check the Record constructor
                val variantType = vType as? MezzeType.VariantType
                if (variantType != null) {
                    println("v is variant with constructors: ${variantType.constructors.keys}")
                    val recordConstructorType = variantType.constructors["Record"]
                    println("Record constructor type: $recordConstructorType")

                    // Check if it's a record type
                    val recordDataType = recordConstructorType as? MezzeType.RecordType
                    if (recordDataType != null) {
                        println("Record data fields: ${recordDataType.fields}")
                        val dataFieldType = recordDataType.fields["data"]
                        println("data field type: $dataFieldType")

                        // Check if data field is open or closed
                        val dataRecordType = dataFieldType as? MezzeType.RecordType
                        if (dataRecordType != null) {
                            println("data is ${if (dataRecordType.isOpen) "OPEN" else "CLOSED"} record")
                            println("data fields: ${dataRecordType.fields}")
                            println("data rowVar: ${dataRecordType.rowVar}")
                        }
                    }
                }
            }
        }
    }
}
