package com.vanvatcorporation.doubleclips.drive;

import java.util.List;

/**
 * Terms of Service / Privacy Policy as the server sends them (backend LegalApp). The server parses the Markdown into blocks
 * of styled runs, so the app only has to draw them. Gson fills the public fields.
 */
public final class LegalModels {
    private LegalModels() {}

    public static final class Span {
        public String t;
        public Boolean b, i, c;
        public String href;
    }

    /** type: h (heading, level), p, li (depth, ordered, number), quote, rule. */
    public static final class Block {
        public String type;
        public List<Span> spans;
        public Integer level, depth, number;
        public Boolean ordered;
    }

    public static final class Document {
        public String key, slug, version, lastUpdated, lang, title;
        public boolean fallback;
        public List<String> languages;
        public List<Block> blocks;
    }

    public static final class StatusDoc {
        public String key, slug, version, lastUpdated, title;
        public boolean accepted;
    }

    /** What the signed-in user still has to accept (also inside GET /me as `legal`). */
    public static final class Status {
        public String product, inline;
        public boolean required;
        public List<StatusDoc> documents;
    }
}
