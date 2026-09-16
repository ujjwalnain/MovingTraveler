// Additional Google Play Services linking permission: see docs/COPYING-EXCEPTION.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Displays the notices generated from this build's dependencies by Google's OSS plugin. */
final class LicenseViewer {
    static void show(Activity activity) {
        List<String> names = new ArrayList<>();
        List<long[]> positions = new ArrayList<>();
        names.add("MapLibre native third-party notices"); positions.add(null);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                activity.getResources().openRawResource(R.raw.third_party_license_metadata), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf(' ');
                if (split < 0) continue;
                String[] position = line.substring(0, split).split(":");
                long offset = Long.parseLong(position[0]); int length = Integer.parseInt(position[1]);
                if (offset < 0 || length < 0 || length > 2_000_000) continue;
                names.add(line.substring(split + 1)); positions.add(new long[]{offset, length});
            }
            new AlertDialog.Builder(activity).setTitle("Library licenses")
                    .setItems(names.toArray(new String[0]), (dialog, index) -> showText(activity, names.get(index), positions.get(index)))
                    .setNegativeButton("Close", null).show();
        } catch (Exception error) {
            new AlertDialog.Builder(activity).setMessage("Library notices could not be opened. See the corresponding source release.")
                    .setPositiveButton("Close", null).show();
        }
    }

    private static void showText(Activity activity, String title, long[] range) {
        if (range == null) {
            try (InputStream in = activity.getAssets().open("MAPLIBRE-NATIVE-NOTICES.txt")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                display(activity, title, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception error) { unavailable(activity); }
            return;
        }
        try (InputStream in = activity.getResources().openRawResource(R.raw.third_party_licenses)) {
            long left = range[0];
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped == 0) { if (in.read() == -1) throw new java.io.EOFException(); skipped = 1; }
                left -= skipped;
            }
            byte[] bytes = new byte[(int) range[1]]; int total = 0;
            while (total < bytes.length) {
                int n = in.read(bytes, total, bytes.length - total);
                if (n == -1) throw new java.io.EOFException(); total += n;
            }
            display(activity, title, new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception error) { unavailable(activity); }
    }
    private static void display(Activity activity, String title, String content) {
        TextView text = new TextView(activity); text.setTextSize(14); text.setTextIsSelectable(true);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding); text.setText(content);
        android.text.util.Linkify.addLinks(text, android.text.util.Linkify.WEB_URLS);
        ScrollView scroll = new ScrollView(activity); scroll.addView(text);
        new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }
    private static void unavailable(Activity activity) {
        new AlertDialog.Builder(activity).setMessage("This license could not be read.").setPositiveButton("Close", null).show();
    }
}
