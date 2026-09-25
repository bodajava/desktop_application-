package com.examhalls.ui;

import com.examhalls.MainApp;
import com.examhalls.util.Messages;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Save-dialog + background export + open-when-done, shared by every export button. */
public final class Exports {

    private static File lastDirectory;

    private Exports() {
    }

    public enum Kind {
        PDF("PDF", "*.pdf"), EXCEL("Excel", "*.xlsx");

        final String label;
        final String pattern;

        Kind(String label, String pattern) {
            this.label = label;
            this.pattern = pattern;
        }
    }

    /**
     * Asks where to save {@code suggestedName}, runs {@code export} off the UI thread with the
     * overlay shown, then opens the file and shows a toast. Does nothing if the user cancels.
     */
    public static void run(Kind kind, String suggestedName, BusyOverlay busy, Consumer<Path> export) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("reports.saveTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(kind.label, kind.pattern));
        chooser.setInitialFileName(suggestedName);
        File home = new File(System.getProperty("user.home"), "Documents");
        chooser.setInitialDirectory(lastDirectory != null && lastDirectory.isDirectory() ? lastDirectory
                : home.isDirectory() ? home : new File(System.getProperty("user.home")));
        File file = chooser.showSaveDialog(Navigator.get().stage());
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        Path target = file.toPath();
        busy.show(Messages.get("busy.exporting"));
        FxAsync.run(() -> {
            export.accept(target);
            return target;
        }, path -> {
            Navigator.get().shell().toast(Messages.get("toast.exported", path.getFileName()));
            MainApp.openFile(path);
        }, e -> Alerts.error(Navigator.get().stage(), e), busy::hide);
    }

    /** Filesystem-safe fragment for suggested file names. */
    public static String safe(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
