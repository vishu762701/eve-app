package org.telegram.messenger;

// EVE-SHIM
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

public class ImageReceiver {
    private View parentView;
    private Drawable drawable;
    private float imageX;
    private float imageY;
    private float imageW;
    private float imageH;
    private int roundRadius;
    private boolean visible = true;

    public boolean ignoreNotifications;
    public Shader thumbShader;
    public Shader staticThumbShader;

    public ImageReceiver() {}

    public ImageReceiver(View parent) {
        this.parentView = parent;
    }

    public View getParentView() {
        return parentView;
    }

    public void setParentView(View view) {
        this.parentView = view;
    }

    public void setRoundRadius(int value) {
        this.roundRadius = value;
    }

    public void setRoundRadius(int[] value) {
        if (value != null && value.length > 0) {
            this.roundRadius = value[0];
        }
    }

    public int getRoundRadius() {
        return roundRadius;
    }

    public void setAllowLoadingOnAttachedOnly(boolean value) {}

    public void onAttachedToWindow() {}

    public void onDetachedFromWindow() {}

    public void setImage(ImageLocation mediaLocation, String imageFilter, Drawable thumb, String ext, int size) {
        this.drawable = thumb;
    }

    public void setImage(ImageLocation mediaLocation, String imageFilter, Drawable thumb, String ext, Object parentObject, int size) {
        this.drawable = thumb;
    }

    public void setForUserOrChat(Object userOrChat, Drawable defaultAvatar) {
        this.drawable = defaultAvatar;
    }

    public void setImageCoords(RectF rect) {
        if (rect != null) {
            setImageCoords(rect.left, rect.top, rect.width(), rect.height());
        }
    }

    public void setImageCoords(float x, float y, float width, float height) {
        this.imageX = x;
        this.imageY = y;
        this.imageW = width;
        this.imageH = height;
    }

    public float getImageX() { return imageX; }
    public float getImageY() { return imageY; }
    public float getImageWidth() { return imageW; }
    public float getImageHeight() { return imageH; }
    public float getImageX2() { return imageX + imageW; }
    public float getImageY2() { return imageY + imageH; }
    public float getCenterX() { return imageX + imageW / 2.0f; }
    public float getCenterY() { return imageY + imageH / 2.0f; }

    public void setVisible(boolean value, boolean invalidate) {
        this.visible = value;
    }

    public boolean getVisible() {
        return visible;
    }

    public Drawable getDrawable() {
        return drawable;
    }

    public Bitmap getBitmap() {
        return null;
    }

    public int getBitmapWidth() {
        return (int) imageW;
    }

    public boolean updateThumbShaderMatrix() {
        return false;
    }

    public boolean draw(Canvas canvas) {
        if (drawable != null && visible) {
            drawable.setBounds((int) imageX, (int) imageY, (int) (imageX + imageW), (int) (imageY + imageH));
            drawable.draw(canvas);
            return true;
        }
        return false;
    }
}
