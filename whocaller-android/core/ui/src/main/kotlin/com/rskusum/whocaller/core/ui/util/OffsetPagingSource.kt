package com.rskusum.whocaller.core.ui.util

import androidx.paging.PagingSource
import androidx.paging.PagingState

/** Generic offset/limit PagingSource so large lists (call log) are never fully loaded in memory. */
class OffsetPagingSource<T : Any>(
    private val loader: suspend (offset: Int, limit: Int) -> List<T>,
) : PagingSource<Int, T>() {

    override fun getRefreshKey(state: PagingState<Int, T>): Int? =
        state.anchorPosition?.let { anchor -> (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val offset = params.key ?: 0
        return try {
            val items = loader(offset, params.loadSize)
            LoadResult.Page(
                data = items,
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                nextKey = if (items.size < params.loadSize) null else offset + items.size,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
