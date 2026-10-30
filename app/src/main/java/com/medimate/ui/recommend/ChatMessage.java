package com.medimate.ui.recommend;

import com.medimate.recommend.ProductLinks;

import java.util.ArrayList;
import java.util.List;

/** 채팅 말풍선 하나. 봇 말풍선에는 링크 버튼이 붙을 수 있다 (README 8-9) */
public class ChatMessage {

    public enum Role { USER, BOT }

    final Role role;
    final String text;
    final List<ProductLinks.Link> links;

    ChatMessage(Role role, String text) {
        this(role, text, new ArrayList<>());
    }

    ChatMessage(Role role, String text, List<ProductLinks.Link> links) {
        this.role = role;
        this.text = text;
        this.links = links;
    }
}
