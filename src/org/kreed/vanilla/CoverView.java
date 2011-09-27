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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

/**
 * Displays a flingable/draggable View of cover art/song info images
 * generated by CoverBitmap.
 */
public final class CoverView extends View implements Handler.Callback {
	private static int SNAP_VELOCITY = -1;

	/**
	 * The Handler with which to do background work. Will be null until
	 * setupHandler is called.
	 */
	private Handler mHandler;
	/**
	 * How to render cover art and metadata. One of
	 * CoverBitmap.STYLE_*
	 */
	private int mCoverStyle;

	public interface Callback {
		public void nextSong();
		public void previousSong();
		public void upSwipe();
		public void downSwipe();
	}

	private Callback mCallback;

	/**
	 * The current set of songs: 0 = previous, 1 = current, and 2 = next.
	 */
	private Song[] mSongs = new Song[3];
	/**
	 * Cache of cover bitmaps generated for songs. The song ids are the keys.
	 */
	private Cache<Bitmap> mBitmapCache = new Cache<Bitmap>(8);

	private Scroller mScroller;
	private VelocityTracker mVelocityTracker;
	private float mLastMotionX;
	private float mLastMotionY;
	private float mStartX;
	private float mStartY;
	private int mTentativeCover = -1;
	/**
	 * Ignore the next pointer up event, for long presses.
	 */
	private boolean mIgnoreNextUp;

	/**
	 * Constructor intended to be called by inflating from XML.
	 */
	public CoverView(Context context, AttributeSet attributes)
	{
		super(context, attributes);

		mScroller = new Scroller(context);

		if (SNAP_VELOCITY == -1)
			SNAP_VELOCITY = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
	}

	/**
	 * Setup the Handler and callback. This must be called before
	 * the CoverView is used.
	 *
	 * @param looper A looper created on a worker thread.
	 * @param callback The callback for nextSong/previousSong
	 * @param style One of CoverBitmap.STYLE_*
	 */
	public void setup(Looper looper, Callback callback, int style)
	{
		mHandler = new Handler(looper, this);
		mCallback = callback;
		mCoverStyle = style;
	}

	/**
	 * Reset the scroll position to its default state.
	 */
	private void resetScroll()
	{
		if (!mScroller.isFinished())
			mScroller.abortAnimation();
		scrollTo(getWidth(), 0);
	}

	/**
	 * Recreate all the necessary cached bitmaps.
	 */
	private void regenerateBitmaps()
	{
		Object[] bitmaps = mBitmapCache.clear();
		for (int i = bitmaps.length; --i != -1; ) {
			if (bitmaps[i] != null)
				((Bitmap)bitmaps[i]).recycle();
			bitmaps[i] = null;
		}
		for (int i = 3; --i != -1; )
			setSong(i, mSongs[i]);
	}

	/**
	 * Recreate the cover art views and reset the scroll position whenever the
	 * size of this view changes.
	 */
	@Override
	protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight)
	{
		if (width == 0 || height == 0)
			return;

		regenerateBitmaps();
		resetScroll();
	}

	/**
	 * Paint the cover art views to the canvas.
	 */
	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);

		int width = getWidth();
		int height = getHeight();
		int scrollX = getScrollX();

		canvas.drawColor(Color.BLACK);

		for (int x = 0, i = 0; i != 3; ++i, x += width) {
			if (mSongs[i] != null && scrollX + width > x && scrollX < x + width) {
				long id = mSongs[i].id;
				if (id == -1)
					continue;
				Bitmap bitmap = mBitmapCache.get(id);
				if (bitmap != null) {
					int xOffset = (width - bitmap.getWidth()) / 2;
					int yOffset = (height - bitmap.getHeight()) / 2;
					canvas.drawBitmap(bitmap, x + xOffset, yOffset, null);
				}
			}
		}
	}

	/**
	 * Scrolls the view when dragged. Animates a fling to one of the three covers
	 * when finished. The cover flung to will be either the nearest cover, or if
	 * the fling is fast enough, the cover in the direction of the fling.
	 *
	 * Also performs a click on the view when it is tapped without dragging.
	 */
	@Override
	public boolean onTouchEvent(MotionEvent ev)
	{
		if (mVelocityTracker == null)
			mVelocityTracker = VelocityTracker.obtain();
		mVelocityTracker.addMovement(ev);

		float x = ev.getX();
		float y = ev.getY();
		int scrollX = getScrollX();
		int width = getWidth();

		switch (ev.getAction()) {
		case MotionEvent.ACTION_DOWN:
			if (!mScroller.isFinished())
				mScroller.abortAnimation();

			mStartX = x;
			mStartY = y;
			mLastMotionX = x;
			mLastMotionY = y;

			mHandler.sendEmptyMessageDelayed(MSG_LONG_CLICK, ViewConfiguration.getLongPressTimeout());
			break;
		case MotionEvent.ACTION_MOVE: {
			float deltaX = mLastMotionX - x;
			float deltaY = mLastMotionY - y;

			if (Math.abs(deltaX) > Math.abs(deltaY)) {
				if (deltaX < 0) {
					int availableToScroll = scrollX - (mSongs[0] == null ? width : 0);
					if (availableToScroll > 0)
						scrollBy(Math.max(-availableToScroll, (int)deltaX), 0);
				} else if (deltaX > 0) {
					int availableToScroll = width * 2 - scrollX;
					if (availableToScroll > 0)
						scrollBy(Math.min(availableToScroll, (int)deltaX), 0);
				}
			}

			mLastMotionX = x;
			mLastMotionY = y;
			break;
		}
		case MotionEvent.ACTION_UP: {
			mHandler.removeMessages(MSG_LONG_CLICK);

			VelocityTracker velocityTracker = mVelocityTracker;
			velocityTracker.computeCurrentVelocity(250);
			int velocity = (int) velocityTracker.getXVelocity();

			int min = mSongs[0] == null ? 1 : 0;
			int max = 2;
			int nearestCover = (scrollX + width / 2) / width;
			int whichCover = Math.max(min, Math.min(nearestCover, max));

			if (velocity > SNAP_VELOCITY)
				whichCover = min;
			else if (velocity < -SNAP_VELOCITY)
				whichCover = max;

			float diffX = Math.abs(mStartX - x);
			float diffY = Math.abs(mStartY - y);
			if (diffX + diffY < 10) {
				// A long press was performed and thus the normal action should
				// not be executed.
				if (mIgnoreNextUp)
					mIgnoreNextUp = false;
				else
					performClick();
				whichCover = 1;
			} else if (diffY > diffX) {
				if (mStartY - y > 0)
					mCallback.upSwipe();
				else
					mCallback.downSwipe();
				whichCover = 1;
			}

			int newX = whichCover * width;
			int delta = newX - scrollX;
			mScroller.startScroll(scrollX, 0, delta, 0, Math.abs(delta) * 2);
			if (whichCover != 1)
				mTentativeCover = whichCover;

			postInvalidate();

			if (mVelocityTracker != null) {
				mVelocityTracker.recycle();
				mVelocityTracker = null;
			}

			break;
		}
		}
		return true;
	}

	/**
	 * Update position for fling scroll animation and, when it is finished,
	 * notify PlaybackService that the user has requested a track change and
	 * update the cover art views.
	 */
	@Override
	public void computeScroll()
	{
		if (mScroller.computeScrollOffset()) {
			scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
			postInvalidate();
		} else if (mTentativeCover != -1) {
			int delta = mTentativeCover - 1;
			mTentativeCover = -1;
			if (delta == 1)
				mCallback.nextSong();
			else
				mCallback.previousSong();
			resetScroll();
		}
	}

	/**
	 * Generates a bitmap for the given Song and stores it in the cache.
	 *
	 * Prunes old bitmaps if the timeline becomes full.
	 */
	private void generateBitmap(Song song)
	{
		if (song == null || song.id == -1)
			return;

		Bitmap bitmap = mBitmapCache.get(song.id);
		if (bitmap == null) {
			mBitmapCache.put(song.id, CoverBitmap.createBitmap(getContext(), mCoverStyle, song, getWidth(), getHeight(), mBitmapCache.discardOldest()));
			postInvalidate();
		} else {
			mBitmapCache.touch(song.id);
		}
	}

	/**
	 * Set the Song at position <code>i</code> to <code>song</code>, generating
	 * the bitmap for it in the background if needed.
	 */
	public void setSong(int i, Song song)
	{
		mSongs[i] = song;
		if (song != null)
			mHandler.sendMessage(mHandler.obtainMessage(MSG_GENERATE_BITMAP, song));
	}

	/**
	 * Query all songs. Must be called on the UI thread.
	 *
	 * @param service Service to query from.
	 */
	public void querySongs(PlaybackService service)
	{
		for (int i = 3; --i != -1; )
			setSong(i, service.getSong(i - 1));
		resetScroll();
		invalidate();
	}

	/**
	 * Call {@link CoverView#generateBitmap(Song)} for the given song.
	 *
	 * obj must be the Song to generate a bitmap for.
	 */
	private static final int MSG_GENERATE_BITMAP = 0;
	/**
	 * Perform a long click.
	 *
	 * @see View#performLongClick()
	 */
	private static final int MSG_LONG_CLICK = 2;

	public boolean handleMessage(Message message)
	{
		switch (message.what) {
		case MSG_GENERATE_BITMAP:
			generateBitmap((Song)message.obj);
			break;
		case MSG_LONG_CLICK:
			if (Math.abs(mStartX - mLastMotionX) + Math.abs(mStartY - mLastMotionY) < 10) {
				mIgnoreNextUp = true;
				performLongClick();
			}
			break;
		default:
			return false;
		}

		return true;
	}
}
