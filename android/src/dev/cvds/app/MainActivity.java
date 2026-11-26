package dev.cvds.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Full-screen WebView for the Cobbleverse DS touch UI, which the mod serves on localhost.
 * The window is not focusable, so tapping it never takes controller input away from the
 * game; it becomes focusable only while a text field is being edited.
 */
public class MainActivity extends Activity {
	static final String URL_UI = "http://127.0.0.1:25599/";
	static final String EXTRA_PLACED = "dev.cvds.app.PLACED";

	private final Handler handler = new Handler(Looper.getMainLooper());
	private WebView web;
	private boolean waiting;
	private boolean probing;
	private boolean paused; // hidden behind another app: no polling, no page timers (battery)
	private Vibrator vibrator;

	@Override
	protected void onCreate(Bundle saved) {
		super.onCreate(saved);
		// No FLAG_KEEP_SCREEN_ON: the game keeps the device awake while it runs (OLED care).
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
		web = new WebView(this);
		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setCacheMode(WebSettings.LOAD_NO_CACHE);
		s.setTextZoom(100);
		// The UI is laid out for 1240 CSS px (the bottom screen's real width); scale it to fit.
		s.setUseWideViewPort(true);
		s.setLoadWithOverviewMode(true);
		s.setSupportZoom(false);
		s.setBuiltInZoomControls(false);
		s.setDisplayZoomControls(false);
		web.setHapticFeedbackEnabled(false);
		web.setOnLongClickListener(v -> true); // no text-selection popups on long-press
		web.setLongClickable(false);
		web.addJavascriptInterface(new Bridge(), "CVDSApp");
		web.setWebViewClient(new WebViewClient() {
			@Override
			public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
				if (req.isForMainFrame()) showWaiting();
			}
		});
		setContentView(web);
		vibrator = getSystemService(Vibrator.class);
		showWaiting();
		probe();
	}

	private void showWaiting() {
		setFocusable(false);
		if (!probing) probe(); // e.g. the game quit between a good probe and loading the page
		if (waiting) return;
		waiting = true;
		web.loadDataWithBaseURL(null,
			"<html><body style='background:#15171c;color:#9aa3b5;font:28px system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>"
				+ "<div id=m>Waiting for Minecraft…<br><small>Start Cobbleverse with the Cobbleverse DS mod installed.</small></div>"
				// Go fully black after 20 s so a static message never sits on the OLED.
				+ "<script>setTimeout(function(){document.getElementById('m').style.display='none';document.body.style.background='#000'},20000)</script></body></html>",
			"text/html", "utf-8", null);
	}

	/** Polls until the mod answers, then loads the UI. The UI itself handles later disconnects. */
	private void probe() {
		probing = true;
		new Thread(() -> {
			boolean up = false;
			try {
				HttpURLConnection c = (HttpURLConnection) new URL(URL_UI + "api/state?rev=-2&wait=0").openConnection();
				c.setConnectTimeout(1500);
				c.setReadTimeout(3000);
				up = c.getResponseCode() == 200;
				c.disconnect();
			} catch (Exception ignored) {
			}
			boolean ok = up;
			handler.post(() -> {
				if (isFinishing() || isDestroyed() || paused) { probing = false; return; } // onResume starts it again
				if (ok) {
					probing = false;
					waiting = false;
					setFocusable(false);
					web.loadUrl(URL_UI);
				} else {
					handler.postDelayed(this::probe, 2000);
				}
			});
		}, "cvds-probe").start();
	}

	/**
	 * Launched again while already running (launcher icon, adb): the single instance may sit behind the
	 * bottom screen's home, so bring its task to the front of whatever display it is on.
	 */
	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		try { getSystemService(ActivityManager.class).moveTaskToFront(getTaskId(), 0); } catch (RuntimeException ignored) {}
	}

	@Override
	protected void onResume() {
		super.onResume();
		paused = false;
		if (web != null) {
			web.resumeTimers();
			if (waiting && !probing) probe();
			web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
			web.onResume();
		}
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacksAndMessages(null);
		if (web != null) web.destroy();
		super.onDestroy();
	}

	/** Focusable only while typing; otherwise the controller must stay with the game. */
	void setFocusable(boolean focusable) {
		if (focusable) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
		else getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
	}

	@Override
	protected void onPause() {
		setFocusable(false);
		paused = true;
		if (web != null) { web.onPause(); web.pauseTimers(); } // stops the page's polling while hidden
		super.onPause();
	}

	final class Bridge {
		@JavascriptInterface
		public void setFocusable(boolean focusable) {
			handler.post(() -> MainActivity.this.setFocusable(focusable));
		}

		/** A short tick for taps ("tick") or a firmer one for long-presses ("heavy"). */
		@JavascriptInterface
		public void haptic(String kind) {
			if (vibrator == null || !vibrator.hasVibrator()) return;
			try {
				int effect = "heavy".equals(kind) ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_TICK;
				vibrator.vibrate(VibrationEffect.createPredefined(effect));
			} catch (RuntimeException ignored) {}
		}
	}
}
