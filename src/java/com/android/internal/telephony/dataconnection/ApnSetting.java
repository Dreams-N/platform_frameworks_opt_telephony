/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 *
 * Not a Contribution.
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

package com.android.internal.telephony.dataconnection;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;

/**
 * This class represents a apn setting for create PDP link
 */
public class ApnSetting {

    static final String V2_FORMAT_REGEX = "^\\[ApnSettingV2\\]\\s*";

    public ApnSetting(int id, String numeric, String carrier, String apn,
            String proxy, String port,
            String mmsc, String mmsProxy, String mmsPort,
            String user, String password, int authType, String[] types,
            String protocol, String roamingProtocol, boolean carrierEnabled, int bearer) {
        super(id, numeric, apn, user, password, authType,
                types, protocol, roamingProtocol, bearer,
                carrier, proxy, port, mmsc, mmsProxy, mmsPort,
                carrierEnabled);
    }

    /**
     * Creates an ApnSetting object from a string.
     *
     * @param data the string to read.
     *
     * The string must be in one of two formats (newlines added for clarity,
     * spaces are optional):
     *
     * v1 format:
     *   <carrier>, <apn>, <proxy>, <port>, <mmsc>, <mmsproxy>,
     *   <mmsport>, <user>, <password>, <authtype>, <mcc>,<mnc>,
     *   <type>[, <type>...]
     *
     * v2 format:
     *   [ApnSettingV2] <carrier>, <apn>, <proxy>, <port>, <mmsc>, <mmsproxy>,
     *   <mmsport>, <user>, <password>, <authtype>, <mcc>, <mnc>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearer>
     *
     * Note that the strings generated by toString() do not contain the username
     * and password and thus cannot be read by this method.
     */
    public static ApnSetting fromString(String data) {
        if (data == null) return null;

        int version;
        // matches() operates on the whole string, so append .* to the regex.
        if (data.matches(V2_FORMAT_REGEX + ".*")) {
            version = 2;
            data = data.replaceFirst(V2_FORMAT_REGEX, "");
        } else {
            version = 1;
        }

        String[] a = data.split("\\s*,\\s*");
        if (a.length < 14) {
            return null;
        }

        int authType;
        try {
            authType = Integer.parseInt(a[12]);
        } catch (Exception e) {
            authType = 0;
        }

        String[] typeArray;
        String protocol, roamingProtocol;
        boolean carrierEnabled;
        int bearer;
        if (version == 1) {
            typeArray = new String[a.length - 13];
            System.arraycopy(a, 13, typeArray, 0, a.length - 13);
            protocol = RILConstants.SETUP_DATA_PROTOCOL_IP;
            roamingProtocol = RILConstants.SETUP_DATA_PROTOCOL_IP;
            carrierEnabled = true;
            bearer = 0;
        } else {
            if (a.length < 18) {
                return null;
            }
            typeArray = a[13].split("\\s*\\|\\s*");
            protocol = a[14];
            roamingProtocol = a[15];
            try {
                carrierEnabled = Boolean.parseBoolean(a[16]);
            } catch (Exception e) {
                carrierEnabled = true;
            }
            bearer = Integer.parseInt(a[17]);
        }

        return new ApnSetting(-1,a[10]+a[11],a[0],a[1],a[2],a[3],a[7],a[8],
                a[9],a[4],a[5],authType,typeArray,protocol,roamingProtocol,carrierEnabled,bearer);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ApnSettingV2] ")
        .append(carrier)
        .append(", ").append(id)
        .append(", ").append(numeric)
        .append(", ").append(apn)
        .append(", ").append(proxy)
        .append(", ").append(mmsc)
        .append(", ").append(mmsProxy)
        .append(", ").append(mmsPort)
        .append(", ").append(port)
        .append(", ").append(authType).append(", ");
        for (int i = 0; i < types.length; i++) {
            sb.append(types[i]);
            if (i < types.length - 1) {
                sb.append(" | ");
            }
        }
        sb.append(", ").append(protocol);
        sb.append(", ").append(roamingProtocol);
        sb.append(", ").append(carrierEnabled);
        sb.append(", ").append(bearer);
        return sb.toString();
    }

    public boolean canHandleType(String type) {
        for (String t : types) {
            // DEFAULT handles all, and HIPRI is handled by DEFAULT
            if (t.equalsIgnoreCase(type) ||
                    t.equalsIgnoreCase(PhoneConstants.APN_TYPE_ALL) ||
                    (t.equalsIgnoreCase(PhoneConstants.APN_TYPE_DEFAULT) &&
                    type.equalsIgnoreCase(PhoneConstants.APN_TYPE_HIPRI))) {
                return true;
            }
        }
        return false;
    }

    // TODO - if we have this function we should also have hashCode.
    // Also should handle changes in type order and perhaps case-insensitivity
    @Override
    public boolean equals(Object o) {
        if (o instanceof ApnSetting == false) return false;
        return (toString().equals(o.toString()));
    }
}
