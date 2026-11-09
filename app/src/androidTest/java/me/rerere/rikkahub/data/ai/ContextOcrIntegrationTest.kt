package me.rerere.rikkahub.data.ai

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestControl
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ContextOcrIntegrationTest {
    @Test fun editedOcrRequestDoesNotPoisonTheOrdinaryImageCache() = runBlocking {
        fun reply(text: String) = """{"id":"ocr","model":"fixture-ocr","choices":[{"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}"""
        LoopbackProvider(reply("edited-result")).use { server ->
            val settings = GlobalContext.get().get<SettingsStore>()
            val previous = settings.awaitLoaded()
            val model = Model(modelId = "fixture-ocr", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
            val provider = ProviderSetting.OpenAI(name = "OCR fixture", models = listOf(model), baseUrl = server.baseUrl)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val image = File(context.cacheDir, "context-ocr-${Uuid.random()}.png")
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            settings.update { it.copy(providers = it.providers + provider, ocrModelId = model.id, ocrPrompt = "fixture-ocr-prompt") }
            try {
                val part = UIMessagePart.Image(image.toURI().toString())
                val edited = OcrTransformer.performOcr(part, requestControl = { _, _ ->
                    object : RequestControl {
                        override suspend fun prepare(protocol: String, endpoint: String, serializedBody: String) =
                            serializedBody.replace("fixture-ocr-prompt", "edited-ocr-prompt")
                    }
                })
                assertTrue(edited.contains("edited-result"))
                assertTrue(server.bodies.peek().contains("edited-ocr-prompt"))
                server.response = reply("ordinary-result")
                val ordinary = OcrTransformer.performOcr(part)
                assertTrue(ordinary.contains("ordinary-result"))
                assertEquals(2, server.bodies.size)
                server.response = reply("unexpected-third-request")
                assertEquals(ordinary, OcrTransformer.performOcr(part))
                assertEquals(2, server.bodies.size)
            } finally {
                settings.update { it.copy(providers = it.providers.filterNot { p -> p.id == provider.id }, ocrModelId = previous.ocrModelId, ocrPrompt = previous.ocrPrompt) }
                image.delete()
            }
        }
    }
}
