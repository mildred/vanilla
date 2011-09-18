/*
 * Copyright (C) 2010 Christopher Eby <kreed@kreed.org>
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

import android.provider.MediaStore;

/**
 * Subclasses MediaAdapter to represent songs. Providers some song-specific
 * logic over plain MediaAdapter: exclusion of non-music media files and
 * better sorting inside albums.
 */
public class SongMediaAdapter extends MediaAdapter {
	/**
	 * Construct a MediaAdapter backed by MediaStore.Audio.Media.
	 *
	 * @param activity The activity that owns this adapter.
	 * @param expandable Whether an expander arrow should by shown to the right
	 * of views
	 * @param limiter An initial limiter to use.
	 */
	public SongMediaAdapter(SongSelector activity, boolean expandable, MediaAdapter.Limiter limiter)
	{
		super(activity, MediaUtils.TYPE_SONG, expandable, limiter);
	}

	@Override
	protected String getDefaultSelection()
	{
		return MediaStore.Audio.Media.IS_MUSIC + "!=0";
	}

	@Override
	protected String getSortOrder()
	{
		Limiter limiter = getLimiter();
		if (limiter != null && limiter.type == MediaUtils.TYPE_ALBUM)
			return MediaStore.Audio.Media.TRACK;
		return super.getSortOrder();
	}
}
