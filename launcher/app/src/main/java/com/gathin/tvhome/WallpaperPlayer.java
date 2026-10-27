package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final Config config;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    private static final class MovieViews {
        final LinearLayout container;
        final TextView title;
        final TextView rating;
        final TextView overview;

        MovieViews(LinearLayout container, TextView title, TextView rating, TextView overview) {
            this.container = container;
            this.title = title;
            this.rating = rating;
            this.overview = overview;
        }
    }

    private MovieViews moviePanel;
    private final Map<String, WallpaperCaptions.MovieMeta> names = new java.util.HashMap<>();
    private boolean panelVisible = true;
    private final android.os.Handler rotationHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long ROTATION_INTERVAL_MS = 25_000L; // Smooth rotation every 25 seconds

    private final Runnable autoRotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (active && !closed && files.size() > 1) {
                rotateNext();
                rotationHandler.postDelayed(this, ROTATION_INTERVAL_MS);
            }
        }
    };

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        this.config = config;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names.clear();
        if (config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()) {
            names.putAll(WallpaperCaptions.read(files.get(0).getParentFile()));
        }
        moviePanel = config.wallpaperCaptions ? makeMoviePanel(context, root) : null;
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        rotationHandler.removeCallbacks(autoRotateRunnable);
        rotationHandler.postDelayed(autoRotateRunnable, ROTATION_INTERVAL_MS);
        update();
        TmdbWallpaperFetcher.checkAndUpdate(root.getContext(), this, config);
    }

    void reload(File wallpapersDir) {
        if (wallpapersDir == null || !wallpapersDir.isDirectory()) return;
        List<File> newImages = WallpaperRotation.images(wallpapersDir);
        if (newImages.isEmpty()) return;
        Map<String, WallpaperCaptions.MovieMeta> newNames = WallpaperCaptions.read(wallpapersDir);
        rotationHandler.post(() -> {
            files.clear();
            files.addAll(newImages);
            names.clear();
            names.putAll(newNames);
            if (moviePanel == null) {
                moviePanel = makeMoviePanel(root.getContext(), root);
            }
            if (image.getParent() == null) {
                root.addView(image, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
        });
    }

    void stop() {
        active = false;
        rotationHandler.removeCallbacks(autoRotateRunnable);
    }

    private static CharSequence formatMeta(String text) {
        if (text == null || text.isEmpty()) return "";
        int bullet = text.indexOf('•');
        if (bullet > 0) {
            SpannableString ss = new SpannableString(text);
            ss.setSpan(new ForegroundColorSpan(0xFFFFD700), 0, bullet, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new ForegroundColorSpan(0xD9E2E8F0), bullet, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return ss;
        }
        return text;
    }

    void setPanelVisible(boolean visible, boolean animate) {
        panelVisible = visible;
        if (moviePanel == null) return;
        moviePanel.container.animate().cancel();
        if (visible) {
            WallpaperCaptions.MovieMeta meta = names.get(displayed);
            if (meta != null) {
                moviePanel.title.setText(meta.title);
                if (meta.rating != null && !meta.rating.isEmpty()) {
                    moviePanel.rating.setText(formatMeta(meta.rating));
                    moviePanel.rating.setVisibility(View.VISIBLE);
                } else {
                    moviePanel.rating.setVisibility(View.GONE);
                }
                if (meta.overview != null && !meta.overview.isEmpty()) {
                    moviePanel.overview.setText(meta.overview);
                    moviePanel.overview.setVisibility(View.VISIBLE);
                } else {
                    moviePanel.overview.setVisibility(View.GONE);
                }
            }
        }
        float dp = root.getContext().getResources().getDisplayMetrics().density;
        float targetY = visible ? 0f : -90f * dp; // Animate up outside view
        float targetAlpha = visible ? 1f : 0f;
        if (!animate) {
            moviePanel.container.setTranslationY(targetY);
            moviePanel.container.setAlpha(targetAlpha);
            return;
        }
        if (visible) {
            moviePanel.container.animate()
                    .translationY(targetY)
                    .alpha(targetAlpha)
                    .setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            moviePanel.container.animate()
                    .translationY(targetY)
                    .alpha(targetAlpha)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .start();
        }
    }

    void close() {
        active = false;
        closed = true;
        rotationHandler.removeCallbacks(autoRotateRunnable);
        decoder.shutdownNow();
    }

    private void rotateNext() {
        if (!active || closed || loading || files.size() <= 1) return;
        File next = WallpaperRotation.pick(files, displayed, order, random);
        if (next == null || next.getName().equals(displayed)) return;
        loadAndDisplay(next);
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        loadAndDisplay(selected);
    }

    private void loadAndDisplay(File file) {
        if (loading) return;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(file);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(file);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + file.getName());
                    rotateNext();
                    return;
                }
                show(bitmap);
                displayed = file.getName();
                showCaption(true);
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
            });
        });
    }

    private static MovieViews makeMoviePanel(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setFocusable(false);
        panel.setAlpha(0f);

        // Movie title: prominent, elegant font - full title visible without cropping
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTextColor(0xFFFFFFFF);
        title.setShadowLayer(10 * dp, 0, 2 * dp, 0xE6000000);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(Math.round(850 * dp));
        title.setLineSpacing(2 * dp, 1.1f);
        title.setPadding(0, 0, Math.round(16 * dp), Math.round(4 * dp));
        title.setFocusable(false);
        try {
            title.setTypeface(UiFont.get(context, R.string.font_medium));
        } catch (Exception ignored) {}

        // Rating in gold stars + Year, Genre, Runtime
        TextView rating = new TextView(context);
        rating.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        rating.setTextColor(0xFFFFD700); // Gold base
        rating.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        rating.setSingleLine(true);
        rating.setEllipsize(TextUtils.TruncateAt.END);
        rating.setMaxWidth(Math.round(850 * dp));
        rating.setFocusable(false);
        rating.setPadding(0, Math.round(4 * dp), 0, Math.round(6 * dp));
        try {
            rating.setTypeface(UiFont.get(context, R.string.font_medium));
        } catch (Exception ignored) {}

        // Overview / description in Czech
        TextView overview = new TextView(context);
        overview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        overview.setTextColor(0xD9E2E8F0);
        overview.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setMaxWidth(Math.round(640 * dp));
        overview.setLineSpacing(2 * dp, 1.15f);
        overview.setFocusable(false);
        try {
            overview.setTypeface(UiFont.get(context, R.string.font_light));
        } catch (Exception ignored) {}

        panel.addView(title);
        panel.addView(rating);
        panel.addView(overview);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        params.topMargin = Math.round(155 * dp);
        params.setMarginStart(Math.round(60 * dp));
        root.addView(panel, params);

        return new MovieViews(panel, title, rating, overview);
    }

    private void showCaption(boolean fade) {
        if (moviePanel == null) return;
        WallpaperCaptions.MovieMeta meta = names.get(displayed);
        moviePanel.container.animate().cancel();
        if (meta == null || !panelVisible) {
            if (meta != null) {
                moviePanel.title.setText(meta.title);
                if (meta.rating != null && !meta.rating.isEmpty()) {
                    moviePanel.rating.setText(formatMeta(meta.rating));
                    moviePanel.rating.setVisibility(View.VISIBLE);
                } else {
                    moviePanel.rating.setVisibility(View.GONE);
                }
                if (meta.overview != null && !meta.overview.isEmpty()) {
                    moviePanel.overview.setText(meta.overview);
                    moviePanel.overview.setVisibility(View.VISIBLE);
                } else {
                    moviePanel.overview.setVisibility(View.GONE);
                }
            }
            if (!panelVisible) {
                float dp = root.getContext().getResources().getDisplayMetrics().density;
                moviePanel.container.setTranslationY(-90f * dp);
            }
            moviePanel.container.setAlpha(0f);
            return;
        }

        Runnable updateTexts = () -> {
            moviePanel.title.setText(meta.title);
            if (meta.rating != null && !meta.rating.isEmpty()) {
                moviePanel.rating.setText(formatMeta(meta.rating));
                moviePanel.rating.setVisibility(View.VISIBLE);
            } else {
                moviePanel.rating.setVisibility(View.GONE);
            }
            if (meta.overview != null && !meta.overview.isEmpty()) {
                moviePanel.overview.setText(meta.overview);
                moviePanel.overview.setVisibility(View.VISIBLE);
            } else {
                moviePanel.overview.setVisibility(View.GONE);
            }
        };

        updateTexts.run();
        if (!fade || moviePanel.container.getAlpha() == 0f) {
            moviePanel.container.setTranslationY(0f);
            moviePanel.container.setAlpha(1f);
            return;
        }

        moviePanel.container.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            updateTexts.run();
            if (panelVisible) {
                moviePanel.container.setTranslationY(0f);
                moviePanel.container.animate().alpha(1f).setDuration(350).start();
            }
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
