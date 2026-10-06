package com.medimate.ui.recommend;

/** 채팅 말풍선 하나 */
public class ChatMessage {

    public enum Role { USER, BOT }

    final Role role;
    final String text;

    ChatMessage(Role role, String text) {
        this.role = role;
        this.text = text;
    }
}
