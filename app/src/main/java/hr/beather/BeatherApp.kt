package hr.beather

import android.app.Application
import hr.beather.data.Units

class BeatherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Units.load(this)
    }
}
