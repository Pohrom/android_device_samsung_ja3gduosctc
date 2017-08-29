/*
 * Copyright (C) 2012-2014 The CyanogenMod Project
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

import static com.android.internal.telephony.RILConstants.*;

import android.media.AudioManager;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.telephony.Rlog;
import android.telephony.SmsMessage;
import android.telephony.SignalStrength;
import android.telephony.PhoneNumberUtils;
import com.android.internal.telephony.Operators;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccUtils;

/**
 * Samsung RIL
 * SCH-i959 is supported
 * This RIL is univerisal meaning it supports CDMA and GSM radio.
 * Handles most GSM and CDMA cases.
 * {@hide}
 */
public class SamsungLegacyRIL extends RIL implements CommandsInterface {

    public static final int RIL_REQUEST_DIAL_EMERGENCY = 10001;
    public static final int RIL_UNSOL_SIM_APPLICATION_REFRESH = 1100;
    public static final int RIL_UNSOL_RELEASE_COMPLETE_MESSAGE = 11001;
    public static final int RIL_UNSOL_STK_SEND_SMS_RESULT = 11002;
    public static final int RIL_UNSOL_STK_CALL_CONTROL_RESULT = 11003;
    public static final int RIL_UNSOL_DEVICE_READY_NOTI = 11008;
    public static final int RIL_UNSOL_GPS_NOTI = 11009;
    public static final int RIL_UNSOL_AM = 11010;
    public static final int RIL_UNSOL_SAP = 11013;
    public static final int RIL_UNSOL_SIM_PB_READY = 11021;
    public static final int RIL_UNSOL_IMS_REGISTRATION_STATE_CHANGED = 11027;
    public static final int RIL_UNSOL_MODIFY_CALL = 11028;
    public static final int RIL_UNSOL_VOICE_SYSTEM_ID = 11032;
    public static final int RIL_UNSOL_IMS_RETRYOVER = 11034;
    public static final int RIL_UNSOL_PB_INIT_COMPLETE = 11035;
    public static final int RIL_UNSOL_HYSTERESIS_DCN = 11037;
    public static final int RIL_UNSOL_HOME_NETWORK_NOTI = 11043;
    public static final int RIL_UNSOL_STK_CALL_STATUS = 11054;
    public static final int RIL_UNSOL_ON_SS = 11055;
    public static final int RIL_UNSOL_MODEM_CAP = 11056;
    public static final int RIL_UNSOL_DUN = 11060;
    public static final int RIL_UNSOL_IMS_PREFERENCE_CHANGED = 11061;

    protected static final int EVENT_RIL_CONNECTED = 1;

    protected boolean isGSM = false;

    private AudioManager mAudioManager;
    private ConnectionStateListener mConnectionStateListener;
    private TelephonyMetrics mMetrics = TelephonyMetrics.getInstance();

    class PreferredNetworkListInfo
    {
      public int mGsmAct;
      public int mGsmCompactAct;
      public int mIndex;
      public int mMode;
      public String mOperator;
      public String mPlmn;
      public int mUtranAct;
      
      public PreferredNetworkListInfo()
      {
        this.mIndex = 0;
        this.mOperator = "";
        this.mPlmn = "";
        this.mGsmAct = 0;
        this.mGsmCompactAct = 0;
        this.mUtranAct = 0;
        this.mMode = 0;
      }
      
      public PreferredNetworkListInfo(int paramInt1, String paramString1, String paramString2, int paramInt2, int paramInt3, int paramInt4, int paramInt5)
      {
        this.mIndex = paramInt1;
        this.mOperator = paramString1;
        this.mPlmn = paramString2;
        this.mGsmAct = paramInt2;
        this.mGsmCompactAct = paramInt3;
        this.mUtranAct = paramInt4;
        this.mMode = paramInt5;
      }
      
      public PreferredNetworkListInfo(PreferredNetworkListInfo paramPreferredNetworkListInfo)
      {
        copyFrom(paramPreferredNetworkListInfo);
      }
      
      protected void copyFrom(PreferredNetworkListInfo paramPreferredNetworkListInfo)
      {
        this.mIndex = paramPreferredNetworkListInfo.mIndex;
        this.mOperator = paramPreferredNetworkListInfo.mOperator;
        this.mPlmn = paramPreferredNetworkListInfo.mPlmn;
        this.mGsmAct = paramPreferredNetworkListInfo.mGsmAct;
        this.mGsmCompactAct = paramPreferredNetworkListInfo.mGsmCompactAct;
        this.mUtranAct = paramPreferredNetworkListInfo.mUtranAct;
        this.mMode = paramPreferredNetworkListInfo.mMode;
      }
      
      public String toString()
      {
        return "PreferredNetworkListInfo: { index: " + this.mIndex + ", operator: " + this.mOperator + ", plmn: " + this.mPlmn + ", gsmAct: " + this.mGsmAct + ", gsmCompactAct: " + this.mGsmCompactAct + ", utranAct: " + this.mUtranAct + ", mode: " + this.mMode + " }";
      }
    }

    private class ConnectionStateListener extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_RIL_CONNECTED:
                    riljLogv("RIL connected");
                    mAudioManager.setParameters("ril_state_connected=1");
                    break;
                default:
                    riljLogv("Unknown connection event");
                    break;
            }
        }
    }

    public SamsungLegacyRIL(Context context, int networkMode,
            int cdmaSubscription) {
        this(context, networkMode, cdmaSubscription, null);
    }

    public SamsungLegacyRIL(Context context, int networkMode,
                   int cdmaSubscription, Integer instanceId) {
        super(context, networkMode, cdmaSubscription,instanceId);
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        mConnectionStateListener = new ConnectionStateListener();
        registerForRilConnected(mConnectionStateListener, EVENT_RIL_CONNECTED, null); 
    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus appStatus;

        IccCardStatus cardStatus = new IccCardStatus();
        cardStatus.setCardState(p.readInt());
        cardStatus.setUniversalPinState(p.readInt());
        cardStatus.mGsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.mCdmaSubscriptionAppIndex = p.readInt();
        cardStatus.mImsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        cardStatus.mApplications = new IccCardApplicationStatus[numApplications];

        appStatus = new IccCardApplicationStatus();
        for (int i = 0 ; i < numApplications ; i++) {
            if (i!=0) {
                appStatus = new IccCardApplicationStatus();
            }
            appStatus.app_type       = appStatus.AppTypeFromRILInt(p.readInt());
            appStatus.app_state      = appStatus.AppStateFromRILInt(p.readInt());
            appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(p.readInt());
            appStatus.aid            = p.readString();
            appStatus.app_label      = p.readString();
            appStatus.pin1_replaced  = p.readInt();
            appStatus.pin1           = appStatus.PinStateFromRILInt(p.readInt());
            appStatus.pin2           = appStatus.PinStateFromRILInt(p.readInt());
            p.readInt(); // remaining_count_pin1 - pin1_num_retries
            p.readInt(); // remaining_count_puk1 - puk1_nufm_retries
            p.readInt(); // remaining_count_pin2 - pin2_num_retries
            p.readInt(); // remaining_count_puk2 - puk2_num_retries
            p.readInt(); // - perso_unblock_retries
            cardStatus.mApplications[i] = appStatus;
        }

        return cardStatus;
    }

    public void
    acceptCall(int type, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_ANSWER, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + type);

        mMetrics.writeRilAnswer(mInstanceId, rr.mSerial);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(type);
        send(rr);
    }

    @Override
    public void
    acceptCall(Message result) {
        acceptCall(0, result);
    }

    @Override
    protected Object
    responseCallList(Parcel p) {
        int num;
        int voiceSettings;
        ArrayList<DriverCall> response;
        DriverCall dc;

        num = p.readInt();
        response = new ArrayList<DriverCall>(num);

        if (RILJ_LOGV) {
            riljLog("responseCallList: num=" + num +
                    " mEmergencyCallbackModeRegistrant=" + mEmergencyCallbackModeRegistrant +
                    " mTestingEmergencyCall=" + mTestingEmergencyCall.get());
        }
        for (int i = 0 ; i < num ; i++) {
            dc = new DriverCall();

            dc.state = DriverCall.stateFromCLCC(p.readInt());
            dc.index = p.readInt();
            dc.TOA = p.readInt();
            dc.isMpty = (0 != p.readInt());
            dc.isMT = (0 != p.readInt());
            dc.als = p.readInt();
            voiceSettings = p.readInt();
            dc.isVoice = (0 == voiceSettings) ? false : true;

            int type = p.readInt();
            int domain = p.readInt();
            String extras = p.readString();

            dc.isVoicePrivacy = (0 != p.readInt());
            dc.number = p.readString();
            dc.numberPresentation = DriverCall.presentationFromCLIP(p.readInt());
            dc.name = p.readString();
            Rlog.d(RILJ_LOG_TAG, "responseCallList dc.name = " + dc.name);

            dc.namePresentation = DriverCall.presentationFromCLIP(p.readInt());
            int uusInfoPresent = p.readInt();
            if (uusInfoPresent == 1) {
                dc.uusInfo = new UUSInfo();
                dc.uusInfo.setType(p.readInt());
                dc.uusInfo.setDcs(p.readInt());
                byte[] userData = p.createByteArray();
                dc.uusInfo.setUserData(userData);
                riljLogv(String.format("Incoming UUS : type=%d, dcs=%d, length=%d",
                                dc.uusInfo.getType(), dc.uusInfo.getDcs(),
                                dc.uusInfo.getUserData().length));
                riljLogv("Incoming UUS : data (string)="
                        + new String(dc.uusInfo.getUserData()));
                riljLogv("Incoming UUS : data (hex): "
                        + IccUtils.bytesToHexString(dc.uusInfo.getUserData()));
            } else {
                riljLogv("Incoming UUS : NOT present!");
            }

            // Make sure there's a leading + on addresses with a TOA of 145
            dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

            response.add(dc);

            if (dc.isVoicePrivacy) {
                mVoicePrivacyOnRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is enabled");
            } else {
                mVoicePrivacyOffRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is disabled");
            }
        }

        Collections.sort(response);

        if ((num == 0) && mTestingEmergencyCall.getAndSet(false)) {
            if (mEmergencyCallbackModeRegistrant != null) {
                riljLog("responseCallList: call ended, testing emergency call," +
                            " notify ECM Registrants");
                mEmergencyCallbackModeRegistrant.notifyRegistrant();
            }
        }

        return response;
    }

    @Override
    protected void
    processUnsolicited (Parcel p, int type) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_AM:
                ret = responseString(p);
                String amString = (String) ret;
                Rlog.d(RILJ_LOG_TAG, "Executing AM: " + amString);

                try {
                    Runtime.getRuntime().exec("am " + amString);
                } catch (IOException e) {
                    e.printStackTrace();
                    Rlog.e(RILJ_LOG_TAG, "am " + amString + " could not be executed.");
                }
                break;
            /* unsolicitied reqeusts can be responsed */
            case RIL_UNSOL_SIM_APPLICATION_REFRESH: ret = responseInts(p); break;
            case RIL_UNSOL_STK_SEND_SMS_RESULT: ret = responseInts(p); break;
            case RIL_UNSOL_STK_CALL_CONTROL_RESULT: ret = responseString(p); break;
            case RIL_UNSOL_DEVICE_READY_NOTI: ret = responseVoid(p); break;
            case RIL_UNSOL_GPS_NOTI: ret = responseVoid(p); break;
            case RIL_UNSOL_SAP: ret = responseRaw(p); break;
            case RIL_UNSOL_SIM_PB_READY: ret = responseVoid(p); break;
            case RIL_UNSOL_IMS_REGISTRATION_STATE_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_VOICE_SYSTEM_ID: ret = responseInts(p); break;
            case RIL_UNSOL_IMS_RETRYOVER: ret = responseVoid(p); break;
            case RIL_UNSOL_PB_INIT_COMPLETE: ret = responseVoid(p); break;
            case RIL_UNSOL_HYSTERESIS_DCN: ret = responseVoid(p); break;
            case RIL_UNSOL_HOME_NETWORK_NOTI: ret = responseVoid(p); break;
            case RIL_UNSOL_STK_CALL_STATUS: ret = responseInts(p); break;
            case RIL_UNSOL_MODEM_CAP: ret = responseRaw(p); break;
            case RIL_UNSOL_DUN: ret = responseStrings(p); break;
            case RIL_UNSOL_IMS_PREFERENCE_CHANGED: ret = responseInts(p); break;
            /* unsolicitied reqeusts cannot be responsed */
            case RIL_UNSOL_MODIFY_CALL:
            case RIL_UNSOL_RELEASE_COMPLETE_MESSAGE:
            case RIL_UNSOL_ON_SS:
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p, type);
                return;
        }
    }

    @Override
    protected RILRequest
    processSolicited (Parcel p, int type) {
        int serial, error;
        boolean found = false;

        serial = p.readInt();
        error = p.readInt();

        RILRequest rr;

        rr = findAndRemoveRequestFromList(serial);

        if (rr == null) {
            Rlog.w(RILJ_LOG_TAG, "Unexpected solicited response! sn: "
                            + serial + " error: " + error);
            return null;
        }

        Object ret = null;

        if (error == 0 || p.dataAvail() > 0) {
            // either command succeeds or command fails but with data payload
            try {switch (rr.mRequest) {
            case 1:
                ret = responseIccCardStatus(p);
                break;
            case 2:
                ret = responseInts(p);
                break;
            case 3:
                ret = responseInts(p);
                break;
            case 4:
                ret = responseInts(p);
                break;
            case 5:
                ret = responseInts(p);
                break;
            case 6:
                ret = responseInts(p);
                break;
            case 7:
                ret = responseInts(p);
                break;
            case 8:
                ret = responseInts(p);
                break;
            case 9:
                ret = responseCallList(p);
                break;
            case 10:
                ret = responseVoid(p);
                break;
            case 11:
                ret = responseString(p);
                break;
            case 12:
                ret = responseVoid(p);
                break;
            case 13:
                ret = responseVoid(p);
                break;
            case 14:
                if (this.mTestingEmergencyCall.getAndSet(false) && this.mEmergencyCallbackModeRegistrant != null) {
                    riljLog("testing emergency call, notify ECM Registrants");
                    this.mEmergencyCallbackModeRegistrant.notifyRegistrant();
                }
                ret = responseVoid(p);
                break;
            case 15:
                ret = responseVoid(p);
                break;
            case 16:
                ret = responseVoid(p);
                break;
            case 17:
                ret = responseVoid(p);
                break;
            case 18:
                ret = responseFailCause(p);
                break;
            case 19:
                ret = responseSignalStrength(p);
                break;
            case 20:
                ret = responseStrings(p);
                break;
            case 21:
                ret = responseStrings(p);
                break;
            case 22:
                ret = operatorCheck(p);
                break;
            case 23:
                ret = responseVoid(p);
                break;
            case 24:
                ret = responseVoid(p);
                break;
            case 25:
                ret = responseSMS(p);
                break;
            case 26:
                ret = responseSMS(p);
                break;
            case 27:
                ret = responseSetupDataCall(p);
                break;
            case 28:
                ret = responseICC_IO(p);
                break;
            case 29:
                ret = responseVoid(p);
                break;
            case 30:
                ret = responseVoid(p);
                break;
            case 31:
                ret = responseInts(p);
                break;
            case 32:
                ret = responseVoid(p);
                break;
            case 33:
                ret = responseCallForward(p);
                break;
            case 34:
                ret = responseVoid(p);
                break;
            case 35:
                ret = responseInts(p);
                break;
            case 36:
                ret = responseVoid(p);
                break;
            case 37:
                ret = responseVoid(p);
                break;
            case 38:
                ret = responseString(p);
                break;
            case 39:
                ret = responseString(p);
                break;
            case 40:
                ret = responseVoid(p);
                break;
            case 41:
                ret = responseVoid(p);
                break;
            case 42:
                ret = responseInts(p);
                break;
            case 43:
                ret = responseInts(p);
                break;
            case 44:
                ret = responseVoid(p);
                break;
            case 45:
                ret = responseInts(p);
                break;
            case 46:
                ret = responseVoid(p);
                break;
            case 47:
                ret = responseVoid(p);
                break;
            case 48:
                ret = responseOperatorInfos(p);
                break;
            case 49:
                ret = responseVoid(p);
                break;
            case 50:
                ret = responseVoid(p);
                break;
            case 51:
                ret = responseString(p);
                break;
            case 52:
                ret = responseVoid(p);
                break;
            case 53:
                ret = responseVoid(p);
                break;
            case 54:
                ret = responseInts(p);
                break;
            case 55:
                ret = responseInts(p);
                break;
            case 56:
                ret = responseInts(p);
                break;
            case 57:
                ret = responseDataCallList(p);
                break;
            case 58:
                ret = responseVoid(p);
                break;
            case 59:
                ret = responseRaw(p);
                break;
            case 60:
                ret = responseStrings(p);
                break;
            case 61:
                ret = responseVoid(p);
                break;
            case 62:
                ret = responseVoid(p);
                break;
            case 63:
                ret = responseInts(p);
                break;
            case 64:
                ret = responseVoid(p);
                break;
            case 65:
                ret = responseVoid(p);
                break;
            case 66:
                ret = responseInts(p);
                break;
            case 67:
                ret = responseString(p);
                break;
            case 68:
                ret = responseVoid(p);
                break;
            case 69:
                ret = responseString(p);
                break;
            case 70:
                ret = responseVoid(p);
                break;
            case 71:
                ret = responseInts(p);
                break;
            case 72:
                ret = responseVoid(p);
                break;
            case 73:
                ret = responseVoid(p);
                break;
            case 74:
                ret = responseGetPreferredNetworkType(p);
                break;
            case 75:
                ret = responseCellList(p);
                break;
            case 76:
                ret = responseVoid(p);
                break;
            case 77:
                ret = responseVoid(p);
                break;
            case 78:
                ret = responseVoid(p);
                break;
            case 79:
                ret = responseInts(p);
                break;
            case 80:
                ret = responseVoid(p);
                break;
            case 81:
                ret = responseInts(p);
                break;
            case 82:
                ret = responseVoid(p);
                break;
            case 83:
                ret = responseInts(p);
                break;
            case 84:
                ret = responseVoid(p);
                break;
            case 85:
                ret = responseVoid(p);
                break;
            case 86:
                ret = responseVoid(p);
                break;
            case 87:
                ret = responseSMS(p);
                break;
            case 88:
                ret = responseVoid(p);
                break;
            case 89:
                ret = responseGmsBroadcastConfig(p);
                break;
            case 90:
                ret = responseVoid(p);
                break;
            case 91:
                ret = responseVoid(p);
                break;
            case 92:
                ret = responseCdmaBroadcastConfig(p);
                break;
            case 93:
                ret = responseVoid(p);
                break;
            case 94:
                ret = responseVoid(p);
                break;
            case 95:
                ret = responseStrings(p);
                break;
            case 96:
                ret = responseInts(p);
                break;
            case 97:
                ret = responseVoid(p);
                break;
            case 98:
                ret = responseStrings(p);
                break;
            case 99:
                ret = responseVoid(p);
                break;
            case 100:
                ret = responseString(p);
                break;
            case 101:
                ret = responseVoid(p);
                break;
            case 102:
                ret = responseVoid(p);
                break;
            case 103:
                ret = responseVoid(p);
                break;
            case 104:
                ret = responseInts(p);
                break;
            case 105:
                ret = responseString(p);
                break;
            case 106:
                ret = responseVoid(p);
                break;
            case 107:
                ret = responseICC_IO(p);
                break;
            case 108:
                ret = responseInts(p);
                break;
            case 109:
                ret = responseCellInfoList(p);
                break;
            case 110:
                ret = responseVoid(p);
                break;
            case 111:
                ret = responseVoid(p);
                break;
            case 112:
                ret = responseInts(p);
                break;
            case 113:
                ret = responseSMS(p);
                break;
            case 114:
                ret = responseICC_IO(p);
                break;
            case 115:
                ret = responseInts(p);
                break;
            case 116:
                ret = responseVoid(p);
                break;
            case 117:
                ret = responseICC_IO(p);
                break;
            case 118:
                ret = responseString(p);
                break;
            case 119:
                ret = responseVoid(p);
                break;
            case 120:
                ret = responseVoid(p);
                break;
            case 121:
                ret = responseVoid(p);
                break;
            case 122:
                ret = responseVoid(p);
                break;
            case 123:
                ret = responseVoid(p);
                break;
            case 124:
                ret = responseHardwareConfig(p);
                break;
            case 125:
                ret = responseICC_IO(p);
                break;
            case 128:
                ret = responseVoid(p);
                break;
            case 129:
                ret = responseVoid(p);
                break;
            case 10001:
                ret = responseVoid(p);
                break;
            case 10002:
                ret = responseVoid(p);
                break;
            case 10003:
                ret = responseInts(p);
                break;
            case 10004:
                ret = responseVoid(p);
                break;
            case 10005:
                ret = responseVoid(p);
                break;
            case 10006:
                ret = responseVoid(p);
                break;
            case 10007:
                ret = responseVoid(p);
                break;
            case 10009:
                ret = responseInts(p);
                break;
            case 10011:
                ret = responseInts(p);
                break;
            case 10012:
                ret = responseInts(p);
                break;
            case 10014:
                ret = responseVoid(p);
                break;
            case 10015:
                ret = responseVoid(p);
                break;
            case 10016:
                ret = responsePreferredNetworkList(p);
                break;
            case 10017:
                ret = responseInts(p);
                break;
            case 10018:
                ret = responseInts(p);
                break;
            case 10019:
                ret = responseVoid(p);
                break;
            case 10020:
                ret = responseSMS(p);
                break;
            case 10021:
                ret = responseVoid(p);
                break;
            case 10022:
                ret = responseVoid(p);
                break;
            case 10023:
                ret = responseSimPowerDone(p);
                break;
            case 10024:
                ret = responseVoid(p);
                break;
            case 10025:
                ret = responseBootstrap(p);
                break;
            case 10026:
                ret = responseNaf(p);
                break;
            case 10008:
            case 10010:
            case 10013:
            default:
                throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            //break;
            }} catch (Throwable tr) {
                // Exceptions here usually mean invalid RIL responses

                Rlog.w(RILJ_LOG_TAG, rr.serialString() + "< "
                        + requestToString(rr.mRequest)
                        + " exception, possible invalid RIL response", tr);

                if (rr.mResult != null) {
                    AsyncResult.forMessage(rr.mResult, null, tr);
                    rr.mResult.sendToTarget();
                }
                return rr;
            }
        }

        if (rr.mRequest == RIL_REQUEST_SHUTDOWN) {
            riljLog("Response to RIL_REQUEST_SHUTDOWN received. Error is " + error + " Setting Radio State to Unavailable regardless of error.");
            setRadioState(RadioState.RADIO_UNAVAILABLE);
            if ("1".equals(SystemProperties.get("sys.deviceOffReq", "0"))) {
                if (this.mInstanceId.intValue() == 0) {
                    SystemProperties.set("ril.deviceOffRes", "1");
                } else if (this.mInstanceId.intValue() == 1) {
                    SystemProperties.set("ril.deviceOffRes2", "1");
                }
            }
        }


        if (rr.mRequest == 73) {
            if (this.mPreferredNetworkType < 8 || this.mPreferredNetworkType > 12) {
                Settings.System.putInt(this.mContext.getContentResolver(), "lte_mode_switch", 0);
                Rlog.d(RILJ_LOG_TAG, "Set LTE_MODE_SWITCH off");
            } else {
                Settings.System.putInt(this.mContext.getContentResolver(), "lte_mode_switch", 1);
                Rlog.d(RILJ_LOG_TAG, "Set LTE_MODE_SWITCH on");
            }
        }

        // Here and below fake RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED, see b/7255789.
        // This is needed otherwise we don't automatically transition to the main lock
        // screen when the pin or puk is entered incorrectly.
        switch (rr.mRequest) {
            case RIL_REQUEST_ENTER_SIM_PUK:
            case RIL_REQUEST_ENTER_SIM_PUK2:
                if (mIccStatusChangedRegistrants != null) {
                    if (RILJ_LOGD) {
                        riljLog("ON enter sim puk fakeSimStatusChanged: reg count="
                                + mIccStatusChangedRegistrants.size());
                    }
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
        }

        if (error != 0) {
            switch (rr.mRequest) {
                case RIL_REQUEST_ENTER_SIM_PIN:
                case RIL_REQUEST_ENTER_SIM_PIN2:
                case RIL_REQUEST_CHANGE_SIM_PIN:
                case RIL_REQUEST_CHANGE_SIM_PIN2:
                case RIL_REQUEST_SET_FACILITY_LOCK:
                    if (mIccStatusChangedRegistrants != null) {
                        if (RILJ_LOGD) {
                            riljLog("ON some errors fakeSimStatusChanged: reg count="
                                    + mIccStatusChangedRegistrants.size());
                        }
                        mIccStatusChangedRegistrants.notifyRegistrants();
                    }
                    break;
            }

            rr.onError(error, ret);
        } else {

            if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                    + " " + retToString(rr.mRequest, ret));

            if (rr.mResult != null) {
                AsyncResult.forMessage(rr.mResult, ret, null);
                rr.mResult.sendToTarget();
            }
        }

        mMetrics.writeOnRilSolicitedResponse(mInstanceId, rr.mSerial, error,
                rr.mRequest, ret);
        
        return rr;
    }

    @Override
    public void setPhoneType(int phoneType){
        super.setPhoneType(phoneType);
        isGSM = (phoneType != RILConstants.CDMA_PHONE);
    }

    @Override
    protected Object responseSignalStrength(Parcel p) {
        int gsmSignalStrength = p.readInt() & 0xff;
        int gsmBitErrorRate = p.readInt();
        int cdmaDbm = p.readInt();
        int cdmaEcio = p.readInt();
        int evdoDbm = p.readInt();
        int evdoEcio = p.readInt();
        int evdoSnr = p.readInt();
        int lteSignalStrength = p.readInt();
        int lteRsrp = p.readInt();
        int lteRsrq = p.readInt();
        int lteRssnr = p.readInt();
        int lteCqi = p.readInt();
        int tdScdmaRscp = p.readInt();

        //gsm
        gsmSignalStrength &= 0xff; //gsmDbm

        //cdma
        // Take just the least significant byte as the signal strength
        cdmaDbm %= 256;
        evdoDbm %= 256;

        // RIL_LTE_SignalStrength
        if (!isGSM){
            lteRsrp = cdmaDbm;
        }else if ((lteSignalStrength & 0xff) == 255 || lteSignalStrength == 99) {
            // If LTE is not enabled, clear LTE results
            // 7-11 must be -1 for GSM signal strength to be used (see
            // frameworks/base/telephony/java/android/telephony/SignalStrength.java)
            // make sure lte is disabled
            lteSignalStrength = 99;
            lteRsrp = SignalStrength.INVALID;
            lteRsrq = SignalStrength.INVALID;
            lteRssnr = SignalStrength.INVALID;
            lteCqi = SignalStrength.INVALID;
        }else{ // lte is gsm on samsung/qualcomm cdma stack
            lteSignalStrength &= 0xff;
        }

        if (RILJ_LOGD)
            riljLog("gsmSignalStrength:" + gsmSignalStrength + " gsmBitErrorRate:" + gsmBitErrorRate +
                    " cdmaDbm:" + cdmaDbm + " cdmaEcio:" + cdmaEcio + " evdoDbm:" + evdoDbm +
                    " evdoEcio: " + evdoEcio + " evdoSnr:" + evdoSnr +
                    " lteSignalStrength:" + lteSignalStrength + " lteRsrp:" + lteRsrp +
                    " lteRsrq:" + lteRsrq + " lteRssnr:" + lteRssnr + " lteCqi:" + lteCqi +
                    " tdScdmaRscp:" + tdScdmaRscp + " isGSM:" + (isGSM ? "true" : "false"));

        return new SignalStrength(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio, evdoDbm, cdmaEcio, evdoSnr, lteSignalStrength, lteRsrp, lteRsrq, lteRssnr, lteCqi, (p.readInt() != 0));

    }

    private Object
    operatorCheck(Parcel p) {
        String response[] = (String[])responseStrings(p);
        for(int i=0; i<2; i++){
            if (response[i]!= null){
                response[i] = Operators.operatorReplace(response[i]);
            }
        }
        return response;
    }

    @Override
    protected Object
    responseSuppServiceNotification(Parcel p) {
        SuppServiceNotification notification = new SuppServiceNotification();

        notification.notificationType = p.readInt();
        notification.code = p.readInt();
        notification.index = p.readInt();
        notification.type = p.readInt();
        notification.number = p.readString();

        return notification;
    }

    private Object responseNaf(Parcel p) {
        return IccUtils.hexStringToBytes(p.readString());
    }

    private Object responseBootstrap(Parcel p) {
        Bundle b = new Bundle();
        b.putByteArray("res", IccUtils.hexStringToBytes(p.readString()));
        b.putByteArray("auts", IccUtils.hexStringToBytes(p.readString()));
        return b;
    }

    private Object responseSimPowerDone(Parcel p) {
        Rlog.d(RILJ_LOG_TAG, "ResponseSimPowerDone");
        int numInts = p.readInt();
        int[] response = new int[numInts];
        for (int i = 0; i < numInts; i++) {
            response[i] = p.readInt();
        }
        Rlog.d(RILJ_LOG_TAG, "ResponseSimPowerDone : " + response[0]);
        return Integer.valueOf(response[0]);
    }

    private Object responsePreferredNetworkList(Parcel p) {
        int num = p.readInt();
        Rlog.d(RILJ_LOG_TAG, "number of network list = " + num);
        ArrayList<PreferredNetworkListInfo> response = new ArrayList(num);
        for (int i = 0; i < num; i++) {
            PreferredNetworkListInfo preferredNetwork = new PreferredNetworkListInfo();
            preferredNetwork.mIndex = p.readInt();
            preferredNetwork.mOperator = p.readString();
            preferredNetwork.mPlmn = p.readString();
            preferredNetwork.mGsmAct = p.readInt();
            preferredNetwork.mGsmCompactAct = p.readInt();
            preferredNetwork.mUtranAct = p.readInt();
            preferredNetwork.mMode = p.readInt();
            response.add(preferredNetwork);
        }
        return response;
    }

    @Override
    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);

        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);
        rr.mParcel.writeInt(1);		// mark
        rr.mParcel.writeString("");	// mark

        if (uusInfo == null) {
            rr.mParcel.writeInt(0); // UUS information is absent
        } else {
            rr.mParcel.writeInt(1); // UUS information is present
            rr.mParcel.writeInt(uusInfo.getType());
            rr.mParcel.writeInt(uusInfo.getDcs());
            rr.mParcel.writeByteArray(uusInfo.getUserData());
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    //this method is used in the search network functionality.
    // in mobile network setting-> network operators
    @Override
    protected Object
    responseOperatorInfos(Parcel p) {
        String strings[] = (String [])responseStrings(p);
        ArrayList<OperatorInfo> ret;

        if (strings.length % mQANElements != 0) {
            throw new RuntimeException(
                                       "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                                       + strings.length + " strings, expected multiple of " + mQANElements);
        }

        ret = new ArrayList<OperatorInfo>(strings.length / mQANElements);
        Operators init = null;
        if (strings.length != 0) {
            init = new Operators();
        }
        for (int i = 0 ; i < strings.length ; i += mQANElements) {
            String temp = init.unOptimizedOperatorReplace(strings[i+0]);
            ret.add (
                     new OperatorInfo(
                                      temp, //operatorAlphaLong
                                      temp,//operatorAlphaShort
                                      strings[i+2],//operatorNumeric
                                      strings[i+3]));//state
        }

        return ret;
    }

    public void dialEmergencyCall(String address, int clirMode, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL_EMERGENCY, result);

        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);
        rr.mParcel.writeInt(3);
        rr.mParcel.writeString("");
        rr.mParcel.writeInt(0);

        riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
    
        send(rr);
    }


}
