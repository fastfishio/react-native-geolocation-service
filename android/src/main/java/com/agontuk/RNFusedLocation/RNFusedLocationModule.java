package com.agontuk.RNFusedLocation;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.location.Location;
import android.util.Log;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.SystemClock;
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter;

import com.huawei.hms.common.ApiException;
import com.huawei.hms.common.ResolvableApiException;
import com.huawei.hms.location.FusedLocationProviderClient;
import com.huawei.hms.location.LocationCallback;
import com.huawei.hms.location.LocationRequest;
import com.huawei.hms.location.LocationResult;
import com.huawei.hms.location.LocationServices;
import com.huawei.hms.location.LocationSettingsRequest;
import com.huawei.hms.location.LocationSettingsResponse;
import com.huawei.hms.location.LocationSettingsStatusCodes;
import com.huawei.hms.location.SettingsClient;
import com.huawei.hmf.tasks.Task;
import com.huawei.hmf.tasks.OnCompleteListener;


import java.lang.RuntimeException;

public class RNFusedLocationModule extends ReactContextBaseJavaModule {
    public static final String TAG = "RNFusedLocation";
    private static final int REQUEST_SETTINGS_SINGLE_UPDATE = 11403;
    private static final int REQUEST_SETTINGS_CONTINUOUS_UPDATE = 11404;
    private static final float DEFAULT_DISTANCE_FILTER = 100;
    private static final int DEFAULT_ACCURACY = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
    private static final long DEFAULT_INTERVAL = 10 * 1000;  /* 10 secs */;
    private static final long DEFAULT_FASTEST_INTERVAL = 5 * 1000; /* 5 sec */;

    private boolean mShowLocationDialog = true;
    private boolean mForceRequestLocation = false;
    private int mLocationPriority = DEFAULT_ACCURACY;
    private long mUpdateInterval = DEFAULT_INTERVAL;
    private long mFastestInterval = DEFAULT_FASTEST_INTERVAL;
    private double mMaximumAge = Double.POSITIVE_INFINITY;
    private long mTimeout = Long.MAX_VALUE;
    private float mDistanceFilter = DEFAULT_DISTANCE_FILTER;

    private Callback mSuccessCallback;
    private Callback mErrorCallback;
    private FusedLocationProviderClient mFusedProviderClient;
    private SettingsClient mSettingsClient;
    private LocationRequest mLocationRequest;
    private LocationCallback mLocationCallback;

    private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
        @Override
        public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
            if (requestCode == REQUEST_SETTINGS_SINGLE_UPDATE) {
                if (resultCode == Activity.RESULT_OK) {
                    // Location settings changed successfully, request user location.
                    getUserLocation();
                    return;
                }

                if (!mForceRequestLocation) {
                    invokeError(
                        LocationError.SETTINGS_NOT_SATISFIED.getValue(),
                        "Location settings are not satisfied.",
                        true
                    );
                    return;
                }

                if (!LocationUtils.isLocationEnabled(getContext())) {
                    invokeError(
                        LocationError.POSITION_UNAVAILABLE.getValue(),
                        "No location provider available.",
                        true
                    );
                    return;
                }

                getUserLocation();
            } else if (requestCode == REQUEST_SETTINGS_CONTINUOUS_UPDATE) {
                if (resultCode == Activity.RESULT_OK ) {
                    // Location settings changed successfully, request user location.
                    getLocationUpdates();
                    return;
                }

                if (!mForceRequestLocation) {
                    invokeError(
                        LocationError.SETTINGS_NOT_SATISFIED.getValue(),
                        "Location settings are not satisfied.",
                        false
                    );
                    return;
                }

                if (!LocationUtils.isLocationEnabled(getContext())) {
                    invokeError(
                        LocationError.POSITION_UNAVAILABLE.getValue(),
                        "No location provider available.",
                        false
                    );
                    return;
                }

                getLocationUpdates();
            }
        }
    };

    public RNFusedLocationModule(ReactApplicationContext reactContext) {
        super(reactContext);

        mFusedProviderClient = LocationServices.getFusedLocationProviderClient(reactContext);
        mSettingsClient = LocationServices.getSettingsClient(reactContext);
        reactContext.addActivityEventListener(mActivityEventListener);

        Log.i(TAG, TAG + " initialized");
    }

    @Override
    public String getName() {
        return TAG;
    }

    /**
     * Get the current position. This can return almost immediately if the location is cached or
     * request an update, which might take a while.
     *
     * @param options map containing optional arguments: timeout (millis), maximumAge (millis),
     *        highAccuracy (boolean), distanceFilter (double) and showLocationDialog (boolean)
     * @param success success callback
     * @param error error callback
     */
    @ReactMethod
    public void getCurrentPosition(ReadableMap options, final Callback success, final Callback error) {
        ReactApplicationContext context = getContext();

        mSuccessCallback = success;
        mErrorCallback = error;

        if (!LocationUtils.hasLocationPermission(context)) {
            invokeError(
                LocationError.PERMISSION_DENIED.getValue(),
                "Location permission not granted.",
                true
            );
            return;
        }

        if (!LocationUtils.isGooglePlayServicesAvailable(context)) {
            invokeError(
                LocationError.PLAY_SERVICE_NOT_AVAILABLE.getValue(),
                "Google play service is not available.",
                true
            );
            return;
        }

        boolean highAccuracy = options.hasKey("enableHighAccuracy") &&
            options.getBoolean("enableHighAccuracy");

        // TODO: Make other PRIORITY_* constants availabe to the user
        mLocationPriority = highAccuracy ? LocationRequest.PRIORITY_HIGH_ACCURACY : DEFAULT_ACCURACY;

        mTimeout = options.hasKey("timeout") ? (long) options.getDouble("timeout") : Long.MAX_VALUE;
        mMaximumAge = options.hasKey("maximumAge")
            ? options.getDouble("maximumAge")
            : Double.POSITIVE_INFINITY;
        mDistanceFilter = options.hasKey("distanceFilter")
            ? (float) options.getDouble("distanceFilter")
            : 0;
        mShowLocationDialog = options.hasKey("showLocationDialog")
            ? options.getBoolean("showLocationDialog")
            : true;
        mForceRequestLocation = options.hasKey("forceRequestLocation")
            ? options.getBoolean("forceRequestLocation")
            : false;

        LocationSettingsRequest locationSettingsRequest = buildLocationSettingsRequest();

        if (mSettingsClient != null) {
            mSettingsClient.checkLocationSettings(locationSettingsRequest)
                .addOnCompleteListener(new OnCompleteListener<LocationSettingsResponse>() {
                    @Override
                    public void onComplete(Task<LocationSettingsResponse> task) {
                        onLocationSettingsResponse(task, true);
                    }
                });
        }
    }

    /**
     * Start listening for location updates. These will be emitted via the
     * {@link RCTDeviceEventEmitter} as {@code geolocationDidChange} events.
     *
     * @param options map containing optional arguments: highAccuracy (boolean), distanceFilter (double),
     *        interval (millis), fastestInterval (millis)
     */
    @ReactMethod
    public void startObserving(ReadableMap options) {
        ReactApplicationContext context = getContext();

        if (!LocationUtils.hasLocationPermission(context)) {
            invokeError(
                LocationError.PERMISSION_DENIED.getValue(),
                "Location permission not granted.",
                false
            );
            return;
        }

        if (!LocationUtils.isLocationEnabled(context)) {
            invokeError(
                LocationError.POSITION_UNAVAILABLE.getValue(),
                "No location provider available.",
                false
            );
            return;
        }

        if (!LocationUtils.isGooglePlayServicesAvailable(context)) {
            invokeError(
                LocationError.PLAY_SERVICE_NOT_AVAILABLE.getValue(),
                "Google play service is not available.",
                false
            );
            return;
        }

        boolean highAccuracy = options.hasKey("enableHighAccuracy")
            && options.getBoolean("enableHighAccuracy");

        // TODO: Make other PRIORITY_* constants availabe to the user
        mLocationPriority = highAccuracy ? LocationRequest.PRIORITY_HIGH_ACCURACY : DEFAULT_ACCURACY;
        mDistanceFilter = options.hasKey("distanceFilter")
            ? (float) options.getDouble("distanceFilter")
            : DEFAULT_DISTANCE_FILTER;
        mUpdateInterval = options.hasKey("interval")
            ? (long) options.getDouble("interval")
            : DEFAULT_INTERVAL;
        mFastestInterval = options.hasKey("fastestInterval")
            ? (long) options.getDouble("fastestInterval")
            : DEFAULT_INTERVAL;
        mShowLocationDialog = options.hasKey("showLocationDialog")
            ? options.getBoolean("showLocationDialog")
            : true;
        mForceRequestLocation = options.hasKey("forceRequestLocation")
            ? options.getBoolean("forceRequestLocation")
            : false;

        LocationSettingsRequest locationSettingsRequest = buildLocationSettingsRequest();

        if (mSettingsClient != null) {
            mSettingsClient.checkLocationSettings(locationSettingsRequest)
                .addOnCompleteListener(new OnCompleteListener<LocationSettingsResponse>() {
                    @Override
                    public void onComplete(Task<LocationSettingsResponse> task) {
                        onLocationSettingsResponse(task, false);
                    }
                });
        }
    }

    /**
     * Stop listening for location updates.
     */
    @ReactMethod
    public void stopObserving() {
        if (mFusedProviderClient != null && mLocationCallback != null) {
            mFusedProviderClient.removeLocationUpdates(mLocationCallback);
            mLocationCallback = null;
        }
    }

    /**
     * Build location setting request using current configuration
     */
    private LocationSettingsRequest buildLocationSettingsRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setPriority(mLocationPriority)
            .setInterval(mUpdateInterval)
            .setFastestInterval(mFastestInterval)
            .setSmallestDisplacement(mDistanceFilter);

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
        builder.addLocationRequest(mLocationRequest);
        LocationSettingsRequest locationSettingsRequest = builder.build();

        return locationSettingsRequest;
    }

    /**
     * Check location setting response and decide whether to proceed with
     * location request or not.
     */
     private void onLocationSettingsResponse(
           Task<LocationSettingsResponse> task,
           boolean isSingleUpdate
       ) {
           try {
               LocationSettingsResponse response = task.getResult();
               // All location settings are satisfied, start location request.
               if (isSingleUpdate) {
                   getUserLocation();
               } else {
                   getLocationUpdates();
               }
           } catch (Exception exception) {
               invokeError(
                       LocationError.SETTINGS_NOT_SATISFIED.getValue(),
                       "Location settings are not satisfied.",
                       isSingleUpdate
               );

           }
       }
    /**
     * Get last known location if it exists, otherwise request a new update.
     */
    private void getUserLocation() {
        if (mFusedProviderClient != null) {
            mFusedProviderClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
                @Override
                public void onComplete(Task<Location> task) {
                    Location location = null;

                    try {
                        location = task.getResult();
                    } catch (Exception exception) {
                        Log.w(TAG, "getLastLocation error: " + exception.getMessage());
                    }

                    if (location != null &&
                            (SystemClock.currentTimeMillis() - location.getTime()) < mMaximumAge) {
                        invokeSuccess(LocationUtils.locationToMap(location), true);
                        return;
                    }

                    // Last location is not available, request location update.
                    new SingleLocationUpdate(
                        mFusedProviderClient,
                        mLocationRequest,
                        mTimeout,
                        mSuccessCallback,
                        mErrorCallback
                    ).getLocation();
                }
            });
        }
    }

    /**
     * Get periodic location updates based on the current location request.
     */
    private void getLocationUpdates() {
        if (mFusedProviderClient != null && mLocationRequest != null) {
            mLocationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    Location location = locationResult.getLastLocation();
                    invokeSuccess(LocationUtils.locationToMap(location), false);
                }
            };

            mFusedProviderClient.requestLocationUpdates(mLocationRequest, mLocationCallback, null);
        }
    }

    /**
     * Get react context
     */
    private ReactApplicationContext getContext() {
        return getReactApplicationContext();
    }

    /**
     * Clear the JS callbacks
     */
    private void clearCallbacks() {
        mSuccessCallback = null;
        mErrorCallback = null;
    }

    /**
     * Helper method to invoke success callback
     */
    private void invokeSuccess(WritableMap data, boolean isSingleUpdate) {
        if (!isSingleUpdate) {
            getContext().getJSModule(RCTDeviceEventEmitter.class)
                .emit("geolocationDidChange", data);

            return;
        }

        try {
            if (mSuccessCallback != null) {
                mSuccessCallback.invoke(data);
            }

            clearCallbacks();
        } catch (RuntimeException e) {
            // Illegal callback invocation
            Log.w(TAG, e.getMessage());
        }
    }

    /**
     * Helper method to invoke error callback
     */
    private void invokeError(int code, String message, boolean isSingleUpdate) {
        if (!isSingleUpdate) {
            getContext().getJSModule(RCTDeviceEventEmitter.class)
                .emit("geolocationError", LocationUtils.buildError(code, message));

            return;
        }

        try {
            if (mErrorCallback != null) {
                mErrorCallback.invoke(LocationUtils.buildError(code, message));
            }

            clearCallbacks();
        } catch (RuntimeException e) {
            // Illegal callback invocation
            Log.w(TAG, e.getMessage());
        }
    }
}
