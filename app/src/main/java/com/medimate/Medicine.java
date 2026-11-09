package com.medimate;

public class Medicine {

    private String name;
    private String info;

    public Medicine(String name, String info) {
        this.name = name;
        this.info = info;
    }

    public String getName() {
        return name;
    }

    public String getInfo() {
        return info;
    }
}
