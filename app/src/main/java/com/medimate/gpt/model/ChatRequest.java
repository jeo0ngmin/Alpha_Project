package com.medimate.gpt.model;

import com.google.gson.annotations.SerializedName;

import java.util.Arrays;
import java.util.List;

/** OpenAI chat completions 요청. JSON 모드, temperature 0 (README 5-5). */
public class ChatRequest {

    private final String model;
    private final List<Message> messages;
    private final double temperature = 0;
    @SerializedName("response_format")
    private final ResponseFormat responseFormat = new ResponseFormat();

    public ChatRequest(String model, String systemPrompt, String userText) {
        this.model = model;
        this.messages = Arrays.asList(new Message("system", systemPrompt), new Message("user", userText));
    }

    public static class Message {
        private final String role;
        private final String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    static class ResponseFormat {
        private final String type = "json_object";
    }
}
