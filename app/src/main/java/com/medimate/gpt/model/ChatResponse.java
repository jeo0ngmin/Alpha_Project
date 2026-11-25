package com.medimate.gpt.model;

import java.util.List;

/** OpenAI chat completions 응답 중 쓰는 부분: choices[0].message.content */
public class ChatResponse {

    private List<Choice> choices;

    /** 모델이 돌려준 본문. 없으면 null */
    public String getContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).message == null) {
            return null;
        }
        return choices.get(0).message.content;
    }

    static class Choice {
        private Message message;
    }

    static class Message {
        private String content;
    }
}
