package com.eve.app.ui.common

import android.app.Activity
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Reusable base Activity that hosts Telegram's original ActionBarLayout and DrawerLayoutContainer,
 * implementing the full INavigationLayout contract backed by genuine Telegram navigation.
 */
abstract class TelegramHostActivity<F : BaseFragment> :
    EveBaseActivity(),
    INavigationLayout,
    INavigationLayout.INavigationLayoutDelegate {

    protected lateinit var fragment: F
    lateinit var drawerLayoutContainer: DrawerLayoutContainer
        private set
    lateinit var actionBarLayout: ActionBarLayout
        private set

    private var hostWindow: Window? = null

    override fun getWindow(): Window = hostWindow ?: super<EveBaseActivity>.getWindow()

    protected open fun createTelegramFragment(savedInstanceState: Bundle?): F? = createFragment()

    protected open fun createFragment(): F? = null

    protected open fun onBeforeCreateFragment(savedInstanceState: Bundle?): Boolean = true

    protected open fun onFragmentHosted(fragment: F) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!onBeforeCreateFragment(savedInstanceState)) {
            return
        }

        AndroidUtilities.checkDisplaySize(this, resources.configuration)
        Theme.syncFromEvePalette(this)
        Theme.createCommonResources(this)

        drawerLayoutContainer = DrawerLayoutContainer(this)
        actionBarLayout = ActionBarLayout(this, true)

        drawerLayoutContainer.setParentActionBarLayout(actionBarLayout)
        drawerLayoutContainer.setActionBarLayout(actionBarLayout)
        actionBarLayout.setDrawerLayoutContainer(drawerLayoutContainer)
        actionBarLayout.setDelegate(this)

        drawerLayoutContainer.addView(
            actionBarLayout,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
        )

        setContentView(drawerLayoutContainer)

        val created = createTelegramFragment(savedInstanceState) ?: return
        fragment = created

        actionBarLayout.presentFragment(
            INavigationLayout.NavigationParams(fragment).setNoAnimation(true)
        )

        onFragmentHosted(fragment)
    }

    override fun onResume() {
        super.onResume()
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.onPause()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.onLowMemory()
        }
    }

    public override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.onUserLeaveHint()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.onConfigurationChanged(newConfig)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!::actionBarLayout.isInitialized) {
            super.onBackPressed()
            return
        }
        if (actionBarLayout.checkTransitionAnimation()) {
            return
        }
        if (actionBarLayout.fragmentStack.size > 1) {
            actionBarLayout.onBackPressed()
        } else {
            val last = actionBarLayout.lastFragment
            if (last == null || last.onBackPressed(true)) {
                super.onBackPressed()
            }
        }
    }

    // --- INavigationLayout implementation backed by ActionBarLayout ---

    override fun getView(): ViewGroup =
        if (::actionBarLayout.isInitialized) actionBarLayout else findViewById(android.R.id.content)

    override fun getParentActivity(): Activity = this

    override fun getOverlayContainerView(): FrameLayout =
        if (::drawerLayoutContainer.isInitialized) drawerLayoutContainer else findViewById(android.R.id.content)

    override fun getFragmentStack(): MutableList<BaseFragment> =
        if (::actionBarLayout.isInitialized) actionBarLayout.fragmentStack else mutableListOf()

    override fun isLayersLayout(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isLayersLayout

    override fun isRightLayout(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isRightLayout

    override fun presentFragment(params: INavigationLayout.NavigationParams?): Boolean {
        if (!::actionBarLayout.isInitialized || params == null) return false
        return actionBarLayout.presentFragment(params)
    }

    override fun checkTransitionAnimation(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.checkTransitionAnimation()

    override fun addFragmentToStack(fragment: BaseFragment?, position: Int): Boolean {
        if (!::actionBarLayout.isInitialized || fragment == null) return false
        return actionBarLayout.addFragmentToStack(fragment, position)
    }

    override fun removeFragmentFromStack(fragment: BaseFragment?, immediate: Boolean) {
        if (!::actionBarLayout.isInitialized || fragment == null) return
        actionBarLayout.removeFragmentFromStack(fragment, immediate)
    }

    override fun setDelegate(delegate: INavigationLayout.INavigationLayoutDelegate?) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setDelegate(delegate)
        }
    }

    override fun closeLastFragment(animated: Boolean, forceNoAnimation: Boolean) {
        if (!::actionBarLayout.isInitialized) {
            finish()
            return
        }
        if (actionBarLayout.fragmentStack.size > 1) {
            actionBarLayout.closeLastFragment(animated, forceNoAnimation)
        } else {
            finish()
        }
    }

    override fun setRemoveActionBarExtraHeight(removeExtraHeight: Boolean) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setRemoveActionBarExtraHeight(removeExtraHeight)
        }
    }

    override fun setTitleOverlayText(title: String?, titleId: Int, action: Runnable?) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setTitleOverlayText(title, titleId, action)
        }
    }

    override fun animateThemedValues(
        settings: INavigationLayout.ThemeAnimationSettings?,
        onDone: Runnable?
    ) {
        if (::actionBarLayout.isInitialized && settings != null) {
            actionBarLayout.animateThemedValues(settings, onDone)
        } else {
            onDone?.run()
        }
    }

    override fun getThemeAnimationValue(): Float =
        if (::actionBarLayout.isInitialized) actionBarLayout.themeAnimationValue else 1f

    override fun setFragmentStackChangedListener(onFragmentStackChanged: Runnable?) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setFragmentStackChangedListener(onFragmentStackChanged)
        }
    }

    override fun isTransitionAnimationInProgress(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isTransitionAnimationInProgress

    override fun resumeDelayedFragmentAnimation() {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.resumeDelayedFragmentAnimation()
        }
    }

    override fun allowSwipe(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.allowSwipe()

    override fun isInPassivePreviewMode(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isInPassivePreviewMode

    override fun setInBubbleMode(bubbleMode: Boolean) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setInBubbleMode(bubbleMode)
        }
    }

    override fun isInBubbleMode(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isInBubbleMode

    override fun isInPreviewMode(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isInPreviewMode

    override fun isPreviewOpenAnimationInProgress(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isPreviewOpenAnimationInProgress

    override fun movePreviewFragment(dy: Float) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.movePreviewFragment(dy)
        }
    }

    override fun expandPreviewFragment() {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.expandPreviewFragment()
        }
    }

    override fun finishPreviewFragment() {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.finishPreviewFragment()
        }
    }

    override fun setFragmentPanTranslationOffset(offset: Int) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setFragmentPanTranslationOffset(offset)
        }
    }

    override fun setHighlightActionButtons(highlight: Boolean) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setHighlightActionButtons(highlight)
        }
    }

    override fun getCurrentPreviewFragmentAlpha(): Float =
        if (::actionBarLayout.isInitialized) actionBarLayout.currentPreviewFragmentAlpha else 1f

    override fun drawCurrentPreviewFragment(canvas: Canvas?, foregroundDrawable: Drawable?) {
        if (::actionBarLayout.isInitialized && canvas != null) {
            actionBarLayout.drawCurrentPreviewFragment(canvas, foregroundDrawable)
        }
    }

    override fun drawHeaderShadow(canvas: Canvas?, alpha: Int, y: Int) {
        if (::actionBarLayout.isInitialized && canvas != null) {
            actionBarLayout.drawHeaderShadow(canvas, alpha, y)
        }
    }

    override fun isSwipeInProgress(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isSwipeInProgress

    override fun extendActionMode(menu: Menu?): Boolean =
        ::actionBarLayout.isInitialized && menu != null && actionBarLayout.extendActionMode(menu)

    override fun onActionModeStarted(mode: Any?) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.onActionModeStarted(mode)
        }
    }

    override fun onActionModeFinished(mode: Any?) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.onActionModeFinished(mode)
        }
    }

    override fun setIsSheet(isSheet: Boolean) {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setIsSheet(isSheet)
        }
    }

    override fun isSheet(): Boolean =
        ::actionBarLayout.isInitialized && actionBarLayout.isSheet

    override fun updateTitleOverlay() {
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.updateTitleOverlay()
        }
    }

    override fun setWindow(window: Window?) {
        this.hostWindow = window
        if (::actionBarLayout.isInitialized) {
            actionBarLayout.setWindow(window)
        }
    }

    override fun setNavigationBarColor(color: Int) {
        (hostWindow ?: window)?.navigationBarColor = color
        if (::drawerLayoutContainer.isInitialized) {
            drawerLayoutContainer.setInternalNavigationBarColor(color)
        }
    }

    // --- INavigationLayout.INavigationLayoutDelegate ---

    override fun needPresentFragment(
        layout: INavigationLayout?,
        params: INavigationLayout.NavigationParams?
    ): Boolean = true

    override fun needAddFragmentToStack(
        fragment: BaseFragment?,
        layout: INavigationLayout?
    ): Boolean = true

    override fun needCloseLastFragment(layout: INavigationLayout?): Boolean = true
}
