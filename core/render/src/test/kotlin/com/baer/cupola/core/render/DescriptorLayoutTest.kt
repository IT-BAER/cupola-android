package com.baer.cupola.core.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Set 0 of each pipeline layout (descriptor_bindings.h) against the shader that uses it: the same bindings by name
 * (C++ kFooBar is GLSL fooBar or block FooBar), and no more combined image samplers per stage than every Vulkan device
 * supports (16, the minimum of maxPerStageDescriptorSamplers and maxPerStageDescriptorSampledImages).
 */
class DescriptorLayoutTest {
    private data class Binding(val binding: Int, val name: String, val sampler: Boolean)

    @Test
    fun earthLayoutMatchesShader() = check("earth.frag", "earth_binding")

    @Test
    fun stationLayoutMatchesShader() = check("station.frag", "station_binding")

    private fun check(shader: String, namespace: String) {
        val glsl = File("src/main/cpp/shaders/$shader").readText()
        val declared = Regex("""layout\(set = 0, binding = (\d+)[^)]*\)\s*uniform\s+(\w+)\s*(\w*)\s*[;{]""")
            .findAll(glsl)
            .map { m ->
                val (binding, type, name) = m.destructured
                Binding(binding.toInt(), name.ifEmpty { type }, type.startsWith("sampler"))
            }
            .toList()
        val samplers = declared.count { it.sampler }
        assertTrue("$shader declares $samplers samplers, more than $MAX_STAGE_SAMPLERS", samplers <= MAX_STAGE_SAMPLERS)
        assertEquals("$shader: binding 0 is the scene block, the others samplers",
            declared.map { it.binding != 0 }, declared.map { it.sampler })

        val header = File("src/main/cpp/descriptor_bindings.h").readText()
        val block = Regex("""namespace $namespace \{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(header)
            ?.groupValues?.get(1) ?: error("namespace $namespace missing in descriptor_bindings.h")
        val cpp = Regex("""constexpr\s+uint32_t\s+k(\w+)\s*=\s*(\d+)\s*;""").findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val count = cpp.getValue("Count")
        assertEquals("$namespace bindings", (0 until count).toList(), declared.map { it.binding }.sorted())
        assertEquals(cpp - "Count", declared.associate { it.name.replaceFirstChar(Char::uppercaseChar) to it.binding })
        val maxSamplers = Regex("""constexpr\s+uint32_t\s+kMaxStageSamplers\s*=\s*(\d+)\s*;""").find(header)
            ?.groupValues?.get(1)?.toInt()
        assertEquals(MAX_STAGE_SAMPLERS, maxSamplers)
    }

    private companion object {
        const val MAX_STAGE_SAMPLERS = 16
    }
}
