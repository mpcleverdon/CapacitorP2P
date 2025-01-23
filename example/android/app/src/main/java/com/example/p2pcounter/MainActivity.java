package com.example.p2pcounter;

import android.os.Bundle;
import android.util.Log;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            Log.d(TAG, "MainActivity onCreate");
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }

    @Override
    public void onResume() {
        try {
            super.onResume();
            Log.d(TAG, "MainActivity onResume");
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume", e);
        }
    }
} 