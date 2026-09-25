package com.examhalls.report;

import com.examhalls.config.AppSettings;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.lowagie.text.pdf.BaseFont;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Locates a TrueType font with Arabic glyphs to embed in PDFs. Fonts are not bundled (licensing);
 * the configured path wins, otherwise common OS fonts are tried.
 */
public final class ReportFonts {

    private static final List<String> REGULAR = List.of(
            "C:/Windows/Fonts/tahoma.ttf", "C:/Windows/Fonts/arial.ttf",
            "/System/Library/Fonts/Supplemental/Tahoma.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/Library/Fonts/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansArabic-Regular.ttf");
    private static final List<String> BOLD = List.of(
            "C:/Windows/Fonts/tahomabd.ttf", "C:/Windows/Fonts/arialbd.ttf",
            "/System/Library/Fonts/Supplemental/Tahoma Bold.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
            "/Library/Fonts/Arial Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansArabic-Bold.ttf");

    private static BaseFont regular;
    private static BaseFont bold;

    private ReportFonts() {
    }

    public static synchronized BaseFont regular() {
        if (regular == null) {
            regular = load(AppSettings.get("reports.font.regular", null), REGULAR, null);
        }
        return regular;
    }

    /** Falls back to the regular font when no bold face is found. */
    public static synchronized BaseFont bold() {
        if (bold == null) {
            bold = load(AppSettings.get("reports.font.bold", null), BOLD, regular());
        }
        return bold;
    }

    private static BaseFont load(String configured, List<String> candidates, BaseFont fallback) {
        String path = configured;
        if (path == null) {
            path = candidates.stream().filter(p -> Files.isRegularFile(Path.of(p))).findFirst().orElse(null);
        }
        if (path == null) {
            if (fallback != null) {
                return fallback;
            }
            throw new AppException(ErrorCode.REPORT_FONT_MISSING,
                    "No Arabic-capable font found; set reports.font.regular in application.properties");
        }
        try {
            return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Exception e) {
            throw new AppException(ErrorCode.REPORT_FONT_MISSING, "Cannot load font " + path + ": " + e.getMessage(), e);
        }
    }
}
