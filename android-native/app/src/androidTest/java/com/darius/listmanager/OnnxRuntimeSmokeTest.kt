package com.darius.listmanager

import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxRuntimeSmokeTest {
    @Test
    fun loadsNativeRuntimeOnDevice() {
        assertNotNull(OrtEnvironment.getEnvironment())
    }
}
