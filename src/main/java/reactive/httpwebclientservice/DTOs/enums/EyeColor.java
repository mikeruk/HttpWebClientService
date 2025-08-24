package reactive.httpwebclientservice.DTOs.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EyeColor {
    BLACK("black"),
    BLUE("blue"),
    BROWN("brown"),
    GRAY("gray"),
    GREEN("green"),
    HAZEL("hazel");

    private final String value;

    EyeColor(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

