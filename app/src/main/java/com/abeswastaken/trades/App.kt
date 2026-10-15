package com.abeswastaken.trades

import android.app.Application
import com.abeswastaken.trades.data.Repository
import com.abeswastaken.trades.data.SettingsStore
import com.abeswastaken.trades.data.TradesDb
import com.abeswastaken.trades.util.AppLog

class App : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("Crash on ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        repo = Repository(TradesDb.create(this), SettingsStore(this), filesDir)
        AppLog.i("App started, version ${packageManager.getPackageInfo(packageName, 0).versionName}")
    }
}
