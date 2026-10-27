package com.vanvatcorporation.doubleclips.auth;

import com.vanvatcorporation.doubleclips.data.TemplateData;

import java.util.ArrayList;
import java.util.List;

/** One page of the server's list envelope ({ items, nextCursor, hasMore, personalized }). */
public class TemplatePage {
    public String returnResult;
    public List<TemplateData> items = new ArrayList<>();
    public String nextCursor;
    public boolean hasMore;
    public boolean personalized;
    public String algorithm;
}
