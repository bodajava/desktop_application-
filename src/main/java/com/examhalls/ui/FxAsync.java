package com.examhalls.ui;

import com.examhalls.exception.AppException;
import com.examhalls.exception.OracleErrorTranslator;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs service calls off the JavaFX Application Thread and delivers the outcome back on it.
 * Every failure reaches {@code onError} as an {@link AppException} (already translated).
 */
public final class FxAsync {

    private static final Logger log = LoggerFactory.getLogger(FxAsync.class);

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fx-worker");
        t.setDaemon(true);
        return t;
    });

    private FxAsync() {
    }

    /**
     * @param work      runs on a worker thread
     * @param onSuccess FX thread
     * @param onError   FX thread
     * @param always    FX thread, after success or error (e.g. hide the busy overlay); may be null
     */
    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<AppException> onError, Runnable always) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> {
            try {
                onSuccess.accept(task.getValue());
            } finally {
                if (always != null) {
                    always.run();
                }
            }
        });
        task.setOnFailed(e -> {
            AppException ae = OracleErrorTranslator.wrap(task.getException());
            log.warn("Background task failed: {} ({})", ae.code(), ae.detail(), task.getException());
            try {
                onError.accept(ae);
            } finally {
                if (always != null) {
                    always.run();
                }
            }
        });
        EXECUTOR.execute(task);
    }

    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<AppException> onError) {
        run(work, onSuccess, onError, null);
    }

    public static void fx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }
}
