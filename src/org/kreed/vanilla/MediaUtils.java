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

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MediaUtils {
	/**
	 * Type indicating an id represents an artist.
	 */
	public static final int TYPE_ARTIST = 1;
	/**
	 * Type indicating an id represents an album.
	 */
	public static final int TYPE_ALBUM = 2;
	/**
	 * Type indicating an id represents a song.
	 */
	public static final int TYPE_SONG = 3;
	/**
	 * Type indicating an id represents a playlist.
	 */
	public static final int TYPE_PLAYLIST = 4;
	/**
	 * Type indicating ids represent genres.
	 */
	public static final int TYPE_GENRE = 5;

	/**
	 * Cached random instance.
	 */
	private static Random sRandom;

	/**
	 * Shuffled list of all ids in the library.
	 */
	private static long[] sAllSongs;
	private static int sAllSongsIdx;

	/**
	 * Query this many songs at a time from sAllSongs.
	 */
	private static final int RANDOM_POPULATE_SIZE = 20;
	private static Song[] sRandomCache = new Song[RANDOM_POPULATE_SIZE];
	private static int sRandomCacheIdx;
	private static int sRandomCacheEnd;

	/**
	 * Total number of songs in the music library, or -1 for uninitialized.
	 */
	private static int sSongCount = -1;

	/**
	 * Returns a cached random instanced, creating it if necessary.
	 */
	public static Random getRandom()
	{
		if (sRandom == null)
			sRandom = new Random();
		return sRandom;
	}

	/**
	 * Builds a query that will return all the songs represented by the given
	 * parameters.
	 *
	 * @param type MediaUtils.TYPE_ARTIST, TYPE_ALBUM, or TYPE_SONG.
	 * @param id The MediaStore id of the song, artist, or album.
	 * @param projection The columns to query.
	 * @param select An extra selection to pass to the query, or null.
	 * @return The initialized query.
	 */
	public static QueryTask buildMediaQuery(int type, long id, String[] projection, String select)
	{
		Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
		StringBuilder selection = new StringBuilder();

		switch (type) {
		case TYPE_SONG:
			selection.append(MediaStore.Audio.Media._ID);
			break;
		case TYPE_ARTIST:
			selection.append(MediaStore.Audio.Media.ARTIST_ID);
			break;
		case TYPE_ALBUM:
			selection.append(MediaStore.Audio.Media.ALBUM_ID);
			break;
		default:
			throw new IllegalArgumentException("Invalid type specified: " + type);
		}

		selection.append('=');
		selection.append(id);
		selection.append(" AND is_music!=0");

		if (select != null) {
			selection.append(" AND ");
			selection.append(select);
		}

		String sort = MediaStore.Audio.Media.ARTIST_KEY + ',' + MediaStore.Audio.Media.ALBUM_KEY + ',' + MediaStore.Audio.Media.TRACK;
		return new QueryTask(media, projection, selection.toString(), null, sort);
	}

	/**
	 * Builds a query that will return all the songs in the playlist with the
	 * given id.
	 *
	 * @param id The id of the playlist in MediaStore.Audio.Playlists.
	 * @param projection The columns to query.
	 * @param selection The selection to pass to the query, or null.
	 * @return The initialized query.
	 */
	public static QueryTask buildPlaylistQuery(long id, String[] projection, String selection)
	{
		Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", id);
		String sort = MediaStore.Audio.Playlists.Members.PLAY_ORDER;
		return new QueryTask(uri, projection, selection, null, sort);
	}

	/**
	 * Builds a query that will return all the songs in the genre with the
	 * given id.
	 *
	 * @param id The id of the genre in MediaStore.Audio.Genres.
	 * @param projection The columns to query.
	 * @param selection The selection to pass to the query, or null.
	 * @param selectionArgs The arguments to substitute into the selection.
	 */
	public static QueryTask buildGenreQuery(long id, String[] projection, String selection, String[] selectionArgs)
	{
		Uri uri = MediaStore.Audio.Genres.Members.getContentUri("external", id);
		String sort = MediaStore.Audio.Genres.Members.TITLE_KEY;
		return new QueryTask(uri, projection, selection, selectionArgs, sort);
	}

	/**
	 * Builds a query with the given information.
	 *
	 * @param type Type the id represents. Must be one of the Song.TYPE_*
	 * constants.
	 * @param id The id of the element in the MediaStore content provider for
	 * the given type.
	 * @param selection An extra selection to be passed to the query. May be
	 * null. Must not be used with type == TYPE_SONG or type == TYPE_PLAYLIST
	 */
	public static QueryTask buildQuery(int type, long id, String[] projection, String selection)
	{
		switch (type) {
		case TYPE_ARTIST:
		case TYPE_ALBUM:
		case TYPE_SONG:
			return buildMediaQuery(type, id, projection, selection);
		case TYPE_PLAYLIST:
			return buildPlaylistQuery(id, projection, selection);
		case TYPE_GENRE:
			return buildGenreQuery(id, projection, selection, null);
		default:
			throw new IllegalArgumentException("Specified type not valid: " + type);
		}
	}

	/**
	 * Query the MediaStore to determine the id of the genre the song belongs
	 * to.
	 *
	 * @param resolver A ContentResolver to use.
	 * @param id The id of the song to query the genre for.
	 */
	public static long queryGenreForSong(ContentResolver resolver, long id)
	{
		// This is terribly inefficient, but it seems to be the only way to do
		// this. Honeycomb introduced an API to query the genre of the song.
		// We should look into it when ICS is released.

		// query ids of all the genres
		Uri uri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI;
		String[] projection = { "_id" };
		Cursor cursor = resolver.query(uri, projection, null, null, null);

		if (cursor != null) {
			String selection = "_id=" + id;
			while (cursor.moveToNext()) {
				// check if the given song belongs to this genre
				long genreId = cursor.getLong(0);
				Uri genreUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId);
				Cursor c = resolver.query(genreUri, projection, selection, null, null);
				if (c != null) {
					if (c.getCount() == 1)
						return genreId;
					c.close();
				}
			}
			cursor.close();
		}

		return -1;
	}

	/**
	 * Shuffle an array using Fisher-Yates algorithm.
	 *
	 * @param list The array. It will be shuffled in place.
	 */
	public static void shuffle(long[] list)
	{
		Random random = getRandom();
		for (int i = list.length; --i != -1; ) {
			int j = random.nextInt(i + 1);
			long tmp = list[j];
			list[j] = list[i];
			list[i] = tmp;
		}
	}

	/**
	 * Shuffle an array using Fisher-Yates algorithm.
	 *
	 * @param list The array. It will be shuffled in place.
	 * @param end Only elements before this index will be shuffled.
	 */
	public static void shuffle(Song[] list, int end)
	{
		assert(end <= list.length && end >= 0);
		Random random = getRandom();
		for (int i = end; --i != -1; ) {
			int j = random.nextInt(i + 1);
			Song tmp = list[j];
			list[j] = list[i];
			list[i] = tmp;
		}
	}

	/**
	 * Shuffle a Song list using Fisher-Yates algorithm.
	 *
	 * @param albumShuffle If true, preserve the order of tracks inside albums.
	 */
	public static void shuffle(List<Song> list, boolean albumShuffle)
	{
		int size = list.size();
		if (size < 2)
			return;

		Random random = getRandom();
		if (albumShuffle) {
			Song[] songs = list.toArray(new Song[size]);
			Song[] temp = new Song[size];

			// Make sure the albums are in order
			Arrays.sort(songs);

			// This is Fisher-Yates algorithm, but it swaps albums instead of
			// single elements.
			for (int i = size; --i != -1; ) {
				Song songI = songs[i];
				if (i > 0 && songs[i - 1].albumId == songI.albumId)
					// This index is not the start of an album. Skip it.
					continue;

				int j = random.nextInt(i + 1);
				while (j > 0 && songs[j - 1].albumId == songs[j].albumId)
					// This index is not the start of an album. Find the start.
					j -= 1;

				int lowerStart = Math.min(i, j);
				int upperStart = Math.max(i, j);

				if (lowerStart == upperStart)
					// Swap with ourself. That was easy!
					continue;

				long lowerAlbum = songs[lowerStart].albumId;
				int lowerEnd = lowerStart;
				while (lowerEnd + 1 < size && songs[lowerEnd + 1].albumId == lowerAlbum)
					lowerEnd += 1;

				long upperAlbum = songs[upperStart].albumId;
				int upperEnd = upperStart;
				while (upperEnd + 1 < size && songs[upperEnd + 1].albumId == upperAlbum)
					upperEnd += 1;

				int lowerSize = lowerEnd - lowerStart + 1;
				int upperSize = upperEnd - upperStart + 1;

				if (lowerSize == 1 && upperSize == 1) {
					// Easy, single element swap
					Song tempSong = songs[lowerStart];
					songs[lowerStart] = songs[upperStart];
					songs[upperStart] = tempSong;
				} else {
					// Slow multi-element swap. Copy to a new array in the
					// swapped order.
					System.arraycopy(songs, 0, temp, 0, lowerStart); // copy elements before lower
					System.arraycopy(songs, upperStart, temp, lowerStart, upperSize); // copy upper elements to lower spot
					System.arraycopy(songs, lowerEnd + 1, temp, lowerStart + upperSize, upperStart - lowerEnd - 1); // copy elements between upper and lower
					System.arraycopy(songs, lowerStart, temp, lowerStart + upperEnd - lowerEnd, lowerSize); // copy lower elements to upper spot
					System.arraycopy(songs, upperEnd + 1, temp, upperEnd + 1, size - upperEnd - 1); // copy elements remaining elements after upper

					// New array is finished. Use the old array as temp for the
					// next iteration.
					Song[] tempTemp = songs;
					songs = temp;
					temp = tempTemp;
				}
			}

			list.clear();
			list.addAll(Arrays.asList(songs));
		} else {
			Collections.shuffle(list, random);
		}
	}

	/**
	 * Determine if any songs are available from the library.
	 *
	 * @param resolver A ContentResolver to use.
	 * @return True if it's possible to retrieve any songs, false otherwise. For
	 * example, false could be returned if there are no songs in the library.
	 */
	public static boolean isSongAvailable(ContentResolver resolver)
	{
		if (sSongCount == -1) {
			Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
			String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
			Cursor cursor = resolver.query(media, new String[]{"count(_id)"}, selection, null, null);
			if (cursor == null) {
				sSongCount = 0;
			} else {
				cursor.moveToFirst();
				sSongCount = cursor.getInt(0);
				cursor.close();
			}
		}

		return sSongCount != 0;
	}

	/**
	 * Returns a shuffled array contaning the ids of all the songs on the
	 * device's library.
	 *
	 * @param resolver A ContentResolver to use.
	 */
	public static long[] queryAllSongs(ContentResolver resolver)
	{
		sAllSongsIdx = 0;
		sRandomCacheEnd = -1;

		Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
		String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
		Cursor cursor = resolver.query(media, Song.EMPTY_PROJECTION, selection, null, null);
		if (cursor == null || cursor.getCount() == 0) {
			sSongCount = 0;
			return null;
		}

		int count = cursor.getCount();
		long[] ids = new long[count];
		for (int i = 0; i != count; ++i) {
			if (!cursor.moveToNext())
				return null;
			ids[i] = cursor.getLong(0);
		}
		sSongCount = count;
		cursor.close();

		shuffle(ids);

		return ids;
	}

	public static void onMediaChange()
	{
		sSongCount = -1;
		sAllSongs = null;
	}

	/**
	 * Returns a song randomly selected from all the songs in the Android
	 * MediaStore.
	 *
	 * @param resolver A ContentResolver to use.
	 */
	public static Song randomSong(ContentResolver resolver)
	{
		long[] songs = sAllSongs;

		if (songs == null) {
			songs = queryAllSongs(resolver);
			if (songs == null)
				return null;
			sAllSongs = songs;
		} else if (sAllSongsIdx == sAllSongs.length) {
			sAllSongsIdx = 0;
			sRandomCacheEnd = -1;
			shuffle(sAllSongs);
		}

		if (sAllSongsIdx >= sRandomCacheEnd) {
			Uri media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

			StringBuilder selection = new StringBuilder("_ID IN (");

			boolean first = true;
			int end = Math.min(sAllSongsIdx + RANDOM_POPULATE_SIZE, sAllSongs.length);
			for (int i = sAllSongsIdx; i != end; ++i) {
				if (!first)
					selection.append(',');

				first = false;

				selection.append(sAllSongs[i]);
			}

			selection.append(')');

			Cursor cursor = resolver.query(media, Song.FILLED_PROJECTION, selection.toString(), null, null);

			if (cursor == null) {
				sAllSongs = null;
				return null;
			}

			int count = cursor.getCount();
			if (count > 0) {
				assert(count <= RANDOM_POPULATE_SIZE);

				for (int i = 0; i != count; ++i) {
					cursor.moveToNext();
					Song newSong = new Song(-1);
					newSong.populate(cursor);
					newSong.flags |= Song.FLAG_RANDOM;
					sRandomCache[i] = newSong;
				}
			}

			cursor.close();

			// The query will return sorted results; undo that
			shuffle(sRandomCache, count);

			sRandomCacheIdx = 0;
			sRandomCacheEnd = sAllSongsIdx + count;
		}

		Song result = sRandomCache[sRandomCacheIdx];
		++sRandomCacheIdx;
		++sAllSongsIdx;

		return result;
	}

	public static final float DB_MIN = -144.0f; // Noise floor of 24-bit digital audio.

	public static float decibelsToLinearScale(float decibels)
	{
		if (decibels == 0)
			return 1.0f;
		else if (decibels <= DB_MIN)
			return 0.0f;
		else
			return (float)Math.pow(10.0f, decibels / 20.0f);
	}
}
