/*
 * Copyright (C) 2012-2015 The CyanogenMod Project
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

import android.content.Context;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.dataconnection.DcFailCause;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Qualcomm RIL for the Samsung SCH-I959.
 * {@hide}
 */
public class SamsungLegacyRIL extends RIL implements CommandsInterface {

    protected static final int EVENT_RIL_CONNECTED = 1;
    private boolean isGSM = false;

    private AudioManager mAudioManager;
    private ConnectionStateListener mConnectionStateListener;
    private TelephonyMetrics mMetrics = TelephonyMetrics.getInstance();

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

    public SamsungLegacyRIL(Context context, int networkModes, int cdmaSubscription) {
        this(context, networkModes, cdmaSubscription, null);
    }

    public SamsungLegacyRIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        super(context, preferredNetworkType, cdmaSubscription, instanceId);
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
            p.readInt(); // remaining_count_puk1 - puk1_num_retries
            p.readInt(); // remaining_count_pin2 - pin2_num_retries
            p.readInt(); // remaining_count_puk2 - puk2_num_retries
            p.readInt(); // - perso_unblock_retries
            cardStatus.mApplications[i] = appStatus;
        }
        if (numApplications==1 && !isGSM && appStatus.app_type == appStatus.AppTypeFromRILInt(2)) {
            cardStatus.mApplications = new IccCardApplicationStatus[numApplications+2];
            cardStatus.mGsmUmtsSubscriptionAppIndex = 0;
            cardStatus.mApplications[cardStatus.mGsmUmtsSubscriptionAppIndex]=appStatus;
            cardStatus.mCdmaSubscriptionAppIndex = 1;
            cardStatus.mImsSubscriptionAppIndex = 2;
            IccCardApplicationStatus appStatus2 = new IccCardApplicationStatus();
            appStatus2.app_type       = appStatus2.AppTypeFromRILInt(4); // csim state
            appStatus2.app_state      = appStatus.app_state;
            appStatus2.perso_substate = appStatus.perso_substate;
            appStatus2.aid            = appStatus.aid;
            appStatus2.app_label      = appStatus.app_label;
            appStatus2.pin1_replaced  = appStatus.pin1_replaced;
            appStatus2.pin1           = appStatus.pin1;
            appStatus2.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mCdmaSubscriptionAppIndex] = appStatus2;
            IccCardApplicationStatus appStatus3 = new IccCardApplicationStatus();
            appStatus3.app_type       = appStatus3.AppTypeFromRILInt(5); // ims state
            appStatus3.app_state      = appStatus.app_state;
            appStatus3.perso_substate = appStatus.perso_substate;
            appStatus3.aid            = appStatus.aid;
            appStatus3.app_label      = appStatus.app_label;
            appStatus3.pin1_replaced  = appStatus.pin1_replaced;
            appStatus3.pin1           = appStatus.pin1;
            appStatus3.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mImsSubscriptionAppIndex] = appStatus3;
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
    protected Object responseSignalStrength(Parcel p) {
        int numInts = 12;
        int response[];

        // Get raw data
        response = new int[numInts];
        for (int i = 0; i < numInts; i++) {
            response[i] = p.readInt();
        }
        //gsm
        response[0] &= 0xff;
        //cdma
        response[2] %= 256;
        response[4] %= 256;
        response[7] &= 0xff;

        return new SignalStrength(response[0], response[1], response[2], response[3], response[4], response[5], response[6], response[7], response[8], response[9], response[10], response[11], true);

    }

    @Override
    public void setPhoneType(int phoneType){
        super.setPhoneType(phoneType);
        isGSM = (phoneType != RILConstants.CDMA_PHONE);
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

    private void
    fixNitz(Parcel p) {
        int dataPosition = p.dataPosition();
        String nitz = p.readString();
        long nitzReceiveTime = p.readLong();

        String[] nitzParts = nitz.split(",");
        if (nitzParts.length >= 4) {
            // 0=date, 1=time+zone, 2=dst, 3(+)=garbage that confuses ServiceStateTracker
            nitz = nitzParts[0] + "," + nitzParts[1] + "," + nitzParts[2];
            p.setDataPosition(dataPosition);
            p.writeString(nitz);
            p.writeLong(nitzReceiveTime);
            // The string is shorter now, drop the extra bytes
            p.setDataSize(p.dataPosition());
        }
    }

    @Override
    protected void
    processUnsolicited (Parcel p, int type) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RIL_CONNECTED:
                ret = responseInts(p);
                setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                setCdmaSubscriptionSource(mCdmaSubscription, null);
                if(mRilVersion >= 8)
                    setCellInfoListRate(Integer.MAX_VALUE, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                break;
            case RIL_UNSOL_NITZ_TIME_RECEIVED:
                fixNitz(p);
                p.setDataPosition(dataPosition);
                super.processUnsolicited(p, type);
                break;
            // SAMSUNG STATES
            case 11010: // RIL_UNSOL_AM:
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
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: 
                ret = responseDataCallList(p);
                break;
            case 11021: // RIL_UNSOL_RESPONSE_HANDOVER:
                ret = responseVoid(p);
                break;
            case 1036:
                ret = responseVoid(p);
                break;
            case 11017: // RIL_UNSOL_WB_AMR_STATE:
                ret = responseInts(p);
                setWbAmr(((int[])ret)[0]);
                break;
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p, type);
                return;
        }

    }

    @Override
    protected Object
    responseSetupDataCall(Parcel p) {
        int ver = p.readInt();
        int num = p.readInt();
        if (RILJ_LOGV) riljLog("responseSetupDataCall ver=" + ver + " num=" + num);

        DataCallResponse dataCall;

        if (ver < 5) {
            dataCall = new DataCallResponse();
            dataCall.version = ver;
            dataCall.cid = Integer.parseInt(p.readString());
            dataCall.ifname = p.readString();
            if (TextUtils.isEmpty(dataCall.ifname)) {
                throw new RuntimeException(
                        "RIL_REQUEST_SETUP_DATA_CALL response, no ifname");
            }
            String addresses = p.readString();
            if (!TextUtils.isEmpty(addresses)) {
                dataCall.addresses = addresses.split(" ");
                Rlog.d(RILJ_LOG_TAG, "responseSetupDataCall: Override gateways by addresses.");
                dataCall.gateways = dataCall.addresses;
            }
            if (num >= 4) {
                String dnses = p.readString();
                if (RILJ_LOGD) riljLog("responseSetupDataCall got dnses=" + dnses);
                if (!TextUtils.isEmpty(dnses)) {
                    dataCall.dnses = dnses.split(" ");
                }
            }
            if (num >= 5) {
                String gateways = p.readString();
                if (RILJ_LOGD) riljLog("responseSetupDataCall dropped gateways=" + gateways);
            }
            if (num >= 6) {
                String pcscf = p.readString();
                if (RILJ_LOGD) riljLog("responseSetupDataCall got pcscf=" + pcscf);
                if (!TextUtils.isEmpty(pcscf)) {
                    dataCall.pcscf = pcscf.split(" ");
                }
            }
        } else {
            if (num != 1) {
                throw new RuntimeException(
                        "RIL_REQUEST_SETUP_DATA_CALL response expecting 1 RIL_Data_Call_response_v5"
                        + " got " + num);
            }
            dataCall = getDataCallResponse(p, ver);
        }

        return dataCall;
    }

    @Override
    protected Object
    responseDataCallList(Parcel p) {
        ArrayList<DataCallResponse> response;

        int ver = p.readInt();
        int num = p.readInt();
        Rlog.d(RILJ_LOG_TAG, "responseDataCallList ver=" + ver + " num=" + num);

        response = new ArrayList<DataCallResponse>(num);
        for (int i = 0; i < num; i++) {
            DataCallResponse resp = getDataCallResponse(p, ver);
            Rlog.d(RILJ_LOG_TAG, "DataCallResponse[" + i + "]: " + resp.toString());

            // dirty hack to fix mobile data unreachable
            Rlog.d(RILJ_LOG_TAG, "Override gateways by addresses.");
            resp.gateways = resp.addresses;

            if (isGSM == false && resp.addresses.length == 0) {
                Rlog.d(RILJ_LOG_TAG, "Dropping the DataCallResponse.");
            } else {
                response.add(resp);
            }
        }
        return response;
    }

    @Override
    protected RILRequest
    processSolicited (Parcel p, int type) {
        int serial, error;
        boolean found = false;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        serial = p.readInt();
        error = p.readInt();
        RILRequest rr = null;
        /* Pre-process the reply before popping it */
        synchronized (mRequestList) {
            RILRequest tr = mRequestList.get(serial);
            if (tr != null && tr.mSerial == serial) {
                if (error == 0 || p.dataAvail() > 0) {
                    try {switch (tr.mRequest) {
                            /* Get those we're interested in */
                        case RIL_REQUEST_SETUP_DATA_CALL:
                        case RIL_REQUEST_VOICE_REGISTRATION_STATE:
                        case RIL_REQUEST_DATA_REGISTRATION_STATE:
                        case RIL_REQUEST_OPERATOR:
                            rr = tr;
                            break;
                    }} catch (Throwable thr) {
                        // Exceptions here usually mean invalid RIL responses
                        if (tr.mResult != null) {
                            AsyncResult.forMessage(tr.mResult, null, thr);
                            tr.mResult.sendToTarget();
                        }
                        return tr;
                    }
                }
            }
        }
        if (rr == null) {
            /* Nothing we care about, go up */
            p.setDataPosition(dataPosition);
            // Forward responses that we are not overriding to the super class
            return super.processSolicited(p, type);
        }
        rr = findAndRemoveRequestFromList(serial);
        if (rr == null) {
            return rr;
        }
        Object ret = null;
        if (error == 0 || p.dataAvail() > 0) {
            switch (rr.mRequest) {
                case RIL_REQUEST_SETUP_DATA_CALL: ret =  responseSetupDataCall(p); break;
                case RIL_REQUEST_VOICE_REGISTRATION_STATE: ret = responseVoiceDataRegistrationState(p, false); break;
                case RIL_REQUEST_DATA_REGISTRATION_STATE: ret = responseVoiceDataRegistrationState(p, true); break;
                case RIL_REQUEST_OPERATOR: ret =  operatorCheck(p); break;
                default:
                    throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            }
            //break;
        }
        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                               + " " + retToString(rr.mRequest, ret));
        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }
        return rr;
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

    private Object
    responseVoiceDataRegistrationState(Parcel p, boolean data) {
        String response[] = (String[])responseStrings(p);
        if (isGSM){
            if (data &&
                response.length > 4 &&
                response[0].equals("1") &&
                response[3].equals("102")) {
                response[3] = "2";
            }
            return response;
        }
        if (response.length>=10){
            for(int i=6; i<=9; i++){
                if (response[i]== null){
                    response[i]=Integer.toString(Integer.MAX_VALUE);
                } else {
                    try {
                        Integer.parseInt(response[i]);
                    } catch(NumberFormatException e) {
                        response[i]=Integer.toString(Integer.parseInt(response[i],16));
                    }
                }
            }
        }

        return response;
    }

    /**
     * Set audio parameter "wb_amr" for HD-Voice (Wideband AMR).
     *
     * @param state: 0 = unsupported, 1 = supported.
     */
    private void setWbAmr(int state) {
        if (state == 1) {
            Rlog.d(RILJ_LOG_TAG, "setWbAmr(): setting audio parameter - wb_amr=on");
            mAudioManager.setParameters("wide_voice_enable=true");
        }else if (state == 0) {
            Rlog.d(RILJ_LOG_TAG, "setWbAmr(): setting audio parameter - wb_amr=off");
            mAudioManager.setParameters("wide_voice_enable=false");
        }
    }

    // Workaround for Samsung CDMA "ring of death" bug:
    //
    // Symptom: As soon as the phone receives notice of an incoming call, an
    // audible "old fashioned ring" is emitted through the earpiece and
    // persists through the duration of the call, or until reboot if the call
    // isn't answered.
    //
    // Background: The CDMA telephony stack implements a number of "signal info
    // tones" that are locally generated by ToneGenerator and mixed into the
    // voice call path in response to radio RIL_UNSOL_CDMA_INFO_REC requests.
    // One of these tones, IS95_CONST_IR_SIG_IS54B_L, is requested by the
    // radio just prior to notice of an incoming call when the voice call
    // path is muted. CallNotifier is responsible for stopping all signal
    // tones (by "playing" the TONE_CDMA_SIGNAL_OFF tone) upon receipt of a
    // "new ringing connection", prior to unmuting the voice call path.
    //
    // Problem: CallNotifier's incoming call path is designed to minimize
    // latency to notify users of incoming calls ASAP. Thus,
    // SignalInfoTonePlayer requests are handled asynchronously by spawning a
    // one-shot thread for each. Unfortunately the ToneGenerator API does
    // not provide a mechanism to specify an ordering on requests, and thus,
    // unexpected thread interleaving may result in ToneGenerator processing
    // them in the opposite order that CallNotifier intended. In this case,
    // playing the "signal off" tone first, followed by playing the "old
    // fashioned ring" indefinitely.
    //
    // Solution: An API change to ToneGenerator is required to enable
    // SignalInfoTonePlayer to impose an ordering on requests (i.e., drop any
    // request that's older than the most recent observed). Such a change,
    // or another appropriate fix should be implemented in AOSP first.
    //
    // Workaround: Intercept RIL_UNSOL_CDMA_INFO_REC requests from the radio,
    // check for a signal info record matching IS95_CONST_IR_SIG_IS54B_L, and
    // drop it so it's never seen by CallNotifier. If other signal tones are
    // observed to cause this problem, they should be dropped here as well.
    @Override
    protected void notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        final int response = RIL_UNSOL_CDMA_INFO_REC;

        if (infoRec.record instanceof CdmaInformationRecords.CdmaSignalInfoRec) {
            CdmaInformationRecords.CdmaSignalInfoRec sir = (CdmaInformationRecords.CdmaSignalInfoRec) infoRec.record;
            if (sir != null
                    && sir.isPresent
                    && sir.signalType == SignalToneUtil.IS95_CONST_IR_SIGNAL_IS54B
                    && sir.alertPitch == SignalToneUtil.IS95_CONST_IR_ALERT_MED
                    && sir.signal == SignalToneUtil.IS95_CONST_IR_SIG_IS54B_L) {

                Rlog.d(RILJ_LOG_TAG, "Dropping \"" + responseToString(response) + " "
                        + retToString(response, sir)
                        + "\" to prevent \"ring of death\" bug.");
                return;
            }
        }

        super.notifyRegistrantsCdmaInfoRec(infoRec);
    }

    @Override
    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);

        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeString("");

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
    
    @Override
    protected DataCallResponse getDataCallResponse(Parcel p, int version) {
        DataCallResponse dataCall = new DataCallResponse();

        dataCall.version = version;
        dataCall.status = p.readInt();
        dataCall.suggestedRetryTime = p.readInt();
        dataCall.cid = p.readInt();
        dataCall.active = p.readInt();
        dataCall.type = p.readString();
        dataCall.ifname = p.readString();
        if ((dataCall.status == DcFailCause.NONE.getErrorCode()) &&
                TextUtils.isEmpty(dataCall.ifname)) {
            throw new RuntimeException("getDataCallResponse, no ifname");
        }
        String addresses = p.readString();
        if (!TextUtils.isEmpty(addresses)) {
            dataCall.addresses = addresses.split(" ");
            Rlog.d(RILJ_LOG_TAG, "responseSetupDataCall: Override gateways by addresses.");
            dataCall.gateways = dataCall.addresses;
        }
        String dnses = p.readString();
        if (!TextUtils.isEmpty(dnses)) {
            dataCall.dnses = dnses.split(" ");
        }
        String gateways = p.readString();
        if (version >= 10) {
            String pcscf = p.readString();
            if (!TextUtils.isEmpty(pcscf)) {
                dataCall.pcscf = pcscf.split(" ");
            }
        }
        if (version >= 11) {
            dataCall.mtu = p.readInt();
        }
     
        return dataCall;
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

    @Override
    public void getImsRegistrationState(Message result) {
        if(mRilVersion >= 8)
            super.getImsRegistrationState(result);
        else {
            if (result != null) {
                CommandException ex = new CommandException(
                    CommandException.Error.REQUEST_NOT_SUPPORTED);
                AsyncResult.forMessage(result, null, ex);
                result.sendToTarget();
            }
        }
    }

    // This call causes ril to crash the socket, stopping further communication
    @Override
    public void
    getHardwareConfig (Message result) {
        riljLog("Ignoring call to 'getHardwareConfig'");
        if (result != null) {
            CommandException ex = new CommandException(
                CommandException.Error.REQUEST_NOT_SUPPORTED);
            AsyncResult.forMessage(result, null, ex);
            result.sendToTarget();
        }
    }

    @Override
    public void getRadioCapability(Message response) {
        riljLog("getRadioCapability: returning static radio capability");
        if (response != null) {
            Object ret = makeStaticRadioCapability();
            AsyncResult.forMessage(response, ret, null);
            response.sendToTarget();
        }
    }
}
