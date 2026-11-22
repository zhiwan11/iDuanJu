package app.iduanju

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import app.iduanju.ui.RootScreen
import app.iduanju.ui.theme.IdjTheme
import app.iduanju.ui.theme.IdjThemeColors

object PipHost {
    val isInPip = mutableStateOf(false)
    var onUserLeaveHint: (() -> Unit)? = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if(BuildConfig.DEBUG) {
            app.iduanju.ui.UiEvidence.keepChrome=intent?.getBooleanExtra("qaKeepChrome",false) ?: false
            app.iduanju.ui.UiEvidence.page=intent?.getStringExtra("qaPage").orEmpty()
            if(app.iduanju.ui.UiEvidence.page=="novel")app.iduanju.ui.home.HomeJump.label="小说"
            app.iduanju.ui.UiEvidence.panel=intent?.getStringExtra("qaPanel")
            app.iduanju.ui.UiEvidence.scroll=intent?.getBooleanExtra("qaScroll",false) ?: false
            app.iduanju.ui.UiEvidence.landscape=intent?.getBooleanExtra("qaLandscape",false) ?: false
            app.iduanju.ui.UiEvidence.switchEpisode=intent?.getBooleanExtra("qaSwitch",false) ?: false
        }
        setContent {
            IdjTheme {
                Surface(Modifier.fillMaxSize(), color = IdjThemeColors.bg) {
                    RootScreen(initialRoute = intent?.getStringExtra("route"))
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PipHost.onUserLeaveHint?.invoke()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipHost.isInPip.value = isInPictureInPictureMode
    }
}
