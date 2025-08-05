package org.getoutline.sdk.example.connectivity

import android.app.Application
import timber.log.Timber

/**
 * time : 8/6/25
 * desc :
 */
class App: Application() {

	override fun onCreate() {
		super.onCreate()

		Timber.plant(Timber.DebugTree())
	}
}