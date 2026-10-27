package com.vanvatcorporation.doubleclips.auth;

import java.util.Objects;

/** Which list of templates a screen shows. */
public final class TemplateFeedSource {
    public enum Kind { FEED, SEARCH, LIKED, BOOKMARKED, MINE }

    public final Kind kind;
    /** Only for SEARCH. */
    public final String query;

    private TemplateFeedSource(Kind kind, String query) {
        this.kind = kind;
        this.query = query == null ? "" : query;
    }

    /** The per-account suggestion feed (the Templates tab). */
    public static TemplateFeedSource feed() { return new TemplateFeedSource(Kind.FEED, null); }
    public static TemplateFeedSource search(String query) { return new TemplateFeedSource(Kind.SEARCH, query); }
    /** The signed-in account's Liked list (Profile). */
    public static TemplateFeedSource liked() { return new TemplateFeedSource(Kind.LIKED, null); }
    /** The signed-in account's Saved (bookmarked) list (Profile). */
    public static TemplateFeedSource bookmarked() { return new TemplateFeedSource(Kind.BOOKMARKED, null); }
    /** The templates the signed-in account posted itself (Profile -> My Templates), newest first. */
    public static TemplateFeedSource mine() { return new TemplateFeedSource(Kind.MINE, null); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TemplateFeedSource)) return false;
        TemplateFeedSource other = (TemplateFeedSource) o;
        return kind == other.kind && query.equals(other.query);
    }

    @Override public int hashCode() { return Objects.hash(kind, query); }
}
