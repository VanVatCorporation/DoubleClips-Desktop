package com.vanvatcorporation.doubleclips.helper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DateHelper {

    public static String convertTimestampToHHMMSSFormat(long timestamp) {
        long seconds = timestamp / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    public static String convertTimestampToMMSSFormat(long timestamp) {
        long seconds = timestamp / 1000;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        return String.format("%02d:%02d", minutes, secs);
    }

    public static String convertTimestampToDateTimeStringFormat(long timestamp) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(timestamp));
    }
}
