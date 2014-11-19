/*
* Copyright (C) 2014 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.telephony;

import static android.Manifest.permission.READ_PHONE_STATE;
import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.SpnOverride;
import android.provider.Telephony;
import android.content.ContentUris;
import android.net.Uri;
import android.database.Cursor;
import android.text.TextUtils;

import java.util.List;

/**
 *@hide
 */
public class SubscriptionInfoUpdater extends Handler {
    private static final String LOG_TAG = "SUB";
    private static final int PROJECT_SIM_NUM = TelephonyManager.getDefault().getPhoneCount();
    private static final int EVENT_OFFSET = 8;
    private static final int EVENT_QUERY_ICCID_DONE = 1;
    private static final String ICCID_STRING_FOR_NO_SIM = "";
    /**
     *  int[] sInsertSimState maintains all slots' SIM inserted status currently,
     *  it may contain 4 kinds of values:
     *    SIM_NOT_INSERT : no SIM inserted in slot i now
     *    SIM_CHANGED    : a valid SIM insert in slot i and is different SIM from last time
     *                     it will later become SIM_NEW or SIM_REPOSITION during update procedure
     *    SIM_NOT_CHANGE : a valid SIM insert in slot i and is the same SIM as last time
     *    SIM_NEW        : a valid SIM insert in slot i and is a new SIM
     *    SIM_REPOSITION : a valid SIM insert in slot i and is inserted in different slot last time
     *    positive integer #: index to distinguish SIM cards with the same IccId
     */
    public static final int SIM_NOT_CHANGE = 0;
    public static final int SIM_CHANGED    = -1;
    public static final int SIM_NEW        = -2;
    public static final int SIM_REPOSITION = -3;
    public static final int SIM_NOT_INSERT = -99;

    public static final int STATUS_NO_SIM_INSERTED = 0x00;
    public static final int STATUS_SIM1_INSERTED = 0x01;
    public static final int STATUS_SIM2_INSERTED = 0x02;
    public static final int STATUS_SIM3_INSERTED = 0x04;
    public static final int STATUS_SIM4_INSERTED = 0x08;

    // Key used to read/write the current IMSI. Updated on SIM_STATE_CHANGED - LOADED.
    public static final String CURR_IMSI = "curr_imsi";

    private static Phone[] sPhone;
    private static Context sContext = null;
    private static IccFileHandler[] sFh = new IccFileHandler[PROJECT_SIM_NUM];
    private static String sIccId[] = new String[PROJECT_SIM_NUM];
    private static int[] sInsertSimState = new int[PROJECT_SIM_NUM];
    private static TelephonyManager sTelephonyMgr = null;
    // To prevent repeatedly update flow every time receiver SIM_STATE_CHANGE
    private static boolean sNeedUpdate = true;

    public SubscriptionInfoUpdater(Context context, Phone[] phoneProxy, CommandsInterface[] ci) {
        logd("Constructor invoked");

        sContext = context;
        sPhone = phoneProxy;
        IntentFilter intentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        sContext.registerReceiver(sReceiver, intentFilter);
    }

    private static int encodeEventId(int event, int slotId) {
        return event << (slotId * EVENT_OFFSET);
    }

    private final BroadcastReceiver sReceiver = new  BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logd("[Receiver]+");
            String action = intent.getAction();
            int slotId;
            logd("Action: " + action);
            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY,
                        SubscriptionManager.INVALID_SLOT_ID);
                logd("slotId: " + slotId + " simStatus: " + simStatus);
                if (slotId == SubscriptionManager.INVALID_SLOT_ID) {
                    return;
                }
                if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(simStatus)
                        || IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(simStatus)) {
                    if (sIccId[slotId] != null && sIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
                        logd("SIM" + (slotId + 1) + " hot plug in");
                        sIccId[slotId] = null;
                        sNeedUpdate = true;
                    }
                    queryIccId(slotId);
                } else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simStatus)) {
                    queryIccId(slotId);
                    if (sTelephonyMgr == null) {
                        sTelephonyMgr = TelephonyManager.from(sContext);
                    }

                    int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                            SubscriptionManager.INVALID_SUB_ID);

                    if (SubscriptionManager.isValidSubId(subId)) {
                        String msisdn = TelephonyManager.getDefault().getLine1NumberForSubscriber(subId);
                        ContentResolver contentResolver = sContext.getContentResolver();

                        if (msisdn != null) {
                            ContentValues number = new ContentValues(1);
                            number.put(SubscriptionManager.NUMBER, msisdn);
                            contentResolver.update(SubscriptionManager.CONTENT_URI, number,
                                    SubscriptionManager._ID + "=" + Long.toString(subId), null);
                        }

                        SubscriptionInfo subInfo =
                                SubscriptionManager.getSubscriptionInfoForSubscriber(subId);

                        if (subInfo != null
                                && subInfo.getNameSource() !=
                                SubscriptionManager.NAME_SOURCE_USER_INPUT) {
                            String nameToSet;
                            String CarrierName =
                                    TelephonyManager.getDefault().getSimOperator(subId);
                            logd("CarrierName = " + CarrierName);
                            String simCarrierName =
                                    TelephonyManager.getDefault().getSimOperatorName(subId);

                            if (!TextUtils.isEmpty(simCarrierName)) {
                                nameToSet = simCarrierName;
                            } else {
                                nameToSet = "CARD " + Integer.toString(slotId + 1);
                            }
                            logd("sim name = " + nameToSet + " carrier name = " + simCarrierName);

                            ContentValues name = new ContentValues(1);
                            name.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                            name.put(SubscriptionManager.CARRIER_NAME,
                                    !TextUtils.isEmpty(simCarrierName) ? simCarrierName :
                                    sContext.getString(com.android.internal.R.string.unknownName));
                            contentResolver.update(SubscriptionManager.CONTENT_URI, name,
                                    SubscriptionManager._ID + "=" + Long.toString(subId), null);
                        }

                        /* Update preferred network type and network selection mode on IMSI change.
                         * Storing last IMSI in SharedPreference for now. Can consider making it
                         * part of subscription info db */
                        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                        String storedImsi = sp.getString(CURR_IMSI + slotId, "");
                        String newImsi = sPhone[slotId].getSubscriberId();

                        if (!storedImsi.equals(newImsi)) {
                            int networkType = RILConstants.PREFERRED_NETWORK_MODE;

                            // Set the modem network mode
                            sPhone[slotId].setPreferredNetworkType(networkType, null);
                            Settings.Global.putInt(sPhone[slotId].getContext().getContentResolver(),
                                    Settings.Global.PREFERRED_NETWORK_MODE, networkType);

                            // Only support automatic selection mode on IMSI change
                            sPhone[slotId].setNetworkSelectionModeAutomatic(null);

                            // Update stored IMSI
                            SharedPreferences.Editor editor = sp.edit();
                            editor.putString(CURR_IMSI + slotId, newImsi);
                            editor.apply();
                        }
                    } else {
                        logd("[Receiver] Invalid subId, could not update ContentResolver");
                    }
                } else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simStatus)) {
                    if (sIccId[slotId] != null && !sIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
                        logd("SIM" + (slotId + 1) + " hot plug out");
                        sNeedUpdate = true;
                    }
                    sFh[slotId] = null;
                    sIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
                    if (isAllIccIdQueryDone() && sNeedUpdate) {
                        updateSubscriptionInfoByIccId();
                    }
                }
            }
            logd("[Receiver]-");
        }
    };

    private boolean isAllIccIdQueryDone() {
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sIccId[i] == null) {
                logd("Wait for SIM" + (i + 1) + " IccId");
                return false;
            }
        }
        logd("All IccIds query complete");

        return true;
    }

    public static void setDisplayNameForNewSub(String newSubName, int subId, int newNameSource) {
        SubscriptionInfo subInfo = SubscriptionManager.getSubscriptionInfoForSubscriber(subId);
        if (subInfo != null) {
            // overwrite SIM display name if it is not assigned by user
            int oldNameSource = subInfo.getNameSource();
            CharSequence oldSubName = subInfo.getDisplayName();
            logd("[setDisplayNameForNewSub] subId = " + subInfo.getSubscriptionId()
                    + ", oldSimName = " + oldSubName + ", oldNameSource = " + oldNameSource
                    + ", newSubName = " + newSubName + ", newNameSource = " + newNameSource);
            if (oldSubName == null ||
                (oldNameSource == SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE && newSubName != null) ||
                (oldNameSource == SubscriptionManager.NAME_SOURCE_SIM_SOURCE && newSubName != null
                        && !newSubName.equals(oldSubName))) {
                SubscriptionManager.setDisplayName(newSubName, subInfo.getSubscriptionId(),
                        newNameSource);
            }
        } else {
            logd("SUB" + (subId + 1) + " SubInfo not created yet");
        }
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult)msg.obj;
        int msgNum = msg.what;
        int slotId;
        for (slotId = PhoneConstants.SUB1; slotId <= PhoneConstants.SUB3; slotId++) {
            int pivot = 1 << (slotId * EVENT_OFFSET);
            if (msgNum >= pivot) {
                continue;
            } else {
                break;
            }
        }
        slotId--;
        int event = msgNum >> (slotId * EVENT_OFFSET);
        switch (event) {
            case EVENT_QUERY_ICCID_DONE:
                logd("handleMessage : <EVENT_QUERY_ICCID_DONE> SIM" + (slotId + 1));
                if (ar.exception == null) {
                    if (ar.result != null) {
                        byte[] data = (byte[])ar.result;
                        sIccId[slotId] = IccUtils.bcdToString(data, 0, data.length);
                    } else {
                        logd("Null ar");
                        sIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
                    }
                } else {
                    sIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
                    logd("Query IccId fail: " + ar.exception);
                }
                logd("sIccId[" + slotId + "] = " + sIccId[slotId]);
                if (isAllIccIdQueryDone() && sNeedUpdate) {
                    updateSubscriptionInfoByIccId();
                }
                break;
            default:
                logd("Unknown msg:" + msg.what);
        }
    }

    private void queryIccId(int slotId) {
        logd("queryIccId: slotid=" + slotId);
        if (sFh[slotId] == null) {
            logd("Getting IccFileHandler");
            sFh[slotId] = ((PhoneProxy)sPhone[slotId]).getIccFileHandler();
        }
        if (sFh[slotId] != null) {
            String iccId = sIccId[slotId];
            if (iccId == null) {
                logd("Querying IccId");
                sFh[slotId].loadEFTransparent(IccConstants.EF_ICCID, obtainMessage(encodeEventId(EVENT_QUERY_ICCID_DONE, slotId)));
            } else {
                logd("NOT Querying IccId its already set sIccid[" + slotId + "]=" + iccId);
            }
        } else {
            logd("sFh[" + slotId + "] is null, ignore");
        }
    }

    /**
     * TODO: Simplify more, as no one is interested in what happened
     * only what the current list contains.
     */
    synchronized private void updateSubscriptionInfoByIccId() {
        logd("updateSubscriptionInfoByIccId:+ Start");
        sNeedUpdate = false;

        SubscriptionManager.clearSubscriptionInfo();

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sInsertSimState[i] = SIM_NOT_CHANGE;
        }

        int insertedSimCount = PROJECT_SIM_NUM;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (ICCID_STRING_FOR_NO_SIM.equals(sIccId[i])) {
                insertedSimCount--;
                sInsertSimState[i] = SIM_NOT_INSERT;
            }
        }
        logd("insertedSimCount = " + insertedSimCount);

        int index = 0;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sInsertSimState[i] == SIM_NOT_INSERT) {
                continue;
            }
            index = 2;
            for (int j = i + 1; j < PROJECT_SIM_NUM; j++) {
                if (sInsertSimState[j] == SIM_NOT_CHANGE && sIccId[i].equals(sIccId[j])) {
                    sInsertSimState[i] = 1;
                    sInsertSimState[j] = index;
                    index++;
                }
            }
        }

        ContentResolver contentResolver = sContext.getContentResolver();
        String[] oldIccId = new String[PROJECT_SIM_NUM];
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            oldIccId[i] = null;
            List<SubscriptionInfo> oldSubInfo =
                    SubscriptionController.getInstance().getSubInfoUsingSlotIdWithCheck(i, false);
            if (oldSubInfo != null) {
                oldIccId[i] = oldSubInfo.get(0).getIccId();
                logd("updateSubscriptionInfoByIccId: oldSubId = "
                        + oldSubInfo.get(0).getSubscriptionId());
                if (sInsertSimState[i] == SIM_NOT_CHANGE && !sIccId[i].equals(oldIccId[i])) {
                    sInsertSimState[i] = SIM_CHANGED;
                }
                if (sInsertSimState[i] != SIM_NOT_CHANGE) {
                    ContentValues value = new ContentValues(1);
                    value.put(SubscriptionManager.SIM_ID, SubscriptionManager.INVALID_SLOT_ID);
                    contentResolver.update(SubscriptionManager.CONTENT_URI, value,
                            SubscriptionManager._ID + "="
                            + Integer.toString(oldSubInfo.get(0).getSubscriptionId()), null);
                }
            } else {
                if (sInsertSimState[i] == SIM_NOT_CHANGE) {
                    // no SIM inserted last time, but there is one SIM inserted now
                    sInsertSimState[i] = SIM_CHANGED;
                }
                oldIccId[i] = ICCID_STRING_FOR_NO_SIM;
                logd("updateSubscriptionInfoByIccId: No SIM in slot " + i + " last time");
            }
        }

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            logd("updateSubscriptionInfoByIccId: oldIccId[" + i + "] = " + oldIccId[i] +
                    ", sIccId[" + i + "] = " + sIccId[i]);
        }

        //check if the inserted SIM is new SIM
        int nNewCardCount = 0;
        int nNewSimStatus = 0;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sInsertSimState[i] == SIM_NOT_INSERT) {
                logd("updateSubscriptionInfoByIccId: No SIM inserted in slot " + i + " this time");
            } else {
                if (sInsertSimState[i] > 0) {
                    //some special SIMs may have the same IccIds, add suffix to distinguish them
                    //FIXME: addSubInfoRecord can return an error.
                    SubscriptionManager.addSubscriptionInfoRecord(sIccId[i]
                            + Integer.toString(sInsertSimState[i]), i);
                    logd("SUB" + (i + 1) + " has invalid IccId");
                } else /*if (sInsertSimState[i] != SIM_NOT_INSERT)*/ {
                    SubscriptionManager.addSubscriptionInfoRecord(sIccId[i], i);
                }
                if (isNewSim(sIccId[i], oldIccId)) {
                    nNewCardCount++;
                    switch (i) {
                        case PhoneConstants.SUB1:
                            nNewSimStatus |= STATUS_SIM1_INSERTED;
                            break;
                        case PhoneConstants.SUB2:
                            nNewSimStatus |= STATUS_SIM2_INSERTED;
                            break;
                        case PhoneConstants.SUB3:
                            nNewSimStatus |= STATUS_SIM3_INSERTED;
                            break;
                        //case PhoneConstants.SUB3:
                        //    nNewSimStatus |= STATUS_SIM4_INSERTED;
                        //    break;
                    }

                    sInsertSimState[i] = SIM_NEW;
                }
            }
        }

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sInsertSimState[i] == SIM_CHANGED) {
                sInsertSimState[i] = SIM_REPOSITION;
            }
            logd("updateSubscriptionInfoByIccId: sInsertSimState[" + i + "] = "
                    + sInsertSimState[i]);
        }

        List<SubscriptionInfo> subInfos = SubscriptionManager.getActiveSubscriptionInfoList();
        int nSubCount = (subInfos == null) ? 0 : subInfos.size();
        logd("updateSubscriptionInfoByIccId: nSubCount = " + nSubCount);
        for (int i=0; i<nSubCount; i++) {
            SubscriptionInfo temp = subInfos.get(i);

            String msisdn = TelephonyManager.getDefault().getLine1NumberForSubscriber(
                    temp.getSubscriptionId());

            if (msisdn != null) {
                ContentValues value = new ContentValues(1);
                value.put(SubscriptionManager.NUMBER, msisdn);
                contentResolver.update(SubscriptionManager.CONTENT_URI, value,
                        SubscriptionManager._ID + "="
                        + Integer.toString(temp.getSubscriptionId()), null);
            }
        }

        SubscriptionController.getInstance().notifySubscriptionInfoChanged();
        logd("updateSubscriptionInfoByIccId:- SsubscriptionInfo update complete");
    }

    private static boolean isNewSim(String iccId, String[] oldIccId) {
        boolean newSim = true;
        for(int i = 0; i < PROJECT_SIM_NUM; i++) {
            if(iccId.equals(oldIccId[i])) {
                newSim = false;
                break;
            }
        }
        logd("newSim = " + newSim);

        return newSim;
    }

    public void dispose() {
        logd("[dispose]");
        sContext.unregisterReceiver(sReceiver);
    }

    private static void logd(String message) {
        Rlog.d(LOG_TAG, "[SubscriptionInfoUpdater]" + message);
    }
}
