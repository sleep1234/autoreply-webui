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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import de.robv.android.xposed.XposedBridge
import dev.example.autoreply.ui.WhitelistEntry
import dev.example.autoreply.ui.WhitelistScreen

/**
 * 白名单选择器：由 PopupMenuHook 点击「自动回复白名单」菜单项后触发，
 * 查 rconversation 弹选择器（ComposeView + WindowManager + XposedLifecycleOwner）。
 */
object WhitelistLauncher {

    private const val TAG = "[Whitelist]"

    @Volatile private var pickerView: ComposeView? = null
    @Volatile private var pickerOwner: XposedLifecycleOwner? = null

    fun isShowing(): Boolean = pickerView != null

    fun showConversationPicker(activity: Activity, conversations: List<WhitelistEntry>) {
        if (pickerView != null) return

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
        pickerOwner = owner

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
                            WhitelistScreen(
                                initialConversations = conversations,
                                onClose = { dismissPicker() },
                            )
                        }
                    }
                }
            }
        }

        runCatching {
            wm.addView(view, params)
            pickerView = view
            XposedBridge.log("$TAG 选择器已打开")
        }.onFailure {
            XposedBridge.log("$TAG addView 失败: ${it.message}")
            owner.onDestroy()
            pickerOwner = null
        }
    }

    fun dismissPicker() {
        val view = pickerView ?: return
        val wm = view.context.getSystemService(Activity.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(view) }
        pickerView = null
        pickerOwner?.onDestroy()
        pickerOwner = null
        XposedBridge.log("$TAG 选择器已关闭")
    }
}

class XposedLifecycleOwner private constructor() :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    companion object {
        fun create(): XposedLifecycleOwner =
            XposedLifecycleOwner().apply { onCreate(); onStart(); onResume() }
    }
}