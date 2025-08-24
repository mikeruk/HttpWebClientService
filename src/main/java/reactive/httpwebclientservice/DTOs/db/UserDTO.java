package reactive.httpwebclientservice.DTOs.db;

import java.time.LocalDateTime;

public class UserDTO {
    private Long id;
    private LocalDateTime ts;

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public LocalDateTime getTs() {
        return ts;
    }
    public void setTs(LocalDateTime ts) {
        this.ts = ts;
    }

}
