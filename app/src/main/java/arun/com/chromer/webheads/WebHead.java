package arun.com.chromer.webheads;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.BounceInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import arun.com.chromer.R;
import arun.com.chromer.util.Preferences;
import arun.com.chromer.util.Util;
import timber.log.Timber;

/**
 * Created by Arun on 30/01/2016.
 */
@SuppressLint("ViewConstructor")
public class WebHead extends FrameLayout {

    private static int WEB_HEAD_COUNT = 0;

    private static final int STACKING_GAP_PX = Util.dpToPx(6);

    private static WindowManager sWindowManager;

    private static final double MAGNETISM_THRESHOLD = Util.dpToPx(120);

    private static Point mCentreLockPoint;

    private final String mUrl;

    private final GestureDetector mGestDetector = new GestureDetector(getContext(), new GestureTapListener());

    private final int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

    private float posX;
    private float posY;

    private int initialDownX, initialDownY;

    private WindowManager.LayoutParams mWindowParams;

    private int mDispHeight, mDispWidth;

    private boolean mDragging;

    private Spring mScaleSpring, mWallAttachSpring, mXSpring, mYSpring;
    private SpringSystem mSpringSystem;

    private WebHeadCircle contentView;

    private boolean mWasRemoveLocked;

    private boolean mDimmed;

    private boolean mUserManuallyMoved;

    private WebHeadInteractionListener mInteractionListener;

    private boolean isBeingDestroyed;

    private ImageView mFavicon;

    private ImageView mAppIcon;

    public WebHead(Context context, String url) {
        super(context);
        mUrl = url;

        if (sWindowManager == null) {
            sWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        }

        init(context, url);

        WEB_HEAD_COUNT++;

        Timber.d("Created %d webheads", WEB_HEAD_COUNT);
    }


    private void init(Context context, String url) {
        contentView = new WebHeadCircle(context, url);
        addView(contentView);

        mWindowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        setDisplayMetrics();
        setSpawnLocation();
        setUpSprings();
    }

    private void initFavicon() {
        if (mFavicon == null) {
            mFavicon = (ImageView) LayoutInflater.from(getContext()).inflate(R.layout.favicon_layout, this, false);
            addView(mFavicon);
        }
    }

    private void initAppIcon() {
        if (mAppIcon == null) {
            mAppIcon = (ImageView) LayoutInflater.from(getContext()).inflate(R.layout.web_head_app_indicator_layout, this, false);
            addView(mAppIcon);
        }
    }

    private void setUpSprings() {
        mSpringSystem = SpringSystem.create();

        mScaleSpring = mSpringSystem.createSpring();
        mScaleSpring.addListener(new ScaleSpringListener());

        mWallAttachSpring = mSpringSystem.createSpring();
        mWallAttachSpring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
        mWallAttachSpring.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                if (!mDragging) {
                    mWindowParams.x = (int) spring.getCurrentValue();
                    sWindowManager.updateViewLayout(WebHead.this, mWindowParams);
                }
            }
        });

        SpringConfig movingSpringConfig = SpringConfig.fromOrigamiTensionAndFriction(100, 7);

        mYSpring = mSpringSystem.createSpring();
        mYSpring.setSpringConfig(movingSpringConfig);
        mYSpring.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                mWindowParams.y = (int) spring.getCurrentValue();
                sWindowManager.updateViewLayout(WebHead.this, mWindowParams);
            }
        });

        mXSpring = mSpringSystem.createSpring();
        mYSpring.setSpringConfig(movingSpringConfig);
        mXSpring.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                mWindowParams.x = (int) spring.getCurrentValue();
                sWindowManager.updateViewLayout(WebHead.this, mWindowParams);
            }
        });
    }

    private void setDisplayMetrics() {
        final DisplayMetrics metrics = new DisplayMetrics();
        sWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDispWidth = metrics.widthPixels;
        mDispHeight = metrics.heightPixels;
    }

    @SuppressLint("RtlHardcoded")
    private void setSpawnLocation() {
        mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Preferences.webHeadsSpawnLocation(getContext()) == 1) {
            mWindowParams.x = (int) (mDispWidth - WebHeadCircle.getSizePx() * 0.8);
        } else {
            mWindowParams.x = (int) (0 - WebHeadCircle.getSizePx() * 0.2);
        }
        mWindowParams.y = mDispHeight / 3;
    }

    private RemoveWebHead getRemoveWebHead() {
        return RemoveWebHead.get(getContext());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Don't react to any touch event and consume it when we are being destroyed
        if (isBeingDestroyed) return true;
        try {
            mGestDetector.onTouchEvent(event);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialDownX = mWindowParams.x;
                    initialDownY = mWindowParams.y;

                    posX = event.getRawX();
                    posY = event.getRawY();

                    // Shrink on touch
                    setTouchingScale();

                    // transparent on touch
                    setTouchingAlpha();
                    break;
                case MotionEvent.ACTION_UP:
                    mDragging = false;

                    if (mWasRemoveLocked) {
                        // If head was locked onto a remove bubble before, then kill ourselves
                        destroySelf(true);
                        return true;
                    }

                    // Expand on release
                    setReleaseScale();

                    // opaque on release
                    setReleaseAlpha();

                    // Go to the nearest side and rest there
                    stickToWall();

                    // hide remove view
                    RemoveWebHead.hideSelf();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getRawX() - posX, event.getRawY() - posY) > mTouchSlop) {
                        mDragging = true;
                    }

                    if (mDragging) {
                        move(event);
                        getRemoveWebHead().reveal();
                    }
                default:
                    break;
            }
        } catch (NullPointerException e) {
            destroySelf(true);
        }
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Spring scale animation when getting attached to window
        mScaleSpring.setCurrentValue(0);
        mScaleSpring.setEndValue(1f);
    }

    private void stickToWall() {
        int x = mWindowParams.x;
        int dispCentre = mDispWidth / 2;

        mWallAttachSpring.setCurrentValue(x, true);

        int xOffset = (getWidth() / 2);

        if ((x + xOffset) >= dispCentre) {
            // move to right wall
            mWallAttachSpring.setEndValue(mDispWidth - (getWidth() * 0.8));
        } else {
            // move to left wall
            mWallAttachSpring.setEndValue(0 - (getWidth() * 0.2));
        }
    }

    private void move(MotionEvent event) {
        mUserManuallyMoved = true;

        mWindowParams.x = (int) (initialDownX + (event.getRawX() - posX));
        mWindowParams.y = (int) (initialDownY + (event.getRawY() - posY));

        if (isNearRemoveCircle()) {
            getRemoveWebHead().grow();
            setReleaseAlpha();
            setReleaseScale();
            mXSpring.setEndValue(getCentreLockPoint().x);
            mYSpring.setEndValue(getCentreLockPoint().y);
        } else {
            mXSpring.setCurrentValue(mWindowParams.x);
            mYSpring.setCurrentValue(mWindowParams.y);
            getRemoveWebHead().shrink();
            setTouchingAlpha();
            setTouchingScale();
            sWindowManager.updateViewLayout(this, mWindowParams);
        }
    }

    private void setReleaseScale() {
        mScaleSpring.setEndValue(1f);
    }

    private void setReleaseAlpha() {
        if (!mDimmed) {
            contentView.setAlpha(1f);
            if (mFavicon != null) {
                mFavicon.setAlpha(1f);
            }
        }
    }

    private void setTouchingAlpha() {
        if (!mDimmed) {
            contentView.setAlpha(0.7f);
            if (mFavicon != null) {
                mFavicon.setAlpha(0.7f);
            }
        }
    }

    private void setTouchingScale() {
        mScaleSpring.setEndValue(0.8f);
    }

    private boolean isNearRemoveCircle() {
        Point p = getRemoveWebHead().getCenterCoordinates();
        int rX = p.x;
        int rY = p.y;

        int offset = getWidth() / 2;
        int x = mWindowParams.x + offset;
        int y = mWindowParams.y + offset + (offset / 2);

        if (getEuclideanDistance(rX, rY, x, y) < MAGNETISM_THRESHOLD) {
            mWasRemoveLocked = true;
            return true;
        } else {
            mWasRemoveLocked = false;
            return false;
        }
    }

    private Point getCentreLockPoint() {
        if (mCentreLockPoint == null) {
            Point removeCentre = getRemoveWebHead().getCenterCoordinates();
            int offset = getWidth() / 2;
            int x = removeCentre.x - offset;
            int y = removeCentre.y - offset - (offset / 2) - (offset / 4);
            mCentreLockPoint = new Point(x, y);
        }
        return mCentreLockPoint;
    }

    private double getEuclideanDistance(int x1, int y1, int x2, int y2) {
        double x = x1 - x2;
        double y = y1 - y2;
        return Math.sqrt(x * x + y * y);
    }

    public ValueAnimator getStackDistanceAnimator() {
        ValueAnimator animator = null;
        if (!mUserManuallyMoved) {
            animator = ValueAnimator.ofInt(mWindowParams.y, mWindowParams.y + STACKING_GAP_PX);
            animator.setInterpolator(new BounceInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mWindowParams.y = (int) animation.getAnimatedValue();
                    sWindowManager.updateViewLayout(WebHead.this, mWindowParams);
                }
            });
        }
        return animator;
    }

    public void dim() {
        if (!mDimmed) {
            contentView.setAlpha(0.3f);
            if (mFavicon != null) {
                mFavicon.setAlpha(0.3f);
            }
            mDimmed = true;
        }
    }

    public void bright() {
        if (mDimmed) {
            contentView.setAlpha(1f);
            if (mFavicon != null) {
                mFavicon.setAlpha(0.1f);
            }
            mDimmed = false;
        }
    }

    public void setWebHeadInteractionListener(WebHeadInteractionListener listener) {
        mInteractionListener = listener;
    }

    public void setFaviconDrawable(@NonNull Drawable drawable) {
        contentView.clearUrlIndicator();
        initFavicon();
        mFavicon.setImageDrawable(drawable);
        initAppIcon();
    }

    /*
    Really bad method, need to refactor.
    private Bitmap getAppIcon() {
        String packageName = getContext().getPackageName();
        final int appIconSize = getContext().getResources().getDimensionPixelSize(R.dimen.web_head_app_indicator_icon);
        final int size = getContext().getResources().getDimensionPixelSize(R.dimen.web_head_app_indicator_circle);
        Bitmap appIconBitmap;
        Bitmap resultBitmap = null;
        try {
            Drawable appIconDrawable = getContext().getApplicationContext().getPackageManager().getApplicationIcon(packageName);
            if (appIconDrawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) appIconDrawable;
                appIconBitmap = Bitmap.createScaledBitmap(bitmapDrawable.getBitmap(), appIconSize, appIconSize, false);
            } else return null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        if (appIconBitmap != null) {
            resultBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(resultBitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            // canvas.drawCircle(size / 2, size / 2, size / 2, paint);
            int left = (size - appIconBitmap.getWidth()) / 2;
            int top = (size - appIconBitmap.getHeight()) / 2;
            ColorFilter filter = new LightingColorFilter(Color.parseColor("#9E9E9E"), 0);
            paint.setColorFilter(filter);
            canvas.drawBitmap(appIconBitmap, left, top, null);
        }
        return resultBitmap;
    }*/

    private boolean isLastWebHead() {
        return WEB_HEAD_COUNT - 1 == 0;
    }

    @NonNull
    public ImageView getFaviconView() {
        initFavicon();
        return mFavicon;
    }

    public WindowManager.LayoutParams getWindowParams() {
        return mWindowParams;
    }

    public String getUrl() {
        return mUrl;
    }

    public void destroySelf(boolean shouldReceiveCallback) {
        isBeingDestroyed = true;

        if (mInteractionListener != null && shouldReceiveCallback) {
            mInteractionListener.onWebHeadDestroy(this, isLastWebHead());
        }

        WEB_HEAD_COUNT--;

        Timber.d("%d Webheads remaining", WEB_HEAD_COUNT);

        mWallAttachSpring.setAtRest();
        mWallAttachSpring.destroy();
        mWallAttachSpring = null;

        mScaleSpring.setAtRest();
        mScaleSpring.destroy();
        mScaleSpring = null;

        mYSpring.setAtRest();
        mYSpring.destroy();
        mYSpring = null;

        mXSpring.setAtRest();
        mXSpring.destroy();
        mXSpring = null;

        RemoveWebHead.hideSelf();

        mSpringSystem = null;

        setWebHeadInteractionListener(null);

        removeView(contentView);

        if (mFavicon != null) removeView(mFavicon);
        if (mAppIcon != null) removeView(mAppIcon);

        contentView = null;
        mFavicon = null;
        mAppIcon = null;
        sWindowManager.removeView(this);
    }

    public interface WebHeadInteractionListener {
        void onWebHeadClick(@NonNull WebHead webHead);

        void onWebHeadDestroy(@NonNull WebHead webHead, boolean isLastWebHead);
    }

    private class ScaleSpringListener extends SimpleSpringListener {

        @Override
        public void onSpringUpdate(Spring spring) {
            float value = (float) spring.getCurrentValue();
            contentView.setScaleX(value);
            contentView.setScaleY(value);
            if (mFavicon != null) {
                mFavicon.setScaleY(value);
                mFavicon.setScaleX(value);
            }
        }
    }

    private class GestureTapListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (mInteractionListener != null) mInteractionListener.onWebHeadClick(WebHead.this);

            RemoveWebHead.hideSelf();

            return super.onSingleTapConfirmed(e);
        }

    }

}
