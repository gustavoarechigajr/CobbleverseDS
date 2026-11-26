package dev.cvds.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;

/**
 * The app icon: an invisible trampoline that opens the panel (or brings the running one forward) on the
 * Thor's bottom screen, wherever the icon was tapped, then closes. The panel itself is single-instance, so
 * it can't relaunch itself onto another display (the start would just come back to the same instance).
 */
public class LaunchActivity extends Activity {
	@Override
	protected void onCreate(Bundle saved) {
		super.onCreate(saved);
		Intent i = new Intent(this, MainActivity.class)
			.putExtra(MainActivity.EXTRA_PLACED, true)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		Bundle opts = null;
		Display[] bottom = getSystemService(DisplayManager.class).getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
		if (bottom.length > 0) opts = ActivityOptions.makeBasic().setLaunchDisplayId(bottom[0].getDisplayId()).toBundle();
		try {
			startActivity(i, opts);
		} catch (RuntimeException e) {
			startActivity(i); // launching on that display isn't allowed: open wherever we are
		}
		finish();
	}
}
