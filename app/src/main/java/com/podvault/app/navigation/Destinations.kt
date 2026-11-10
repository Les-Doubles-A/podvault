package com.podvault.app.navigation

/** Top-level bottom-nav destinations (spec section 5). */
sealed class TopDestination(val route: String, val label: String) {
    data object Home : TopDestination("home", "Accueil")
    data object Library : TopDestination("library", "Bibliothèque")
    data object Bookmarks : TopDestination("bookmarks", "Points mémoire")
    data object Search : TopDestination("search", "Recherche")
    data object Settings : TopDestination("settings", "Paramètres")

    companion object {
        val all = listOf(Home, Library, Bookmarks, Search, Settings)
    }
}

/** Routes reached by drilling into content rather than via the bottom bar. */
object DetailRoutes {
    const val PODCAST = "podcast/{podcastId}"
    const val PLAYER = "player/{episodeId}?positionMs={positionMs}"
    const val DUPLICATES = "duplicates"
    const val STATISTICS = "statistics"
    const val QUEUE = "queue"

    fun podcast(id: Long) = "podcast/$id"
    fun player(id: Long, positionMs: Long? = null) =
        "player/$id?positionMs=${positionMs?.toString().orEmpty()}"
}
