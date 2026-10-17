package com.coooolfan.xiaomialbumsyncer.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AlbumTimelineTest {
    private val first = LocalDate.of(2026, 10, 10)
    private val second = first.plusDays(1)

    @Test fun changedHashRefreshesDaysWithDecreasesAndSameCountReplacements() {
        val previous = AlbumTimeline("before", mapOf(first to 3L, second to 2L))
        val decreased = AlbumTimeline("after", mapOf(first to 2L, second to 2L))
        assertEquals(setOf(first, second), decreased.daysToRefreshSince(previous))
        val replaced = AlbumTimeline("replacement", previous.dayCount)
        assertEquals(setOf(first, second), replaced.daysToRefreshSince(previous))
    }

    @Test fun addedDayCannotHideReplacementOnAnotherDay() {
        val previous = AlbumTimeline("before", mapOf(first to 3L))
        val latest = AlbumTimeline("after", mapOf(first to 3L, second to 1L))
        assertEquals(setOf(first, second), latest.daysToRefreshSince(previous))
    }

    @Test fun unchangedIndexAndEmptyDatesRequireNoAssetQueries() {
        val timeline = AlbumTimeline("same", mapOf(first to 3L))
        assertTrue(timeline.daysToRefreshSince(timeline).isEmpty())
        assertTrue(AlbumTimeline("empty", mapOf(first to 0L)).daysToRefreshSince(timeline).isEmpty())
    }
}
