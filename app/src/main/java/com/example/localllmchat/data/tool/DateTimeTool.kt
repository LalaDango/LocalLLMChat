package com.example.localllmchat.data.tool

import com.example.localllmchat.data.remote.FunctionDefinition
import com.example.localllmchat.data.remote.ToolDefinition
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DateTimeTool : ToolHandler {
    private val gson = Gson()

    override val definition = ToolDefinition(
        function = FunctionDefinition(
            name = "get_datetime",
            description = "Get the current date and time. The result contains datetime/date/time/day_of_week; use these values when answering the user.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "timezone" to mapOf(
                        "type" to "string",
                        "description" to "Timezone (e.g. Asia/Tokyo). Defaults to device timezone."
                    )
                )
            )
        )
    )

    override suspend fun execute(arguments: String): String {
        val zoneId = try {
            val args = gson.fromJson(arguments, JsonObject::class.java)
            val tz = args?.get("timezone")?.asString
            if (tz != null) ZoneId.of(tz) else ZoneId.systemDefault()
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }

        val now = ZonedDateTime.now(zoneId)
        val result = JsonObject()
        result.addProperty("datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        result.addProperty("timezone", zoneId.id)
        result.addProperty("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE))
        result.addProperty("time", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
        result.addProperty("day_of_week", now.dayOfWeek.name)
        return gson.toJson(result)
    }
}
