package dev.jdtech.jellyfin.services

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.player.local.mpv.MPVPlayer
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.internal.wait
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var player: Player? = null
    private var mediaSession: MediaSession? = null
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var repository: JellyfinRepository



    // Create your Player and MediaSession in the onCreate lifecycle event
    override fun onCreate() {
        super.onCreate()

        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

        val trackSelector = DefaultTrackSelector(application)

        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTunnelingEnabled(true)
                .setPreferredAudioLanguage(
                    appPreferences.getValue(appPreferences.preferredAudioLanguage)
                )
                .setPreferredTextLanguage(
                    appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
                )
        )

        if (appPreferences.getValue(appPreferences.playerMpv)) {
            player =
                MPVPlayer.Builder(application)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelectionParameters(trackSelector.parameters)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .setVideoOutput(appPreferences.getValue(appPreferences.playerMpvVo))
                    .setAudioOutput(appPreferences.getValue(appPreferences.playerMpvAo))
                    .setHwDec(appPreferences.getValue(appPreferences.playerMpvHwdec))
                    .build()
        } else {
            val renderersFactory =
                DefaultRenderersFactory(application)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            player =
                ExoPlayer.Builder(application, renderersFactory)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelector(trackSelector)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .build()
        }
        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("returnToPlayback", true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .build()

        scope.launch {
            while (true) {
                updatePlaybackProgress()
                delay(5000L)
            }
        }
    }

    // Remember to release the player and media session in onDestroy
    override fun onDestroy() {
        scope.launch {
            updatePlaybackProgress()
        }

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    suspend fun updatePlaybackProgress() {
        Timber.d("Updating playback progress")
        if (player?.currentMediaItem != null && player!!.currentMediaItem!!.mediaId.isNotEmpty()) {
            val itemId = UUID.fromString(player!!.currentMediaItem!!.mediaId)
            try {
                val positionTicks = player!!.currentPosition.times(10000)
                val isPaused = !player!!.isPlaying
                repository.postPlaybackProgress(
                    itemId,
                    positionTicks,
                    isPaused,
                )
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession
}