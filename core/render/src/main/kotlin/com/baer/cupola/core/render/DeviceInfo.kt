package com.baer.cupola.core.render

/** Properties of the shared Vulkan device. [driverVersion] is vendor encoded; [apiVersion] uses VK_MAKE_API_VERSION. */
data class DeviceInfo(
    val name: String,
    val apiVersion: Int,
    val driverVersion: Long,
    val astcLdr: Boolean,
    val maxImageDimension2D: Int,
    val timestampPeriod: Float,
    val timestampsSupported: Boolean,
) {
    val apiVersionString: String
        get() = "${(apiVersion ushr 22) and 0x7F}.${(apiVersion ushr 12) and 0x3FF}.${apiVersion and 0xFFF}"
}
