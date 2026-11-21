package org.telegram.ui.Stories;

// EVE-SHIM
import android.graphics.RectF;
import org.telegram.ui.ActionBar.Theme;

public class StoriesUtilities {
    public static class AvatarStoryParams {
        public RectF originalAvatarRect = new RectF();
        public boolean allowLongress;
        public boolean drawnLive;
        public int forceState;
        public boolean drawHiddenStoriesAsSegments;
        public boolean isStoryCell;
        public Theme.ResourcesProvider resourcesProvider;

        public AvatarStoryParams(boolean isStoryCell) {
            this(isStoryCell, null);
        }

        public AvatarStoryParams(boolean isStoryCell, Theme.ResourcesProvider resourcesProvider) {
            this.isStoryCell = isStoryCell;
            this.resourcesProvider = resourcesProvider;
        }

        public void onLongPress() {}
    }
}
