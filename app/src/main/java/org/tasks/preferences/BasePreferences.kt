package org.tasks.preferences

import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.tasks.R
import org.tasks.databinding.ActivityPreferencesBinding
import org.tasks.injection.ThemedInjectingAppCompatActivity
import org.tasks.ui.MenuColorizer

private const val EXTRA_TITLE = "extra_title"

abstract class BasePreferences : ThemedInjectingAppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPreferencesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        toolbar = binding.toolbar.toolbar
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, getRootPreference())
                .commit()
            toolbar.title = getString(getRootTitle())
        } else {
            toolbar.title = savedInstanceState.getCharSequence(EXTRA_TITLE)
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                toolbar.title = getString(getRootTitle())
            }
        }
        toolbar.navigationIcon =
            ContextCompat.getDrawable(this, R.drawable.ic_outline_arrow_back_24px);
        toolbar.setNavigationOnClickListener { onBackPressed() }
        setupMenu()
        MenuColorizer.colorToolbar(this, toolbar)
    }

    open fun setupMenu() {}

    abstract fun getRootTitle(): Int

    abstract fun getRootPreference(): PreferenceFragmentCompat

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(EXTRA_TITLE, toolbar.title)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment
        ).apply {
            arguments = args
            setTargetFragment(caller, 0)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .addToBackStack(null)
            .commit()
        toolbar.title = pref.title
        return true
    }
}