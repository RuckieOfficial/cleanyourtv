package com.gathin.tvhome;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names for a wallpaper collection (wallpaper_captions): {@code wallpapers/captions.txt}, one
 * "filename<TAB>name" per line. Blank lines, lines starting with # and lines without a tab are skipped; a name
 * is cut to {@link #MAX_LENGTH} characters. No Android types, so it is tested on the JVM.
 */
final class WallpaperCaptions {
    static final String FILE_NAME = "captions.txt";

    static final class MovieMeta {
        final String title;
        final String rating;
        final String overview;

        MovieMeta(String title, String rating, String overview) {
            this.title = title;
            this.rating = rating;
            this.overview = overview;
        }
    }

    private WallpaperCaptions() { }

    static Map<String, MovieMeta> parse(List<String> lines) {
        Map<String, MovieMeta> names = new HashMap<>();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            String file = parts[0].trim();
            String title = parts[1].trim();
            String rating = parts.length > 2 ? parts[2].trim() : "";
            String overview = parts.length > 3 ? parts[3].trim() : "";
            if (!file.isEmpty() && !title.isEmpty()) {
                names.put(file, new MovieMeta(title, rating, overview));
            }
        }
        return names;
    }

    /** The names in {@code folder}/captions.txt, or none when the file is missing or unreadable. */
    static Map<String, MovieMeta> read(File folder) {
        File file = folder == null ? null : new File(folder, FILE_NAME);
        if (file == null || !file.isFile() || file.length() > 512 * 1024) return Collections.emptyMap();
        try {
            return parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
