package com.example.localllmchat.data.leap

import android.content.Context
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.manifest.LeapDownloader
import ai.liquid.leap.manifest.LeapDownloaderConfig
import ai.liquid.leap.manifest.ModelSource
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.ChatMessageContent
import ai.liquid.leap.message.MessageResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class LeapModelState {
    data object NotLoaded : LeapModelState()
    data class Downloading(val progress: Float) : LeapModelState()
    data object Loading : LeapModelState()
    data object Ready : LeapModelState()
    data class Error(val message: String) : LeapModelState()
}

class LeapModelManager(context: Context) {

    private val leapDownloader = LeapDownloader(
        LeapDownloaderConfig(
            saveDir = context.getExternalFilesDir(null)!!.resolve("leap_models").absolutePath
        )
    )

    private var modelRunner: ModelRunner? = null

    private val _modelState = MutableStateFlow<LeapModelState>(LeapModelState.NotLoaded)
    val modelState: StateFlow<LeapModelState> = _modelState.asStateFlow()

    companion object {
        private const val HF_BASE = "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main"
        private const val MODEL_FILE = "LFM2.5-VL-1.6B-Q4_0.gguf"
        private const val MMPROJ_FILE = "mmproj-LFM2.5-VL-1.6b-Q8_0.gguf"
    }

    suspend fun loadModel() {
        if (_modelState.value is LeapModelState.Ready) return

        try {
            _modelState.value = LeapModelState.Downloading(0f)

            val modelSource = ModelSource(
                modelPath = "$HF_BASE/$MODEL_FILE",
                mmprojPath = "$HF_BASE/$MMPROJ_FILE",
                modelName = "LFM2.5-VL-1.6B",
                quantizationId = "Q4_0"
            )

            val runner = leapDownloader.loadSimpleModel(
                model = modelSource,
                progress = { progressData ->
                    _modelState.value = LeapModelState.Downloading(progressData.progress)
                }
            )

            _modelState.value = LeapModelState.Loading
            modelRunner = runner
            _modelState.value = LeapModelState.Ready
        } catch (e: Exception) {
            _modelState.value = LeapModelState.Error(
                e.message ?: "モデルのロードに失敗しました"
            )
        }
    }

    /**
     * 画像をVLモデルで読み取り、テキスト記述を返す
     *
     * @param jpegBytes JPEG形式の画像バイト配列
     * @param userPrompt ユーザーの質問テキスト
     * @return 画像の内容を説明するテキスト
     */
    suspend fun describeImage(jpegBytes: ByteArray, userPrompt: String): Result<String> {
        val runner = modelRunner ?: return Result.failure(
            IllegalStateException("モデルがロードされていません")
        )

        return try {
            val conv = runner.createConversation(
                systemPrompt = "You are a vision assistant. Describe the image content in detail. If there is text in the image, transcribe it. Respond in the same language as the user's question."
            )

            val imageContent = ChatMessageContent.Image(jpegBytes)
            val textContent = ChatMessageContent.Text(userPrompt)
            val message = ChatMessage(
                role = ChatMessage.Role.USER,
                content = listOf(imageContent, textContent)
            )

            val responseBuilder = StringBuilder()
            conv.generateResponse(message)
                .collect { response ->
                    when (response) {
                        is MessageResponse.Chunk -> {
                            responseBuilder.append(response.text)
                        }
                        is MessageResponse.Complete -> {
                            // Generation finished
                        }
                        else -> {}
                    }
                }

            Result.success(responseBuilder.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * モデルをアンロードしてメモリを解放する
     */
    suspend fun unloadModel() {
        try {
            modelRunner?.unload()
        } catch (_: Exception) {
            // Ignore errors during cleanup
        }
        modelRunner = null
        _modelState.value = LeapModelState.NotLoaded
    }

    /**
     * モデルがReady状態かどうか
     */
    fun isReady(): Boolean = _modelState.value is LeapModelState.Ready
}
