/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.util

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.util.UUID

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/795
object BatchPatchIntents {
    const val ACTION_PATCH_APP = "app.universal.patch.manager.action.PATCH_APP"
    const val ACTION_CHECK_UPDATES = "app.universal.patch.manager.action.CHECK_PATCH_UPDATES"
    const val ACTION_SHOW_RESULT = "app.universal.patch.manager.action.SHOW_BATCH_RESULT"
    const val ACTION_SHOW_APPS = "app.universal.patch.manager.action.SHOW_PATCHED_APPS"
    const val EXTRA_PACKAGES = "packages"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_SCHEDULED = "scheduled"
    private const val EXTRA_INTERNAL_TOKEN = "internal_token"
    private const val INTERNAL_TOKEN_PREFERENCES = "internal_batch_actions"
    private const val INTERNAL_TOKEN_KEY = "token"

    fun markInternal(context: Context, intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_INTERNAL_TOKEN, internalToken(context))
    }

    fun isTrustedInternal(context: Context, intent: Intent): Boolean {
        val expected = internalToken(context).toByteArray()
        val provided = intent.getStringExtra(EXTRA_INTERNAL_TOKEN)?.toByteArray() ?: return false
        return MessageDigest.isEqual(expected, provided)
    }

    fun packageNames(intent: Intent): List<String> = (
        intent.getStringArrayExtra(EXTRA_PACKAGES)?.toList()
            ?: intent.getStringArrayListExtra(EXTRA_PACKAGES)
            ?: intent.getStringExtra(EXTRA_PACKAGES)?.split(',')
        ).orEmpty()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    private fun internalToken(context: Context): String {
        val preferences = context.managerStorageContext.getSharedPreferences(
            INTERNAL_TOKEN_PREFERENCES,
            Context.MODE_PRIVATE
        )
        preferences.getString(INTERNAL_TOKEN_KEY, null)?.let { return it }
        return synchronized(this) {
            preferences.getString(INTERNAL_TOKEN_KEY, null) ?: UUID.randomUUID().toString().also {
                check(preferences.edit().putString(INTERNAL_TOKEN_KEY, it).commit()) {
                    "Failed to persist the internal batch action token"
                }
            }
        }
    }
}
