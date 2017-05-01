/*
 * Copyright (C) 2017 Vadim Frolov
 * Licensed under GNU's GPL 3 or any later version, see README
 */

package com.vadimfrolov.twobuttonremote;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.gson.Gson;
import com.vadimfrolov.twobuttonremote.Network.HostBean;
import com.vadimfrolov.twobuttonremote.Network.NetInfo;
import com.vadimfrolov.twobuttonremote.Network.RemoteAsyncTask;
import com.vadimfrolov.twobuttonremote.Network.RemoteCommand;
import com.vadimfrolov.twobuttonremote.Network.RemoteCommandResult;

import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//import com.google.android.gms.auth.api.Auth;
//import com.google.android.gms.auth.api.credentials.Credential;
//import com.google.android.gms.common.api.GoogleApiClient;

public class MainActivity extends ActivityNet
        implements SharedPreferences.OnSharedPreferenceChangeListener,
        RemoteCommandResult {

    public static final String KEY_PREF_TARGET = "target";
    private final String TAG = "MainActivity";

    //private GoogleApiClient mGoogleApiClient;
    private HostBean mTarget;
    private TextView mViewName;
    private TextView mViewAddress;
    SharedPreferences mPrefs;
    ImageView mIconAlive;

    Button mBtnTogglePower;
    Button mBtnRestart;
    TextView mViewStatus;
    Stack<RemoteAsyncTask> mSshTasks;
    private RemoteCommandResult mDelegate; // reference to this, for code that can not use this directly
    private ScheduledThreadPoolExecutor mSch = null;
    private ScheduledFuture<?> mPollFuture = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create action bar as a toolbar
        Toolbar toolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(this);

        mViewName = (TextView) findViewById(R.id.id);
        mViewAddress = (TextView) findViewById(R.id.content);
        mIconAlive = (ImageView) findViewById(R.id.alive);
        mBtnTogglePower = (Button) findViewById(R.id.btn_toggle_power);
        mBtnRestart = (Button) findViewById(R.id.btn_restart);
        mViewStatus = (TextView) findViewById(R.id.text_status);

        mViewStatus.setMovementMethod(new ScrollingMovementMethod());

        Gson gson = new Gson();
        String targetJson =  mPrefs.getString(KEY_PREF_TARGET, "");
        if (targetJson.length() > 0) {
            mTarget = gson.fromJson(targetJson, HostBean.class);
        }

        mSshTasks = new Stack<>();
        mDelegate = this;
        mIsConnected = false;

        mBtnRestart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopTargetPolling();

                RemoteCommand cmd = new RemoteCommand(mTarget, RemoteCommand.SSH);
                cmd.command = "sudo shutdown -r now";
                mSshTasks.push(new RemoteAsyncTask(mDelegate));
                mSshTasks.peek().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, cmd);

                logForUser(getResources().getString(R.string.reboot_sent));
            }
        });
        mBtnTogglePower.setOnClickListener(mPowerActor);

        // create a pool with only 3 threads
        mSch = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(3);
        mSch.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            Gson gson = new Gson();
            String targetJson = gson.toJson(mTarget);

            SharedPreferences.Editor editor = mPrefs.edit();
            editor.putString(MainActivity.KEY_PREF_TARGET, targetJson);
            editor.commit();
        } catch (Exception e) {
            Log.d(TAG, "Failed to save target as JSON: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mIsConnected && mTarget != null && mTarget.ipAddress != null && !mTarget.ipAddress.equals(NetInfo.NOIP)) {
            startTargetPolling();
        }
        updateView();
    }

    @Override
    public void onPause() {
        super.onPause();

        stopTargetPolling();
        while (!mSshTasks.isEmpty()) {
            mSshTasks.pop().cancel(true);
        }
        // unregisterReceiver(mNetworkReceiver);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent = null;
        switch (item.getItemId()) {
            case R.id.action_add_host:
                intent = new Intent(this, SearchConfigureActivity.class);
//                PendingIntent pendingIntent = TaskStackBuilder.create(this)
//                        .addNextIntentWithParentStack(intent)
//                        .getPendingIntent(0, PendingIntent.FLAG_CANCEL_CURRENT);
//                NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
//                builder.setContentIntent(pendingIntent);
                this.startActivity(intent);
                break;

            case R.id.action_edit_host:
                intent = new Intent(this, TargetConfigurationActivity.class);
                intent.putExtra(HostBean.EXTRA, mTarget);
                this.startActivity(intent);
                break;

            case R.id.action_delete_host:
                mPrefs.edit().remove(KEY_PREF_TARGET).apply();
                mTarget = null;
                updateView();
                break;

            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem miAdd = (MenuItem) menu.findItem(R.id.action_add_host);
        MenuItem miEdit = (MenuItem) menu.findItem(R.id.action_edit_host);
        MenuItem miDelete = (MenuItem) menu.findItem(R.id.action_delete_host);
        boolean targetIsValid = mTarget != null && mTarget.ipAddress != null && !mTarget.ipAddress.equals(NetInfo.NOIP);

        if (targetIsValid) {
            miAdd.setTitle(getResources().getString(R.string.replace_host));
        } else
        {
            miAdd.setTitle(getResources().getString(R.string.add_host));
        }
        miEdit.setEnabled(targetIsValid);
        miDelete.setEnabled(targetIsValid);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && key.equals(KEY_PREF_TARGET)) {
            Gson gson = new Gson();
            String json = sharedPreferences.getString(KEY_PREF_TARGET, "");
            mTarget = gson.fromJson(json, HostBean.class);
            updateView();
        }
    }

    protected void updateView() {
        mBtnTogglePower.setEnabled(mIsConnected && mTarget != null);
        if (!mIsConnected) {
            mViewStatus.setText(getResources().getString(R.string.no_network));
        }

        boolean isTargetValid = true;
        boolean isTargetAlive = mTarget != null && mTarget.isAlive;
        if (mTarget == null || mTarget.ipAddress.equals(NetInfo.NOIP)) {
            isTargetValid = false;
            mViewName.setText(getResources().getString(R.string.no_device));
            mViewAddress.setText("");
        }

        if (isTargetValid) {
            mViewName.setText(mTarget.name());
            mViewAddress.setText(mTarget.hardwareAddress.toUpperCase());
        }

        mBtnRestart.setEnabled(mIsConnected && isTargetAlive);
        int color = isTargetAlive ? Color.GREEN : Color.RED;
        mIconAlive.setColorFilter(color);
        mIconAlive.setVisibility(isTargetValid ? View.VISIBLE : View.GONE);
        int textId = isTargetAlive ? R.string.shutdown : R.string.turn_on;
        mBtnTogglePower.setText(getResources().getString(textId));

        invalidateOptionsMenu();
    }

    protected void updateNetworkStatus() {
        if (mIsConnected) {
            mViewStatus.setText("");
            if (mTarget != null) {
                if (mTarget.broadcastIp == null || mTarget.broadcastIp.equals(NetInfo.NOIP)) {
                    mTarget.broadcastIp = mNetInfo.broadcastIp;
                }
                startTargetPolling();
            }
        } else {
            if (mTarget != null) {
                mTarget.isAlive = false;
            }
            stopTargetPolling();
        }
        updateView();
    }

    private View.OnClickListener mPowerActor = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mTarget == null) {
                return;
            }

            RemoteCommand cmd = new RemoteCommand(mTarget);
            if (mTarget.isAlive) {
                cmd.commandType = RemoteCommand.SSH;
                cmd.command = "sudo poweroff";

                logForUser(getResources().getString(R.string.shutdown_sent));
            } else {
                cmd.commandType = RemoteCommand.WOL;

                logForUser(getResources().getString(R.string.wol_sent));
            }
            mSshTasks.push(new RemoteAsyncTask(mDelegate));
            mSshTasks.peek().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, cmd);
        }
    };

    private void startTargetPolling() {
        Runnable pollTask = new Runnable() {
            @Override
            public void run() {
                if (mIsConnected && mTarget != null) {
                    RemoteCommand cmd = new RemoteCommand(mTarget, RemoteCommand.PING);
                    (new RemoteAsyncTask(mDelegate)).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, cmd);
                }
            }
        };
        if (mSch != null) {
            if (mPollFuture == null) {
                mPollFuture = mSch.scheduleWithFixedDelay(pollTask, 0, 5, TimeUnit.SECONDS);
            }
        }
    }

    private void stopTargetPolling() {
        if (mSch != null && mPollFuture != null) {
            mPollFuture.cancel(true);
            mPollFuture = null;
        }
    }

    @Override
    public void onRemoteCommandFinished(RemoteCommand output) {
        if (mTarget == null || mViewStatus == null || output == null)
            return;

        switch (output.commandType) {
            case RemoteCommand.PING:
                mTarget.isAlive = output.result.equals("success");
                break;

            case RemoteCommand.WOL:
                if (output.result.equals("success")) {
                    logForUser(getResources().getString(R.string.wol_received));
                }
                if (output.result.equals("invalid gateway")) {
                    logForUser(getResources().getString(R.string.no_gateway));
                }
                break;

            case RemoteCommand.SSH:
                if (output.command.contains("-r now")) {
                    if (output.result.length() > 0) {
                        logForUser(getResources().getString(R.string.reboot_received));
                    } else {
                        logForUser(getResources().getString(R.string.reboot_failed));
                    }
                    startTargetPolling();
                } else if (output.command.contains("poweroff")) {
                    if (output.result.length() > 0) {
                        logForUser(getResources().getString(R.string.shutdown_received));
                    } else {
                        logForUser(getResources().getString(R.string.shutdown_failed));
                    }
                }
                break;

            default:
                break;
        }

        updateView();
    }

    private void logForUser(String msg) {
        if (mViewStatus == null)
            return;

        mViewStatus.setText(mViewStatus.getText().toString() + msg + "\n");
    }
}
