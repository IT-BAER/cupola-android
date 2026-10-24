package com.baer.cupola.core.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The float layout is declared twice (Kotlin and C++); this keeps both in lockstep. */
class FrameInputsLayoutTest {
    @Test
    fun cppHeaderMatchesKotlinConstants() {
        val header = File("src/main/cpp/frame_inputs.h").readText()
        val cpp = Regex("""constexpr\s+int\s+k(\w+)\s*=\s*(\d+)\s*;""").findAll(header)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val kotlin = mapOf(
            "Load" to FrameInputs.LOAD,
            "LoadIterations" to FrameInputs.LOAD_ITERATIONS,
            "TimeSeconds" to FrameInputs.TIME_SECONDS,
            "LookYawDeg" to FrameInputs.LOOK_YAW_DEG,
            "LookPitchDeg" to FrameInputs.LOOK_PITCH_DEG,
            "Scene" to FrameInputs.SCENE,
            "RenderScale" to FrameInputs.RENDER_SCALE,
            "Effects" to FrameInputs.EFFECTS,
            "SceneBlock" to FrameInputs.SCENE_BLOCK,
            "CameraPosKm" to FrameInputs.CAMERA_POS_KM,
            "CameraEllipsoidC" to FrameInputs.CAMERA_ELLIPSOID_C,
            "CameraRight" to FrameInputs.CAMERA_RIGHT,
            "MonthWeight" to FrameInputs.MONTH_WEIGHT,
            "CameraUp" to FrameInputs.CAMERA_UP,
            "MonthLayer0" to FrameInputs.MONTH_LAYER0,
            "CameraBack" to FrameInputs.CAMERA_BACK,
            "MonthLayer1" to FrameInputs.MONTH_LAYER1,
            "SunDir" to FrameInputs.SUN_DIR,
            "TanHalfFovLong" to FrameInputs.TAN_HALF_FOV_LONG,
            "MoonDir" to FrameInputs.MOON_DIR,
            "MoonLight" to FrameInputs.MOON_LIGHT,
            "InteriorRight" to FrameInputs.INTERIOR_RIGHT,
            "Interior" to FrameInputs.INTERIOR,
            "InteriorUp" to FrameInputs.INTERIOR_UP,
            "EarthLight" to FrameInputs.EARTH_LIGHT,
            "InteriorBack" to FrameInputs.INTERIOR_BACK,
            "Exposure" to FrameInputs.EXPOSURE,
            "SkyUp" to FrameInputs.SKY_UP,
            "SkyHorizonNadir" to FrameInputs.SKY_HORIZON_NADIR,
            "SkySunAxis" to FrameInputs.SKY_SUN_AXIS,
            "SkyTopNadir" to FrameInputs.SKY_TOP_NADIR,
            "CloudFlow" to FrameInputs.CLOUD_FLOW,
            "StarRowX" to FrameInputs.STAR_ROW_X,
            "StarRowY" to FrameInputs.STAR_ROW_Y,
            "StarRowZ" to FrameInputs.STAR_ROW_Z,
            "SunLight" to FrameInputs.SUN_LIGHT,
            "HomeCity" to FrameInputs.HOME_CITY,
            "Count" to FrameInputs.COUNT,
        )
        assertEquals(kotlin, cpp)
    }
}
