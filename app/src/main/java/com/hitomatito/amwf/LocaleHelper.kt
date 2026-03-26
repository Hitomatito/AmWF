package com.hitomatito.amwf

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {
    
    private const val PREFS_NAME = "AmWF_Prefs"
    private const val KEY_LANGUAGE = "app_language"
    
    const val SPANISH = "es"
    const val ENGLISH = "en"
    
    private var prefs: SharedPreferences? = null
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun setLocale(context: Context, language: String): Context {
        saveLanguage(language)
        return updateResources(context, language)
    }
    
    fun getLocale(context: Context): String {
        return prefs?.getString(KEY_LANGUAGE, getSystemLanguage()) ?: getSystemLanguage()
    }
    
    fun getCurrentLocale(context: Context): String {
        return prefs?.getString(KEY_LANGUAGE, SPANISH) ?: SPANISH
    }
    
    private fun saveLanguage(language: String) {
        prefs?.edit()?.putString(KEY_LANGUAGE, language)?.apply()
    }
    
    private fun getSystemLanguage(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Locale.getDefault().language
        } else {
            java.util.Locale.getDefault().language
        }
    }
    
    fun updateResources(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        
        val config = Configuration(context.resources.configuration)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLocales(android.os.LocaleList(locale))
        } else {
            config.setLocale(locale)
        }
        
        return context.createConfigurationContext(config)
    }
    
    fun applyLocale(context: Context): Context {
        val language = getCurrentLocale(context)
        return updateResources(context, language)
    }
    
    fun toggleLanguage(context: Context): String {
        val currentLang = getCurrentLocale(context)
        val newLang = if (currentLang == SPANISH) ENGLISH else SPANISH
        setLocale(context, newLang)
        return newLang
    }
    
    fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
            SPANISH -> "Español"
            ENGLISH -> "English"
            else -> "Unknown"
        }
    }
}
