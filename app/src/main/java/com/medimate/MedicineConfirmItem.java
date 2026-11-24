package com.medimate;

public class MedicineConfirmItem {

    private String name;
    private String info;
    private boolean selected;


    public MedicineConfirmItem(
            String name,
            String info,
            boolean selected
    ) {

        this.name = name;
        this.info = info;
        this.selected = selected;
    }


    public String getName() {
        return name;
    }


    public String getInfo() {
        return info;
    }


    public boolean isSelected() {
        return selected;
    }


    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}