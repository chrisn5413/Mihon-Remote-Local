package eu.kanade.tachiyomi.extension.remotelibrary

/** Our extension's applicationId. Used when building explicit Intents from a foreign
 *  context (screen.context is Mihon's context; Intent(ctx, Class) would produce
 *  {app.mihon/our.Activity} which Android cannot resolve). */
internal const val EXT_PACKAGE = "eu.kanade.tachiyomi.extension.remotelibrary"
