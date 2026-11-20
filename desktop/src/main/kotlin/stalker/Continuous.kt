package stalker

/** Reproducción continua (siguiente disco al terminar) y Rocola (toda la música al azar). */
object Continuous {
    const val ROCOLA = "Rocola"
    const val ROCOLA_FAVS = "Rocola · Favoritos"

    /** Pausa entre discos: lo que tarda cambiar un vinilo. */
    const val PAUSE_MS = 2000L

    /** Discos que ya sonaron en esta cadena (para no repetir hasta pasar por todos). */
    private val played = mutableSetOf<String>()

    fun displayTitle(t: String): String = if (t == ROCOLA || t == ROCOLA_FAVS) tr(t) else t

    fun resetChain() = played.clear()

    /** Todas las canciones mezcladas: sin repetir y sin dos seguidas del mismo disco. */
    fun rocola(albums: List<Album>, favorites: Set<String>, onlyFavorites: Boolean): List<Track> {
        val pool = albums.filter { !onlyFavorites || it.key in favorites }.flatMap { it.tracks }
        val list = pool.shuffled().toMutableList()
        for (i in 1 until list.size) {
            if (list[i].albumKey != list[i - 1].albumKey) continue
            val j = (i + 1 until list.size).firstOrNull { list[it].albumKey != list[i - 1].albumKey } ?: break
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
        return list
    }

    /** El disco que entra cuando termina la cola, o null si no corresponde seguir. */
    fun next(albums: List<Album>, favorites: Set<String>, queue: List<Track>, title: String): Pair<List<Track>, String>? {
        if (!Prefs.continuous || queue.isEmpty()) return null
        if (title == ROCOLA || title == ROCOLA_FAVS) {
            val again = rocola(albums, favorites, title == ROCOLA_FAVS)
            return if (again.isEmpty()) null else again to title
        }
        val keys = queue.map { it.albumKey }.distinct()
        if (keys.size != 1 || albums.isEmpty()) return null
        val current = keys.first()
        val shelf = albums.map { it.key }
        if (shelf.size == 1 && shelf[0] == current) return null

        played.add(current)
        if (shelf.all { it in played }) { played.clear(); played.add(current) }
        val at = shelf.indexOf(current)
        // En orden de estantería, empezando justo después del actual y dando la vuelta.
        val ring = if (at < 0) shelf else shelf.drop(at + 1) + shelf.take(at)
        val fresh = ring.filter { it !in played }
        val byKey = albums.associateBy { it.key }
        val artist = (byKey[current]?.artist ?: queue.first().artist).lowercase()
        val pick = when (Prefs.nextOrder) {
            "Al azar" -> fresh.randomOrNull()
            "Mismo artista" -> fresh.firstOrNull { byKey[it]?.artist?.lowercase() == artist } ?: fresh.firstOrNull()
            else -> ring.firstOrNull()
        } ?: ring.firstOrNull() ?: return null
        played.add(pick)
        val a = byKey[pick] ?: return null
        return a.tracks to a.title
    }
}
