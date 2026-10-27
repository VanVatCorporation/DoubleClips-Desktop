package com.vanvatcorporation.doubleclips.auth;

import com.vanvatcorporation.doubleclips.data.TemplateData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pages through one {@link TemplateFeedSource} (feed / search / liked / saved) with the server's cursor. JavaFX thread only.
 *
 *  - A new {@link #start} bumps a generation: an answer for an older source (the user kept typing in the search box,
 *    pressed refresh...) is dropped instead of landing in the new list.
 *  - Templates already delivered are never delivered again.
 */
public class TemplatePager {
    public interface Listener {
        /** A new list begins: clear whatever is shown. */
        void onReset();
        /** More templates (never ones delivered before). */
        void onPage(List<TemplateData> items, boolean hasMore);
        void onLoading(boolean loading);
        void onError(String message);
    }

    private static final int MAX_EMPTY_RETRIES = 3;

    private final Listener listener;
    private TemplateFeedSource source = TemplateFeedSource.feed();
    private String cursor;
    private boolean hasMore = true;
    private boolean loading;
    private int generation;
    private final Set<String> delivered = new HashSet<>();

    public TemplatePager(Listener listener) {
        this.listener = listener;
    }

    public TemplateFeedSource getSource() { return source; }
    public boolean hasMore() { return hasMore; }
    public boolean isLoading() { return loading; }

    /** Begins a fresh list (first page) of the given source. */
    public void start(TemplateFeedSource newSource) {
        generation++;
        source = newSource;
        cursor = null;
        hasMore = true;
        loading = false;
        delivered.clear();
        listener.onReset();
        loadNext();
    }

    public void loadNext() {
        loadNext(0);
    }

    private void loadNext(int emptyRetries) {
        if (loading || !hasMore) return;
        loading = true;
        listener.onLoading(true);
        final int gen = generation;

        TemplateRepository.getInstance().fetchPage(source, cursor, TemplateRepository.PAGE_SIZE,
                page -> {
                    if (gen != generation) return;           // an older source: drop
                    loading = false;
                    cursor = page.nextCursor;
                    hasMore = page.hasMore && cursor != null && !cursor.isEmpty();

                    List<TemplateData> fresh = new ArrayList<>();
                    for (TemplateData d : page.items) {
                        if (delivered.add(d.getTemplateId().toLowerCase())) fresh.add(d);
                    }
                    if (fresh.isEmpty() && hasMore && emptyRetries < MAX_EMPTY_RETRIES) {
                        loadNext(emptyRetries + 1);          // a whole page of repeats: ask for the next one
                        return;
                    }
                    listener.onLoading(false);
                    listener.onPage(fresh, hasMore);
                },
                message -> {
                    if (gen != generation) return;
                    loading = false;
                    listener.onLoading(false);
                    listener.onError(message);
                });
    }
}
