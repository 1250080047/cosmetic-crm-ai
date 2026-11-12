package com.example.PhanTichAI.dto;

public class AiPredictResponse {
    private int cluster_id;
    private String segment_name;
    private String color_code;
    private double discount_percent;
    private String gift_description;
    private String cashier_script;
    private String active_version;

    public AiPredictResponse() {}

    public int getCluster_id() { return cluster_id; }
    public void setCluster_id(int cluster_id) { this.cluster_id = cluster_id; }

    public String getSegment_name() { return segment_name; }
    public void setSegment_name(String segment_name) { this.segment_name = segment_name; }

    public String getColor_code() { return color_code; }
    public void setColor_code(String color_code) { this.color_code = color_code; }

    public double getDiscount_percent() { return discount_percent; }
    public void setDiscount_percent(double discount_percent) { this.discount_percent = discount_percent; }

    public String getGift_description() { return gift_description; }
    public void setGift_description(String gift_description) { this.gift_description = gift_description; }

    public String getCashier_script() { return cashier_script; }
    public void setCashier_script(String cashier_script) { this.cashier_script = cashier_script; }
    public String getActive_version() { return active_version; }
    public void setActive_version(String active_version) { this.active_version = active_version; }
}
