package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Autonomous TMDb movie backdrop fetcher running directly on Android TV.
 * Downloads top trending and popular backdrops in high resolution with localized metadata,
 * storing a clean rotating queue of wallpapers on device without needing external scripts.
 */
final class TmdbWallpaperFetcher {
    private static final String TAG = "TmdbWallpaperFetcher";
    private static final String PREFS_NAME = "tmdb_wallpaper_prefs";
    private static final String KEY_LAST_UPDATE = "last_update_ms";
    private static final long UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final int TARGET_POPULAR_COUNT = 12;
    private static final int TARGET_DISNEY_COUNT = 8;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IS_FETCHING = new AtomicBoolean(false);

    private TmdbWallpaperFetcher() {}

    static void checkAndUpdate(Context context, WallpaperPlayer player, Config cfg) {
        if (context == null || IS_FETCHING.get()) return;

        String key = cfg != null && !TextUtils.isEmpty(cfg.tmdbApiKey) ? cfg.tmdbApiKey.trim() : "";
        if (TextUtils.isEmpty(key)) {
            key = BuildConfig.TMDB_API_KEY != null ? BuildConfig.TMDB_API_KEY.trim() : "";
        }
        if (TextUtils.isEmpty(key)) {
            File baseDir = Config.dir(context);
            File keyFile = baseDir != null ? new File(baseDir, "tmdb_key.txt") : null;
            if (keyFile != null && keyFile.exists() && keyFile.canRead()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(keyFile), StandardCharsets.UTF_8))) {
                    String line = br.readLine();
                    if (line != null) key = line.trim();
                } catch (Exception ignored) {}
            }
        }

        if (TextUtils.isEmpty(key)) {
            Log.i(TAG, "No TMDb API key configured (set TMDB_API_KEY in env or tmdb_api_key in config.json). Skipping TMDb fetch.");
            return;
        }

        String lang = cfg != null && !TextUtils.isEmpty(cfg.tmdbLanguage) ? cfg.tmdbLanguage.trim() : "en-US";

        long lastUpdate = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_UPDATE, 0);
        File baseDir = Config.dir(context);
        File wallpapersDir = baseDir != null ? new File(baseDir, "wallpapers") : null;
        int existingCount = wallpapersDir != null ? WallpaperRotation.images(wallpapersDir).size() : 0;

        boolean isDue = (System.currentTimeMillis() - lastUpdate) > UPDATE_INTERVAL_MS;
        boolean isEmptyOrFew = existingCount < 5;

        if (!isDue && !isEmptyOrFew) {
            return;
        }

        final String finalKey = key;
        final String finalLang = lang;
        EXECUTOR.execute(() -> doFetch(context.getApplicationContext(), player, finalKey, finalLang));
    }

    private static void doFetch(Context context, WallpaperPlayer player, String apiKey, String lang) {
        if (!IS_FETCHING.compareAndSet(false, true)) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo net = cm != null ? cm.getActiveNetworkInfo() : null;
            if (net == null || !net.isConnected()) {
                Log.i(TAG, "No internet connection; skipping wallpaper fetch.");
                return;
            }

            Log.i(TAG, "Starting autonomous background TMDb fetch on TV (lang=" + lang + ")...");
            List<JSONObject> popular = new ArrayList<>();
            Set<Integer> seenIds = new HashSet<>();

            // 1. Fetch up to 12 popular movies with localized overview
            for (int page = 1; page <= 3 && popular.size() < TARGET_POPULAR_COUNT; page++) {
                String url = "https://api.themoviedb.org/3/movie/popular?api_key=" + apiKey
                        + "&language=" + lang + "&page=" + page;
                JSONObject json = fetchJson(url);
                if (json != null) {
                    JSONArray results = json.optJSONArray("results");
                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject m = results.optJSONObject(i);
                            if (m == null) continue;
                            int id = m.optInt("id", 0);
                            String bp = m.optString("backdrop_path", "");
                            String ov = m.optString("overview", "").trim();
                            if (id > 0 && !bp.isEmpty() && !bp.equals("null") && ov.length() >= 20 && !seenIds.contains(id)) {
                                seenIds.add(id);
                                popular.add(m);
                                if (popular.size() == TARGET_POPULAR_COUNT) break;
                            }
                        }
                    }
                }
            }

            // 2. Fetch up to 8 Disney/Pixar movies with localized overview
            List<JSONObject> disney = new ArrayList<>();
            for (int page = 1; page <= 3 && disney.size() < TARGET_DISNEY_COUNT; page++) {
                String url = "https://api.themoviedb.org/3/discover/movie?api_key=" + apiKey
                        + "&with_companies=2%7C3&language=" + lang + "&page=" + page;
                JSONObject json = fetchJson(url);
                if (json != null) {
                    JSONArray results = json.optJSONArray("results");
                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject m = results.optJSONObject(i);
                            if (m == null) continue;
                            int id = m.optInt("id", 0);
                            String bp = m.optString("backdrop_path", "");
                            String ov = m.optString("overview", "").trim();
                            if (id > 0 && !bp.isEmpty() && !bp.equals("null") && ov.length() >= 20 && !seenIds.contains(id)) {
                                seenIds.add(id);
                                disney.add(m);
                                if (disney.size() == TARGET_DISNEY_COUNT) break;
                            }
                        }
                    }
                }
            }

            List<JSONObject> allMovies = new ArrayList<>(popular);
            allMovies.addAll(disney);
            if (allMovies.isEmpty()) {
                Log.w(TAG, "No movies found to download.");
                return;
            }

            File baseDir = Config.dir(context);
            if (baseDir == null) return;
            File stagingDir = new File(baseDir, "wallpapers_staging");
            deleteRecursively(stagingDir);
            if (!stagingDir.mkdirs()) {
                Log.e(TAG, "Could not create staging directory: " + stagingDir);
                return;
            }

            List<String> captionsLines = new ArrayList<>();
            int count = 0;

            for (JSONObject m : allMovies) {
                int mid = m.optInt("id", 0);
                String title = m.optString("title", "").replace("\t", " ").trim();
                String bp = m.optString("backdrop_path", "");
                if (mid <= 0 || title.isEmpty() || bp.isEmpty() || bp.equals("null")) continue;

                // Movie details for runtime and genres
                String duration = "";
                List<String> genres = new ArrayList<>();
                try {
                    String detailsUrl = "https://api.themoviedb.org/3/movie/" + mid + "?api_key=" + apiKey + "&language=" + lang;
                    JSONObject details = fetchJson(detailsUrl);
                    if (details != null) {
                        int runtime = details.optInt("runtime", 0);
                        if (runtime >= 60) {
                            duration = (runtime / 60) + "h " + (runtime % 60) + "m";
                        } else if (runtime > 0) {
                            duration = runtime + "m";
                        }
                        JSONArray genresArr = details.optJSONArray("genres");
                        if (genresArr != null) {
                            for (int g = 0; g < genresArr.length() && g < 3; g++) {
                                JSONObject gObj = genresArr.optJSONObject(g);
                                if (gObj != null) {
                                    String gName = gObj.optString("name", "").trim();
                                    if (!gName.isEmpty()) genres.add(gName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load details for " + mid, e);
                }

                // Rating & gold stars
                double vote = m.optDouble("vote_average", 0.0);
                int starsCount = (int) Math.round(vote / 2.0);
                if (starsCount < 0) starsCount = 0;
                if (starsCount > 5) starsCount = 5;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < starsCount; i++) sb.append("★");
                for (int i = starsCount; i < 5; i++) sb.append("☆");
                String starsStr = String.format(Locale.ROOT, "%s %.1f", sb.toString(), vote);

                // Year
                String rel = m.optString("release_date", "");
                String year = rel.length() >= 4 ? rel.substring(0, 4) : "";

                // Meta line
                List<String> metaParts = new ArrayList<>();
                metaParts.add(starsStr);
                if (!year.isEmpty()) metaParts.add(year);
                if (!genres.isEmpty()) metaParts.add(TextUtils.join(", ", genres));
                if (!duration.isEmpty()) metaParts.add(duration);
                String metaLine = TextUtils.join("   •   ", metaParts);

                // Overview
                String overview = m.optString("overview", "").replace("\t", " ").replace("\n", " ").trim();
                if (overview.length() > 180) {
                    overview = overview.substring(0, 177) + "...";
                }

                count++;
                String fileName = String.format(Locale.ROOT, "%02d_%d.jpg", count, mid);
                File destFile = new File(stagingDir, fileName);
                String imgUrl = "https://image.tmdb.org/t/p/original" + bp;

                boolean downloaded = downloadFile(imgUrl, destFile);
                if (downloaded) {
                    captionsLines.add(fileName + "\t" + title + "\t" + metaLine + "\t" + overview);
                    Log.i(TAG, "[" + count + "/" + allMovies.size() + "] Downloaded: " + title);
                }
            }

            // Only swap if we successfully downloaded at least 5 wallpapers
            if (captionsLines.size() >= 5) {
                File captionsFile = new File(stagingDir, WallpaperCaptions.FILE_NAME);
                writeCaptions(captionsFile, captionsLines);

                File wallpapersDir = new File(baseDir, "wallpapers");
                if (!wallpapersDir.exists()) wallpapersDir.mkdirs();

                // Clean out old files
                File[] oldFiles = wallpapersDir.listFiles();
                if (oldFiles != null) {
                    for (File f : oldFiles) f.delete();
                }

                // Move new files into wallpapers directory
                File[] newFiles = stagingDir.listFiles();
                if (newFiles != null) {
                    for (File f : newFiles) {
                        f.renameTo(new File(wallpapersDir, f.getName()));
                    }
                }

                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply();

                Log.i(TAG, "TMDb wallpapers updated successfully (" + captionsLines.size() + " wallpapers).");

                if (player != null) {
                    player.reload(wallpapersDir);
                }
            }
            deleteRecursively(stagingDir);
        } catch (Exception e) {
            Log.e(TAG, "Error in TMDb fetch worker", e);
        } finally {
            IS_FETCHING.set(false);
        }
    }

    private static JSONObject fetchJson(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "CleanTV-AndroidTV/1.0");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return new JSONObject(sb.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP error fetching JSON: " + urlString, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static boolean downloadFile(String urlString, File dest) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "CleanTV-AndroidTV/1.0");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (conn.getResponseCode() == 200) {
                in = conn.getInputStream();
                out = new FileOutputStream(dest);
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error downloading image: " + urlString, e);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private static void writeCaptions(File file, List<String> lines) {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                bw.write(line);
                bw.newLine();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing captions.txt", e);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        file.delete();
    }
}
