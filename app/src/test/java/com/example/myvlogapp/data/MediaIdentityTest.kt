package com.example.myvlogapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 同じ動画かを見分ける鍵のうち、MediaStoreのURIから作る部分（[mediaStoreKey]） */
class MediaIdentityTest {

    @Test
    fun mediaStoreUrisOfTheSameVideoShareAKeyAcrossVolumeNames() {
        // ギャラリーの一覧は "external"、getMediaUri が返すのは "external_primary" のことがある
        // （content://media/external/video/media/4969 と content://media/external_primary/video/media/4969）
        assertEquals("media:4969", mediaStoreKey("media", "4969"))
    }

    @Test
    fun nonMediaStoreUrisHaveNoMediaKey() {
        assertNull(mediaStoreKey("com.android.externalstorage.documents", "primary:DCIM/Camera/a.mp4"))
        // MediaStoreでも番号で終わらないもの（一覧そのものなど）は、動画を指していない
        assertNull(mediaStoreKey("media", "media"))
        assertNull(mediaStoreKey(null, "4969"))
    }
}
