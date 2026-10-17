package com.jedrama.fork.library

import com.jedrama.fork.generation.SeriesDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AccÃ¨s aux sÃ©ries (drafts + bibliothÃ¨que locale) avec Ã©tat rÃ©actif.
 *
 * Cette interface permet de tester l'UI indÃ©pendamment de l'implÃ©mentation
 * disque (SeriesDraftStore + Library).
 */
interface SeriesRepository {

    /** Toutes les sÃ©ries, les plus rÃ©centes d'abord. */
    val series: Flow<List<SeriesDraft>>

    /** La sÃ©rie en cours (inachevÃ©e), s'il y en a une. */
    val pendingSeries: Flow<SeriesDraft?>

    /** Charge une sÃ©rie par son ID. */
    fun loadSeries(id: String): SeriesDraft?

    /** Sauvegarde une sÃ©rie. */
    fun saveSeries(draft: SeriesDraft)

    /** Supprime une sÃ©rie (script + vidÃ©os). */
    fun deleteSeries(id: String)

    /** Nombre d'octets utilisÃ©s par la bibliothÃ¨que. */
    fun bytesUsed(): Long

    /** RafraÃ®chit le flux de sÃ©ries (appelÃ© aprÃ¨s modification). */
    fun refresh()
}

/** ImplÃ©mentation concrÃ¨te : dÃ©lÃ¨gue Ã  SeriesDraftStore et Library. */
class SeriesRepositoryImpl(
    private val store: com.jedrama.fork.generation.SeriesDraftStore,
    private val library: Library,
) : SeriesRepository {

    private val _series = MutableStateFlow<List<SeriesDraft>>(emptyList())
    override val series: Flow<List<SeriesDraft>> = _series.asStateFlow()

    private val _pending = MutableStateFlow<SeriesDraft?>(null)
    override val pendingSeries: Flow<SeriesDraft?> = _pending.asStateFlow()

    init {
        refresh()
    }

    override fun loadSeries(id: String): SeriesDraft? = store.load(id)

    override fun saveSeries(draft: SeriesDraft) {
        store.save(draft)
        refresh()
    }

    override fun deleteSeries(id: String) {
        store.delete(id)
        refresh()
    }

    override fun bytesUsed(): Long = library.bytesUsed()

    override fun refresh() {
        _series.value = store.all()
        _pending.value = store.pending()
    }
}
