package com.example.subaruaha

import com.google.gson.Gson
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SDLHeader(val frameType: Int, val sessionId: Int, val payloadSize: Int, val correlationId: Int)
data class AhaStation(val stationId: String, val name: String, val category: String, val imageUrl: String, val streamUrl: String)
data class GetStationsResponse(val GetStationsResponse: StationsContent)
data class StationsContent(val status: String = "SUCCESS", val stations: List<AhaStation>)

class AhaRpcHandler {
    private val gson = Gson()
    
    // Curated high-quality working stream URLs
    private val stationsList = listOf(
        AhaStation("101", "Lofi Beats", "Chill", "", "https://stream.zeno.fm/f3b5u78nas8uv"),
        AhaStation("102", "Synthwave Retro", "Electronic", "", "https://nightride.fm/stream/synthwave.mp3"),
        AhaStation("103", "Radio Paradise Mellow", "Acoustic/Jazz", "", "https://stream.radioparadise.com/mellow-128"),
        AhaStation("104", "BBC Radio 1", "Pop/Top40", "", "http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one")
    )

    fun handle(type: Int, sid: Int, cid: Int, json: String): ByteArray? {
        return when {
            json.contains("RegisterAppInterface") -> pack(0x00, sid, cid, """{"RegisterAppInterfaceResponse":{"status":"SUCCESS"}}""")
            json.contains("GetStations") -> pack(0x00, sid, cid, gson.toJson(GetStationsResponse(StationsContent(stations = stationsList))))
            else -> null
        }
    }

    fun pack(type: Int, sid: Int, cid: Int, payload: String?): ByteArray {
        val body = payload?.toByteArray() ?: byteArrayOf()
        return ByteBuffer.allocate(12 + body.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0x50.toByte()); put(type.toByte()); put(0x07.toByte()); put(sid.toByte())
            putInt(body.size); putInt(cid); put(body)
        }.array()
    }
}
