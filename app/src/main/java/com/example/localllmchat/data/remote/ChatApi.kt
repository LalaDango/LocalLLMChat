package com.example.localllmchat.data.remote

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface ChatApi {
    @POST("v1/chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String = "Bearer ",
        @Body request: ChatRequest
    ): ChatResponse

    @Streaming
    @POST("v1/chat/completions")
    suspend fun chatStream(
        @Header("Authorization") authorization: String = "Bearer ",
        @Body request: ChatRequest
    ): ResponseBody

    @Streaming
    @POST("v1/chat/completions")
    suspend fun chatStreamMultimodal(
        @Header("Authorization") authorization: String = "Bearer ",
        @Body request: ApiChatRequest
    ): ResponseBody
}
