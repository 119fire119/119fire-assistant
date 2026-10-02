package com.fire119.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores only the automation mode the user explicitly enabled before reboot. */
public final class AssistantBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (context.getSharedPreferences("119fire_native", Context.MODE_PRIVATE)
                .getBoolean("background_assistant_enabled", false)) {
            try { BackgroundAssistantService.start(context); } catch (Exception ignored) { }
        }
    }
}
