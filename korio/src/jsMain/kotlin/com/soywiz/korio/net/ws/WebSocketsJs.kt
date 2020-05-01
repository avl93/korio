package com.soywiz.korio.net.ws

import com.soywiz.korio.async.*
import com.soywiz.korio.util.*
import org.khronos.webgl.*
import org.w3c.dom.*

actual suspend fun WebSocketClient(
	url: String,
	protocols: List<String>?,
	origin: String?,
	wskey: String?,
	debug: Boolean
): WebSocketClient = JsWebSocketClient(url, protocols, DEBUG = debug).apply { init() }

class JsWebSocketClient(url: String, protocols: List<String>?, val DEBUG: Boolean) :
	WebSocketClient(url, protocols, true) {
	val jsws = if (protocols != null) {
		WebSocket(url, arrayOf(*protocols.toTypedArray()))
	} else {
		WebSocket(url)
	}.apply {
		this.binaryType = BinaryType.ARRAYBUFFER
		this.addEventListener("open", { onOpen(Unit) })
		this.addEventListener("close", { e ->
			val event = e as CloseEvent
			var code = event.code.toInt()
			var reason = event.reason
			var wasClean = event.wasClean
			onClose(Unit)
		})
		this.addEventListener("message", { e ->
			val event = e as MessageEvent
			val data = event.data
			if (DEBUG) println("[WS-RECV]: $data :: stringListeners=${onStringMessage.listenerCount}, binaryListeners=${onBinaryMessage.listenerCount}, anyListeners=${onAnyMessage.listenerCount}")
			if (data is String) {
				val js = data.unsafeCast<String>()
				onStringMessage(js)
				onAnyMessage(js)
			} else {
				val jb = data.unsafeCast<ArrayBuffer>()
                val ba = jb.toByteArray()
				onBinaryMessage(ba)
				onAnyMessage(ba)
			}
		})
	}

	suspend fun init() {
		if (DEBUG) println("[WS] Wait connection ($url)...")
		onOpen.waitOne()
		if (DEBUG) println("[WS] Connected!")
	}

	override fun close(code: Int, reason: String) {
		//jsws.methods["close"](code, reason)
		jsws.close()
	}

	override suspend fun send(message: String) {
		if (DEBUG) println("[WS-SEND]: $message")
		jsws.send(message)
	}

	override suspend fun send(message: ByteArray) {
		if (DEBUG) println("[WS-SEND]: ${message.toList()}")
		jsws.send(message.toInt8Array())
	}
}
