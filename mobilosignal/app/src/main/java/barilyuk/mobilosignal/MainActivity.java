package barilyuk.mobilosignal;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.CellSignalStrength;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import android.widget.RelativeLayout;
import android.content.Intent;
import android.annotation.TargetApi;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREF_NAME = "SIMSelection";

    // SIM 1 views
    private TextView tvSim1Signal, sim1OperatorName, sim1DbmText;
    private View sim1Marker, sim1GradientBar;

    // SIM 2 views
    private TextView tvSim2Signal, sim2OperatorName, sim2DbmText;
    private View sim2Marker, sim2GradientBar;

    private CheckBox autostartCheckBox;
    private static final String AUTO_START_KEY = "AutoStart";
    private RadioGroup textColorRadioGroup;
    private RadioButton radioBlack;
    private RadioButton radioWhite;
    private static final String RADIO_CHOSEN_BLACK_KEY = "RadioChosenBlack";

    private TelephonyManager telephonyManager;
    private SubscriptionManager subscriptionManager;
    private final List<PhoneStateListener> listeners = new ArrayList<>();

    private int sim1SubId = -1;
    private int sim2SubId = -1;

    private SharedPreferences sharedPreferences;

    // Listens for SIM subscription changes (SIM added/removed)
    private SubscriptionManager.OnSubscriptionsChangedListener subscriptionsChangedListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Autostart checkbox
        autostartCheckBox = findViewById(R.id.autostartCheckBox);

        // notification's small icon color selection
        textColorRadioGroup = findViewById(R.id.textColorRadioGroup);
        radioBlack = findViewById(R.id.radioBlack);
        radioWhite = findViewById(R.id.radioWhite);

        // Initialize SIM1 views
        tvSim1Signal = findViewById(R.id.tvSim1Signal);
        sim1OperatorName = findViewById(R.id.sim1OperatorName);
        sim1Marker = findViewById(R.id.sim1Marker);
        sim1DbmText = findViewById(R.id.sim1DbmText);
        sim1GradientBar = findViewById(R.id.sim1GradientBar);

        // Initialize SIM2 views
        tvSim2Signal = findViewById(R.id.tvSim2Signal);
        sim2OperatorName = findViewById(R.id.sim2OperatorName);
        sim2Marker = findViewById(R.id.sim2Marker);
        sim2DbmText = findViewById(R.id.sim2DbmText);
        sim2GradientBar = findViewById(R.id.sim2GradientBar);

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        subscriptionManager = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        // Listen for SIM subscription changes (e.g. SIM inserted/removed, radio on/off)
        subscriptionsChangedListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
            @Override
            public void onSubscriptionsChanged() {
                Log.d("MainActivity", "Subscriptions changed, re-detecting SIMs");
                runOnUiThread(MainActivity.this::refreshSimDetection);
            }
        };

        if (hasPermissions()) {
            startListening();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        }

        // Initialize exit button
        Button exitButton = findViewById(R.id.exitButton);

        // Set initial state for the autostart checkbox
        boolean isAutoStartEnabled = sharedPreferences.getBoolean(AUTO_START_KEY, false);
        autostartCheckBox.setChecked(isAutoStartEnabled);

        // Set initial state for radio color selection (default to white)
        boolean radioChosenBlack = sharedPreferences.getBoolean(RADIO_CHOSEN_BLACK_KEY, false);
        radioBlack.setChecked(radioChosenBlack);
        radioWhite.setChecked(!radioChosenBlack);

        // Set checkbox change listener for autostart
        autostartCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(AUTO_START_KEY, isChecked);
            editor.apply();
        });

        // Set radio group change listener for text color
        textColorRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isBlackSelected = (checkedId == R.id.radioBlack);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(RADIO_CHOSEN_BLACK_KEY, isBlackSelected);
            editor.apply();
        });

        // Exit button to kill notification service, activity, and fully exit the app
        exitButton.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(MainActivity.this, SignalStrengthService.class);
            stopService(serviceIntent);
            finish();
            System.exit(0);
        });
    }

    private void updateSimVisibility() {
        // SIM1: always visible when available
        boolean hasSim1 = sim1SubId != -1;
        findViewById(R.id.sim1OperatorLabel).setVisibility(hasSim1 ? View.VISIBLE : View.GONE);
        sim1OperatorName.setVisibility(hasSim1 ? View.VISIBLE : View.GONE);
        tvSim1Signal.setVisibility(hasSim1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.sim1IndicatorContainer).setVisibility(hasSim1 ? View.VISIBLE : View.GONE);

        // SIM2: only visible when available
        boolean hasSim2 = sim2SubId != -1;
        findViewById(R.id.sim2OperatorLabel).setVisibility(hasSim2 ? View.VISIBLE : View.GONE);
        sim2OperatorName.setVisibility(hasSim2 ? View.VISIBLE : View.GONE);
        tvSim2Signal.setVisibility(hasSim2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.sim2IndicatorContainer).setVisibility(hasSim2 ? View.VISIBLE : View.GONE);

        Log.d("MainActivity", "SIM visibility: SIM1=" + hasSim1 + " SIM2=" + hasSim2);
    }

    /**
     * Re-detect SIMs without tearing down listeners that haven't changed.
     * Called on subscription change or initial setup.
     */
    private void refreshSimDetection() {
        if (!hasPermissions()) return;

        List<SubscriptionInfo> activeSubs = subscriptionManager.getActiveSubscriptionInfoList();
        if (activeSubs == null) activeSubs = new ArrayList<>();

        // Find which subscription IDs are currently active
        java.util.Set<Integer> activeSubIds = new java.util.HashSet<>();
        for (SubscriptionInfo si : activeSubs) {
            activeSubIds.add(si.getSubscriptionId());
        }

        // Remove listeners for SIMs that are no longer active
        java.util.Iterator<PhoneStateListener> iter = listeners.iterator();
        while (iter.hasNext()) {
            PhoneStateListener l = iter.next();
            // We can't easily get the subId from a listener, so we'll clean up
            // inactive ones based on our simStates tracking
        }

        // Check if our tracked SIMs are still active
        boolean sim1StillThere = sim1SubId != -1 && activeSubIds.contains(sim1SubId);
        boolean sim2StillThere = sim2SubId != -1 && activeSubIds.contains(sim2SubId);

        if (!sim1StillThere) sim1SubId = -1;
        if (!sim2StillThere) sim2SubId = -1;

        // Assign new SIMs to available slots
        for (SubscriptionInfo si : activeSubs) {
            int subId = si.getSubscriptionId();
            int slotIndex = si.getSimSlotIndex();

            // Skip if already tracked
            if (subId == sim1SubId || subId == sim2SubId) continue;

            if (sim1SubId == -1) {
                sim1SubId = subId;
                setupSimListener(subId, slotIndex, 0);
            } else if (sim2SubId == -1) {
                sim2SubId = subId;
                setupSimListener(subId, slotIndex, 1);
            }
        }

        updateSimVisibility();

        // If no SIMs at all, show a message
        if (sim1SubId == -1 && sim2SubId == -1) {
            tvSim1Signal.setText("\uD83D\uDCF6 N/A");
            sim1OperatorName.setText("---");
            Toast.makeText(this, "No active SIM cards found", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupSimListener(int subscriptionId, int slotIndex, int displaySlot) {
        TelephonyManager tmForSim = telephonyManager.createForSubscriptionId(subscriptionId);

        // Get operator name
        CharSequence carrierName = null;
        List<SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
        if (subs != null) {
            for (SubscriptionInfo si : subs) {
                if (si.getSubscriptionId() == subscriptionId) {
                    carrierName = si.getCarrierName();
                    break;
                }
            }
        }
        String operatorName = (carrierName != null && carrierName.length() > 0)
                ? carrierName.toString()
                : tmForSim.getSimOperatorName();

        final String opName = (operatorName == null || operatorName.isEmpty()) ? "SIM " + (displaySlot + 1) : operatorName;

        runOnUiThread(() -> {
            if (displaySlot == 0) {
                sim1OperatorName.setText(opName);
            } else {
                sim2OperatorName.setText(opName);
            }
            updateSimVisibility();
        });

        PhoneStateListener listener = new PhoneStateListener() {
            @Override
            public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
                super.onSignalStrengthsChanged(signalStrength);

                int dbm;
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                    dbm = getDbmFromSignalStrength(signalStrength);
                } else {
                    dbm = extractDbm(signalStrength);
                }

                String networkType = getNetworkTypeForSim(tmForSim, slotIndex);

                runOnUiThread(() -> {
                    String signalText = "\uD83D\uDCF6 " + (dbm == -1 ? "-0 dBm 0G" : dbm + " dBm " + networkType);

                    int textColor;
                    switch (networkType) {
                        case "4G":
                        case "5G":
                            textColor = android.graphics.Color.rgb(0, 160, 0); // green
                            break;
                        case "3G":
                            textColor = android.graphics.Color.rgb(200, 160, 0); // yellow-ish
                            break;
                        default: // 2G, 0G, 1G, etc.
                            textColor = android.graphics.Color.RED;
                            break;
                    }

                    if (displaySlot == 0) {
                        tvSim1Signal.setText(signalText);
                        tvSim1Signal.setTextColor(textColor);
                        updateSim1SignalIndicator(dbm);
                    } else {
                        tvSim2Signal.setText(signalText);
                        tvSim2Signal.setTextColor(textColor);
                        updateSim2SignalIndicator(dbm);
                    }
                });
            }
        };

        listeners.add(listener);
        tmForSim.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        Log.d("MainActivity", "Listener set up for subId=" + subscriptionId + " slot=" + slotIndex + " display=" + displaySlot);
    }

    private void startListening() {
        if (subscriptionManager != null) {
            subscriptionManager.addOnSubscriptionsChangedListener(subscriptionsChangedListener);
        }
        refreshSimDetection();
    }

    private void stopListening() {
        if (subscriptionManager != null && subscriptionsChangedListener != null) {
            subscriptionManager.removeOnSubscriptionsChangedListener(subscriptionsChangedListener);
        }
        for (PhoneStateListener listener : listeners) {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
        }
        listeners.clear();
        sim1SubId = -1;
        sim2SubId = -1;
    }

    private void updateSim1SignalIndicator(int dbmValue) {
        updateSignalIndicator(sim1Marker, sim1DbmText, sim1GradientBar, dbmValue);
    }

    private void updateSim2SignalIndicator(int dbmValue) {
        updateSignalIndicator(sim2Marker, sim2DbmText, sim2GradientBar, dbmValue);
    }

    private void updateSignalIndicator(View marker, TextView dbmText, View gradientBar, int dbmValue) {
        if (dbmValue == -1) {
            dbmText.setText("-- dBm");
        } else {
            dbmText.setText(dbmValue + " dBm");
        }

        float position = calculateMarkerPosition(dbmValue);

        gradientBar.post(() -> {
            dbmText.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

            int barWidth = gradientBar.getWidth();
            int markerWidth = marker.getWidth();
            int textWidth = dbmText.getMeasuredWidth();

            if (barWidth <= 0 || markerWidth <= 0) return; // Not laid out yet

            int markerX = (int) (position * (barWidth - markerWidth));

            RelativeLayout.LayoutParams markerParams = (RelativeLayout.LayoutParams) marker.getLayoutParams();
            markerParams.leftMargin = markerX;
            marker.setLayoutParams(markerParams);

            int idealTextX = markerX + (markerWidth / 2) - (textWidth / 2);
            int minTextX = 0;
            int maxTextX = barWidth - textWidth;

            int finalTextX = Math.max(minTextX, Math.min(maxTextX, idealTextX));

            RelativeLayout.LayoutParams textParams = (RelativeLayout.LayoutParams) dbmText.getLayoutParams();
            textParams.leftMargin = finalTextX;
            dbmText.setLayoutParams(textParams);
        });
    }

    private float calculateMarkerPosition(int dbmValue) {
        if (dbmValue == -1) {
            return 0.0f;
        }
        int clampedDbm = Math.max(-120, Math.min(-50, dbmValue));
        return (clampedDbm + 120) / 70.0f;
    }

    private String getNetworkTypeForSim(TelephonyManager tm, int simSlotIndex) {
        try {
            int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                networkType = tm.getDataNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN ||
                        (isDualSim() && !isDataSim(tm))) {
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
            Log.e("NetworkType", "Error getting network type for SIM " + simSlotIndex, e);
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
            Log.e("NetworkType", "Error checking if data SIM", e);
        }
        return true;
    }

    private boolean isDualSim() {
        try {
            List<SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
            return subs != null && subs.size() > 1;
        } catch (Exception e) {
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private int getNetworkTypeFromServiceState(TelephonyManager tm) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return tm.getVoiceNetworkType();
            }
        } catch (Exception e) {
            Log.e("NetworkType", "Error getting network type from service state", e);
        }
        return TelephonyManager.NETWORK_TYPE_UNKNOWN;
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
            if (cdmaDbm != 0) return cdmaDbm;
        } catch (Exception ignored) {}

        try {
            int evdoDbm = signalStrength.getEvdoDbm();
            if (evdoDbm != 0) return evdoDbm;
        } catch (Exception ignored) {}

        return -1;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                startListening();
                startSignalService();
            } else {
                Toast.makeText(this, "Permission denied, cannot read SIM signal", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermissions()) {
            startSignalService();
            // Re-detect SIMs on resume (may have changed while in background)
            refreshSimDetection();
        }
    }

    private boolean hasPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startSignalService() {
        Intent serviceIntent = new Intent(this, SignalStrengthService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}