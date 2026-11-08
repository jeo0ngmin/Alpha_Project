package com.medimate.pill.model;

public enum ColorClass {
    WHITE("흰색"), YELLOW("노란색"), ORANGE("주황색"), PINK("분홍색"), RED("빨간색"), BROWN("갈색"),
    LIGHT_GREEN("연두색"), GREEN("초록색"), BLUE("파란색"), NAVY("남색"), PURPLE("보라색"),
    GRAY("회색"), BLACK("검은색"), TRANSPARENT("투명");

    public final String label;

    ColorClass(String label) {
        this.label = label;
    }
}
