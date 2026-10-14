package com.swmansion.enriched.markdown.parser

import android.util.Log

/**
 * Display names for code block fence languages ("py" -> "Python"), from the
 * shared C++ core (cpp/highlight/CodeBlockLanguages.hpp) so every platform
 * labels a fence the same way. When the native library is unavailable the
 * raw fence string is shown instead.
 */
object CodeBlockLanguages {
  init {
    try {
      System.loadLibrary("enriched_markdown_parser")
    } catch (e: UnsatisfiedLinkError) {
      Log.e("CodeBlockLanguages", "Failed to load native library", e)
    }
  }

  @JvmStatic
  private external fun nativeDisplayName(language: String): String

  fun displayName(language: String?): String {
    if (language.isNullOrEmpty()) return ""
    return try {
      nativeDisplayName(language)
    } catch (e: UnsatisfiedLinkError) {
      language
    }
  }
}
