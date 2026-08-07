package barilyuk.mobilosignal;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.telephony.CellSignalStrength;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.RemoteViews;
import android.annotation.TargetApi;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

public class SignalStrengthService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "SignalStrengthService";
    private static final String CHANNEL_ID = "SignalStrengthChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "SIMSelection";
    private static final String RADIO_CHOSEN_BLACK_KEY = "RadioChosenBlack";

    private TelephonyManager telephonyManager;
    private SubscriptionManager subscriptionManager;
    private List<PhoneStateListener> listeners = new ArrayList<>();

    private String sim1SignalText = "-0 dBm";
    private String sim2SignalText = "-0 dBm";
    private String sim1NetworkType = "0G";
    private String sim2NetworkType = "0G";
    private boolean sim1Available = false;
    private boolean sim2Available = false;
    private boolean isDualSim = false;
    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        subscriptionManager = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        // Initialize SharedPreferences and register listener
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        if (hasPermissions()) {
            startListening();
        } else {
            Log.e(TAG, "Missing required permissions");
        }
    }

    private boolean hasPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionInfoList == null || subscriptionInfoList.isEmpty()) {
            Log.w(TAG, "No active SIM cards found");
            return;
        }

        isDualSim = subscriptionInfoList.size() > 1;
        sim1Available = false;
        sim2Available = false;

        for (SubscriptionInfo subscriptionInfo : subscriptionInfoList) {
            int subscriptionId = subscriptionInfo.getSubscriptionId();
            int simSlotIndex = subscriptionInfo.getSimSlotIndex();
            TelephonyManager tmForSim = telephonyManager.createForSubscriptionId(subscriptionId);

            if (simSlotIndex == 0) {
                sim1Available = true;
            } else if (simSlotIndex == 1) {
                sim2Available = true;
            }

            PhoneStateListener listener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                    super.onSignalStrengthsChanged(signalStrength);

                    int dbm;
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                        dbm = getDbmFromSignalStrength(signalStrength);
                    } else {
                        dbm = extractDbm(signalStrength);
                    }

                    String networkType = getNetworkTypeForSim(tmForSim, simSlotIndex);
                    String signalText = (dbm == -1 ? "-0 dBm" : dbm + " dBm");

                    if (simSlotIndex == 0) {
                        sim1SignalText = signalText;
                        sim1NetworkType = networkType;
                    } else if (simSlotIndex == 1) {
                        sim2SignalText = signalText;
                        sim2NetworkType = networkType;
                    }

                    updateNotification();
                }
            };

            listeners.add(listener);
            tmForSim.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (RADIO_CHOSEN_BLACK_KEY.equals(key)) {
            Log.d(TAG, "Text color preference changed, updating notification");
            updateNotification();
        }
    }

    private void updateNotification() {
        Notification notification = createNotification();
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification createNotification() {
        Log.d(TAG, "createNotification: Updating notification");
        boolean isRadioChosenBlack = sharedPreferences.getBoolean(RADIO_CHOSEN_BLACK_KEY, false);
        int TEXT_COLOR = isRadioChosenBlack ? Color.BLACK : Color.WHITE;

        // Build notification text based on available SIMs
        String collapsedText;
        String expandedText;

        if (isDualSim && sim1Available && sim2Available) {
            collapsedText = "\uD83D\uDCF6 SIM1: " + sim1SignalText + " (" + sim1NetworkType + ") | SIM2: " + sim2SignalText + " (" + sim2NetworkType + ")";
            expandedText = "\uD83D\uDCF6 Signal Strength\n  SIM 1: " + sim1SignalText + " (" + sim1NetworkType + ")\n  SIM 2: " + sim2SignalText + " (" + sim2NetworkType + ")";
        } else if (sim1Available) {
            collapsedText = "\uD83D\uDCF6 " + sim1SignalText + " (" + sim1NetworkType + ")";
            expandedText = "\uD83D\uDCF6 Signal strength: " + sim1SignalText + " (" + sim1NetworkType + ")";
        } else if (sim2Available) {
            collapsedText = "\uD83D\uDCF6 " + sim2SignalText + " (" + sim2NetworkType + ")";
            expandedText = "\uD83D\uDCF6 Signal strength: " + sim2SignalText + " (" + sim2NetworkType + ")";
        } else {
            collapsedText = "\uD83D\uDCF6 Signal: N/A";
            expandedText = "\uD83D\uDCF6 Signal strength: N/A";
        }

        RemoteViews notificationExpandedLayout = new RemoteViews(getPackageName(), R.layout.notification_expanded);
        notificationExpandedLayout.setTextViewText(R.id.notification_text_expanded, expandedText);

        RemoteViews notificationLayout = new RemoteViews(getPackageName(), R.layout.notification);
        notificationLayout.setTextViewText(R.id.notification_text, collapsedText);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Determine the best signal value for the small icon
        String shortText = "-140";
        String bestSignalText = sim1SignalText;
        if (sim2Available && sim2SignalText.compareTo(sim1SignalText) > 0) {
            bestSignalText = sim2SignalText;
        }
        if (bestSignalText.contains("dBm")) {
            try {
                int dbmValue = Integer.parseInt(bestSignalText.split(" ")[0]);
                if (dbmValue >= -120 && dbmValue <= -30) {
                    shortText = String.valueOf(dbmValue);
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing dBm value for icon", e);
            }
        }

        Bitmap signalBitmap = BitmapUtils.textToBitmap(shortText, TEXT_COLOR);
        Icon icon = Icon.createWithBitmap(signalBitmap);

        return new Notification.Builder(this, CHANNEL_ID)
                .setCustomContentView(notificationLayout)
                .setSmallIcon(icon)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_LOW)
                .setCustomBigContentView(notificationExpandedLayout)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Signal Strength Monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows current signal strength");
            channel.setSound(null, null);
            channel.enableVibration(false);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private String getNetworkType(TelephonyManager tm) {
        try {
            int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                networkType = tm.getDataNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    networkType = tm.getVoiceNetworkType();
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkType = tm.getDataNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    networkType = tm.getNetworkType();
                }
            } else {
                networkType = tm.getNetworkType();
            }

            return getNetworkTypeString(networkType);

        } catch (Exception e) {
            Log.e(TAG, "Error getting network type", e);
            return "0G";
        }
    }

    private String getNetworkTypeString(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "4G";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            default:
                return "0G";
        }
    }

    private String getNetworkTypeForSim(TelephonyManager tm, int simSlotIndex) {
        try {
            int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                networkType = tm.getDataNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN ||
                        (isDualSim && !isDataSim(tm))) {
                    networkType = tm.getVoiceNetworkType();
                }
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    networkType = getNetworkTypeFromServiceState(tm);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkType = tm.getDataNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    networkType = tm.getNetworkType();
                }
            } else {
                networkType = tm.getNetworkType();
            }

            return getNetworkTypeString(networkType);

        } catch (Exception e) {
            Log.e(TAG, "Error getting network type for SIM " + simSlotIndex, e);
            return "0G";
        }
    }

    private boolean isDataSim(TelephonyManager tm) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return tm.getSubscriptionId() == dataSubId;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if data SIM", e);
        }
        return true;
    }

    @TargetApi(Build.VERSION_CODES.R)
    private int getNetworkTypeFromServiceState(TelephonyManager tm) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return tm.getVoiceNetworkType();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network type from service state", e);
        }
        return TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    private int extractDbm(SignalStrength signalStrength) {
        try {
            List<CellSignalStrength> strengths = signalStrength.getCellSignalStrengths();
            if (strengths != null && !strengths.isEmpty()) {
                return strengths.get(0).getDbm();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private int getDbmFromSignalStrength(SignalStrength signalStrength) {
        if (signalStrength == null) return -1;

        int gsmSignalStrength = signalStrength.getGsmSignalStrength();
        if (gsmSignalStrength != 99) {
            return -113 + 2 * gsmSignalStrength;
        }

        try {
            int cdmaDbm = signalStrength.getCdmaDbm();
            if (cdmaDbm != 0) {
                return cdmaDbm;
            }
        } catch (Exception ignored) {}

        try {
            int evdoDbm = signalStrength.getEvdoDbm();
            if (evdoDbm != 0) {
                return evdoDbm;
            }
        } catch (Exception ignored) {}

        return -1;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");

        if (sharedPreferences != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        }

        for (PhoneStateListener listener : listeners) {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
        }
        listeners.clear();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}