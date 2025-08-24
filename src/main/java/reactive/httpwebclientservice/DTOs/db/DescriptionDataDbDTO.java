package reactive.httpwebclientservice.DTOs.db;


import jakarta.validation.constraints.Size;

public class DescriptionDataDbDTO {

    private Long id;

    @Size(min = 50, max = 500, message = "description must be between 50 and 500 characters")
    private String description;


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }


    @Override
    public String toString() {
        return "DescriptionDataDbDTO{" +
                "description='" + description + '\'' +
                '}' + '\n';
    }
}

