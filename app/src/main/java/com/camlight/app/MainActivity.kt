package com.camlight.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camlight.app.ui.AppRoot
import com.camlight.app.ui.CameraViewModel
import com.camlight.app.ui.SetupViewModel

class MainActivity : ComponentActivity() {
    private val vm: SetupViewModel by viewModels()
    private val camVm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    val state by vm.state.collectAsStateWithLifecycle()
                    androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()) {
                        AppRoot(state, vm, camVm)
                    }
                }
            }
        }
    }
}
