/*
 * Copyright 2010 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.android.apps.mytracks.signalstrength;

import static com.google.android.apps.mytracks.signalstrength.SignalStrengthConstants.*;

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.MyTracksProviderUtilsImpl;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.content.WaypointType;
import com.google.android.apps.mytracks.services.ITrackRecordingService;
import com.google.android.apps.mytracks.signalstrength.SignalStrengthListener.SignalStrengthCallback;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * Serivce which actually reads signal strength and sends it to My Tracks.
 *
 * @author Rodrigo Damazio
 */
public class SignalStrengthService extends Service
    implements ServiceConnection, SignalStrengthCallback, OnSharedPreferenceChangeListener {

  private ComponentName mytracksServiceComponent;
  private SharedPreferences preferences;
  private SignalStrengthListenerFactory signalListenerFactory;
  private SignalStrengthListener signalListener;
  private ITrackRecordingService mytracksService;
  private long lastSamplingTime;
  private long samplingPeriod;
  private MyTracksProviderUtils utils;

  @Override
  public void onCreate() {
    super.onCreate();

    mytracksServiceComponent = new ComponentName(
        getString(R.string.mytracks_service_package),
        getString(R.string.mytracks_service_class));
    preferences = PreferenceManager.getDefaultSharedPreferences(this);
    signalListenerFactory = new SignalStrengthListenerFactory();
    utils = new MyTracksProviderUtilsImpl(this.getContentResolver());
  }

  @Override
  public void onStart(Intent intent, int startId) {
    handleCommand(intent, startId);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    handleCommand(intent, startId);
    return START_STICKY;
  }

  private void handleCommand(Intent intent, int startId) {
    String action = intent.getAction();
    if (START_SAMPLING.equals(action)) {
      startSampling();
    } else {
      stopSampling();
    }
  }

  private void startSampling() {
    // TODO: Start foreground

    if (!isMytracksRunning()) {
      Log.w(TAG, "My Tracks not running!");
      return;
    }

    Log.d(TAG, "Starting sampling");
    Intent intent = new Intent();
    intent.setComponent(mytracksServiceComponent);
    if (!bindService(intent, SignalStrengthService.this, 0)) {
      Log.e(TAG, "Couldn't bind to service.");
      return;
    }
  }

  private boolean isMytracksRunning() {
    ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
    List<RunningServiceInfo> services = activityManager.getRunningServices(Integer.MAX_VALUE);
    for (RunningServiceInfo serviceInfo : services) {
      if (serviceInfo.pid != 0 &&
          serviceInfo.service.equals(mytracksServiceComponent)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void onServiceConnected(ComponentName name, IBinder service) {
    synchronized (this) {
      mytracksService = ITrackRecordingService.Stub.asInterface(service);

      Log.d(TAG, "Bound to My Tracks");

      boolean recording = false;
      try {
        recording = mytracksService.isRecording();
      } catch (RemoteException e) {
        Log.e(TAG, "Failed to talk to my tracks", e);
      }
      if (!recording) {
        Log.w(TAG, "My Tracks is not recording, bailing");
        stopSampling();
        return;
      }

      // We're ready to send waypoints, so register for signal sampling
      signalListener = signalListenerFactory.create(this, this);
      signalListener.register();

      // Register for preference changes
      samplingPeriod = Long.parseLong(preferences.getString(
          getString(R.string.settings_min_signal_sampling_period_key), "-1"));
      preferences.registerOnSharedPreferenceChangeListener(this);

      // Tell the user we've started.
      Toast.makeText(this, R.string.started_sampling, Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public void onSignalStrengthSampled(String description, String icon) {
    long now = System.currentTimeMillis();
    if (now - lastSamplingTime < samplingPeriod * 60 * 1000) {
      return;
    }

    try {
      long waypointId;
      synchronized (this) {
        if (mytracksService == null) {
          Log.d(TAG, "No my tracks service to send to");
          return;
        }

        // First we create a basic waypoint.
        waypointId = mytracksService.insertWaypoint(WaypointType.MARKER);
       
        // Then we fetch the actual waypoint from the provider.
        Waypoint wpt = utils.getWaypoint(waypointId);
        wpt.setName("Signal Strength");
        wpt.setType(Waypoint.TYPE_WAYPOINT);
        wpt.setIcon(icon);
        wpt.setDescription(description);

        // Finally write the modified waypoint back to the db.
        utils.updateWaypoint(wpt);
      }

      if (waypointId >= 0) {
        Log.d(TAG, "Added signal marker");
        lastSamplingTime = now;
      } else {
        Log.e(TAG, "Cannot insert waypoint marker?");
      }
    } catch (RemoteException e) {
      Log.e(TAG, "Cannot talk to my tracks service", e);
    }
  }

  private void stopSampling() {
    Log.d(TAG, "Stopping sampling");

    synchronized (this) {
      // Unregister from preference change updates
      preferences.unregisterOnSharedPreferenceChangeListener(this);

      // Unregister from receiving signal updates
      if (signalListener != null) {
        signalListener.unregister();
        signalListener = null;
      }

      // Unbind from My Tracks
      if (mytracksService != null) {
        unbindService(this);
        mytracksService = null;
      }

      // Reset the last sampling time
      lastSamplingTime = 0;

      // Tell the user we've stopped
      Toast.makeText(this, R.string.stopped_sampling, Toast.LENGTH_SHORT).show();
      
      // Stop
      stopSelf();
    }
  }

  @Override
  public void onServiceDisconnected(ComponentName name) {
    Log.i(TAG, "Disconnected from My Tracks");
    synchronized (this) {
      mytracksService = null;
    }
  }

  @Override
  public void onDestroy() {
    stopSampling();

    super.onDestroy();
  }

  @Override
  public void onSharedPreferenceChanged(
      SharedPreferences sharedPreferences, String key) {
    if (getString(R.string.settings_min_signal_sampling_period_key).equals(key)) {
      samplingPeriod = Long.parseLong(sharedPreferences.getString(key, "-1"));
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  public static void startService(Context context) {
    Intent intent = new Intent();
    intent.setClass(context, SignalStrengthService.class);
    intent.setAction(START_SAMPLING);
    context.startService(intent);
  }

  public static void stopService(Context context) {
    Intent intent = new Intent();
    intent.setClass(context, SignalStrengthService.class);
    intent.setAction(STOP_SAMPLING);
    context.startService(intent);
  }
}
