package jp.kjtr.drivelogger

import jp.kjtr.drivelogger.dashcam.LoopStoragePolicy
import jp.kjtr.drivelogger.dashcam.StoredClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopStoragePolicyTest {
    private val mb = 1024L * 1024
    private val huge = Long.MAX_VALUE / 4

    private fun clip(id: Long, size: Long, protected: Boolean = false, active: Boolean = false) =
        StoredClip(id = id, startedAt = 1_000L * id, sizeBytes = size * mb, protected = protected, active = active)

    @Test
    fun underLimitDeletesNothing() {
        val p = LoopStoragePolicy.plan(listOf(clip(1, 100), clip(2, 100)), limitBytes = 1000 * mb, protectedLimitBytes = 200 * mb, nextBytes = 100 * mb, freeBytes = huge, minFreeBytes = 0)
        assertTrue(p.delete.isEmpty())
        assertEquals(200 * mb, p.normalBytesAfter)
        assertFalse(p.cannotRecord)
    }

    @Test
    fun deletesOldestFirstUntilNextFits() {
        // 上限 500 MB・次は 100 MB → 通常は 400 MB まで。100×5 = 500 なので古い順に 1 本消す
        val clips = (1L..5L).map { clip(it, 100) }.shuffled()
        val p = LoopStoragePolicy.plan(clips, 500 * mb, 100 * mb, 100 * mb, huge, 0)
        assertEquals(listOf(1L), p.delete.map { it.id })
        assertEquals(400 * mb, p.normalBytesAfter)
    }

    @Test
    fun neverDeletesProtectedOrActive() {
        val clips = listOf(clip(1, 100, protected = true), clip(2, 100), clip(3, 100, protected = true), clip(4, 100), clip(5, 100, active = true))
        // 上限 300・保護 200（上限 400 以内）→ 通常に使えるのは 300−200 = 100。次 100 → 通常 0 にしたいが録画中（5）は消さない
        val p = LoopStoragePolicy.plan(clips, 300 * mb, 400 * mb, 100 * mb, huge, 0)
        assertEquals(listOf(2L, 4L), p.delete.map { it.id })
        assertTrue(p.delete.none { it.protected || it.active })
        assertEquals(200 * mb, p.protectedBytes)
    }

    @Test
    fun protectedOverLimitIsReportedButKept() {
        val clips = listOf(clip(1, 300, protected = true), clip(2, 100), clip(3, 100))
        // 保護 300 > 保護の上限 200 → 警告。通常に使えるのは 1000 − 200 = 800（保護の上限までしか通常の枠を削らない）
        val p = LoopStoragePolicy.plan(clips, 1000 * mb, 200 * mb, 100 * mb, huge, 0)
        assertTrue(p.protectedOverLimit)
        assertTrue(p.delete.isEmpty())
    }

    @Test
    fun protectedUpToLimitShrinksNormalBudget() {
        val clips = listOf(clip(1, 150, protected = true), clip(2, 100), clip(3, 100), clip(4, 100))
        // 通常の枠 = 500 − 150 = 350。通常 300 + 次 100 = 400 > 350 → 1 本（2）消す
        val p = LoopStoragePolicy.plan(clips, 500 * mb, 200 * mb, 100 * mb, huge, 0)
        assertEquals(listOf(2L), p.delete.map { it.id })
        assertFalse(p.protectedOverLimit)
    }

    @Test
    fun lowDeviceSpaceDeletesMore() {
        val clips = (1L..4L).map { clip(it, 100) }
        // 上限には余裕があるが、端末の空きが 150 MB しかない。次 100 + 余裕 200 = 300 必要 → 2 本消して空き 350
        val p = LoopStoragePolicy.plan(clips, 10_000 * mb, 1000 * mb, 100 * mb, freeBytes = 150 * mb, minFreeBytes = 200 * mb)
        assertEquals(listOf(1L, 2L), p.delete.map { it.id })
        assertFalse(p.lowDeviceSpace)
        assertFalse(p.cannotRecord)
    }

    @Test
    fun cannotRecordWhenNothingLeftToDelete() {
        val clips = listOf(clip(1, 100, protected = true), clip(2, 50, active = true))
        val p = LoopStoragePolicy.plan(clips, 10_000 * mb, 1000 * mb, 100 * mb, freeBytes = 60 * mb, minFreeBytes = 200 * mb)
        assertTrue(p.delete.isEmpty())
        assertTrue(p.lowDeviceSpace)
        assertTrue(p.cannotRecord)
    }

    /**
     * 長時間録画（0.7.0〜）のファイルは、ループの上限・保護の合計に入れず、消す候補にもしない。
     * 長時間録画が上限より大きくても、ループの動画は「ループだけで上限を超えた分」しか消さない。
     */
    @Test
    fun longRecordingsAreNeitherCountedNorDeleted() {
        val long = (10L..14L).map { StoredClip(it, startedAt = 500L * it, sizeBytes = 1000 * mb, protected = false, longRec = true) } // 5 GB（いちばん古い）
        val loop = (1L..5L).map { clip(it, 100).copy(startedAt = 100_000L * it) } // 500 MB
        // 上限 500 MB・次 100 MB → ループだけで見て 1 本（いちばん古いループ）を消す。長時間は 1 本も消さない
        val p = LoopStoragePolicy.plan(long + loop, 500 * mb, 100 * mb, 100 * mb, huge, 0)
        assertEquals(listOf(1L), p.delete.map { it.id })
        assertTrue(p.delete.none { it.longRec })
        assertEquals(400 * mb, p.normalBytesAfter)
        assertEquals(0L, p.protectedBytes)
        // ループが上限以内なら、長時間録画が何 GB あっても何も消さない
        val p2 = LoopStoragePolicy.plan(long + loop.take(2), 500 * mb, 100 * mb, 100 * mb, huge, 0)
        assertTrue(p2.delete.isEmpty())
        // 端末の空きが足りないときも、消すのはループのファイルだけ（長時間録画は残る）
        val p3 = LoopStoragePolicy.plan(long + loop, 10_000 * mb, 1000 * mb, 100 * mb, freeBytes = 50 * mb, minFreeBytes = 200 * mb)
        assertEquals(listOf(1L, 2L, 3L), p3.delete.map { it.id })
        assertTrue(p3.delete.none { it.longRec })
        // 長時間録画しか無ければ、空きが足りなくても消せる物は無い
        val p4 = LoopStoragePolicy.plan(long, 10_000 * mb, 1000 * mb, 100 * mb, freeBytes = 50 * mb, minFreeBytes = 200 * mb)
        assertTrue(p4.delete.isEmpty())
        assertTrue(p4.cannotRecord)
    }

    @Test
    fun sameStartTimeIsOrderedById() {
        val a = StoredClip(7, 5_000, 100 * mb, false)
        val b = StoredClip(3, 5_000, 100 * mb, false)
        val p = LoopStoragePolicy.plan(listOf(a, b), 150 * mb, 0, 50 * mb, huge, 0)
        assertEquals(listOf(3L), p.delete.map { it.id })
    }
}
