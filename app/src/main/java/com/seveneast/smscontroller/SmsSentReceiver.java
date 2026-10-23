package com.seveneast.smscontroller;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Reports whether Android handed the outgoing command SMS to the carrier. */
public class SmsSentReceiver extends BroadcastReceiver {
    public static final String ACTION_SENT = "com.seveneast.smscontroller.SMS_SENT";
    public static final String ACTION_SENT_RESULT = "com.seveneast.smscontroller.SENT_RESULT";
    public static final String STORE = "controller_sms_store";

    @Override
    public void onReceive(Context context, Intent intent) {
        long token = intent == null ? -1L : intent.getLongExtra("token", -1L);
        boolean success = getResultCode() == Activity.RESULT_OK;
        SharedPreferences store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        store.edit().putLong("last_sent_token", token).putBoolean("last_sent_success", success).apply();

        Intent result = new Intent(ACTION_SENT_RESULT);
        result.setPackage(context.getPackageName());
        result.putExtra("token", token);
        result.putExtra("success", success);
        result.putExtra("result_code", getResultCode());
        context.sendBroadcast(result);
    }
}
