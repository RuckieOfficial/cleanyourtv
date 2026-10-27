package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.Test;

public class WallpaperCaptionsTest {

    @Test
    public void readsFilenameTabName() {
        Map<String, WallpaperCaptions.MovieMeta> names = WallpaperCaptions.parse(Arrays.asList(
                "001.jpg\tRed Spider Nebula · Hubble\t★★★★☆ 8.0\tA great movie",
                "002.jpg\t  Falcon 9 at sunset  "));
        assertEquals("Red Spider Nebula · Hubble", names.get("001.jpg").title);
        assertEquals("★★★★☆ 8.0", names.get("001.jpg").rating);
        assertEquals("A great movie", names.get("001.jpg").overview);
        assertEquals("Falcon 9 at sunset", names.get("002.jpg").title);
    }

    @Test
    public void skipsCommentsBlankAndMalformedLines() {
        Map<String, WallpaperCaptions.MovieMeta> names = WallpaperCaptions.parse(Arrays.asList(
                "# captions for the collection", "", "   ", "no-tab-here.jpg Dusk", "\tno file", "003.jpg\t", null));
        assertTrue(names.isEmpty());
    }

    @Test
    public void laterLineWinsForTheSameFile() {
        Map<String, WallpaperCaptions.MovieMeta> names = WallpaperCaptions.parse(Arrays.asList("005.jpg\tOld", "005.jpg\tNew"));
        assertEquals("New", names.get("005.jpg").title);
        assertFalse(names.containsKey("006.jpg"));
    }

    @Test
    public void missingFolderHasNoNames() {
        assertTrue(WallpaperCaptions.read(null).isEmpty());
    }
}
