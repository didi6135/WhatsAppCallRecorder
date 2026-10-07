package com.didi4164.WhatsAppCallRecorder

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import java.util.Locale

class AppLanguageModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  override fun getName() = "AppLanguage"
  override fun getConstants(): Map<String, Any> = mapOf("deviceLocale" to Locale.getDefault().toLanguageTag())
  @ReactMethod fun setLanguage(language: String, promise: Promise) {
    try { AppText.setLanguage(context, language); promise.resolve(null) }
    catch (_: Exception) { promise.reject("LANGUAGE_SAVE_FAILED", "The app language could not be saved.") }
  }
}
class AppLanguagePackage : ReactPackage {
  override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(AppLanguageModule(context))
  override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
