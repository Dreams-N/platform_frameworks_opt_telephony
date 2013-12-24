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

import android.os.ServiceManager;
import android.os.RemoteException;
import android.telephony.Rlog;

import com.android.internal.telephony.IccPhoneBookInterfaceManagerProxy;
import com.android.internal.telephony.IIccPhoneBook;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.uicc.AdnRecord;

import java.lang.ArrayIndexOutOfBoundsException;
import java.lang.NullPointerException;
import java.util.List;

public class MSimIccPhoneBookInterfaceManagerProxy extends IIccPhoneBook.Stub {
    private static final String TAG = "MSimIccPbkIntMgrProxy";
    private Phone[] mPhone;

    /* only one MSimIccPhonBookInterfaceManagerProxy exists */
    public MSimIccPhoneBookInterfaceManagerProxy(Phone[] phone) {
        if (ServiceManager.getService("simphonebook") == null) {
               ServiceManager.addService("simphonebook", this);
        }
        mPhone = phone;
    }

    public boolean
    updateAdnRecordsInEfBySearch (int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) throws android.os.RemoteException {
            return updateAdnRecordsInEfBySearchOnSubscription(efid, oldTag, oldPhoneNumber, newTag,
                newPhoneNumber, pin2, getDefaultSubscription());
    }

    public boolean
    updateAdnRecordsInEfBySearchOnSubscription(int efid, String oldTag, String oldPhoneNumber, String newTag,
            String newPhoneNumber, String pin2,
            int subscription) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subscription);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateAdnRecordsInEfBySearch(efid, oldTag,
                    oldPhoneNumber, newTag, newPhoneNumber, pin2);
        } else {
            Rlog.e(TAG,"updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:"+subscription);
            return false;
        }
    }

    public boolean
    updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) throws android.os.RemoteException {
            return updateAdnRecordsInEfByIndexOnSubscription(efid, newTag, newPhoneNumber,
                index, pin2, getDefaultSubscription());
    }

    public boolean
    updateAdnRecordsInEfByIndexOnSubscription(int efid, String newTag, String newPhoneNumber,
            int index, String pin2, int subscription) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subscription);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateAdnRecordsInEfByIndex(efid, newTag,
                    newPhoneNumber, index, pin2);
        } else {
            Rlog.e(TAG,"updateAdnRecordsInEfByIndex iccPbkIntMgrProxy is" +
                      " null for Subscription:"+subscription);
            return false;
        }
    }

    public int[] getAdnRecordsSize(int efid) throws android.os.RemoteException {
           return getAdnRecordsSizeOnSubscription(efid, getDefaultSubscription());
    }
    public int[]
    getAdnRecordsSizeOnSubscription(int efid, int subscription) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subscription);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getAdnRecordsSize(efid);
        } else {
            Rlog.e(TAG,"getAdnRecordsSize iccPbkIntMgrProxy is" +
                      " null for Subscription:"+subscription);
            return null;
        }
    }

    public List<AdnRecord> getAdnRecordsInEf(int efid) throws android.os.RemoteException {
           return getAdnRecordsInEfOnSubscription(efid, getDefaultSubscription());
    }

    public List<AdnRecord> getAdnRecordsInEfOnSubscription(int efid, int subscription)
           throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subscription);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getAdnRecordsInEf(efid);
        } else {
            Rlog.e(TAG,"getAdnRecordsInEf iccPbkIntMgrProxy is" +
                      "null for Subscription:"+subscription);
            return null;
        }
    }

    /**
     * get phone book interface manager proxy object based on subscription.
     **/
    private IccPhoneBookInterfaceManagerProxy
    getIccPhoneBookInterfaceManagerProxy(int subscription) {
        try {
            return ((MSimPhoneProxy)mPhone[subscription]).getIccPhoneBookInterfaceManagerProxy();
        } catch (NullPointerException e) {
            Rlog.e(TAG, "Exception is :"+e.toString()+" For subscription :"+subscription );
            e.printStackTrace(); //To print stack trace
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            Rlog.e(TAG, "Exception is :"+e.toString()+" For subscription :"+subscription );
            e.printStackTrace();
            return null;
        }
    }

    private int getDefaultSubscription() {
        return MSimPhoneFactory.getDefaultSubscription();
    }
}
