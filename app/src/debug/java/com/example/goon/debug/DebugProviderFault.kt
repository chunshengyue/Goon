package com.example.goon.debug

import android.net.Uri
import com.example.goon.core.ApplicationSpec
import com.example.goon.core.ModelProvider
import com.example.goon.core.ModelResponse
import org.json.JSONArray
import java.io.IOException

/**
 * debug 专用的模型故障注入。评测需要验证「模型不可用时是否诚实失败」，但改写用户 Provider
 * 配置会污染真实凭据和端点，因此故障只在 bridge 进程内生效，且 release 不包含本文件。
 */
object DebugProviderFault {
    @Volatile
    var mode: String? = null

    fun provider(model: String, faultMode: String): ModelProvider = FailingProvider(model, faultMode)

    private class FailingProvider(private val model: String, private val faultMode: String) : ModelProvider {
        override val modelName: String get() = model

        override fun cancel() {}

        override fun turn(messages: JSONArray, tools: JSONArray, system: String, onDelta: (String) -> Unit, callback: (Result<ModelResponse>) -> Unit) {
            callback(Result.failure(IOException("注入的 $faultMode 故障：模型服务不可用。")))
        }

        override fun complete(prompt: String, project: ApplicationSpec?, createNew: Boolean, imageUri: Uri?, onDelta: (String) -> Unit, callback: (Result<ModelResponse>) -> Unit) {
            callback(Result.failure(IOException("注入的 $faultMode 故障：模型服务不可用。")))
        }
    }
}
