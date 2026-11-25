package com.medimate.server.common;

/** API 명세의 enum. 이름이 곧 JSON 값이다. */
public final class Enums {

    private Enums() {
    }

    public enum PillJobStatus { PENDING, PROCESSING, COMPLETED, NOT_FOUND, FAILED, SELECTED }

    public enum DrugShape { ROUND, OVAL, OBLONG, TRIANGLE, SQUARE, DIAMOND, PENTAGON, HEXAGON, OCTAGON, CAPSULE, OTHER }

    public enum ColorClass {
        WHITE, YELLOW, ORANGE, PINK, RED, BROWN, LIGHT_GREEN, GREEN, BLUE, NAVY, PURPLE, GRAY, BLACK, TRANSPARENT
    }

    public enum LineType { NONE, MINUS, PLUS, OTHER }

    public enum FilePurpose { MEDICATION_BAG, PRESCRIPTION, PILL, PROFILE }
}
