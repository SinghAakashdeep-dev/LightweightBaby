package com.fuelstride.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.widget.Toast;

/**
 * Home-screen icon for Lightweight Baby. Opens the app on claude.ai in your browser,
 * where you're already signed in, so Claude features run on your Claude plan.
 */
public class LauncherActivity extends Activity {

    private static final String APP_URL = "https://claude.ai/artifact/8SphF1vvLCe88tzNk32x3o";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(APP_URL))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                // Lets the browser reuse the same tab each time instead of opening a new one
                .putExtra(Browser.EXTRA_APPLICATION_ID, getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(open);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Install a web browser to open Lightweight Baby.", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
