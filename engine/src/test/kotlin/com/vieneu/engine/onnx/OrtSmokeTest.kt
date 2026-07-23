package com.vieneu.engine.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/** Just proves desktop onnxruntime (linux-aarch64) can load our real graph on host. */
class OrtSmokeTest {
    @Test
    fun loadsPrefillGraph() {
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession("src/main/assets/onnx_update/vieneu_prefill.onnx", OrtSession.SessionOptions())
        assertTrue(session.inputNames.contains("inputs_embeds"))
        session.close()
    }
}
