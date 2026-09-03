package com.facemesh.ar

import android.app.Application
import android.content.Context
import android.util.Log

class FaceMeshApplication : Application() {

    companion object {
        private const val TAG = "FaceMeshApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FaceMeshAR Application started")
    }
}