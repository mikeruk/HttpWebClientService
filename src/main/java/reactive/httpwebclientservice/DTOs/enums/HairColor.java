package reactive.httpwebclientservice.DTOs.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum HairColor {
    AUBURN("auburn"),
    BALD("bald"),
    BLACK("black"),
    BLONDE("blonde"),
    BROWN("brown"),
    BRUNETTE("brunette"),
    CHARCOAL("charcoal"),
    CHESTNUT("chestnut"),
    GOLDEN("golden"),
    GRAY("gray"),
    RED("red"),
    SILVER("silver"),
    WHITE("white");

    private final String value;

    HairColor(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
