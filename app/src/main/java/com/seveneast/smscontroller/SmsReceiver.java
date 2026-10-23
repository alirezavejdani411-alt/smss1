package com.seveneast.smscontroller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** Receives incoming SMS from the controller even when the activity is not open. */
public class SmsReceiver extends BroadcastReceiver {
    public static final String ACTION_INCOMING = "com.seveneast.smscontroller.INCOMING_SMS";
    public static final String STORE = "controller_sms_store";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        try {
            SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (messages == null || messages.length == 0) return;

            String sender = messages[0].getOriginatingAddress();
            StringBuilder body = new StringBuilder();
            long timestamp = messages[0].getTimestampMillis();
            for (SmsMessage message : messages) {
                if (message != null) body.append(message.getMessageBody() == null ? "" : message.getMessageBody());
            }
            if (sender == null || sender.trim().isEmpty() || body.length() == 0) return;

            SharedPreferences store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
            long previousId = store.getLong("last_id", 0L);
            long id = Math.max(Math.max(System.currentTimeMillis(), timestamp), previousId + 1L);
            store.edit()
                    .putString("last_sender", sender)
                    .putString("last_body", body.toString())
                    .putLong("last_id", id)
                    .putLong("last_timestamp", timestamp)
                    .commit();

            Intent update = new Intent(ACTION_INCOMING);
            update.setPackage(context.getPackageName());
            update.putExtra("sender", sender);
            update.putExtra("body", body.toString());
            update.putExtra("id", id);
            context.sendBroadcast(update);
        } catch (RuntimeException ignored) {
            // Do not crash the SMS delivery path if a carrier sends malformed PDU data.
        }
    }
}
