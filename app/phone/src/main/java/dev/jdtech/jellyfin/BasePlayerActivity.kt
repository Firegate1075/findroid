package dev.jdtech.jellyfin

import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.services.PlaybackService
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
abstract class BasePlayerActivity : AppCompatActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    abstract val viewModel: PlayerViewModel

    lateinit var controller: MediaController
    lateinit var controllerFuture: ListenableFuture<MediaController>

    private var wasPip: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                controller = controllerFuture.get()
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onResume() {
        super.onResume()

        Timber.d("resuming")


        if (wasPip) {
            wasPip = false
        } else {
            controllerFuture.addListener(
                {
                    controllerFuture.get().playWhenReady = viewModel.playWhenReady
                },
                MoreExecutors.directExecutor()
            )
        }
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()

        if (isInPictureInPictureMode) {
            wasPip = true
        } else {
            controllerFuture.addListener(
                {
                    // pause the playback if background playback is disabled
                    if (!appPreferences.getValue(appPreferences.playerBackgroundPlaybackAutomatic)) {
                        viewModel.playWhenReady = controllerFuture.get().playWhenReady
                        controllerFuture.get().playWhenReady = false
                        viewModel.updatePlaybackProgress()
                    }
                },
                MoreExecutors.directExecutor()
            )
        }
    }

    override fun onStop() {
        super.onStop()

        if (wasPip) {
            finish()
        }
    }

    protected fun hideSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    protected fun configureInsets(playerControls: View) {
        playerControls.setOnApplyWindowInsetsListener { _, windowInsets ->
            val cutout = windowInsets.displayCutout
            playerControls.updatePadding(
                left = cutout?.safeInsetLeft ?: 0,
                top = cutout?.safeInsetTop ?: 0,
                right = cutout?.safeInsetRight ?: 0,
                bottom = cutout?.safeInsetBottom ?: 0,
            )
            return@setOnApplyWindowInsetsListener windowInsets
        }
    }
}
