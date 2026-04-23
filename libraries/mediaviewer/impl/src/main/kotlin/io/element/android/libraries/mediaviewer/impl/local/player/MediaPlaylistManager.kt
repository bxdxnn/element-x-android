/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.mediaviewer.api.MediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val PAGINATION_TIMEOUT_MS = 10_000L
private const val ARTWORK_THUMBNAIL_SIZE = 256L

/**
 * Provides skip-next / skip-previous media lookups for [MediaPlaybackService].
 *
 * The playlist does not open or subscribe to any timeline up-front. Skip buttons
 * are shown by default; a lookup runs lazily when the user triggers skip. If a
 * lookup hits the end of the room's live timeline in a given direction without
 * finding anything playable, that direction is cached as "no more" so the
 * corresponding skip button hides.
 */
class MediaPlaylistManager(
    private val matrixClientProvider: MatrixClientProvider,
    private val localMediaFactory: LocalMediaFactory,
    private val onPlayableItemsChanged: () -> Unit = {},
) {
    data class PlayableItem(
        val eventId: EventId,
        val mediaSource: MediaSource,
        val thumbnailSource: MediaSource?,
        val filename: String,
        val mimeType: String,
        val senderName: String?,
        val senderAvatar: String?,
    )

    data class SkipResult(
        val mediaItem: MediaItem,
        val eventId: EventId,
    )

    private val skipMutex = Mutex()
    private var currentSessionId: SessionId? = null
    private var currentRoomId: RoomId? = null
    var currentEventId: EventId? = null
        private set

    // Once a skip lookup exhausts the live timeline in a given direction, cache
    // that so the corresponding button disappears instead of popping again.
    private var hasNoNext: Boolean = false
    private var hasNoPrevious: Boolean = false

    /** Shown by default. Only false after we've verified there is no next playable. */
    val hasNext: Boolean
        get() = currentEventId != null && !hasNoNext

    /** Shown by default. Only false after we've verified there is no previous playable. */
    val hasPrevious: Boolean
        get() = currentEventId != null && !hasNoPrevious

    fun initialize(sessionId: SessionId, roomId: RoomId, eventId: EventId) {
        if (sessionId == currentSessionId && roomId == currentRoomId && eventId == currentEventId) {
            return
        }
        currentSessionId = sessionId
        currentRoomId = roomId
        currentEventId = eventId
        // A new anchor event may have different neighbours — clear the "no more" caches.
        hasNoNext = false
        hasNoPrevious = false
        onPlayableItemsChanged()
    }

    suspend fun skipToNext(): SkipResult? = skipMutex.withLock {
        skipLazy(forward = true)
    }

    suspend fun skipToPrevious(): SkipResult? = skipMutex.withLock {
        skipLazy(forward = false)
    }

    private suspend fun skipLazy(forward: Boolean): SkipResult? {
        val sessionId = currentSessionId ?: return null
        val roomId = currentRoomId ?: return null
        val anchorEventId = currentEventId ?: return null

        val client = matrixClientProvider.getOrRestore(sessionId).getOrNull() ?: return null
        val joinedRoom = client.getJoinedRoom(roomId) ?: return null
        val mediaLoader = client.matrixMediaLoader
        val timeline = joinedRoom.liveTimeline

        val items = timeline.timelineItems.first()
        findNeighbour(items, anchorEventId, forward)?.let { target ->
            return completeSkip(target, mediaLoader)
        }

        // Not present in the current live timeline snapshot — try one pagination step.
        val direction = if (forward) Timeline.PaginationDirection.FORWARDS else Timeline.PaginationDirection.BACKWARDS
        val status = if (forward) timeline.forwardPaginationStatus.value else timeline.backwardPaginationStatus.value
        if (!status.hasMoreToLoad) {
            markEndReached(forward)
            return null
        }

        timeline.paginate(direction)
        val target = waitForNeighbour(timeline, anchorEventId, forward)
        if (target == null) {
            markEndReached(forward)
            return null
        }
        return completeSkip(target, mediaLoader)
    }

    private fun findNeighbour(
        items: List<MatrixTimelineItem>,
        anchorEventId: EventId,
        forward: Boolean,
    ): PlayableItem? {
        val playable = items
            .filterIsInstance<MatrixTimelineItem.Event>()
            .mapNotNull { toPlayableItem(it) }
        val idx = playable.indexOfFirst { it.eventId == anchorEventId }
        if (idx < 0) return null
        val targetIdx = if (forward) idx + 1 else idx - 1
        return playable.getOrNull(targetIdx)
    }

    private suspend fun waitForNeighbour(
        timeline: Timeline,
        anchorEventId: EventId,
        forward: Boolean,
    ): PlayableItem? = withTimeoutOrNull(PAGINATION_TIMEOUT_MS) {
        timeline.timelineItems.first { newItems ->
            findNeighbour(newItems, anchorEventId, forward) != null
        }.let { emittedItems ->
            findNeighbour(emittedItems, anchorEventId, forward)
        }
    }

    private suspend fun completeSkip(
        target: PlayableItem,
        mediaLoader: MatrixMediaLoader,
    ): SkipResult? {
        val result = buildSkipResult(target, mediaLoader) ?: return null
        currentEventId = result.eventId
        // Moved to a new anchor; neighbour lookups can succeed again.
        hasNoNext = false
        hasNoPrevious = false
        onPlayableItemsChanged()
        return result
    }

    private fun markEndReached(forward: Boolean) {
        if (forward) hasNoNext = true else hasNoPrevious = true
        onPlayableItemsChanged()
    }

    private fun toPlayableItem(item: MatrixTimelineItem.Event): PlayableItem? {
        val eventId = item.eventId ?: return null
        val content = item.event.content as? MessageContent ?: return null
        val senderName = (item.event.senderProfile as? ProfileDetails.Ready)?.displayName
        val senderAvatar = (item.event.senderProfile as? ProfileDetails.Ready)?.avatarUrl

        return when (val type = content.type) {
            is AudioMessageType -> PlayableItem(
                eventId = eventId,
                mediaSource = type.source,
                thumbnailSource = null,
                filename = type.filename,
                mimeType = type.info?.mimetype ?: "audio/*",
                senderName = senderName,
                senderAvatar = senderAvatar,
            )
            is VideoMessageType -> PlayableItem(
                eventId = eventId,
                mediaSource = type.source,
                thumbnailSource = type.info?.thumbnailSource,
                filename = type.filename,
                mimeType = type.info?.mimetype ?: "video/*",
                senderName = senderName,
                senderAvatar = senderAvatar,
            )
            else -> null
        }
    }

    private suspend fun buildSkipResult(item: PlayableItem, loader: MatrixMediaLoader): SkipResult? {
        return try {
            val mediaFile = loader.downloadMediaFile(
                source = item.mediaSource,
                mimeType = item.mimeType,
                filename = item.filename,
            ).getOrNull() ?: return null

            val mediaInfo = MediaInfo(
                filename = item.filename,
                caption = null,
                mimeType = item.mimeType,
                fileSize = null,
                formattedFileSize = "",
                fileExtension = item.filename.substringAfterLast('.', ""),
                senderId = null,
                senderName = item.senderName,
                senderAvatar = null,
                dateSent = null,
                dateSentFull = null,
                waveform = null,
                duration = null,
            )
            val localMedia = localMediaFactory.createFromMediaFile(mediaFile, mediaInfo)

            val artworkSource = item.thumbnailSource
                ?: item.senderAvatar?.let { MediaSource(it) }
            val artworkBytes = artworkSource?.let { source ->
                loader.loadMediaThumbnail(source, ARTWORK_THUMBNAIL_SIZE, ARTWORK_THUMBNAIL_SIZE).getOrNull()
            }

            val extras = Bundle().apply {
                putString("sessionId", currentSessionId?.value)
                putString("roomId", currentRoomId?.value)
                putString("eventId", item.eventId.value)
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(item.filename)
                .setArtist(item.senderName)
                .apply {
                    if (artworkBytes != null) {
                        setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                }
                .setExtras(extras)
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(item.eventId.value)
                .setUri(localMedia.uri)
                .setMediaMetadata(metadata)
                .build()

            SkipResult(mediaItem = mediaItem, eventId = item.eventId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to download media for skip")
            null
        }
    }

    fun release() {
        currentSessionId = null
        currentRoomId = null
        currentEventId = null
        hasNoNext = false
        hasNoPrevious = false
    }
}
