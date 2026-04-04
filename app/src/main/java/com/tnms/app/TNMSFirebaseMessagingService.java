package com.tnms.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class TNMSFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "tnms_notifications";
    private static final String CHANNEL_NAME = "TNMS Notifications";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "TNMS";
        String body = "";
        String linkUrl = null;

        // Check notification payload
        if (remoteMessage.getNotification() != null) {
            title = remoteMessage.getNotification().getTitle() != null
                    ? remoteMessage.getNotification().getTitle() : title;
            body = remoteMessage.getNotification().getBody() != null
                    ? remoteMessage.getNotification().getBody() : body;
        }

        // Check data payload for deep link
        if (remoteMessage.getData().containsKey("linkUrl")) {
            linkUrl = remoteMessage.getData().get("linkUrl");
        }

        showNotification(title, body, linkUrl);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        // Store the new token — it will be sent to the server when the user logs in
        SharedPreferences prefs = getSharedPreferences("tnms_prefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();
    }

    private void showNotification(String title, String body, String linkUrl) {
        // Create intent that opens MainActivity with the deep link
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (linkUrl != null) {
            intent.putExtra("deep_link", linkUrl);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setContentIntent(pendingIntent);

        // Use a unique notification ID so multiple notifications stack
        int notificationId = (int) System.currentTimeMillis();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(notificationId, builder.build());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Ticket updates, assignments, and alerts");
            channel.enableVibration(true);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
