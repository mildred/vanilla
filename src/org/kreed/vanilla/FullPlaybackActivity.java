/*
 * Copyright (C) 2010, 2011 Christopher Eby <kreed@kreed.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.kreed.vanilla;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class FullPlaybackActivity extends PlaybackActivity implements SeekBar.OnSeekBarChangeListener, SensorEventListener {
	public static final int DISPLAY_INFO_OVERLAP = 0;
	public static final int DISPLAY_INFO_BELOW = 1;
	public static final int DISPLAY_INFO_WIDGETS = 2;
	public static final int DISPLAY_INFO_WIDGETS_ZOOMED = 3;

	/**
	 * Changes in acceleration greater than this number are treated as shakes.
	 */
	private static final float SHAKE_THRESHOLD = 0.25f;
	/**
	 * Minimum time between shake actions.
	 */
	private static final int MIN_SHAKE_PERIOD = 500;

	/**
	 * A Handler running on the UI thread, in contrast with mHandler which runs
	 * on a worker thread.
	 */
	private final Handler mUiHandler = new Handler(this);

	private TextView mOverlayText;
	private View mControlsTop;
	private View mControlsBottom;

	private SeekBar mSeekBar;
	private TextView mSeekText;

	private TextView mTitle;
	private TextView mAlbum;
	private TextView mArtist;

	/**
	 * Current song duration in milliseconds.
	 */
	private long mDuration;
	/**
	 * Current song duration in human-readable form.
	 */
	private String mDurationString;
	private boolean mSeekBarTracking;
	private boolean mPaused;

	/**
	 * Cached StringBuilder for formatting track position.
	 */
	private final StringBuilder mTimeBuilder = new StringBuilder();

	/**
	 * What to do when an accelerometer shake is detected.
	 */
	private int mShakeAction;

	private SensorManager mSensorManager;
	private float mLastX;
	private float mLastY;
	private float mLastZ;
	private long mLastUpdate;
	private long mLastShake;

	@Override
	public void onCreate(Bundle icicle)
	{
		super.onCreate(icicle);

		SharedPreferences settings = PlaybackService.getSettings(this);
		int displayMode = Integer.parseInt(settings.getString("display_mode", "0"));
		boolean hiddenControls = settings.getBoolean("hidden_controls", false);

		int layout = R.layout.full_playback;
		int coverStyle;

		switch (displayMode) {
		default:
			Log.w("VanillaMusic", "Invalid display mode given. Defaulting to overlap.");
			// fall through
		case DISPLAY_INFO_OVERLAP:
			coverStyle = CoverBitmap.STYLE_OVERLAPPING_BOX;
			break;
		case DISPLAY_INFO_BELOW:
			coverStyle = CoverBitmap.STYLE_INFO_BELOW;
			break;
		case DISPLAY_INFO_WIDGETS:
			coverStyle = CoverBitmap.STYLE_NO_INFO;
			layout = R.layout.full_playback_alt;
			break;
		case DISPLAY_INFO_WIDGETS_ZOOMED:
			coverStyle = CoverBitmap.STYLE_NO_INFO_ZOOMED;
			layout = R.layout.full_playback_alt;
			break;
		}

		setContentView(layout);

		CoverView coverView = (CoverView)findViewById(R.id.cover_view);
		coverView.setup(mLooper, this, coverStyle);
		coverView.setOnClickListener(this);
		coverView.setOnLongClickListener(this);
		mCoverView = coverView;

		mControlsTop = findViewById(R.id.controls_top);
		mControlsBottom = findViewById(R.id.controls_bottom);
		if (hiddenControls) {
			mControlsTop.setVisibility(View.GONE);
			mControlsBottom.setVisibility(View.GONE);
		}

		View previousButton = findViewById(R.id.previous);
		previousButton.setOnClickListener(this);
		mPlayPauseButton = (ImageButton)findViewById(R.id.play_pause);
		mPlayPauseButton.setOnClickListener(this);
		View nextButton = findViewById(R.id.next);
		nextButton.setOnClickListener(this);

		mTitle = (TextView)findViewById(R.id.title);
		mAlbum = (TextView)findViewById(R.id.album);
		mArtist = (TextView)findViewById(R.id.artist);

		mSeekText = (TextView)findViewById(R.id.seek_text);
		mSeekBar = (SeekBar)findViewById(R.id.seek_bar);
		mSeekBar.setMax(1000);
		mSeekBar.setOnSeekBarChangeListener(this);
		setDuration(0);

		mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
	}

	@Override
	public void onStart()
	{
		super.onStart();

		SharedPreferences settings = PlaybackService.getSettings(this);
		mShakeAction = Integer.parseInt(settings.getString("shake_action", "0"));
		if (mShakeAction != PlaybackActivity.ACTION_NOTHING)
			mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI);
	}

	@Override
	public void onResume()
	{
		super.onResume();
		mPaused = false;
		updateProgress();
	}

	@Override
	public void onStop()
	{
		mSensorManager.unregisterListener(this);
		super.onStop();
	}

	@Override
	public void onPause()
	{
		super.onPause();
		mPaused = true;
	}

	/**
	 * Hide the message overlay, if it exists.
	 */
	private void hideMessageOverlay()
	{
		if (mOverlayText != null)
			mOverlayText.setVisibility(View.GONE);
	}

	/**
	 * Show some text in a message overlay.
	 *
	 * @param text Resource id of the text to show.
	 */
	private void showOverlayMessage(int text)
	{
		if (mOverlayText == null) {
			TextView view = new TextView(this);
			view.setBackgroundColor(Color.BLACK);
			view.setTextColor(Color.WHITE);
			view.setGravity(Gravity.CENTER);
			view.setPadding(25, 25, 25, 25);
			// Make the view clickable so it eats touch events
			view.setClickable(true);
			addContentView(view,
				new ViewGroup.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
				                           LinearLayout.LayoutParams.FILL_PARENT));
			mOverlayText = view;
		} else {
			mOverlayText.setVisibility(View.VISIBLE);
		}

		mOverlayText.setText(text);
	}

	@Override
	protected void onStateChange(int state, int toggled)
	{
		super.onStateChange(state, toggled);

		if ((toggled & (PlaybackService.FLAG_NO_MEDIA|PlaybackService.FLAG_EMPTY_QUEUE)) != 0) {
			if ((state & PlaybackService.FLAG_NO_MEDIA) != 0) {
				showOverlayMessage(R.string.no_songs);
			} else if ((state & PlaybackService.FLAG_EMPTY_QUEUE) != 0) {
				showOverlayMessage(R.string.empty_queue);
			} else {
				hideMessageOverlay();
			}
		}

		if ((state & PlaybackService.FLAG_PLAYING) != 0)
			updateProgress();
	}

	@Override
	protected void onSongChange(final Song song)
	{
		super.onSongChange(song);

		setDuration(song == null ? 0 : song.duration);

		if (mTitle != null) {
			if (song == null) {
				mTitle.setText(null);
				mAlbum.setText(null);
				mArtist.setText(null);
			} else {
				mTitle.setText(song.title);
				mAlbum.setText(song.album);
				mArtist.setText(song.artist);
			}
		}

		updateProgress();
	}

	/**
	 * Update the current song duration fields.
	 *
	 * @param duration The new duration, in milliseconds.
	 */
	private void setDuration(long duration)
	{
		mDuration = duration;
		mDurationString = DateUtils.formatElapsedTime(mTimeBuilder, duration / 1000);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		menu.add(0, MENU_LIBRARY, 0, R.string.library).setIcon(R.drawable.ic_menu_music_library);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId()) {
		case MENU_LIBRARY:
			openLibrary();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public boolean onSearchRequested()
	{
		openLibrary();
		return false;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		switch (keyCode) {
		case KeyEvent.KEYCODE_DPAD_RIGHT:
			nextSong();
			findViewById(R.id.next).requestFocus();
			return true;
		case KeyEvent.KEYCODE_DPAD_LEFT:
			previousSong();
			findViewById(R.id.previous).requestFocus();
			return true;
		}

		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event)
	{
		switch (keyCode) {
		case KeyEvent.KEYCODE_DPAD_CENTER:
		case KeyEvent.KEYCODE_ENTER:
			toggleControls();
			return true;
		}

		return super.onKeyUp(keyCode, event);
	}

	/**
	 * Update seek bar progress and schedule another update in one second
	 */
	private void updateProgress()
	{
		int position = PlaybackService.hasInstance() ? PlaybackService.get(this).getPosition() : 0;

		if (!mSeekBarTracking) {
			long duration = mDuration;
			mSeekBar.setProgress(duration == 0 ? 0 : (int)(1000 * position / duration));
		}

		StringBuilder builder = mTimeBuilder;
		String current = DateUtils.formatElapsedTime(builder, position / 1000);
		builder.setLength(0);
		builder.append(current);
		builder.append(" / ");
		builder.append(mDurationString);
		mSeekText.setText(builder.toString());

		if (!mPaused && mControlsTop.getVisibility() == View.VISIBLE && (mState & PlaybackService.FLAG_PLAYING) != 0) {
			// Try to update right when the duration increases by one second
			long next = 1000 - position % 1000;
			mUiHandler.removeMessages(MSG_UPDATE_PROGRESS);
			mUiHandler.sendEmptyMessageDelayed(MSG_UPDATE_PROGRESS, next);
		}
	}

	/**
	 * Toggles the visibility of the playback controls.
	 */
	private void toggleControls()
	{
		if (mControlsTop.getVisibility() == View.VISIBLE) {
			mControlsTop.setVisibility(View.GONE);
			mControlsBottom.setVisibility(View.GONE);
		} else {
			mControlsTop.setVisibility(View.VISIBLE);
			mControlsBottom.setVisibility(View.VISIBLE);

			mPlayPauseButton.requestFocus();

			updateProgress();
		}

		int hidden = mControlsTop.getVisibility() == View.VISIBLE ? 0 : 1;
		mHandler.sendMessage(mHandler.obtainMessage(MSG_SAVE_CONTROLS, hidden, 0));
	}

	/**
	 * Update the seekbar progress with the current song progress. This must be
	 * called on the UI Handler.
	 */
	private static final int MSG_UPDATE_PROGRESS = 10;
	/**
	 * Save the hidden_controls preference to storage.
	 */
	private static final int MSG_SAVE_CONTROLS = 14;

	@Override
	public boolean handleMessage(Message message)
	{
		switch (message.what) {
		case MSG_SAVE_CONTROLS: {
			SharedPreferences settings = PlaybackService.getSettings(this);
			SharedPreferences.Editor editor = settings.edit();
			editor.putBoolean("hidden_controls", message.arg1 == 1);
			editor.commit();
			break;
		}
		case MSG_UPDATE_PROGRESS:
			updateProgress();
			break;
		default:
			return super.handleMessage(message);
		}

		return true;
	}

	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
	{
		if (fromUser)
			PlaybackService.get(this).seekToProgress(progress);
	}

	public void onStartTrackingTouch(SeekBar seekBar)
	{
		mSeekBarTracking = true;
	}

	public void onStopTrackingTouch(SeekBar seekBar)
	{
		mSeekBarTracking = false;
	}

	@Override
	public void performAction(int action)
	{
		if (action == PlaybackActivity.ACTION_TOGGLE_CONTROLS)
			toggleControls();
		else
			super.performAction(action);
	}

	@Override
	public void onSensorChanged(SensorEvent se)
	{
		long now = SystemClock.elapsedRealtime();
		// Acceleration for each axis
		float x = se.values[0];
		float y = se.values[1];
		float z = se.values[2];

		// Treat a large change in acceleration as a shake
		float jerk = Math.abs(x + y + z - mLastX - mLastY - mLastZ) / (now - mLastUpdate);
		if (jerk > SHAKE_THRESHOLD && now - mLastShake > MIN_SHAKE_PERIOD) {
			mLastShake = now;
			performAction(mShakeAction);
		}

		mLastUpdate = now;
		mLastX = x;
		mLastY = y;
		mLastZ = z;
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy)
	{
	}
}
