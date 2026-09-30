package dev.example.autoreply.hook

import android.app.Activity
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import de.robv.android.xposed.XposedBridge
import dev.example.autoreply.ui.TunnelScreen

/**
 * 内网穿透设置面板：由 PopupMenuHook 点击「内网穿透」菜单项后触发。
 * 复用 WhitelistLauncher / StatusLauncher 的 overlay 弹窗模式。
 */
object TunnelLauncher {

    private const val TAG = "[TunnelPanel]"

    @Volatile private var panelView: ComposeView? = null
    @Volatile private var panelOwner: XposedLifecycleOwner? = null

    fun showTunnelPanel(activity: Activity) {
        if (panelView != null) return

        val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.CENTER
        params.dimAmount = 0.4f

        val owner = XposedLifecycleOwner.create()
        panelOwner = owner

        val view = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val ctx = LocalContext.current
                val dark = isSystemInDarkTheme()
                val colorScheme = if (dark) dynamicDarkColorScheme(ctx)
                                  else dynamicLightColorScheme(ctx)
                MaterialTheme(colorScheme = colorScheme) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                            TunnelScreen(onClose = { dismissPanel() })
                        }
                    }
                }
            }
        }

        runCatching {
            wm.addView(view, params)
            panelView = view
            XposedBridge.log("$TAG 内网穿透面板已打开")
        }.onFailure {
            XposedBridge.log("$TAG addView 失败: ${it.message}")
            owner.onDestroy()
            panelOwner = null
        }
    }

    fun dismissPanel() {
        val view = panelView ?: return
        val wm = view.context.getSystemService(Activity.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(view) }
        panelView = null
        panelOwner?.onDestroy()
        panelOwner = null
        XposedBridge.log("$TAG 内网穿透面板已关闭")
    }
}