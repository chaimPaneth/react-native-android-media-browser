package com.mediabrowser;

import android.os.Bundle;
import android.util.Log;
import org.json.JSONObject;

final class ResumeParsingUtils {
    private static final String TAG = "ResumeParsingUtils";
    private ResumeParsingUtils() {}

    static long extractResumePosition(Bundle extras) {
        if (extras == null) return 0L;
        String infoJson = extras.getString("info", null);
        if (infoJson == null) return 0L;
        try {
            JSONObject obj = new JSONObject(infoJson);
            Double sec = readSeconds(obj, "timepoint");
            return (sec != null && sec > 0) ? (long)(sec * 1000L) : 0L;
        } catch (Exception e) {
            Log.w(TAG, "parse resume failed: " + e.getMessage());
            return 0L;
        }
    }

    private static Double readSeconds(JSONObject obj, String key) {
        try {
            double v = obj.optDouble(key, Double.NaN);
            if (!Double.isNaN(v)) return v;
            String s = obj.optString(key, null);
            return (s != null) ? Double.parseDouble(s) : null;
        } catch (Exception ignore) { return null; }
    }
}