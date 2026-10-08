package dev.trooped.tvquickbars.ha.ws.handlers

import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import org.json.JSONObject

/** quickbars.control_center → open / update / close the Control Center (DL build). Honours an optional target id. */
class QuickBarsControlCenterHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    override fun canHandle(event: JSONObject): Boolean = event.optString("event_type") == "quickbars.control_center"

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: return
        val data = event.optJSONObject("data") ?: return
        val target = data.optString("id", "")
        if (target.isNotEmpty() && !target.equals(AppIdProvider.get(context), ignoreCase = true)) return
        ctx.listener.onControlCenter(data)
    }
}
