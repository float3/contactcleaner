package com.example.contactcleaner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PhoneAccountReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PhoneAccount.ensure(context);
    }
}
