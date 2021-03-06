package com.iflytek.cyber.iot.show.core.impl.appaction

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.iflytek.cyber.evs.sdk.agent.AppAction
import com.iflytek.cyber.evs.sdk.utils.AppUtil
import com.iflytek.cyber.iot.show.core.utils.TerminalUtils
import java.lang.ref.SoftReference

class EvsAppAction private constructor(private val context: Context) : AppAction() {
    companion object {
        private var instance: SoftReference<EvsAppAction>? = null

        fun get(context: Context?): EvsAppAction {
            instance?.get()?.let {
                return it
            } ?: run {
                val newInstance = EvsAppAction(context!!)
                instance = SoftReference(newInstance)
                return newInstance
            }
        }
    }

    override fun getSupportedExecute(): List<String> {
        return listOf(DATA_TYPE_ACTIVITY, DATA_TYPE_BROADCAST, DATA_TYPE_SERVICE, DATA_TYPE_EXIT)
    }

    override fun check(payload: JSONObject): JSONObject {
        val checkId = payload.getString(KEY_CHECK_ID)
        val actionArray: JSONArray = payload.getJSONArray(KEY_ACTIONS) as JSONArray

        val resultPayload = JSONObject()
        resultPayload[KEY_CHECK_ID] = checkId

        val resultArray = JSONArray()

        for (i in 0 until actionArray.size) {
            val action = actionArray[i] as JSONObject
            val actionId = action.getString(KEY_ACTION_ID)
            val data = action.getJSONObject(KEY_DATA)
            val pkgName = data.getString(KEY_PACKAGE_NAME)
            val uri = data.getString(KEY_URI)
            val supported = AppUtil.isPackageExist(context, pkgName, uri)

            val checkResult = JSONObject()
            checkResult[KEY_ACTION_ID] = actionId
            checkResult[KEY_RESULT] = supported

            resultArray.add(checkResult)
        }

        resultPayload[KEY_ACTIONS] = resultArray

        return resultPayload
    }

    override fun execute(payload: JSONObject, result: JSONObject): Boolean {
        val executeId = payload.getString(KEY_EXECUTION_ID)
        val actionArray = payload.getJSONArray(KEY_ACTIONS)

        var isSuccess = false
        var errorLevel = 0

        for (i in 0 until actionArray.size) {
            try {
                val action = actionArray[i] as JSONObject
                val actionId = action.getString(KEY_ACTION_ID)
                val data = action.getJSONObject(KEY_DATA)
                val type = data.getString(KEY_TYPE)
                val pkgName = data.getString(KEY_PACKAGE_NAME)
                val actionName = data.getString(KEY_ACTION_NAME)
                val className = data.getString(KEY_CLASS_NAME)
                val uri = data.getString(KEY_URI)
                val categoryName = data.getString(KEY_CATEGORY_NAME)
                val extras = data.getJSONObject(KEY_EXTRAS)
                val version = data.getJSONObject(KEY_VERSION)
                var verStart = 0
                var verEnd = Int.MAX_VALUE

                if (version != null) {
                    verStart = version.getIntValue(KEY_START)
                    verEnd = version.getIntValue(KEY_END)
                }

                if (!pkgName.isNullOrEmpty()) {
                    val appInfo = AppUtil.getAppInfo(context, pkgName)
                    if (appInfo == null) {
                        // 不存在对应的app
                        if (errorLevel < FAILURE_LEVEL_APP_NOT_FOUND) {
                            errorLevel = FAILURE_LEVEL_APP_NOT_FOUND
                        }
                        continue
                    } else {
                        if (appInfo.version !in verStart..verEnd) {
                            // 版本不符合，暂时当不存在app处理
                            if (errorLevel < FAILURE_LEVEL_APP_NOT_FOUND) {
                                errorLevel = FAILURE_LEVEL_APP_NOT_FOUND
                            }
                            continue
                        }
                    }
                }

                val intent: Intent? = if (actionName.isNullOrEmpty()) {
                    if (uri.isNullOrEmpty()) {
                        if (className.isNullOrEmpty()) {
                            context.packageManager.getLaunchIntentForPackage(pkgName)
                        } else {
                            Intent()
                        }
                    } else {
                        Intent.parseUri(uri, 0)
                    }
                } else {
                    Intent(actionName)
                }

                if (!pkgName.isNullOrEmpty()) {
                    intent?.setPackage(pkgName)
                }

                if (!className.isNullOrEmpty()) {
                    intent?.setClassName(pkgName, className)
                }

                if (!categoryName.isNullOrEmpty()) {
                    intent?.addCategory(categoryName)
                }

                if (!uri.isNullOrEmpty()) {
                    intent?.run {
                        if (this.data == null) {
                            this.data = Uri.parse(uri)
                        }
                    }
                }

                if (!extras.isNullOrEmpty()) {
                    for (key: String in extras.keys) {
                        intent?.putExtra(key, extras.getString(key))
                    }
                }

                if (!type.isNullOrEmpty() && intent != null) {
                    if (!AppUtil.isActionSupported(context, intent, type)) {
                        if (type != DATA_TYPE_BROADCAST) {
                            // 不支持action
                            if (errorLevel < FAILURE_LEVEL_ACTION_UNSUPPORTED) {
                                errorLevel = FAILURE_LEVEL_ACTION_UNSUPPORTED
                            }
                            continue
                        }
                    }
                }

                when (type) {
                    DATA_TYPE_SERVICE -> {
                        context.startService(intent)
                        isSuccess = true
                    }
                    DATA_TYPE_BROADCAST -> {
                        context.sendBroadcast(intent)
                        isSuccess = true
                    }
                    DATA_TYPE_EXIT -> {
                        if (!pkgName.isNullOrEmpty()) {
                            TerminalUtils.execute("am force-stop $pkgName")
                            isSuccess = true
                        }
                    }
                    else -> {
                        // 打开app
                        if (intent == null) {
                            if (errorLevel < FAILURE_LEVEL_INTERNAL_ERROR) {
                                errorLevel = FAILURE_LEVEL_INTERNAL_ERROR
                            }
                        } else {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                            isSuccess = true
                        }
                    }
                }

                if (isSuccess) {
                    result[KEY_ACTION_ID] = actionId
                    return true
                }
            } catch (t: Throwable) {
                t.printStackTrace()

                errorLevel = FAILURE_LEVEL_ACTION_UNSUPPORTED
            }
        }

        // 执行失败
        result[KEY_EXECUTION_ID] = executeId
        result[KEY_FAILURE_CODE] = codeMap[errorLevel]

        Intent.ACTION_MAIN

        return false
    }

    override fun getForegroundApp(): AppUtil.AppInfo? {
        return AppUtil.getForegroundApp(context)
    }

}