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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.DialogPreference;
import android.preference.PreferenceActivity;

/**
 * The preferences activity in which one can change application preferences.
 */
public class PreferencesActivity
	extends PreferenceActivity
	implements SharedPreferences.OnSharedPreferenceChangeListener
{
	SharedPreferences mSettings;

	/**
	 * Initialize the activity, loading the preference specifications.
	 */
	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);

		mSettings = PlaybackService.getSettings(this);
		mSettings.registerOnSharedPreferenceChangeListener(this);

		updateReplayGainPreferenceState();
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
	{
		updateReplayGainPreferenceState();
	}

	/**
	 * Returns all of the preference keys that should be enabled or disabled according to the
	 * state of the replaygain_enabled setting, excluding "replaygain_enabled".
	 */
	protected String[] replaygainPreferenceKeys()
	{
		return new String[] {
				"replaygain_max_boost",
				"replaygain_no_data_attenuation"
		};
	}

	/**
	 * Gets an instance of a DialogPreference for the given preference key.
	 * @param key
	 */
	protected DialogPreference getDialogPreference(String key)
	{
		return ((DialogPreference)this.getPreferenceManager().findPreference(key));
	}

	/**
	 * Updates the 'enabled' state of all preferences relating to replaygain, according to whether
	 * replaygain processing is enabled or not.
	 */
	protected void updateReplayGainPreferenceState()
	{
		boolean replaygainEnabled = ((CheckBoxPreference)this.getPreferenceManager().findPreference("enable_replaygain")).isChecked();

		for (String key : replaygainPreferenceKeys()) {
			getDialogPreference(key).setEnabled(replaygainEnabled);
		}

		getDialogPreference("volume").setEnabled(!replaygainEnabled);
	}
}
