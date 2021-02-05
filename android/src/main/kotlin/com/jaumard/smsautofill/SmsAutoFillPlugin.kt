package com.jaumard.smsautofill

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender.SendIntentException
import android.telephony.TelephonyManager
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.Credentials
import com.google.android.gms.auth.api.credentials.HintRequest
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.util.regex.Pattern

/**
 * SmsAutoFillPlugin
 */
class SmsAutoFillPlugin(private val channel: MethodChannel, private val registrar: Registrar) : MethodCallHandler {

    val activity: Activity? = registrar.activity()
    private var pendingHintResult: MethodChannel.Result? = null
    private var broadcastReceiver: BroadcastReceiver? = null

    init {
        registrar.addActivityResultListener(ActivityResultListener { requestCode, resultCode, data ->

            if (resultCode == Activity.RESULT_OK && requestCode == PHONE_HINT_REQUEST) {
                val credential: Credential? = data?.getParcelableExtra(Credential.EXTRA_KEY)
                pendingHintResult?.success(credential?.id.orEmpty())
            } else {
                pendingHintResult?.success(null)
            }

            return@ActivityResultListener requestCode == PHONE_HINT_REQUEST
        })
    }
    
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestPhoneHint" -> requestPhoneHint(result)
            "listenForCode" -> listenForSmsCode(result)
            "unregisterListener" -> unregisterListener(result)
            "getAppSignature" -> getAppSignature(result)
            else -> result.notImplemented()
        }
    }

    fun setCode(code: String?) = channel.invokeMethod("smscode", code)

    private fun requestPhoneHint(result: MethodChannel.Result) {
        pendingHintResult = result
        requestHint()
    }

    private fun getAppSignature(result: MethodChannel.Result) {
        val signatureHelper = AppSignatureHelper(registrar.context())
        val appSignature = signatureHelper.appSignature
        result.success(appSignature)
    }

    private fun unregisterListener(result: MethodChannel.Result) {
        runCatching { activity?.unregisterReceiver(broadcastReceiver) }
        result.success("successfully unregister receiver")
    }

    private fun listenForSmsCode(result: MethodChannel.Result) {
        val client = activity?.let { SmsRetriever.getClient(it) }
        val task = client?.startSmsRetriever()
        task?.addOnSuccessListener { receiveSms(result) }
        task?.addOnFailureListener { result.error("ERROR_START_SMS_RETRIEVER", "Can't start sms retriever", null) }
    }

    private fun receiveSms(result: MethodChannel.Result) {
        createBroadcastReceiver()
        activity?.registerReceiver(broadcastReceiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION))
        result.success(null)
    }

    private fun createBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                when {
                    SmsRetriever.SMS_RETRIEVED_ACTION != intent.action -> return
                    else -> {
                        activity?.unregisterReceiver(this)

                        val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as Status?

                        if (status?.statusCode == CommonStatusCodes.SUCCESS) {

                            val message = intent.extras?.get(SmsRetriever.EXTRA_SMS_MESSAGE) as String?
                            val pattern = Pattern.compile("\\d{4,6}")

                            val matcher = pattern.matcher(message.orEmpty())

                            if (matcher.find()) {
                                setCode(matcher.group(0))
                            } else {
                                setCode(message)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isSimSupport(): Boolean {
        val telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.simState != TelephonyManager.SIM_STATE_ABSENT
    }

    private fun requestHint() {

        if (!isSimSupport()) {
            pendingHintResult?.success(null)
            return
        }

        val hintRequest = HintRequest.Builder()
                .setPhoneNumberIdentifierSupported(true)
                .build()

        val intent = activity?.let {
            Credentials.getClient(it)
                .getHintPickerIntent(hintRequest)
        }
        
        try {
            activity?.startIntentSenderForResult(intent?.intentSender, PHONE_HINT_REQUEST, null, 0, 0, 0)
        } catch (e: SendIntentException) {
            pendingHintResult?.success(null)
        }
    }

    companion object {
        private const val PHONE_HINT_REQUEST = 11012

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "sms_autofill")
            channel.setMethodCallHandler(SmsAutoFillPlugin(channel, registrar))
        }
    }
}