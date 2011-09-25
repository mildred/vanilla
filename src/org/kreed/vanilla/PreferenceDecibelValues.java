package org.kreed.vanilla;

import android.content.Context;
import android.preference.ListPreference;
import android.util.AttributeSet;

/**
 * A selection of decibel values.
 */
public class PreferenceDecibelValues extends ListPreference {
	public PreferenceDecibelValues(Context context, AttributeSet attrs)
	{
		super(context, attrs);

		CharSequence[] entryValues = getEntryValues();
		assert(entryValues != null && entryValues.length > 0);

		String[] entryNames = new String[entryValues.length];
		for (int i = 0; i < entryNames.length; ++i) {
			entryNames[i] = String.format("%+.0f db", Float.valueOf(entryValues[i].toString()));
		}

		setEntries(entryNames);
	}
}
