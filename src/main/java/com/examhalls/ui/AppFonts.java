package com.examhalls.ui;

import com.examhalls.MainApp;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Registers the design system's typefaces, bundled in {@code com/examhalls/fonts}, so the app looks
 * the same on lab PCs that do not have them installed: Inter (prose, headings) and JetBrains Mono
 * (buttons, labels, numbers), both under the SIL Open Font License. A missing file is skipped;
 * JavaFX then falls back to the system font for that family.
 */
public final class AppFonts {

    private static final Logger log = LoggerFactory.getLogger(AppFonts.class);
    private static final String[] FILES = {
            "Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf", "Inter-Bold.ttf",
            "JetBrainsMono-Regular.ttf", "JetBrainsMono-Medium.ttf", "JetBrainsMono-SemiBold.ttf"};

    private AppFonts() {
    }

    /** Must run before the first scene is styled. Returns the number of faces registered. */
    public static int load() {
        int loaded = 0;
        for (String file : FILES) {
            try (InputStream in = MainApp.class.getResourceAsStream("fonts/" + file)) {
                if (in != null && Font.loadFont(in, 13) != null) {
                    loaded++;
                }
            } catch (IOException e) {
                log.debug("Could not read font {}", file, e);
            }
        }
        if (loaded < FILES.length) {
            log.info("{} of {} bundled font faces loaded; missing families fall back to the system font",
                    loaded, FILES.length);
        }
        return loaded;
    }
}
