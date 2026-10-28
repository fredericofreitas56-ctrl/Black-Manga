package com.blackmanga.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.request.CachePolicy

/**
 * A single Coil loader shared by every screen.
 *
 * Kotatsu also centralizes image loading so catalogue covers survive screen
 * disposal. Ignore restrictive server cache headers: some manga sources send
 * no-cache/short-lived headers for covers that should remain available locally.
 */
class BlackMangaApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("black_manga_image_cache"))
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            .build()
    }
}
