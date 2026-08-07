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
    private List<PhoneStateListener> listeners = new ArrayList<>();

    private String sim1SignalText = "N/A";
    private String sim2SignalText = "N/A";
    private int sim1Dbm = -1;
    private int sim2Dbm = -1;
    private String sim1NetworkType = "0G";
    private String sim2NetworkType = "0G";
    private String sim1CarrierName = "";
    private String sim2CarrierName = "";
    private boolean sim1Available = false;
    private boolean sim2Available = false;

    private SharedPreferences sharedPreferences;

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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        } else {
            startListening();
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
        autostartCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putBoolean(AUTO_START_KEY, isChecked);
                editor.apply();
            }
        });

        // Set radio group change listener for text color
        textColorRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                boolean isBlackSelected = (checkedId == R.id.radioBlack);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putBoolean(RADIO_CHOSEN_BLACK_KEY, isBlackSelected);
                editor.apply();
            }
        });

        // Exit button to kill notification service, activity, and fully exit the app
        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Stop the notification service
                Intent serviceIntent = new Intent(MainActivity.this, SignalStrengthService.class);
                stopService(serviceIntent);

                // Finish the activity
                finish();

                // Fully exit the app
                System.exit(0);
            }
        });

        // Initially hide SIM2 section until we know it's available
        hideSim2Section();
    }

    private void hideSim2Section() {
        findViewById(R.id.sim2OperatorLabel).setVisibility(View.GONE);
        sim2OperatorName.setVisibility(View.GONE);
        tvSim2Signal.setVisibility(View.GONE);
        findViewById(R.id.sim2IndicatorContainer).setVisibility(View.GONE);
    }

    private void showSim2Section() {
        findViewById(R.id.sim2OperatorLabel).setVisibility(View.VISIBLE);
        sim2OperatorName.setVisibility(View.VISIBLE);
        tvSim2Signal.setVisibility(View.VISIBLE);
        findViewById(R.id.sim2IndicatorContainer).setVisibility(View.VISIBLE);
    }

    private void startListening() {
        List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionInfoList == null || subscriptionInfoList.isEmpty()) {
            Toast.makeText(this, "No active SIM cards found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Reset availability flags
        sim1Available = false;
        sim2Available = false;

        for (SubscriptionInfo subscriptionInfo : subscriptionInfoList) {
            int subscriptionId = subscriptionInfo.getSubscriptionId();
            int simSlotIndex = subscriptionInfo.getSimSlotIndex(); // 0 for SIM1, 1 for SIM2
            TelephonyManager tmForSim = telephonyManager.createForSubscriptionId(subscriptionId);

            // Get carrier/operator name
            CharSequence carrierName = subscriptionInfo.getCarrierName();
            String operatorName = (carrierName != null && carrierName.length() > 0)
                    ? carrierName.toString()
                    : tmForSim.getSimOperatorName();

            // Mark SIM as available and set operator name
            if (simSlotIndex == 0) {
                sim1Available = true;
                sim1CarrierName = operatorName;
                runOnUiThread(() -> sim1OperatorName.setText(operatorName));
            } else if (simSlotIndex == 1) {
                sim2Available = true;
                sim2CarrierName = operatorName;
                runOnUiThread(() -> {
                    sim2OperatorName.setText(operatorName);
                    showSim2Section();
                });
            }

            PhoneStateListener listener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
                    super.onSignalStrengthsChanged(signalStrength);

                    int dbm;
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) { // Android 8 and earlier
                        dbm = getDbmFromSignalStrength(signalStrength);
                    } else {
                        dbm = extractDbm(signalStrength);
                    }

                    // Use the improved network type detection
                    String networkType = getNetworkTypeForSim(tmForSim, simSlotIndex);

                    runOnUiThread(() -> {
                        String displayText = (dbm == -1 ? "N/A" : dbm + " dBm " + networkType);
                        String signalText = "📶 " + (dbm == -1 ? "-0 dBm 0G" : dbm + " dBm " + networkType);

                        if (simSlotIndex == 0) {
                            tvSim1Signal.setText(signalText);
                            sim1SignalText = signalText;
                            sim1Dbm = dbm;
                            sim1NetworkType = networkType;
                            updateSim1SignalIndicator(dbm);
                        } else if (simSlotIndex == 1) {
                            tvSim2Signal.setText(signalText);
                            sim2SignalText = signalText;
                            sim2Dbm = dbm;
                            sim2NetworkType = networkType;
                            updateSim2SignalIndicator(dbm);
                        }
                    });
                }
            };

            listeners.add(listener); // Store listener to prevent garbage collection
            tmForSim.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        }
    }

    private void updateSim1SignalIndicator(int dbmValue) {
        updateSignalIndicator(sim1Marker, sim1DbmText, sim1GradientBar, dbmValue);
    }

    private void updateSim2SignalIndicator(int dbmValue) {
        updateSignalIndicator(sim2Marker, sim2DbmText, sim2GradientBar, dbmValue);
    }

    private void updateSignalIndicator(View marker, TextView dbmText, View gradientBar, int dbmValue) {
        // Update dBm text display
        if (dbmValue == -1) {
            dbmText.setText("-- dBm");
        } else {
            dbmText.setText(dbmValue + " dBm");
        }

        // Calculate position on gradient bar (-120 dBm = left, -50 dBm = right)
        float position = calculateMarkerPosition(dbmValue);

        // Update marker position
        gradientBar.post(() -> {
            dbmText.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

            int barWidth = gradientBar.getWidth();
            int markerWidth = marker.getWidth();
            int textWidth = dbmText.getMeasuredWidth();

            // Calculate the actual position considering marker width
            int markerX = (int) (position * (barWidth - markerWidth));

            // Set marker position
            RelativeLayout.LayoutParams markerParams = (RelativeLayout.LayoutParams) marker.getLayoutParams();
            markerParams.leftMargin = markerX;
            marker.setLayoutParams(markerParams);

            // Calculate text position to keep it centered under marker but within bounds
            int idealTextX = markerX + (markerWidth / 2) - (textWidth / 2);
            int minTextX = 0;
            int maxTextX = barWidth - textWidth;

            // Clamp text position within bounds
            int finalTextX = Math.max(minTextX, Math.min(maxTextX, idealTextX));

            RelativeLayout.LayoutParams textParams = (RelativeLayout.LayoutParams) dbmText.getLayoutParams();
            textParams.leftMargin = finalTextX;
            dbmText.setLayoutParams(textParams);
        });
    }

    private float calculateMarkerPosition(int dbmValue) {
        if (dbmValue == -1) {
            return 0.0f; // Default to left position for invalid values
        }

        // Clamp dBm value between -120 and -50
        int clampedDbm = Math.max(-120, Math.min(-50, dbmValue));

        // Calculate position as percentage (0.0 = left, 1.0 = right)
        // -120 dBm = 0.0 (left), -50 dBm = 1.0 (right)
        float position = (clampedDbm + 120) / 70.0f;

        return position;
    }

    private String getNetworkType(TelephonyManager tm) {
        try {
            int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                networkType = tm.getDataNetworkType();

                // If data network type is unknown, try voice network type as fallback
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    networkType = tm.getVoiceNetworkType();
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ (API 24+)
                networkType = tm.getDataNetworkType();

                // If data network type is unknown, try the legacy getNetworkType
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    networkType = tm.getNetworkType();
                }
            } else {
                // Android 6 and below
                networkType = tm.getNetworkType();
            }

            return getNetworkTypeString(networkType);

        } catch (Exception e) {
            Log.e("NetworkType", "Error getting network type", e);
            return "0G";
        }
    }

    private String getNetworkTypeForSim(TelephonyManager tm, int simSlotIndex) {
        try {
            int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Try to get the most accurate network type for this SIM
                networkType = tm.getDataNetworkType();

                // For dual SIM, if this SIM is not the data SIM, try voice network type
                if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN ||
                        (isDualSim() && !isDataSim(tm))) {
                    networkType = tm.getVoiceNetworkType();
                }

                // If still unknown, try service state
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

    // Helper method to check if this TelephonyManager instance is for the data SIM
    private boolean isDataSim(TelephonyManager tm) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager subManager = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();

                // Get the subscription ID for this TelephonyManager instance
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return tm.getSubscriptionId() == dataSubId;
                }
            }
        } catch (Exception e) {
            Log.e("NetworkType", "Error checking if data SIM", e);
        }
        return true; // Default to true if we can't determine
    }

    // Helper method to check if device has dual SIM
    private boolean isDualSim() {
        try {
            List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
            return subscriptionInfoList != null && subscriptionInfoList.size() > 1;
        } catch (Exception e) {
            return false;
        }
    }

    // Helper method to get network type from service state (Android 11+)
    @TargetApi(Build.VERSION_CODES.R)
    private int getNetworkTypeFromServiceState(TelephonyManager tm) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // This requires additional permissions and might not work on all devices
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

    // For Android 9+ (API 28 and above)
    private int extractDbm(SignalStrength signalStrength) {
        try {
            List<CellSignalStrength> strengths = signalStrength.getCellSignalStrengths();
            if (strengths != null && !strengths.isEmpty()) {
                return strengths.get(0).getDbm(); // Use the first signal
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    // For Android 8 and below
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
    protected void onDestroy() {
        super.onDestroy();
        // Clean up listeners when activity is destroyed
        for (PhoneStateListener listener : listeners) {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
        }
        listeners.clear();
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
        // Start the notification service when app becomes active
        if (hasPermissions()) {
            startSignalService();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep service running when app goes to background
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