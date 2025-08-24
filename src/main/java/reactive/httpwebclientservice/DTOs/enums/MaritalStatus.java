package reactive.httpwebclientservice.DTOs.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MaritalStatus {
    SINGLE("single"),
    DIVORCED("divorced"),
    SEPARATED("separated"),
    WIDOWED("widowed"),
    MARRIED("married");

    private final String value;

    MaritalStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }


}
